package com.coreintra.runtime.support;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.runtime.audit.AuditAction;
import com.coreintra.runtime.audit.AuditActorKind;
import com.coreintra.runtime.audit.AuditLogRow;
import com.coreintra.runtime.audit.AuditLogService;
import com.coreintra.runtime.audit.AuditOutcome;
import com.coreintra.runtime.testing.InMemoryAuditLog;
import com.coreintra.runtime.testing.InMemoryAuditRetention;
import com.coreintra.runtime.testing.InMemoryTemporaryMasterGrants;
import com.coreintra.runtime.testing.InMemoryTemporaryMasterSwitch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §8's doubled auditing, and the report it produces when the session ends.
 */
class TemporaryMasterAuditingTest {

    private static final Instant START = Instant.parse("2026-08-18T09:00:00Z");
    private static final OffsetDateTime ISSUED = OffsetDateTime.ofInstant(START, ZoneOffset.UTC);
    private static final BusinessInstant ISSUED_ON =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0);

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryTemporaryMasterGrants grants = new InMemoryTemporaryMasterGrants();
    private final InMemoryTemporaryMasterSwitch switches = new InMemoryTemporaryMasterSwitch();
    private final InMemoryAuditLog log = new InMemoryAuditLog();

    private final AuditLogService audit =
            new AuditLogService(log, new InMemoryAuditRetention(), clock);
    private final TemporaryMasterService sessions =
            new TemporaryMasterService(grants, switches, clock, false);
    private final TemporaryMasterAccessGate gate =
            new TemporaryMasterAccessGate(sessions, audit, clock);
    private final TemporaryMasterSessionReporter reporter =
            new TemporaryMasterSessionReporter(grants, audit, clock);

    @Test
    @DisplayName("a read a support session is allowed to make is logged, with its row count")
    void allowedReadsAreLogged() {
        TemporaryMasterGrantRow grant = issue("hr.employee:read");

        assertThat(gate.permits(grant.accountId(), "hr.employee:read", "employee", "e-1",
                AuditAction.READ, 1, request())).isTrue();

        AuditLogRow logged = log.rows().get(0);
        assertThat(logged.actorKind()).isEqualTo(AuditActorKind.TEMPORARY_MASTER);
        assertThat(logged.outcome()).isEqualTo(AuditOutcome.ALLOWED);
        assertThat(logged.capability()).isEqualTo("hr.employee:read");
        assertThat(logged.resource()).isEqualTo("employee");
        assertThat(logged.rowsTouched()).isEqualTo(1);
        assertThat(logged.temporaryMasterGrantId()).isEqualTo(grant.id());
        assertThat(logged.occurredAt().toBusinessInstant()).isEqualTo(ISSUED_ON);
    }

    @Test
    @DisplayName("a read a support session is refused is logged too")
    void refusedReadsAreLoggedAsWell() {
        TemporaryMasterGrantRow grant = issue();

        assertThat(gate.permits(grant.accountId(), "hr.compensation:read", "payslip", "p-1",
                AuditAction.READ, 4000, request())).isFalse();

        AuditLogRow logged = log.rows().get(0);
        assertThat(logged.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(logged.rowsTouched())
                .as("nothing was read, so nothing was touched")
                .isZero();
    }

    @Test
    @DisplayName("an export of four thousand rows is not recorded as one read")
    void rowCountsAreKept() {
        TemporaryMasterGrantRow grant = issue("hr.employee:export");

        gate.permits(grant.accountId(), "hr.employee:export", "employee", null,
                AuditAction.EXPORT, 4000, request());

        assertThat(log.rows().get(0).rowsTouched()).isEqualTo(4000);
        assertThat(log.rows().get(0).action()).isEqualTo(AuditAction.EXPORT);
    }

    @Test
    @DisplayName("the session report lists every action, refusals included")
    void reportCoversTheWholeSession() {
        TemporaryMasterGrantRow grant = issue("approval.document:read");
        gate.permits(grant.accountId(), "approval.document:read", "approvalDocument", "d-1",
                AuditAction.READ, 1, request());
        gate.permits(grant.accountId(), "hr.compensation:read", "payslip", "p-1",
                AuditAction.READ, 1, request());

        TemporaryMasterSessionReport report = reporter.reportFor(grant.id());

        assertThat(report.actions()).hasSize(2);
        assertThat(report.rowsTouched()).isEqualTo(1);
        assertThat(report.describe())
                .contains("approval.document:read")
                .contains("hr.compensation:read")
                .contains("DENIED");
    }

    @Test
    @DisplayName("a report is produced when the session is revoked, and says so")
    void reportOnRevocation() {
        TemporaryMasterGrantRow grant = issue("approval.document:read");
        clock.advance(Duration.ofMinutes(10));
        sessions.revoke(grant.id(), "master-1");

        List<TemporaryMasterSessionReport> produced = reporter.closeEndedSessions("c1");

        assertThat(produced).hasSize(1);
        assertThat(produced.get(0).endedReason()).isEqualTo(TemporaryMasterSessionReport.REVOKED);
        assertThat(grants.stored(grant.id()).sessionReportId()).isEqualTo(produced.get(0).id());
    }

    @Test
    @DisplayName("a report is produced when the session simply expires")
    void reportOnExpiry() {
        TemporaryMasterGrantRow grant = issue();

        assertThat(reporter.closeEndedSessions("c1"))
                .as("the session is still live; there is nothing to report yet")
                .isEmpty();

        clock.advance(Duration.ofHours(5));
        List<TemporaryMasterSessionReport> produced = reporter.closeEndedSessions("c1");

        assertThat(produced).hasSize(1);
        assertThat(produced.get(0).endedReason()).isEqualTo(TemporaryMasterSessionReport.EXPIRED);
        assertThat(produced.get(0).describe())
                .as("nothing accessed is itself the answer a client wants")
                .contains("No actions were taken");
        assertThat(reporter.closeEndedSessions("c1"))
                .as("a report is produced once, not on every sweep")
                .isEmpty();
        assertThat(grant.id()).isNotNull();
    }

    @Test
    @DisplayName("an account with no grant is permitted nothing and files no row in anyone's trail")
    void unknownAccountIsNotASession() {
        assertThat(gate.permits("stranger", "hr.employee:read", "employee", "e-1",
                AuditAction.READ, 1, request())).isFalse();
        assertThat(log.rows()).isEmpty();
    }

    private TemporaryMasterGrantRow issue(String... capabilities) {
        TemporaryMasterIssuance.Builder builder = TemporaryMasterIssuance.builder()
                .company("c1", "주식회사 에크미")
                .typedCompanyName("주식회사 에크미")
                .accountId("support-1")
                .issuedByAccountId("master-1")
                .engineerName("Jane Vendor")
                .reason("급여 내보내기가 실패하는 원인을 확인합니다.")
                .issuedAt(ISSUED)
                .issuedOn(ISSUED_ON)
                .timeToLive(Duration.ofHours(4))
                .approval("doc-1", TemporaryMasterIssuance.MODE_SEVERAL, 1)
                .approvedBy("rep-1", "김대표");
        for (String capability : capabilities) {
            builder.capability(capability);
        }
        return sessions.issue(builder.build());
    }

    private static TemporaryMasterAccessGate.RequestContext request() {
        return new TemporaryMasterAccessGate.RequestContext(
                ISSUED_ON, "req-1", "203.0.113.9", "CoreIntra Support Console");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
