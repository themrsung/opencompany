package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.Position;
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

/** 직무: the client's own catalogue, and what retiring one would quietly do. */
class JobFunctionServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        fixture.rank("사원", "사원", 10);
    }

    @Nested
    @DisplayName("the catalogue is the client's")
    class ClientDefined {

        @Test
        @DisplayName("a 직무 can be created and relabelled in both languages")
        void createAndRelabel() {
            JobFunction created = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "hr", "인사",
                    "People", OrgFixture.TODAY);

            JobFunction renamed = fixture.jobFunctions.relabel(admin, created.id(), "인사총무",
                    "People & Admin", OrgFixture.TODAY);

            assertThat(renamed.labelKo()).isEqualTo("인사총무");
            assertThat(renamed.labelEn()).isEqualTo("People & Admin");
        }

        @Test
        @DisplayName("a code another 직무 already uses is refused, retired ones included")
        void codeIsUniquePerCompany() {
            JobFunction created = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "hr", "인사", null,
                    OrgFixture.TODAY);
            fixture.jobFunctions.retire(admin, created.id(), OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "hr", "인사2", null,
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in use");
        }

        @Test
        @DisplayName("retired entries are out of the list unless they are asked for")
        void retiredAreHiddenByDefault() {
            JobFunction kept = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "acc", "회계", null,
                    OrgFixture.TODAY);
            JobFunction gone = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "hr", "인사", null,
                    OrgFixture.TODAY);
            fixture.jobFunctions.retire(admin, gone.id(), OrgFixture.TODAY);

            assertThat(labels(fixture.jobFunctions.list(admin, OrgFixture.COMPANY, false, OrgFixture.TODAY)))
                    .containsExactly("회계");
            assertThat(labels(fixture.jobFunctions.list(admin, OrgFixture.COMPANY, true, OrgFixture.TODAY)))
                    .containsExactly("회계", "인사");
            assertThat(fixture.book.jobFunctions().findById(kept.id())).isPresent();
        }
    }

    @Nested
    @DisplayName("retiring a 직무 somebody performs")
    class Retiring {

        @Test
        @DisplayName("is refused, because grants attached to it reach people through it")
        void refusedWhilePerformed() {
            JobFunction accounting = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "acc", "회계", null,
                    OrgFixture.TODAY);
            fixture.employee("emp-1", "김민준");
            Position held = fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
            fixture.link(held, accounting);

            assertThatThrownBy(() -> fixture.jobFunctions.retire(admin, accounting.id(), OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("performed by");
        }

        @Test
        @DisplayName("is allowed once the position performing it has closed")
        void allowedOnceNobodyPerformsIt() {
            JobFunction accounting = fixture.jobFunctions.create(admin, OrgFixture.COMPANY, "acc", "회계", null,
                    OrgFixture.TODAY);
            fixture.employee("emp-1", "김민준");
            Position held = fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY, OrgFixture.JUNE);
            fixture.link(held, accounting);

            fixture.jobFunctions.retire(admin, accounting.id(), OrgFixture.TODAY);

            assertThat(fixture.book.jobFunctions().findById(accounting.id()).get().isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("a caller with no grants cannot add a 직무")
    void createNeedsPermission() {
        PermissionPrincipal nobody = fixture.person("emp-9", "박지훈");
        fixture.position("emp-9", "sales", "사원", OrgFixture.JANUARY);
        fixture.grant(GrantSource.RANK, "사원", "hr.jobFunction:read", PermissionScope.COMPANY);

        assertThatThrownBy(() -> fixture.jobFunctions.create(nobody, OrgFixture.COMPANY, "hr", "인사", null,
                OrgFixture.TODAY))
                .isInstanceOf(PermissionDeniedException.class);
    }

    private static List<String> labels(List<JobFunction> jobFunctions) {
        List<String> labels = new ArrayList<String>();
        for (JobFunction jobFunction : jobFunctions) {
            labels.add(jobFunction.labelKo());
        }
        return labels;
    }
}
