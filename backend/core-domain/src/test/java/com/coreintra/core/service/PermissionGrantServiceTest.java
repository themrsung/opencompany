package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Writing the grant book, and the two rules that keep it honest: you cannot hand
 * out authority you do not hold, and an explicit deny is never out-voted.
 */
class PermissionGrantServiceTest {

    private static final PermissionKey POST = PermissionKey.parse("accounting.entry:post");
    private static final PermissionKey READ_EMPLOYEE = PermissionKey.parse("hr.employee:read");

    private OrgFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        fixture.rank("사원", "사원", 10);
        fixture.rank("과장", "과장", 30);
        fixture.jobFunction("회계", "회계");
    }

    /** Someone who administers permissions but holds no business permission of their own. */
    private PermissionPrincipal permissionAdmin() {
        PermissionPrincipal admin = fixture.person("emp-adm", "권한담당");
        fixture.position("emp-adm", "hq", "과장", OrgFixture.JANUARY);
        fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "admin.permission:grant",
                PermissionScope.COMPANY);
        fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "admin.permission:revoke",
                PermissionScope.COMPANY);
        fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "admin.permission:read",
                PermissionScope.COMPANY);
        return admin;
    }

    @Nested
    @DisplayName("a principal cannot grant a permission it does not itself hold")
    class NoSelfElevation {

        @Test
        @DisplayName("administering permissions is not the same as holding them")
        void cannotGrantWhatYouDoNotHold() {
            PermissionPrincipal admin = permissionAdmin();

            // The whole point: without this rule, admin.permission:grant is a root
            // account with extra steps.
            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원", POST,
                    PermissionScope.ORG_UNIT, true, "재경팀 전표 입력", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);

            assertThat(fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원")).isEmpty();
        }

        @Test
        @DisplayName("the same admin cannot write the permission to their own account either")
        void cannotElevateOwnAccount() {
            PermissionPrincipal admin = permissionAdmin();

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.USER_ACCOUNT, admin.accountId(), POST,
                    PermissionScope.COMPANY, true, "직접 부여", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("holding it makes the same grant go through")
        void holdingItIsEnough() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "accounting.entry:post",
                    PermissionScope.COMPANY);

            PermissionGrant granted = fixture.grants.grant(admin, GrantSource.RANK, "사원", POST,
                    PermissionScope.ORG_UNIT, true, "재경팀 전표 입력", OrgFixture.TODAY);

            assertThat(granted.key()).isEqualTo(POST);
            assertThat(granted.scope()).isEqualTo(PermissionScope.ORG_UNIT);
            assertThat(granted.sourceLabel()).isEqualTo("사원");
        }

        @Test
        @DisplayName("holding it in one unit is not enough to hand it to a whole rank")
        void narrowHoldingCannotBecomeWide() {
            PermissionPrincipal admin = permissionAdmin();
            // Held only inside 재경팀. A rank spans the company, so the reach of the
            // grant being written is wider than the authority behind it.
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "accounting.entry:post",
                    PermissionScope.ORG_UNIT);

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원", POST,
                    PermissionScope.ORG_UNIT, true, "재경팀 전표 입력", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("taking a right away is gated exactly as giving one is")
        void denyingAlsoNeedsTheRight() {
            PermissionPrincipal admin = permissionAdmin();

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원", POST,
                    PermissionScope.ORG_UNIT, false, "전표 입력 금지", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("revoking someone else's right needs that right too")
        void revokeNeedsTheRight() {
            PermissionPrincipal admin = permissionAdmin();
            PermissionGrantRow seeded = fixture.grant(GrantSource.RANK, "사원", "accounting.entry:post",
                    PermissionScope.ORG_UNIT);

            assertThatThrownBy(() -> fixture.grants.revoke(admin, seeded.id(), "정리", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
            assertThat(fixture.book.grants().findById(seeded.id())).isPresent();
        }

        @Test
        @DisplayName("a wildcard is not issued through this service at all")
        void wildcardsAreNotIssuedHere() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "accounting.entry:post",
                    PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원",
                    PermissionKey.parse("accounting.*:post"), PermissionScope.COMPANY, true, "일괄",
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("wildcard");
        }

        @Test
        @DisplayName("temporary master capabilities are not issued from the grant book")
        void temporaryMasterCapabilitiesAreElsewhere() {
            PermissionPrincipal admin = permissionAdmin();

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.TEMPORARY_MASTER_CAPABILITY,
                    admin.accountId(), POST, PermissionScope.COMPANY, true, "지원", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temporary-master flow");
        }
    }

    @Nested
    @DisplayName("an explicit deny beats a grant from every source")
    class DenyWins {

        /**
         * 김민준 is allowed to read employees four times over: by rank, by 직무, by
         * unit, and directly on the account. Each test then denies it from one
         * source and expects the read to fail.
         */
        private PermissionPrincipal allowedFromEverySource() {
            PermissionPrincipal person = fixture.person("emp-1", "김민준");
            fixture.position("emp-1", "finance", "과장", OrgFixture.JANUARY);
            fixture.link(fixture.book.positions().findByEmployeeIdOrderByEffectiveFromAsc("emp-1").get(0),
                    fixture.book.jobFunctions().findById("회계").get());
            fixture.grant(GrantSource.RANK, "과장", "hr.employee:read", PermissionScope.COMPANY);
            fixture.grant(GrantSource.JOB_FUNCTION, "회계", "hr.employee:read", PermissionScope.COMPANY);
            fixture.grant(GrantSource.ORG_UNIT, "finance", "hr.employee:read", PermissionScope.COMPANY);
            fixture.grant(GrantSource.USER_ACCOUNT, person.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);
            fixture.employee("emp-2", "이서준");
            return person;
        }

        @Test
        @DisplayName("four allows are not enough on their own to be interesting")
        void allowsWork() {
            PermissionPrincipal person = allowedFromEverySource();

            assertThat(fixture.employees.read(person, "emp-2", OrgFixture.TODAY).nameKo()).isEqualTo("이서준");
        }

        @Test
        @DisplayName("a deny attached to the rank beats all four allows")
        void denyOnRank() {
            PermissionPrincipal person = allowedFromEverySource();
            fixture.deny(GrantSource.RANK, "과장", "hr.employee:read", PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.employees.read(person, "emp-2", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("not overridden");
        }

        @Test
        @DisplayName("a deny attached to the 직무 beats all four allows")
        void denyOnJobFunction() {
            PermissionPrincipal person = allowedFromEverySource();
            fixture.deny(GrantSource.JOB_FUNCTION, "회계", "hr.employee:read", PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.employees.read(person, "emp-2", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a deny attached to the unit beats all four allows")
        void denyOnUnit() {
            PermissionPrincipal person = allowedFromEverySource();
            fixture.deny(GrantSource.ORG_UNIT, "finance", "hr.employee:read", PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.employees.read(person, "emp-2", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a deny attached to the account beats all four allows")
        void denyOnAccount() {
            PermissionPrincipal person = allowedFromEverySource();
            fixture.deny(GrantSource.USER_ACCOUNT, person.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.employees.read(person, "emp-2", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a denied read drops out of a list rather than emptying it")
        void listsFilterRatherThanFail() {
            PermissionPrincipal person = allowedFromEverySource();
            fixture.deny(GrantSource.RANK, "과장", "hr.employee:read", PermissionScope.COMPANY);

            assertThat(fixture.employees.list(person, OrgFixture.COMPANY, false, OrgFixture.TODAY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the grant book as an admin sees it")
    class Reading {

        @Test
        @DisplayName("lists what is already attached, with the source named")
        void listsAttachedGrants() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.RANK, "과장", "hr.employee:read", PermissionScope.ORG_UNIT);

            List<PermissionGrant> attached = fixture.grants.list(admin, GrantSource.RANK, "과장",
                    OrgFixture.TODAY);

            assertThat(attached).hasSize(1);
            assertThat(attached.get(0).key()).isEqualTo(READ_EMPLOYEE);
            assertThat(attached.get(0).sourceLabel()).isEqualTo("과장");
        }

        @Test
        @DisplayName("refuses to attach the same grant twice")
        void duplicatesAreRefused() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);
            fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE, PermissionScope.ORG_UNIT, true,
                    "팀원 조회", OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE,
                    PermissionScope.ORG_UNIT, true, "팀원 조회", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already attached");
        }

        @Test
        @DisplayName("a revocation stops the grant applying without deleting the row")
        void revokeRetiresWithoutDeleting() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);
            PermissionGrant granted = fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE,
                    PermissionScope.ORG_UNIT, true, "팀원 조회", OrgFixture.TODAY);
            String id = fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원").get(0).id();
            assertThat(granted.isAllow()).isTrue();

            fixture.grants.revoke(admin, id, "조직 개편", OrgFixture.TODAY);

            // Gone from the live view the admin screen and the evaluator share...
            assertThat(fixture.grants.list(admin, GrantSource.RANK, "사원", OrgFixture.TODAY)).isEmpty();
            // ...and still on the record, with who took it away and why. This is
            // what lets an auditor ask who could approve something last March.
            PermissionGrantRow retired =
                    fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원").get(0);
            assertThat(retired.isRevoked()).isTrue();
            assertThat(retired.revokedBy()).isEqualTo(admin.accountId());
            assertThat(retired.revokedReason()).isEqualTo("조직 개편");
        }

        @Test
        @DisplayName("a revoked permission can be granted again, because uniqueness is over live grants")
        void revokedGrantCanBeReissued() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);
            fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE,
                    PermissionScope.ORG_UNIT, true, "팀원 조회", OrgFixture.TODAY);
            String id = fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원").get(0).id();
            fixture.grants.revoke(admin, id, "조직 개편", OrgFixture.TODAY);

            assertThatCode(() -> fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE,
                    PermissionScope.ORG_UNIT, true, "원복", OrgFixture.TODAY))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("revoking twice is a no-op, not an error — two admins pressing the same button")
        void revokeIsIdempotent() {
            PermissionPrincipal admin = permissionAdmin();
            fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "hr.employee:read",
                    PermissionScope.COMPANY);
            fixture.grants.grant(admin, GrantSource.RANK, "사원", READ_EMPLOYEE,
                    PermissionScope.ORG_UNIT, true, "팀원 조회", OrgFixture.TODAY);
            String id = fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원").get(0).id();

            fixture.grants.revoke(admin, id, "조직 개편", OrgFixture.TODAY);
            assertThatCode(() -> fixture.grants.revoke(admin, id, "다시", OrgFixture.TODAY))
                    .doesNotThrowAnyException();

            // The first reason is the true one and is not overwritten.
            assertThat(fixture.book.grants().findBySourceAndSourceId(GrantSource.RANK, "사원").get(0)
                    .revokedReason()).isEqualTo("조직 개편");
        }
    }
}
