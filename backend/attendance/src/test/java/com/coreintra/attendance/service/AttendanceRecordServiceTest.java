package com.coreintra.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Starting and ending a status, and the acceptance test the module exists for:
 * <b>a shift ending at 03:00 is stamped 27:00 on the business day it belongs
 * to</b>.
 */
class AttendanceRecordServiceTest {

    private AttendanceTestWorld world;
    private AttendanceRecordService service;
    private OrgUnit team;
    private PermissionPrincipal worker;

    @BeforeEach
    void setUp() {
        world = new AttendanceTestWorld();
        team = world.unit("DEV");
        worker = world.person("김사원", team);
        world.status("WORKING", AttendanceTestWorld.working());
        world.grants.grant(worker, AttendancePermissions.ATTENDANCE_WRITE, PermissionScope.SELF);
        world.grants.grant(worker, AttendancePermissions.ATTENDANCE_READ, PermissionScope.SELF);
        service = world.recordService();
    }

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(AttendanceTestWorld.DAY, hour, 0, 0);
    }

    @Nested
    @DisplayName("a shift crossing midnight")
    class MidnightCrossing {

        @Test
        @DisplayName("a shift ending at 03:00 is stamped 27:00 on the business day it began")
        void nightShiftEndsAt27() {
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(22), null, null);

            // The clock on the wall says 03:00 on the 31st. The shift is the
            // 30th's.
            AttendanceRecord closed = service.endOpenStatus(worker,
                    AttendanceTestWorld.COMPANY, worker.employeeId(),
                    LocalDateTime.of(2026, 8, 31, 3, 0));

            assertThat(closed.businessDate())
                    .as("the night shift belongs to the 30th, whole")
                    .isEqualTo(AttendanceTestWorld.DAY);
            assertThat(closed.interval().endedAt().toWireString())
                    .isEqualTo("2026-08-30T27:00:00.000");
            assertThat(closed.interval().endedAt().offsetSeconds()).isEqualTo(27 * 3600);
            assertThat(closed.interval().durationSeconds())
                    .as("five hours, not minus nineteen")
                    .isEqualTo(5 * 3600L);
            assertThat(closed.interval().endedAt().absoluteDateTime())
                    .as("the wall clock still reads 03:00 on the 31st; only the business day "
                            + "it is filed under differs")
                    .isEqualTo(LocalDateTime.of(2026, 8, 31, 3, 0));
        }

        @Test
        @DisplayName("the day's records still show one shift, not two fragments")
        void oneShiftNotTwo() {
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(22), null, null);
            service.endOpenStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    LocalDateTime.of(2026, 8, 31, 3, 0));

            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY)).hasSize(1);
            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY.plusDays(1)))
                    .as("nothing leaked into the 31st")
                    .isEmpty();
        }

        @Test
        @DisplayName("the open record is found on yesterday's business date, not just today's")
        void findsYesterdaysOpenRecord() {
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(23), null, null);

            AttendanceRecord closed = service.endOpenStatus(worker,
                    AttendanceTestWorld.COMPANY, worker.employeeId(),
                    LocalDateTime.of(2026, 8, 31, 1, 30));

            assertThat(closed.businessDate()).isEqualTo(AttendanceTestWorld.DAY);
            assertThat(closed.interval().endedAt().toWireString())
                    .isEqualTo("2026-08-30T25:30:00.000");
        }
    }

    @Nested
    @DisplayName("stamping a wall-clock moment onto a business day")
    class Stamping {

        @Test
        @DisplayName("03:00 the next morning is 27:00")
        void afterMidnight() {
            assertThat(AttendanceRecordService.stampOn(AttendanceTestWorld.DAY,
                    LocalDateTime.of(2026, 8, 31, 3, 0)).toWireString())
                    .isEqualTo("2026-08-30T27:00:00.000");
        }

        @Test
        @DisplayName("22:00 the evening before the day opens is -02:00")
        void beforeTheDayOpens() {
            assertThat(AttendanceRecordService.stampOn(AttendanceTestWorld.DAY,
                    LocalDateTime.of(2026, 8, 29, 22, 0)).toWireString())
                    .isEqualTo("2026-08-30T-02:00:00.000");
        }

        @Test
        @DisplayName("a moment outside the 72-hour window is refused rather than guessed")
        void outsideTheWindowRefused() {
            assertThatThrownBy(() -> AttendanceRecordService.stampOn(AttendanceTestWorld.DAY,
                    LocalDateTime.of(2026, 9, 3, 12, 0)))
                    .isInstanceOf(AttendanceRecordService.AttendanceRefusedException.class)
                    .hasMessageContaining("needs correcting");
        }
    }

    @Nested
    @DisplayName("overlap, which is configured rather than assumed")
    class Overlap {

        @Test
        @DisplayName("CLOSE_PREVIOUS ends the open status where the new one begins")
        void closePrevious() {
            world.status("FIELD", AttendanceTestWorld.working());
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);

            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "FIELD", at(14), null, null);

            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY).get(0).interval().endedAt().offsetSeconds())
                    .as("they stopped being at their desk at 14:00, and were not asked to say so "
                            + "separately")
                    .isEqualTo(14 * 3600);
        }

        @Test
        @DisplayName("REFUSE rejects the clash and names what it clashes with")
        void refuse() {
            world.settings = AttendanceSettings.fixed(OverlapPolicy.REFUSE);
            AttendanceRecordService strict = world.recordService();
            world.status("FIELD", AttendanceTestWorld.working());
            strict.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);

            assertThatThrownBy(() -> strict.startStatus(worker, AttendanceTestWorld.COMPANY,
                    worker.employeeId(), "FIELD", at(14), null, null))
                    .isInstanceOf(AttendanceRecordService.AttendanceRefusedException.class)
                    .hasMessageContaining("overlaps an existing record");
        }

        @Test
        @DisplayName("ALLOW lets two statuses run at once, for 교육 during 근무")
        void allow() {
            world.settings = AttendanceSettings.fixed(OverlapPolicy.ALLOW);
            AttendanceRecordService permissive = world.recordService();
            world.status("TRAINING", AttendanceTestWorld.working());
            permissive.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);

            permissive.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "TRAINING", at(14), null, null);

            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY)).hasSize(2);
            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY).get(0).isOpen()).isTrue();
        }

        @Test
        @DisplayName("touching is not overlapping — a handover at 18:00 is not a clash")
        void touchingIsNotAClash() {
            world.settings = AttendanceSettings.fixed(OverlapPolicy.REFUSE);
            AttendanceRecordService strict = world.recordService();
            world.status("FIELD", AttendanceTestWorld.working());
            strict.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);
            strict.endOpenStatusAt(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    at(18));

            strict.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "FIELD", at(18), null, null);

            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("statuses that need an approval behind them")
    class ApprovalBacked {

        @BeforeEach
        void leaveStatus() {
            world.status("LEAVE", StatusBehaviour.builder()
                    .requiresApproval(true)
                    .deductsLeaveBalance(true)
                    .visibleToPeers(true)
                    .build());
        }

        @Test
        @DisplayName("a status that deducts leave cannot be recorded without a 결재 document")
        void needsADocument() {
            assertThatThrownBy(() -> service.startStatus(worker, AttendanceTestWorld.COMPANY,
                    worker.employeeId(), "LEAVE", at(9), null, null))
                    .isInstanceOf(AttendanceRecordService.AttendanceRefusedException.class)
                    .hasMessageContaining("결재된 문서")
                    .hasMessageContaining("leave");
        }

        @Test
        @DisplayName("with the document, the record carries it")
        void carriesTheDocument() {
            AttendanceRecord record = service.startStatus(worker, AttendanceTestWorld.COMPANY,
                    worker.employeeId(), "LEAVE", at(9), "연차", "doc-leave-1");

            assertThat(record.sourceDocumentId()).isEqualTo("doc-leave-1");
        }
    }

    @Nested
    @DisplayName("auto-expiry")
    class AutoExpiry {

        private PermissionPrincipal sweeper;

        @BeforeEach
        void theScheduledJobRunsAsSomebody() {
            // Not a privileged internal caller: a named account whose grants can
            // be inspected like anyone else's (ADR 0003).
            sweeper = world.person("근태봇", team);
            world.grants.grant(sweeper, AttendancePermissions.ATTENDANCE_WRITE,
                    PermissionScope.COMPANY);
        }

        @Test
        @DisplayName("자리비움 is closed at the moment it expired, not when the sweep ran")
        void closedAtExpiryNotAtSweep() {
            world.status("AWAY", StatusBehaviour.builder()
                    .visibleToPeers(true)
                    .autoExpiresAfter(Duration.ofHours(2))
                    .build());
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "AWAY", at(10), null, null);

            assertThat(service.sweepExpired(sweeper, AttendanceTestWorld.COMPANY, at(11)))
                    .as("one hour in, it has not expired")
                    .isEmpty();

            assertThat(service.sweepExpired(sweeper, AttendanceTestWorld.COMPANY, at(15)))
                    .hasSize(1)
                    .first()
                    .extracting(record -> record.interval().endedAt().offsetSeconds())
                    .as("it lapsed at 12:00, whatever time the sweep happened to run")
                    .isEqualTo(12 * 3600);
        }

        @Test
        @DisplayName("yesterday's overnight status is swept too, not left open forever")
        void overnightRecordsAreSwept() {
            world.status("AWAY", StatusBehaviour.builder()
                    .visibleToPeers(true)
                    .autoExpiresAfter(Duration.ofHours(2))
                    .build());
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "AWAY", at(23), null, null);

            // The sweep runs at 09:00 the following business day.
            assertThat(service.sweepExpired(sweeper, AttendanceTestWorld.COMPANY,
                    BusinessInstant.of(AttendanceTestWorld.DAY.plusDays(1), 9, 0, 0)))
                    .as("elapsed time crosses the business-day boundary; comparing raw offsets "
                            + "would make a 23:00 record look one hour young at 09:00")
                    .hasSize(1);
            assertThat(service.dayOf(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    AttendanceTestWorld.DAY).get(0).interval().endedAt().offsetSeconds())
                    .isEqualTo(25 * 3600);
        }

        @Test
        @DisplayName("the sweep runs as a named account and is authorised like anyone else")
        void sweepIsAuthorised() {
            world.status("AWAY", StatusBehaviour.builder()
                    .visibleToPeers(true)
                    .autoExpiresAfter(Duration.ofHours(2))
                    .build());
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "AWAY", at(10), null, null);

            assertThatThrownBy(() -> service.sweepExpired(worker, AttendanceTestWorld.COMPANY,
                    at(15)))
                    .as("a SELF-scoped grant does not authorise closing the whole company's "
                            + "records; there is no privileged internal caller")
                    .isInstanceOf(PermissionDeniedException.class);
            assertThat(world.records.all().get(0).isOpen()).isTrue();
        }

        @Test
        @DisplayName("another company's open records are not this sweep's to close")
        void otherTenantsAreUntouched() {
            world.status("AWAY", StatusBehaviour.builder()
                    .visibleToPeers(true)
                    .autoExpiresAfter(Duration.ofHours(2))
                    .build());
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "AWAY", at(10), null, null);
            AttendanceRecord elsewhere = new AttendanceRecord("rec-other", "emp-somebody",
                    "st-OTHER-COMPANY", at(10));
            world.records.save(elsewhere);

            assertThat(service.sweepExpired(sweeper, AttendanceTestWorld.COMPANY, at(15)))
                    .hasSize(1);
            assertThat(elsewhere.isOpen()).isTrue();
        }

        @Test
        @DisplayName("a status with no expiry configured is left alone")
        void nonExpiringStatusUntouched() {
            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);

            assertThat(service.sweepExpired(sweeper, AttendanceTestWorld.COMPANY, at(23))).isEmpty();
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a SELF-scoped grant covers your own attendance and nobody else's")
        void selfScopeIsEnough() {
            PermissionPrincipal colleague = world.person("박사원", team);

            service.startStatus(worker, AttendanceTestWorld.COMPANY, worker.employeeId(),
                    "WORKING", at(9), null, null);

            assertThatThrownBy(() -> service.startStatus(worker, AttendanceTestWorld.COMPANY,
                    colleague.employeeId(), "WORKING", at(9), null, null))
                    .as("self-service is not a special case; it is a scope")
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a team lead with a unit-scoped grant may correct a colleague's record")
        void unitScopeReachesTheTeam() {
            PermissionPrincipal lead = world.person("이팀장", team);
            PermissionPrincipal colleague = world.person("박사원", team);
            world.grants.grant(lead, AttendancePermissions.ATTENDANCE_WRITE,
                    PermissionScope.ORG_UNIT);

            AttendanceRecord record = service.startStatus(lead, AttendanceTestWorld.COMPANY,
                    colleague.employeeId(), "WORKING", at(9), "대리 입력", null);

            assertThat(record.employeeId()).isEqualTo(colleague.employeeId());
        }

        @Test
        @DisplayName("the check is made on the record's business date, not on today's")
        void checksAgainstTheRecordsDate() {
            PermissionPrincipal newJoiner = world.person("신입", team);
            world.grants.grant(newJoiner, AttendancePermissions.ATTENDANCE_WRITE,
                    PermissionScope.SELF);
            // The position starts in 2020, so a 2019 record predates it. SELF
            // still reaches it — the owner is the owner — but the evaluator was
            // asked about 2019, which is the point.
            AttendanceRecord record = service.startStatus(newJoiner,
                    AttendanceTestWorld.COMPANY, newJoiner.employeeId(), "WORKING",
                    BusinessInstant.of(LocalDate.of(2019, 5, 1), 9, 0, 0), null, null);

            assertThat(record.businessDate()).isEqualTo(LocalDate.of(2019, 5, 1));
        }
    }
}
