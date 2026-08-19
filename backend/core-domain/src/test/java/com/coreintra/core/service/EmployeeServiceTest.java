package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.org.Employee;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** People: who may see them, and what "leaving" does to the row. */
class EmployeeServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        fixture.rank("사원", "사원", 10);
        fixture.rank("팀장", "팀장", 50);
    }

    @Nested
    @DisplayName("reads are scoped to where the person stands")
    class ScopedReads {

        @Test
        @DisplayName("a 팀장 sees their own team and not the one next door")
        void listIsScopedToTheUnit() {
            PermissionPrincipal lead = fixture.person("emp-lead", "팀장");
            fixture.position("emp-lead", "finance", "팀장", OrgFixture.JANUARY);
            fixture.grant(GrantSource.RANK, "팀장", "hr.employee:read", PermissionScope.ORG_UNIT);

            fixture.employee("emp-fin", "김민준");
            fixture.position("emp-fin", "finance", "사원", OrgFixture.JANUARY);
            fixture.employee("emp-sal", "이서준");
            fixture.position("emp-sal", "sales", "사원", OrgFixture.JANUARY);

            assertThat(names(fixture.employees.list(lead, OrgFixture.COMPANY, false, OrgFixture.TODAY)))
                    .containsExactly("김민준", "팀장");
        }

        @Test
        @DisplayName("SELF reaches your own record and nobody else's")
        void selfReachesOnlyYourOwnRow() {
            PermissionPrincipal person = fixture.person("emp-1", "김민준");
            fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
            fixture.grant(GrantSource.RANK, "사원", "hr.employee:read", PermissionScope.SELF);
            fixture.employee("emp-2", "이서준");

            assertThat(fixture.employees.read(person, "emp-1", OrgFixture.TODAY).nameKo()).isEqualTo("김민준");
            assertThatThrownBy(() -> fixture.employees.read(person, "emp-2", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a read of one named row says no rather than returning nothing")
        void readThrowsWhereListFilters() {
            PermissionPrincipal nobody = fixture.person("emp-3", "박지훈");
            fixture.employee("emp-4", "최유진");

            assertThat(fixture.employees.list(nobody, OrgFixture.COMPANY, false, OrgFixture.TODAY)).isEmpty();
            assertThatThrownBy(() -> fixture.employees.read(nobody, "emp-4", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }
    }

    @Nested
    @DisplayName("leaving is a date, not a deletion")
    class Leaving {

        @Test
        @DisplayName("a last day before the hire date is refused")
        void terminationCannotPrecedeHiring() {
            Employee hire = fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "김민준", null,
                    "minjun@example.com", OrgFixture.JUNE, OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.employees.terminate(admin, hire.id(), OrgFixture.JANUARY,
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("before the hire date");
        }

        @Test
        @DisplayName("a leaver keeps their row and drops out of the default list")
        void leaverIsKeptButHidden() {
            Employee hire = fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "김민준", null, null,
                    OrgFixture.JANUARY, OrgFixture.TODAY);
            fixture.employees.terminate(admin, hire.id(), LocalDate.of(2026, 7, 31), OrgFixture.TODAY);

            assertThat(names(fixture.employees.list(admin, OrgFixture.COMPANY, false, OrgFixture.TODAY)))
                    .doesNotContain("김민준");
            assertThat(names(fixture.employees.list(admin, OrgFixture.COMPANY, true, OrgFixture.TODAY)))
                    .as("last quarter's approvals were signed by people who have since left")
                    .contains("김민준");
        }

        @Test
        @DisplayName("a leaver was still employed on a business date before their last day")
        void pastDatesStillSeeThemEmployed() {
            Employee hire = fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "김민준", null, null,
                    OrgFixture.JANUARY, OrgFixture.TODAY);
            fixture.employees.terminate(admin, hire.id(), LocalDate.of(2026, 7, 31), OrgFixture.TODAY);

            assertThat(names(fixture.employees.list(admin, OrgFixture.COMPANY, false, OrgFixture.LAST_MARCH)))
                    .contains("김민준");
        }
    }

    @Nested
    @DisplayName("the client's own employee number")
    class EmployeeNumbers {

        @Test
        @DisplayName("is refused when another employee of the company already has it")
        void numberIsUniquePerCompany() {
            fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "김민준", null, null,
                    OrgFixture.JANUARY, OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "이서준",
                    null, null, OrgFixture.JANUARY, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in use");
        }

        @Test
        @DisplayName("is optional, because plenty of installations number nobody")
        void numberIsOptional() {
            Employee hire = fixture.employees.create(admin, OrgFixture.COMPANY, "  ", "김민준", null, null, null,
                    OrgFixture.TODAY);

            assertThat(hire.employeeNumber()).isNull();
        }

        @Test
        @DisplayName("can be kept unchanged when the same employee is updated")
        void ownNumberIsNotAClash() {
            Employee hire = fixture.employees.create(admin, OrgFixture.COMPANY, "2026-001", "김민준", null, null,
                    OrgFixture.JANUARY, OrgFixture.TODAY);

            Employee updated = fixture.employees.update(admin, hire.id(), "2026-001", "김민준", "Kim Minjun",
                    "minjun@example.com", OrgFixture.JANUARY, OrgFixture.TODAY);

            assertThat(updated.nameEn()).isEqualTo("Kim Minjun");
            assertThat(updated.employeeNumber()).isEqualTo("2026-001");
        }
    }

    private static List<String> names(List<Employee> employees) {
        List<String> names = new ArrayList<String>();
        for (Employee employee : employees) {
            names.add(employee.nameKo());
        }
        return names;
    }
}
