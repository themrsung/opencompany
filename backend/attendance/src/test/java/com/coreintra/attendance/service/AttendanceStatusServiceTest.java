package com.coreintra.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Client-defined statuses, and the claim that they are first-class.
 *
 * <p>The test that matters is
 * {@link #aClientStatusBehavesExactlyLikeABuiltInOne()}: 교육 defined by a client
 * must be indistinguishable from the seeded 근무 everywhere the flags are read.
 * If anything in the module ever branches on a status <em>code</em>, that test
 * is where it will show up.
 */
class AttendanceStatusServiceTest {

    private AttendanceTestWorld world;
    private AttendanceStatusService service;
    private AttendanceRecordService records;
    private OrgUnit team;
    private PermissionPrincipal admin;
    private PermissionPrincipal worker;

    @BeforeEach
    void setUp() {
        world = new AttendanceTestWorld();
        team = world.unit("DEV");
        admin = world.person("관리자", team);
        worker = world.person("김사원", team);
        world.grants.grant(admin, AttendancePermissions.STATUS_ADMIN, PermissionScope.COMPANY);
        world.grants.grant(worker, AttendancePermissions.ATTENDANCE_WRITE, PermissionScope.SELF);
        world.grants.grant(worker, AttendancePermissions.ATTENDANCE_READ, PermissionScope.SELF);
        service = world.statusService();
        records = world.recordService();
    }

    @Test
    @DisplayName("a client status behaves exactly like a built-in one")
    void aClientStatusBehavesExactlyLikeABuiltInOne() {
        service.define(admin, AttendanceTestWorld.COMPANY, "TRAINING", "교육", "Training",
                "#00695C", "book", StatusBehaviour.builder().countsAsWorking(true)
                        .visibleToPeers(true).build(), 70, AttendanceTestWorld.DAY);

        AttendanceStatusType training = service.require(AttendanceTestWorld.COMPANY, "TRAINING");
        assertThat(training.isBuiltIn())
                .as("built_in means 'restorable to factory state', not 'behaves differently'")
                .isFalse();
        assertThat(training.behaviour().countsAsWorking()).isTrue();
        assertThat(training.labelKo()).isEqualTo("교육");
        assertThat(training.labelEn()).isEqualTo("Training");

        assertThat(records.startStatus(worker, AttendanceTestWorld.COMPANY,
                worker.employeeId(), "TRAINING",
                com.coreintra.businesstime.BusinessInstant.of(AttendanceTestWorld.DAY, 9, 0, 0),
                null, null).statusTypeId())
                .isEqualTo(training.id());
    }

    @Test
    @DisplayName("a status that deducts leave must also require approval")
    void deductingWithoutApprovalIsRefused() {
        assertThatThrownBy(() -> StatusBehaviour.builder().deductsLeaveBalance(true)
                .requiresApproval(false).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nobody having agreed to it");
    }

    @Test
    @DisplayName("codes are unique within a company, because records refer to them")
    void codesAreUnique() {
        service.define(admin, AttendanceTestWorld.COMPANY, "TRAINING", "교육", null, null, null,
                AttendanceTestWorld.working(), 70, AttendanceTestWorld.DAY);

        assertThatThrownBy(() -> service.define(admin, AttendanceTestWorld.COMPANY, "TRAINING",
                "다른 교육", null, null, null, AttendanceTestWorld.working(), 80,
                AttendanceTestWorld.DAY))
                .isInstanceOf(AttendanceStatusService.StatusDefinitionException.class)
                .hasMessageContaining("already has");
    }

    @Test
    @DisplayName("a status needs a Korean label, because Korean is the default locale")
    void koreanLabelRequired() {
        assertThatThrownBy(() -> service.define(admin, AttendanceTestWorld.COMPANY, "X", null,
                "English only", null, null, AttendanceTestWorld.working(), 10,
                AttendanceTestWorld.DAY))
                .isInstanceOf(AttendanceStatusService.StatusDefinitionException.class)
                .hasMessageContaining("Korean label");
    }

    @Test
    @DisplayName("retiring a status hides it from the picker but leaves old records readable")
    void retireDoesNotDelete() {
        AttendanceStatusType training = service.define(admin, AttendanceTestWorld.COMPANY,
                "TRAINING", "교육", null, null, null, AttendanceTestWorld.working(), 70,
                AttendanceTestWorld.DAY);

        service.retire(admin, AttendanceTestWorld.COMPANY, training.id(),
                AttendanceTestWorld.DAY);

        assertThat(service.active(AttendanceTestWorld.COMPANY))
                .extracting(AttendanceStatusType::code)
                .doesNotContain("TRAINING");
        assertThat(world.statusTypes.findById(training.id()))
                .as("a year of timesheets pointing at a deleted row would read as blanks")
                .isPresent();
    }

    @Test
    @DisplayName("redefining a status changes what it means from now on")
    void redefine() {
        AttendanceStatusType away = service.define(admin, AttendanceTestWorld.COMPANY, "AWAY",
                "자리비움", null, null, null,
                StatusBehaviour.builder().visibleToPeers(true).build(), 40,
                AttendanceTestWorld.DAY);

        service.redefine(admin, AttendanceTestWorld.COMPANY, away.id(),
                StatusBehaviour.builder().visibleToPeers(true)
                        .autoExpiresAfter(Duration.ofHours(2)).build(),
                AttendanceTestWorld.DAY);

        assertThat(service.require(AttendanceTestWorld.COMPANY, "AWAY").behaviour()
                .expiresAutomatically()).isTrue();
    }

    @Test
    @DisplayName("defining a status is an administrative act and needs the grant")
    void definingNeedsPermission() {
        assertThatThrownBy(() -> service.define(worker, AttendanceTestWorld.COMPANY, "X", "엑스",
                null, null, null, AttendanceTestWorld.working(), 10, AttendanceTestWorld.DAY))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("hr.attendance.status:write");
    }

    @Test
    @DisplayName("an unknown code is refused by name, not silently ignored")
    void unknownCodeRefused() {
        assertThatThrownBy(() -> service.require(AttendanceTestWorld.COMPANY, "NOPE"))
                .isInstanceOf(AttendanceStatusService.StatusDefinitionException.class)
                .hasMessageContaining("\"NOPE\"");
    }
}
