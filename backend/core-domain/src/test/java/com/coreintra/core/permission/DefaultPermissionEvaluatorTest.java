package com.coreintra.core.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The permission rules, stated as tests.
 *
 * <p>Org used throughout — one company, 본사, with a 회계팀 under a 경영지원본부:
 *
 * <pre>
 *   acme (company)
 *     └── hq                (본사)
 *           ├── support     (경영지원본부)
 *           │     └── finance  (회계팀)
 *           └── sales       (영업본부)
 * </pre>
 */
class DefaultPermissionEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    private static final LocalDate LAST_MARCH = LocalDate.of(2026, 3, 15);

    private InMemoryOrg org;
    private PermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        org = new InMemoryOrg()
                .unit("hq", null, "acme")
                .unit("support", "hq", "acme")
                .unit("finance", "support", "acme")
                .unit("sales", "hq", "acme")
                .unit("tokyo", null, "acme-jp");
        evaluator = new DefaultPermissionEvaluator(org, org);
    }

    private PermissionTarget targetIn(String unitId, String companyId, String ownerEmployeeId) {
        return PermissionTarget.builder()
                .orgUnitId(unitId).companyId(companyId).ownerEmployeeId(ownerEmployeeId)
                .asOfBusinessDate(TODAY).description("test target").build();
    }

    @Nested
    @DisplayName("deny by default")
    class DenyByDefault {

        @Test
        @DisplayName("an account with no grants at all can do nothing")
        void noGrantsMeansNo() {
            org.position("acc-1", "emp-1", "finance", "rank-사원", "job-회계", null, null);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            PermissionDecision decision = evaluator.check(
                    principal, "accounting.entry", "post", targetIn("finance", "acme", null));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.summary()).contains("denied by default");
            assertThat(decision.decidingGrant()).isNull();
        }

        @Test
        @DisplayName("a master account is not automatically allowed")
        void masterIsNotMagic() {
            org.position("acc-master", "emp-9", "hq", "rank-대표", null, null, null);
            PermissionPrincipal master = PermissionPrincipal.master("acc-master", "대표", "emp-9");

            // Master is a flag on the account, not a bypass in the evaluator.
            // Authority comes from grants, so it is visible in the explainer.
            assertThat(evaluator.check(master, "accounting.entry", "post",
                    targetIn("finance", "acme", null)).isAllowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("scope resolution")
    class Scopes {

        @Test
        @DisplayName("ORG_UNIT reaches the held unit but not its children")
        void orgUnitDoesNotDescend() {
            org.position("acc-1", "emp-1", "support", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "hr.employee:read", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");

            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("support", "acme", null)).isAllowed()).isTrue();

            PermissionDecision child = evaluator.check(principal, "hr.employee", "read",
                    targetIn("finance", "acme", null));
            assertThat(child.isAllowed()).isFalse();
            assertThat(child.considerations()).anyMatch(c ->
                    !c.applied() && c.reason().contains("ORG_UNIT_SUBTREE"));
        }

        @Test
        @DisplayName("ORG_UNIT_SUBTREE reaches descendants, and the unit itself")
        void subtreeDescendsAndIncludesSelf() {
            org.position("acc-1", "emp-1", "support", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "hr.employee:read", PermissionScope.ORG_UNIT_SUBTREE);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");

            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("finance", "acme", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("support", "acme", null)).isAllowed()).isTrue();
            // A sibling branch is still out of reach.
            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("sales", "acme", null)).isAllowed()).isFalse();
        }

        @Test
        @DisplayName("COMPANY does not cross to a sibling company")
        void companyDoesNotCross() {
            org.position("acc-1", "emp-1", "hq", "rank-이사", null, null, null)
               .grantToRank("rank-이사", "이사", "hr.employee:read", PermissionScope.COMPANY);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "박지훈", "emp-1");

            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("sales", "acme", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("tokyo", "acme-jp", null)).isAllowed())
                    .as("a 본사 이사 does not automatically reach the 자회사")
                    .isFalse();
        }

        @Test
        @DisplayName("SELF reaches only rows owned by this account's employee")
        void selfIsOwnRowsOnly() {
            org.position("acc-1", "emp-1", "sales", "rank-사원", null, null, null)
               .grantToRank("rank-사원", "사원", "hr.leaveRequest:create", PermissionScope.SELF);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "최수빈", "emp-1");

            assertThat(evaluator.check(principal, "hr.leaveRequest", "create",
                    targetIn("sales", "acme", "emp-1")).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "hr.leaveRequest", "create",
                    targetIn("sales", "acme", "emp-2")).isAllowed()).isFalse();
        }

        @Test
        @DisplayName("ALL reaches everything, including other companies")
        void allReachesEverything() {
            org.position("acc-1", "emp-1", "hq", "rank-대표", null, null, null)
               .grantToRank("rank-대표", "대표이사", "hr.employee:read", PermissionScope.ALL);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "대표", "emp-1");

            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("tokyo", "acme-jp", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "hr.employee", "read",
                    PermissionTarget.installationWide(TODAY)).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("explicit deny")
    class Denies {

        @Test
        @DisplayName("beats an allow of any width, within its own scope")
        void denyBeatsAllow() {
            org.position("acc-1", "emp-1", "finance", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "hr.employee:read", PermissionScope.COMPANY)
               .denyToAccount("acc-1", "김민준", "hr.employee:read", PermissionScope.COMPANY);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            PermissionDecision decision = evaluator.check(principal, "hr.employee", "read",
                    targetIn("sales", "acme", null));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.summary()).contains("Explicitly denied");
            assertThat(decision.summary()).contains("not overridden");
        }

        @Test
        @DisplayName("a narrow deny leaves a wider allow intact outside its scope")
        void narrowDenyIsNarrow() {
            // "may read everyone in the company, except their own record"
            org.position("acc-1", "emp-1", "finance", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "hr.employee:read", PermissionScope.COMPANY)
               .denyToAccount("acc-1", "김민준", "hr.employee:read", PermissionScope.SELF);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("sales", "acme", "emp-2")).isAllowed())
                    .as("someone else's record is still readable")
                    .isTrue();
            assertThat(evaluator.check(principal, "hr.employee", "read",
                    targetIn("finance", "acme", "emp-1")).isAllowed())
                    .as("their own record is denied")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("as-of resolution")
    class AsOf {

        @Test
        @DisplayName("a past-dated document resolves against the org as it stood then")
        void pastDatedUsesPastOrg() {
            // Promoted out of 회계팀 into 영업본부 on 1 June.
            org.position("acc-1", "emp-1", "finance", "rank-과장", null, null, LocalDate.of(2026, 6, 1))
               .position("acc-1", "emp-1", "sales", "rank-부장", null, LocalDate.of(2026, 6, 1), null)
               .grantToRank("rank-과장", "과장", "accounting.entry:post", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            PermissionTarget marchEntry = PermissionTarget.builder()
                    .orgUnitId("finance").companyId("acme")
                    .asOfBusinessDate(LAST_MARCH).description("March journal entry").build();
            assertThat(evaluator.check(principal, "accounting.entry", "post", marchEntry).isAllowed())
                    .as("in March they were a 과장 in 회계팀, so the March entry is theirs to post")
                    .isTrue();

            PermissionTarget todayEntry = PermissionTarget.builder()
                    .orgUnitId("finance").companyId("acme")
                    .asOfBusinessDate(TODAY).description("today's journal entry").build();
            assertThat(evaluator.check(principal, "accounting.entry", "post", todayEntry).isAllowed())
                    .as("today they are in 영업본부 and no longer post to the ledger")
                    .isFalse();
        }

        @Test
        @DisplayName("a target must state its business date rather than defaulting to today")
        void businessDateIsRequired() {
            assertThatThrownBy(() -> PermissionTarget.builder().orgUnitId("finance").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("asOfBusinessDate is required");
        }
    }

    @Nested
    @DisplayName("the explainer")
    class Explainer {

        @Test
        @DisplayName("names the deciding grant and its source")
        void namesTheDecidingGrant() {
            org.position("acc-1", "emp-1", "finance", "rank-과장", "job-회계", null, null)
               .grantToJobFunction("job-회계", "회계", "accounting.entry:post", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            PermissionDecision decision = evaluator.check(principal, "accounting.entry", "post",
                    targetIn("finance", "acme", null));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.decidingGrant().source()).isEqualTo(GrantSource.JOB_FUNCTION);
            assertThat(decision.decidingGrant().sourceLabel()).isEqualTo("회계");
            assertThat(decision.explain())
                    .contains("ALLOWED")
                    .contains("JOB_FUNCTION")
                    .contains("회계");
        }

        @Test
        @DisplayName("records why a grant that exists did not apply")
        void recordsWhyAGrantDidNotApply() {
            org.position("acc-1", "emp-1", "sales", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "hr.employee:read", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");

            PermissionDecision decision = evaluator.check(principal, "hr.employee", "read",
                    targetIn("finance", "acme", null));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.considerations()).hasSize(1);
            assertThat(decision.considerations().get(0).applied()).isFalse();
            assertThat(decision.considerations().get(0).reason())
                    .contains("holds no position in unit finance");
        }

        @Test
        @DisplayName("lists everything a principal may do, as of a date")
        void listsEffectivePermissions() {
            org.position("acc-1", "emp-1", "finance", "rank-과장", "job-회계", null, null)
               .grantToRank("rank-과장", "과장", "hr.employee:read", PermissionScope.ORG_UNIT)
               .grantToJobFunction("job-회계", "회계", "accounting.entry:post", PermissionScope.ORG_UNIT)
               .denyToAccount("acc-1", "김민준", "accounting.entry:void", PermissionScope.COMPANY);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "김민준", "emp-1");

            EffectivePermissions effective = evaluator.effectivePermissions(principal, TODAY);

            assertThat(effective.grants()).hasSize(3);
            assertThat(effective.byKey().keySet())
                    .extracting(Object::toString)
                    .containsExactlyInAnyOrder(
                            "hr.employee:read", "accounting.entry:post", "accounting.entry:void");
            assertThat(effective.orgState().rankIds()).containsExactly("rank-과장");
        }
    }

    @Nested
    @DisplayName("wildcards")
    class Wildcards {

        @Test
        @DisplayName("a granted action wildcard covers every action on that resource")
        void actionWildcard() {
            org.position("acc-1", "emp-1", "finance", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "accounting.entry:*", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");

            assertThat(evaluator.check(principal, "accounting.entry", "post",
                    targetIn("finance", "acme", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "accounting.entry", "void",
                    targetIn("finance", "acme", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "accounting.account", "create",
                    targetIn("finance", "acme", null)).isAllowed()).isFalse();
        }

        @Test
        @DisplayName("a granted resource wildcard covers descendants but not the bare prefix")
        void resourceWildcard() {
            org.position("acc-1", "emp-1", "finance", "rank-부장", null, null, null)
               .grantToRank("rank-부장", "부장", "accounting.*:read", PermissionScope.ORG_UNIT);
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");

            assertThat(evaluator.check(principal, "accounting.entry", "read",
                    targetIn("finance", "acme", null)).isAllowed()).isTrue();
            assertThat(evaluator.check(principal, "accounting", "read",
                    targetIn("finance", "acme", null)).isAllowed())
                    .as("'accounting.*' covers what is beneath accounting, not accounting itself")
                    .isFalse();
            assertThat(evaluator.check(principal, "accountingx.entry", "read",
                    targetIn("finance", "acme", null)).isAllowed())
                    .as("prefix matching must respect the dot boundary")
                    .isFalse();
        }

        @Test
        @DisplayName("asking whether you may do a wildcard is a programming error")
        void requiredWildcardRejected() {
            PermissionPrincipal principal = PermissionPrincipal.user("acc-1", "이서연", "emp-1");
            assertThatThrownBy(() -> evaluator.check(principal,
                    PermissionKey.parse("accounting.entry:*"), targetIn("finance", "acme", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be concrete");
        }

        @Test
        @DisplayName("a bare '*' resource cannot be granted at all")
        void bareStarRejected() {
            assertThatThrownBy(() -> PermissionKey.of("*", "read"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("explainer");
        }
    }
}
