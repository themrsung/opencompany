package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionScope;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Turning "the 부장 of the drafter's unit" into people.
 *
 * <p>This is the step that happens once, at submission, and is then frozen. It
 * is therefore the last moment at which the org chart is consulted for a
 * document, and the only place where getting the date wrong would route an
 * approval to the wrong person.
 */
class ApproverDirectoryTest {

    private ApprovalTestWorld world;
    private ApproverDirectory directory;
    private OrgUnit division;
    private OrgUnit team;
    private OrgUnit squad;
    private Rank staff;
    private Rank generalManager;
    private Rank representative;
    private String drafter;

    @BeforeEach
    void setUp() {
        world = new ApprovalTestWorld();
        division = world.unit("HQ", null);
        team = world.unit("DEV", division);
        squad = world.unit("PLATFORM", team);
        staff = world.rank("SAWON", 10, false);
        generalManager = world.rank("BUJANG", 50, false);
        representative = world.rank("DAEPYO", 70, true);
        drafter = world.person("김사원", squad, staff, LocalDate.of(2024, 1, 1));
        directory = world.directory();
    }

    private ApprovalContext context() {
        return new ApprovalContext(ApprovalTestWorld.COMPANY, drafter, "emp-김사원", squad.id(),
                ApprovalTestWorld.DAY);
    }

    private static List<String> accountIds(List<ResolvedApprover> resolved) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ResolvedApprover approver : resolved) {
            ids.add(approver.accountId());
        }
        return ids;
    }

    @Nested
    @DisplayName("where to look")
    class Domains {

        @Test
        @DisplayName("DRAFTER_UNIT looks only at the drafter's own unit")
        void drafterUnit() {
            String squadLead = world.person("박부장", squad, generalManager,
                    LocalDate.of(2019, 1, 1));
            world.person("최부장", team, generalManager, LocalDate.of(2019, 1, 1));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), context())))
                    .containsExactly(squadLead);
        }

        @Test
        @DisplayName("DRAFTER_UNIT_PARENT looks one level up")
        void drafterUnitParent() {
            world.person("박부장", squad, generalManager, LocalDate.of(2019, 1, 1));
            String teamLead = world.person("최부장", team, generalManager,
                    LocalDate.of(2019, 1, 1));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT_PARENT),
                    context())))
                    .containsExactly(teamLead);
        }

        @Test
        @DisplayName("DRAFTER_UNIT_SUBTREE is reflexive — it includes the unit itself")
        void drafterUnitSubtree() {
            String squadLead = world.person("박부장", squad, generalManager,
                    LocalDate.of(2019, 1, 1));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT_SUBTREE),
                    context())))
                    .as("\"subtree\" means the unit and everything under it, which is what the "
                            + "person granting it means by it")
                    .containsExactly(squadLead);
        }

        @Test
        @DisplayName("COMPANY reaches every unit")
        void company() {
            String divisionLead = world.person("본부장", division, generalManager,
                    LocalDate.of(2015, 1, 1));
            String squadLead = world.person("박부장", squad, generalManager,
                    LocalDate.of(2019, 1, 1));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.COMPANY), context())))
                    .containsExactlyInAnyOrder(divisionLead, squadLead);
        }

        @Test
        @DisplayName("a drafter with no unit resolves 부서 domains to nobody, not to everybody")
        void noUnitResolvesToNobody() {
            world.person("박부장", squad, generalManager, LocalDate.of(2019, 1, 1));
            ApprovalContext unattached = new ApprovalContext(ApprovalTestWorld.COMPANY, drafter,
                    "emp-김사원", null, ApprovalTestWorld.DAY);

            assertThat(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT),
                    unattached))
                    .as("resolving to the whole company would route the document to strangers")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what to select on")
    class Selectors {

        @Test
        @DisplayName("a job function resolves to whoever holds it")
        void jobFunction() {
            String accountant = world.person("회계담당", team, staff, LocalDate.of(2021, 1, 1));
            world.person("개발자", team, staff, LocalDate.of(2021, 1, 1));
            world.functions.save(new JobFunction("fn-acc", ApprovalTestWorld.COMPANY,
                    "ACCOUNTING", "회계"));
            world.positions.jobFunctionsByPosition.put("pos-회계담당",
                    Immutables.listOf("fn-acc"));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.jobFunction("ACCOUNTING", RoleExpression.Domain.COMPANY),
                    context())))
                    .containsExactly(accountant);
        }

        @Test
        @DisplayName("a permission expression asks the evaluator, once per candidate")
        void permission() {
            String approver = world.person("승인권자", team, staff, LocalDate.of(2021, 1, 1));
            world.person("일반사원", team, staff, LocalDate.of(2021, 1, 1));
            world.grants.grant(approver,
                    com.coreintra.core.permission.PermissionKey.of("finance.expense", "approve"),
                    PermissionScope.COMPANY);

            assertThat(accountIds(directory.resolve(
                    RoleExpression.permission("finance.expense:approve",
                            RoleExpression.Domain.COMPANY), context())))
                    .as("the evaluator is the only thing entitled to answer a permission "
                            + "question; a reverse index that disagreed with it would be a "
                            + "second permission system")
                    .containsExactly(approver);
        }

        @Test
        @DisplayName("representatives come from the rank flag, not from the label 대표")
        void representatives() {
            String repOne = world.person("대표일", division, representative,
                    LocalDate.of(2010, 1, 1));
            String repTwo = world.person("대표이", division, representative,
                    LocalDate.of(2010, 1, 1));

            assertThat(accountIds(directory.resolve(RoleExpression.representative(), context())))
                    .containsExactlyInAnyOrder(repOne, repTwo);
        }

        @Test
        @DisplayName("a named account resolves to that person and nobody else")
        void account() {
            assertThat(accountIds(directory.resolve(RoleExpression.account(drafter), context())))
                    .containsExactly(drafter);
        }

        @Test
        @DisplayName("a rank code that does not exist is refused rather than resolving to nobody")
        void unknownRankRefused() {
            assertThatThrownBy(() -> directory.resolve(
                    RoleExpression.rank("NOSUCHRANK", RoleExpression.Domain.COMPANY), context()))
                    .isInstanceOf(ApproverDirectory.UnresolvableRoleException.class)
                    .hasMessageContaining("would route nowhere");
        }

        @Test
        @DisplayName("a company with no representative rank cannot route 대표자 결재")
        void noRepresentativeRankRefused() {
            world.ranks.rows.get(representative.id()).setRepresentative(false);

            assertThatThrownBy(() -> directory.resolve(RoleExpression.representative(), context()))
                    .isInstanceOf(ApproverDirectory.UnresolvableRoleException.class)
                    .hasMessageContaining("marked as representative");
        }
    }

    @Nested
    @DisplayName("as of a date")
    class AsOfADate {

        @Test
        @DisplayName("someone who has left is not routed a document, though their history stays")
        void leaversAreNotRouted() {
            String leaver = world.person("퇴사부장", squad, generalManager,
                    LocalDate.of(2019, 1, 1));
            Employee employee = world.employees.rows.get("emp-퇴사부장");
            employee.terminate(ApprovalTestWorld.DAY.minusDays(1));

            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), context())))
                    .as("their position rows survive for the audit trail; routing a live approval "
                            + "to them would stall the document forever")
                    .doesNotContain(leaver);
        }

        @Test
        @DisplayName("a deactivated account is not routed either")
        void deactivatedAccountsAreNotRouted() {
            String suspended = world.person("정지부장", squad, generalManager,
                    LocalDate.of(2019, 1, 1));
            world.accounts.rows.get(suspended).deactivate();

            assertThat(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), context()))
                    .isEmpty();
        }

        @Test
        @DisplayName("a promotion that takes effect tomorrow does not change today's routing")
        void futureAppointmentsAreNotVisible() {
            String tomorrowsLead = world.person("내일부장", team, staff, LocalDate.of(2021, 1, 1));
            Position promotion = new Position("pos-promotion", "emp-내일부장", squad.id(),
                    generalManager.id(), ApprovalTestWorld.DAY.plusDays(1));
            world.positions.save(promotion);

            assertThat(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), context()))
                    .as("resolution is as of the document's business date, never as of now")
                    .isEmpty();

            ApprovalContext tomorrow = new ApprovalContext(ApprovalTestWorld.COMPANY, drafter,
                    "emp-김사원", squad.id(), ApprovalTestWorld.DAY.plusDays(1));
            assertThat(accountIds(directory.resolve(
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), tomorrow)))
                    .containsExactly(tomorrowsLead);
        }
    }

    @Test
    @DisplayName("resolution is ordered most senior first, so a snapshot is stable")
    void orderedSeniorFirst() {
        String junior = world.person("나사원", squad, staff, LocalDate.of(2019, 1, 1));
        String senior = world.person("가부장", squad, generalManager, LocalDate.of(2019, 1, 1));
        String chief = world.person("다대표", squad, representative, LocalDate.of(2019, 1, 1));
        com.coreintra.core.permission.PermissionKey key =
                com.coreintra.core.permission.PermissionKey.of("finance.expense", "approve");
        for (String accountId : new String[] {junior, senior, chief}) {
            world.grants.grant(accountId, key, PermissionScope.COMPANY);
        }

        List<ResolvedApprover> resolved = directory.resolve(
                RoleExpression.permission("finance.expense:approve",
                        RoleExpression.Domain.DRAFTER_UNIT), context());

        assertThat(accountIds(resolved))
                .as("a 결재란 that reshuffles between renders looks tampered with")
                .containsExactly(chief, senior, junior);
        assertThat(resolved.get(0).rankLabel()).isEqualTo("DAEPYO");
        assertThat(accountIds(directory.resolve(
                RoleExpression.permission("finance.expense:approve",
                        RoleExpression.Domain.DRAFTER_UNIT), context())))
                .as("and the same expression resolved twice gives the same order")
                .containsExactly(chief, senior, junior);
    }
}
