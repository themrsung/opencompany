package com.coreintra.app.api.audit;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.runtime.audit.AuditLogRow;
import com.coreintra.runtime.audit.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the audit log. There is no endpoint that writes one and none that
 * deletes one.
 *
 * <p>That absence is the feature. §12 says no account can delete the audit log,
 * master included, and the database enforces it with a trigger — but an API
 * that offered a delete would still be a bug worth having caught in review, so
 * this class exists partly to be the place where someone looks for one and does
 * not find it. Writing is likewise not exposed: entries are made by the code
 * that performs the action, never by a caller describing what they claim to
 * have done.
 *
 * <p>Retention is configurable <b>upward only</b>, which is also enforced in the
 * service. The endpoint here can raise it and has no counterpart that lowers
 * it.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Append-only. Readable, exportable, and impossible to erase.")
public class AuditController {

    private static final PermissionKey READ = PermissionKey.parse("admin.audit:read");
    private static final PermissionKey RETENTION = PermissionKey.parse("admin.audit:retention");

    private final AuditLogService audit;
    private final PermissionEvaluator evaluator;
    private final CurrentPrincipal current;

    public AuditController(AuditLogService audit, PermissionEvaluator evaluator,
            CurrentPrincipal current) {
        this.audit = audit;
        this.evaluator = evaluator;
        this.current = current;
    }

    /** One entry, flattened for reading. */
    public static class Entry {
        private final String id;
        private final String actorAccountId;
        private final String actorKind;
        private final String actorDisplayName;
        private final String outcome;
        private final String capability;
        private final String resource;
        private final String resourceId;
        private final String action;
        private final int rowsTouched;
        private final String businessInstant;
        private final String recordedAt;
        private final String requestId;

        Entry(AuditLogRow row) {
            this.id = row.id();
            this.actorAccountId = row.actorAccountId();
            this.actorDisplayName = row.actorDisplayName();
            this.outcome = String.valueOf(row.outcome());
            this.actorKind = String.valueOf(row.actorKind());
            this.capability = row.capability();
            this.resource = row.resource();
            this.resourceId = row.resourceId();
            this.action = String.valueOf(row.action());
            this.rowsTouched = row.rowsTouched();
            this.businessInstant = row.occurredAt() == null
                    ? null : row.occurredAt().toBusinessInstant().toWireString();
            this.recordedAt = String.valueOf(row.createdAt());
            this.requestId = row.requestId();
        }

        public String getId() {
            return id;
        }

        public String getActorAccountId() {
            return actorAccountId;
        }

        public String getActorKind() {
            return actorKind;
        }

        /** Snapshotted, so the trail still reads correctly after someone is renamed. */
        public String getActorDisplayName() {
            return actorDisplayName;
        }

        /** A refused attempt is recorded too: it is often the more interesting entry. */
        public String getOutcome() {
            return outcome;
        }

        public String getCapability() {
            return capability;
        }

        public String getResource() {
            return resource;
        }

        public String getResourceId() {
            return resourceId;
        }

        public String getAction() {
            return action;
        }

        public int getRowsTouched() {
            return rowsTouched;
        }

        /**
         * Business time: what the organisation agrees happened.
         *
         * <p>Reported alongside {@link #getRecordedAt()} and never instead of
         * it. The two disagreeing is information — a shift stamped 27:00 was
         * recorded at 03:00 UTC the following day — and collapsing them into one
         * field would destroy exactly the evidence an auditor came for.
         */
        public String getBusinessInstant() {
            return businessInstant;
        }

        /** UTC: what the machine observed. */
        public String getRecordedAt() {
            return recordedAt;
        }

        public String getRequestId() {
            return requestId;
        }
    }

    @GetMapping("/trail")
    @Operation(summary = "Everything that happened to one thing",
            description = "The trail for a single resource, oldest first.")
    public List<Entry> trail(@RequestParam String companyId, @RequestParam String resource,
            @RequestParam String resourceId) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, READ, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description("audit trail for " + resource + " " + resourceId)
                .build()).orThrow();

        return map(audit.trailFor(companyId, resource, resourceId));
    }

    @GetMapping("/support-session/{grantId}")
    @Operation(summary = "Everything a support session did",
            description = "The doubled audit: for a temporary master, every request including "
                    + "reads is here.")
    public List<Entry> supportSession(@PathVariable String grantId,
            @RequestParam String companyId) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, READ, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description("support session trail")
                .build()).orThrow();

        return map(audit.trailForGrant(grantId));
    }

    @GetMapping("/retention")
    @Operation(summary = "How long entries are kept")
    public int retention(@RequestParam String companyId) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, READ, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description("audit retention")
                .build()).orThrow();
        return audit.retentionDays(companyId);
    }

    @PostMapping("/retention")
    @Operation(summary = "Keep entries for longer",
            description = "Upward only. There is no endpoint that shortens retention, because "
                    + "shortening it is how a log gets quietly emptied.")
    public ResponseEntity<Void> raiseRetention(@RequestParam String companyId,
            @RequestParam int days) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, RETENTION, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description("audit retention")
                .build()).orThrow();

        audit.raiseRetention(companyId, days, principal.accountId());
        return ResponseEntity.noContent().build();
    }

    private static List<Entry> map(List<AuditLogRow> rows) {
        List<Entry> mapped = new ArrayList<Entry>(rows.size());
        for (AuditLogRow row : rows) {
            mapped.add(new Entry(row));
        }
        return Immutables.copyOf(mapped);
    }
}
