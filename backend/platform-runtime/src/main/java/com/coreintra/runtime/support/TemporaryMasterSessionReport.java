package com.coreintra.runtime.support;

import com.coreintra.compat.Immutables;
import com.coreintra.runtime.audit.AuditLogRow;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a support session actually did, produced when it ends (§8).
 *
 * <p>Generated from the audit trail rather than accumulated alongside it. Two
 * records of the same session would eventually disagree, and the one anybody
 * would believe is the append-only one - so the report is a view over it, and a
 * session whose report is regenerated years later reads the same.
 *
 * <p>"Nothing was accessed" is itself the answer a client wants, so a report is
 * produced for a session that did nothing. An absent report is indistinguishable
 * from a lost one.
 */
public final class TemporaryMasterSessionReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Why the session ended. Both are normal; neither is a fault. */
    public static final String EXPIRED = "expired";
    public static final String REVOKED = "revoked";

    private final String id;
    private final String grantId;
    private final String companyId;
    private final String engineerName;
    private final String reason;
    private final String ticketReference;
    private final OffsetDateTime issuedAt;
    private final OffsetDateTime endedAt;
    private final String endedReason;
    private final List<String> capabilities;
    private final List<AuditLogRow> actions;
    private final int rowsTouched;

    TemporaryMasterSessionReport(String id, TemporaryMasterGrantRow grant, OffsetDateTime endedAt,
                                 String endedReason, List<AuditLogRow> actions) {
        this.id = id;
        this.grantId = grant.id();
        this.companyId = grant.companyId();
        this.engineerName = grant.engineerName();
        this.reason = grant.reason();
        this.ticketReference = grant.ticketReference();
        this.issuedAt = grant.issuedAt();
        this.endedAt = endedAt;
        this.endedReason = endedReason;
        this.capabilities = Immutables.copyOf(grant.capabilities());
        this.actions = Immutables.copyOf(actions);
        int touched = 0;
        for (AuditLogRow action : actions) {
            touched += action.rowsTouched();
        }
        this.rowsTouched = touched;
    }

    public String id() {
        return id;
    }

    public String grantId() {
        return grantId;
    }

    public String companyId() {
        return companyId;
    }

    public String engineerName() {
        return engineerName;
    }

    public String reason() {
        return reason;
    }

    public String ticketReference() {
        return ticketReference;
    }

    public OffsetDateTime issuedAt() {
        return issuedAt;
    }

    public OffsetDateTime endedAt() {
        return endedAt;
    }

    public String endedReason() {
        return endedReason;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    /** Every audited action, oldest first, reads included. */
    public List<AuditLogRow> actions() {
        return actions;
    }

    public int rowsTouched() {
        return rowsTouched;
    }

    /**
     * The plain-text body, for the copy delivered to every master.
     *
     * <p>Deliberately not JSON: the recipients are 대표이사s and office managers,
     * and the first question they ask is what was looked at, not how to parse it.
     */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("지원 세션 보고서 / Support session report\n")
                .append("  세션 / Session:   ").append(grantId).append('\n')
                .append("  담당자 / Engineer: ").append(engineerName).append('\n')
                .append("  사유 / Reason:    ").append(reason).append('\n')
                .append("  티켓 / Ticket:    ")
                .append(ticketReference == null ? "(없음 / none)" : ticketReference).append('\n')
                .append("  발급 / Issued:    ").append(issuedAt).append('\n')
                .append("  종료 / Ended:     ").append(endedAt)
                .append(" (").append(endedReason).append(")\n")
                .append("  권한 / Granted:   ")
                .append(capabilities.isEmpty() ? "(없음 / none)" : capabilities).append('\n');

        if (actions.isEmpty()) {
            out.append("\n  이 세션에서 수행된 작업이 없습니다. / No actions were taken.\n");
            return out.toString();
        }
        out.append("\n  ").append(actions.size()).append(" action(s), ")
                .append(rowsTouched).append(" row(s) touched:\n");
        for (AuditLogRow action : actions) {
            out.append("    ")
                    .append(action.occurredAt().toBusinessInstant().toWireString()).append("  ")
                    .append(action.outcome()).append("  ")
                    .append(action.capability()).append("  ")
                    .append(action.resource()).append('/')
                    .append(action.resourceId() == null ? "-" : action.resourceId())
                    .append("  (").append(action.rowsTouched()).append(" row(s))\n");
        }
        return out.toString();
    }
}
