package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 취업규칙 at the service layer, and the acceptance test that must never regress:
 * employment rules cannot be changed without 대표자 결재, <b>by any path,
 * including a master account</b>.
 *
 * <p>{@code EmploymentRulesTest} proves the domain type cannot be constructed
 * without one. These tests prove the service in front of it does not open a
 * second door: not for an administrator, not for a master account, not with an
 * approval document of the wrong kind, and not with one that only some of the
 * representatives signed.
 */
class EmploymentRulesServiceTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 9, 1);

    private ApprovalTestWorld world;
    private EmploymentRulesService service;
    private ApprovalDocumentService documents;
    private ApprovalActionService actions;
    private OrgUnit head;
    private Rank staff;
    private Rank representative;
    private String hrManager;
    private String repOne;
    private String repTwo;

    @BeforeEach
    void setUp() {
        world = new ApprovalTestWorld();
        head = world.unit("HQ", null);
        staff = world.rank("SAWON", 10, false);
        representative = world.rank("DAEPYO", 70, true);

        hrManager = world.person("인사담당", head, staff, LocalDate.of(2020, 1, 1));
        repOne = world.person("대표일", head, representative, LocalDate.of(2010, 1, 1));
        repTwo = world.person("대표이", head, representative, LocalDate.of(2010, 1, 1));

        world.grants.grantCompanyWide(ApprovalPermissions.RULES_WRITE, hrManager);
        world.grants.grantCompanyWide(ApprovalPermissions.RULES_READ, hrManager);
        world.grants.grantCompanyWide(ApprovalPermissions.DOCUMENT_WRITE, hrManager);

        List<ApprovalLineTemplate.TemplateStep> steps =
                new ArrayList<ApprovalLineTemplate.TemplateStep>();
        steps.add(new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.DRAFT,
                RoleExpression.account(hrManager), false));
        steps.add(new ApprovalLineTemplate.TemplateStep(1, ApprovalStepKind.APPROVE,
                RoleExpression.representative(), false));
        world.templates.all.add(new ApprovalLineTemplate("tpl-rules",
                EmploymentRulesService.DOCUMENT_TYPE, null, steps,
                Immutables.<ApprovalLineTemplate.ThresholdRule>listOf()));

        service = world.employmentRulesService();
        documents = world.documentService();
        actions = world.actionService();
    }

    private static List<EmploymentRules.Section> sections(String... numbers) {
        List<EmploymentRules.Section> list = new ArrayList<EmploymentRules.Section>();
        for (String number : numbers) {
            list.add(new EmploymentRules.Section(number, "제목 " + number, "Heading " + number,
                    "본문 " + number, "Body " + number));
        }
        return list;
    }

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(ApprovalTestWorld.DAY, hour, 0, 0);
    }

    /** Files a 취업규칙 change and takes it through 결재 to APPROVED. */
    private String approvedChange(List<EmploymentRules.Section> text, String... approvers) {
        ApprovalDocumentEntity proposal = service.proposeChange(world.principal(hrManager),
                ApprovalTestWorld.COMPANY, "취업규칙 개정", ApprovalTestWorld.DAY);
        // The proposal is submitted carrying the digest of the very text being
        // proposed, which is what lets publish() prove the two are the same.
        ApprovalDocumentView view = documents.submit(world.principal(hrManager), proposal.id(),
                at(9), EmploymentRulesService.textDigest(text));
        String repStep = view.steps().get(1).id();
        int hour = 10;
        for (String approver : approvers) {
            actions.approve(world.principal(approver), proposal.id(), repStep, at(hour++), null);
        }
        return proposal.id();
    }

    @Nested
    @DisplayName("the representative-approval invariant")
    class Invariant {

        @Test
        @DisplayName("an approved 대표자 결재 publishes a version")
        void approvedChangePublishes() {
            List<EmploymentRules.Section> text = sections("제1조", "제2조");
            String documentId = approvedChange(text, repOne);

            EmploymentRules published = service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, text, documentId);

            assertThat(published.version()).isEqualTo(1);
            assertThat(published.approvalDocumentId()).isEqualTo(documentId);
            assertThat(published.approvingRepresentativeIds()).containsExactly(repOne);
            assertThat(world.rulesStore.versions).hasSize(1);
        }

        @Test
        @DisplayName("no approval document means no employment rules, for anyone")
        void noDocumentNoRules() {
            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, sections("제1조"), null))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("대표자 결재가 필요합니다")
                    .hasMessageContaining("master account does not have one either");
            assertThat(world.rulesStore.versions).isEmpty();
        }

        @Test
        @DisplayName("a master account with every permission still cannot publish without one")
        void masterAccountCannotBypass() {
            world.accounts.rows.get(hrManager).setMaster(true);
            world.grants.grant(hrManager, ApprovalPermissions.RULES_WRITE, PermissionScope.ALL);
            world.grants.grant(hrManager, ApprovalPermissions.DOCUMENT_WRITE, PermissionScope.ALL);
            PermissionPrincipal master = world.master(hrManager);

            assertThatThrownBy(() -> service.publish(master, ApprovalTestWorld.COMPANY, EFFECTIVE,
                    sections("제1조"), null))
                    .as("master is an account flag, not an answer to 'has the 대표 approved it?'")
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class);
            assertThat(world.rulesStore.versions).isEmpty();
        }

        @Test
        @DisplayName("an approval still in progress does not count")
        void inProgressDoesNotCount() {
            ApprovalDocumentEntity proposal = service.proposeChange(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, "취업규칙 개정", ApprovalTestWorld.DAY);
            documents.submit(world.principal(hrManager), proposal.id(), at(9), "rules-v1");

            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, sections("제1조"), proposal.id()))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("IN_PROGRESS");
        }

        @Test
        @DisplayName("the text enacted must be the text that was approved")
        void enactedTextMustMatchTheApprovedText() {
            List<EmploymentRules.Section> approvedText = sections("제1조");
            String documentId = approvedChange(approvedText, repOne);

            List<EmploymentRules.Section> substituted = new ArrayList<EmploymentRules.Section>();
            substituted.add(new EmploymentRules.Section("제1조", "제목 제1조", "Heading 제1조",
                    "본문 제1조 — 연장근로 수당을 지급하지 아니한다", "Body 제1조"));

            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, substituted, documentId))
                    .as("otherwise the 대표's signature authorises a document id rather than a "
                            + "set of words, and anyone holding the id could enact anything")
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("결재된 내용과 시행하려는 내용이 다릅니다");
            assertThat(world.rulesStore.versions).isEmpty();

            assertThat(service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    EFFECTIVE, approvedText, documentId).version())
                    .as("the text that really was approved still enacts")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("enacting needs the write grant as well as the 대표's signature")
        void enactingNeedsThePermissionToo() {
            List<EmploymentRules.Section> text = sections("제1조");
            String documentId = approvedChange(text, repOne);
            String outsider = world.person("외부인", head, staff, LocalDate.of(2024, 1, 1));

            assertThatThrownBy(() -> service.publish(world.principal(outsider),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, text, documentId))
                    .isInstanceOf(com.coreintra.core.permission.PermissionDeniedException.class)
                    .hasMessageContaining("hr.rules:write");
        }

        @Test
        @DisplayName("an approved expense claim is not a licence to rewrite the rules")
        void wrongDocumentTypeRejected() {
            ApprovalDocumentEntity expense = new ApprovalDocumentEntity("doc-expense",
                    ApprovalTestWorld.COMPANY, "EXPENSE_CLAIM", "회식비", hrManager);
            expense.markSubmitted(at(9), "sha256:whatever");
            expense.setState(ApprovalState.APPROVED);
            world.documents.save(expense);

            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, sections("제1조"), "doc-expense"))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("The 대표 approved something else");
        }

        @Test
        @DisplayName("another company's approval does not enact these rules")
        void otherCompanysApprovalRejected() {
            ApprovalDocumentEntity elsewhere = new ApprovalDocumentEntity("doc-other", "co-2",
                    EmploymentRulesService.DOCUMENT_TYPE, "다른 회사 취업규칙", hrManager);
            elsewhere.markSubmitted(at(9), "sha256:whatever");
            elsewhere.setState(ApprovalState.APPROVED);
            world.documents.save(elsewhere);

            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, sections("제1조"), "doc-other"))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("belongs to company co-2");
        }

        @Test
        @DisplayName("the service exposes exactly one way to write employment rules")
        void noSecondWriteMethodExists() {
            // Structural, like the domain's own bypass test. If a second write
            // path is ever added — publishWithOverride, publishAsAdmin, an
            // import — this is where the argument should happen.
            List<String> writeMethods = new ArrayList<String>();
            for (Method method : EmploymentRulesService.class.getDeclaredMethods()) {
                String name = method.getName();
                boolean writes = name.startsWith("publish") || name.startsWith("save")
                        || name.startsWith("enact") || name.startsWith("force")
                        || name.contains("Override") || name.contains("Bypass");
                if (writes && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    writeMethods.add(name);
                }
            }
            assertThat(writeMethods).containsExactly("publish");
        }
    }

    @Nested
    @DisplayName("공동대표 quorum")
    class JointRepresentation {

        @BeforeEach
        void jointOfTwo() {
            world.representation.mode = RepresentationMode.joint(2, 2);
        }

        @Test
        @DisplayName("one representative's approval does not enact the rules")
        void oneRepresentativeIsNotEnough() {
            ApprovalDocumentEntity proposal = service.proposeChange(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, "취업규칙 개정", ApprovalTestWorld.DAY);
            ApprovalDocumentView view = documents.submit(world.principal(hrManager),
                    proposal.id(), at(9), EmploymentRulesService.textDigest(sections("제1조")));
            actions.approve(world.principal(repOne), proposal.id(), view.steps().get(1).id(),
                    at(10), null);

            assertThatThrownBy(() -> service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, sections("제1조"), proposal.id()))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("PARTIALLY_APPROVED")
                    .hasMessageContaining("partially met is not met");
            assertThat(world.rulesStore.versions).isEmpty();
        }

        @Test
        @DisplayName("both representatives, and it is enacted")
        void bothRepresentativesEnact() {
            List<EmploymentRules.Section> text = sections("제1조");
            String documentId = approvedChange(text, repOne, repTwo);

            EmploymentRules published = service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, text, documentId);

            assertThat(published.approvingRepresentativeIds())
                    .containsExactlyInAnyOrder(repOne, repTwo);
            assertThat(published.approvedUnderMode().isJoint()).isTrue();
        }
    }

    @Nested
    @DisplayName("versions, diffs and acknowledgements")
    class Versions {

        @Test
        @DisplayName("employees see the version that was effective on a given date")
        void effectiveOnADate() {
            List<EmploymentRules.Section> first = sections("제1조");
            List<EmploymentRules.Section> second = sections("제1조", "제2조");
            service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 1, 1), first, approvedChange(first, repOne));
            service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 9, 1), second, approvedChange(second, repOne));

            assertThat(service.effectiveOn(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 6, 1)).get().version())
                    .as("a rule taking effect in September does not bind anyone in June")
                    .isEqualTo(1);
            assertThat(service.effectiveOn(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 9, 1)).get().version()).isEqualTo(2);
        }

        @Test
        @DisplayName("the diff is section by section against the previous version")
        void sectionDiff() {
            List<EmploymentRules.Section> original = sections("제1조", "제2조");
            service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 1, 1), original, approvedChange(original, repOne));

            List<EmploymentRules.Section> amended = sections("제1조");
            amended.add(new EmploymentRules.Section("제3조", "신설", "New", "신설 본문", "New body"));
            service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY,
                    LocalDate.of(2026, 9, 1), amended, approvedChange(amended, repOne));

            List<EmploymentRules.SectionDiff> diff = service.diffAgainstPrevious(
                    world.principal(hrManager), ApprovalTestWorld.COMPANY, 2);

            assertThat(diff).extracting(EmploymentRules.SectionDiff::sectionNumber)
                    .containsExactly("제1조", "제3조", "제2조");
            assertThat(diff).extracting(EmploymentRules.SectionDiff::kind)
                    .containsExactly(EmploymentRules.SectionDiff.ChangeKind.UNCHANGED,
                            EmploymentRules.SectionDiff.ChangeKind.ADDED,
                            EmploymentRules.SectionDiff.ChangeKind.REMOVED);
        }

        @Test
        @DisplayName("the first version has no diff to show")
        void firstVersionHasNoDiff() {
            List<EmploymentRules.Section> text = sections("제1조");
            service.publish(world.principal(hrManager), ApprovalTestWorld.COMPANY, EFFECTIVE,
                    text, approvedChange(text, repOne));

            assertThat(service.diffAgainstPrevious(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, 1)).isEmpty();
        }

        @Test
        @DisplayName("acknowledgements are per employee per version, and only your own")
        void acknowledgementsArePersonal() {
            List<EmploymentRules.Section> text = sections("제1조");
            EmploymentRules published = service.publish(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, EFFECTIVE, text, approvedChange(text, repOne));

            service.acknowledge(world.principal(hrManager), published.id(), "emp-인사담당",
                    ApprovalTestWorld.DAY);
            assertThat(service.acknowledgedBy(world.principal(hrManager),
                    ApprovalTestWorld.COMPANY, published.id())).containsExactly("emp-인사담당");

            assertThatThrownBy(() -> service.acknowledge(world.principal(hrManager),
                    published.id(), "emp-대표일", ApprovalTestWorld.DAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("본인만");
        }
    }
}
