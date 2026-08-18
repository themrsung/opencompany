package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.org.Company;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Multi-entity from day one: 본사, 지사 and 자회사 in one table, and who owns whom. */
class CompanyServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        // Re-parenting reaches two companies, and only one of them is the one this
        // administrator holds a position in.
        fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "company.settings:update",
                PermissionScope.ALL);
        fixture.grant(GrantSource.USER_ACCOUNT, admin.accountId(), "company.settings:read", PermissionScope.ALL);
    }

    @Nested
    @DisplayName("who owns whom")
    class Ownership {

        @Test
        @DisplayName("a 자회사 must say which company it belongs to")
        void subsidiaryNeedsAParent() {
            assertThatThrownBy(() -> fixture.companies.create(admin, "SUB", "자회사",
                    Company.CompanyKind.SUBSIDIARY, null, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must say which company");
        }

        @Test
        @DisplayName("a 본사 cannot belong to anybody")
        void headOfficeHasNoParent() {
            assertThatThrownBy(() -> fixture.companies.create(admin, "HQ2", "제2본사",
                    Company.CompanyKind.HEAD_OFFICE, OrgFixture.COMPANY, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("본사");
        }

        @Test
        @DisplayName("a company code is used once across the installation")
        void codeIsUniqueInstallationWide() {
            assertThatThrownBy(() -> fixture.companies.create(admin, "ACME", "에이컴 2",
                    Company.CompanyKind.SUBSIDIARY, OrgFixture.COMPANY, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in use");
        }

        @Test
        @DisplayName("a company cannot be moved under a company it already owns")
        void ownershipCannotCycle() {
            Company first = fixture.companies.create(admin, "SUB1", "자회사1", Company.CompanyKind.SUBSIDIARY,
                    OrgFixture.COMPANY, OrgFixture.TODAY);
            Company second = fixture.companies.create(admin, "SUB2", "자회사2", Company.CompanyKind.SUBSIDIARY,
                    first.id(), OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.companies.reparent(admin, first.id(), second.id(),
                    OrgFixture.TODAY))
                    .as("a foreign key onto the same table is perfectly happy with a ring; "
                            + "a group that owns itself has no head office")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already owns it");
        }

        @Test
        @DisplayName("deactivating a company that still owns others is refused")
        void cannotDeactivateAnOwner() {
            fixture.companies.create(admin, "SUB1", "자회사1", Company.CompanyKind.SUBSIDIARY, OrgFixture.COMPANY,
                    OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.companies.deactivate(admin, OrgFixture.COMPANY, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("still owns");
        }

        @Test
        @DisplayName("deactivating keeps the row, because signed documents point at it")
        void deactivationIsNotDeletion() {
            Company sub = fixture.companies.create(admin, "SUB1", "자회사1", Company.CompanyKind.SUBSIDIARY,
                    OrgFixture.COMPANY, OrgFixture.TODAY);

            fixture.companies.deactivate(admin, sub.id(), OrgFixture.TODAY);

            assertThat(fixture.book.companies().findById(sub.id())).isPresent();
            assertThat(fixture.book.companies().findById(sub.id()).get().isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("authority over a company is not authority to incorporate another")
    class Authorisation {

        @Test
        @DisplayName("a company-scoped administrator cannot add a legal entity")
        void createIsInstallationWide() {
            PermissionPrincipal local = fixture.person("emp-local", "지점장");
            fixture.position("emp-local", "hq", "대표", OrgFixture.JANUARY);
            fixture.grant(GrantSource.USER_ACCOUNT, local.accountId(), "admin.company:create",
                    PermissionScope.COMPANY);

            assertThatThrownBy(() -> fixture.companies.create(local, "SUB1", "자회사1",
                    Company.CompanyKind.SUBSIDIARY, OrgFixture.COMPANY, OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("the list shows the companies the caller may read, and no others")
        void listIsFiltered() {
            fixture.companies.create(admin, "SUB1", "자회사1", Company.CompanyKind.SUBSIDIARY, OrgFixture.COMPANY,
                    OrgFixture.TODAY);
            PermissionPrincipal local = fixture.person("emp-local", "지점장");
            fixture.position("emp-local", "hq", "대표", OrgFixture.JANUARY);
            fixture.grant(GrantSource.USER_ACCOUNT, local.accountId(), "company.settings:read",
                    PermissionScope.COMPANY);

            assertThat(codes(fixture.companies.list(local, OrgFixture.TODAY)))
                    .as("a subsidiary is a separate legal entity; reaching into it needs an "
                            + "explicit installation-wide grant")
                    .containsExactly("ACME");
            assertThat(codes(fixture.companies.list(admin, OrgFixture.TODAY))).containsExactly("ACME", "SUB1");
        }
    }

    private static List<String> codes(List<Company> companies) {
        List<String> codes = new ArrayList<String>();
        for (Company company : companies) {
            codes.add(company.code());
        }
        return codes;
    }
}
