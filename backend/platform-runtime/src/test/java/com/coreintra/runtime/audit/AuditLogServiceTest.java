package com.coreintra.runtime.audit;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.runtime.testing.InMemoryAuditLog;
import com.coreintra.runtime.testing.InMemoryAuditRetention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneOffset.UTC);

    private static final BusinessInstant NINE_AM =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0);

    private final InMemoryAuditLog log = new InMemoryAuditLog();
    private final InMemoryAuditRetention retention = new InMemoryAuditRetention();
    private final AuditLogService service = new AuditLogService(log, retention, FIXED);

    @Test
    @DisplayName("a refused request is recorded, because a log of only successes hides an attack")
    void recordsDenials() {
        service.record(read(AuditOutcome.DENIED).build());

        assertThat(log.rows()).hasSize(1);
        assertThat(log.rows().get(0).outcome()).isEqualTo(AuditOutcome.DENIED);
    }

    @Test
    @DisplayName("business time and UTC are both recorded, and are not the same field")
    void keepsBothClocks() {
        service.record(read(AuditOutcome.ALLOWED).build());

        AuditLogRow row = log.rows().get(0);
        assertThat(row.occurredAt().toBusinessInstant()).isEqualTo(NINE_AM);
        assertThat(row.createdAt().toInstant()).isEqualTo(Instant.parse("2026-08-18T09:00:00Z"));
    }

    @Test
    @DisplayName("an event with no actor and no capability is refused rather than written vaguely")
    void refusesAnUnattributableEvent() {
        assertThatThrownBy(() -> AuditEvent.builder()
                .companyId("c1")
                .actor(null, AuditActorKind.USER, "김민준")
                .capability("hr.employee:read")
                .target("employee", "e1")
                .action(AuditAction.READ)
                .occurredAt(NINE_AM)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANONYMOUS");

        assertThatThrownBy(() -> AuditEvent.builder()
                .companyId("c1")
                .actor("u1", AuditActorKind.USER, "김민준")
                .target("employee", "e1")
                .action(AuditAction.READ)
                .occurredAt(NINE_AM)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability");
    }

    @Test
    @DisplayName("retention can be raised and cannot be lowered")
    void retentionIsUpwardOnly() {
        service.raiseRetention("c1", 1825, "master-1");
        assertThat(service.retentionDays("c1")).isEqualTo(1825);

        service.raiseRetention("c1", 3650, "master-1");
        assertThat(service.retentionDays("c1")).isEqualTo(3650);

        assertThatThrownBy(() -> service.raiseRetention("c1", 1825, "master-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upward only");
    }

    @Test
    @DisplayName("retention cannot start below the five-year floor")
    void retentionHasAFloor() {
        assertThatThrownBy(() -> service.raiseRetention("c1", 30, "master-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1825");
    }

    @Test
    @DisplayName("a support session's actions are readable as one trail")
    void collectsASessionTrail() {
        service.record(read(AuditOutcome.ALLOWED).underTemporaryMaster("g1").build());
        service.record(read(AuditOutcome.DENIED).underTemporaryMaster("g1").build());
        service.record(read(AuditOutcome.ALLOWED).build());

        assertThat(service.trailForGrant("g1")).hasSize(2);
    }

    private static AuditEvent.Builder read(AuditOutcome outcome) {
        return AuditEvent.builder()
                .companyId("c1")
                .actor("u1", AuditActorKind.USER, "김민준")
                .capability("hr.employee:read")
                .target("employee", "e1")
                .action(AuditAction.READ)
                .outcome(outcome)
                .rowsTouched(1)
                .occurredAt(NINE_AM);
    }
}
