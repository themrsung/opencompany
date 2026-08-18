package com.coreintra.runtime.support;

import com.coreintra.runtime.audit.AuditLogRow;
import com.coreintra.runtime.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produces the report §8 promises on expiry or revocation.
 *
 * <p>Reports are generated from the audit trail, so this class holds no state
 * of its own and a report can be regenerated at any time. What it does record
 * is that a report was produced - {@code session_report_id} on the grant - so
 * that a client can tell "the session ended and nobody was told" from "the
 * session ended and here is what happened".
 *
 * <p>{@link #closeEndedSessions} is the sweep. Access does not depend on it:
 * expiry is enforced by {@link TemporaryMasterGrantRow#allowsAt}, so a sweep
 * that is late, stuck or dead delays a report and never extends a session.
 */
@Service
public class TemporaryMasterSessionReporter {

    private final TemporaryMasterRepository grants;
    private final AuditLogService audit;
    private final Clock clock;

    public TemporaryMasterSessionReporter(TemporaryMasterRepository grants, AuditLogService audit,
                                          Clock clock) {
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The report for one session, whether or not it has ended.
     *
     * <p>Readable mid-session too: the banner's "what have they done so far?" is
     * the same question the report answers, and a client should not have to wait
     * for the session to end to ask it.
     */
    @Transactional(readOnly = true)
    public TemporaryMasterSessionReport reportFor(String grantId) {
        TemporaryMasterGrantRow grant = grants.findById(grantId).orElseThrow(
                () -> new IllegalArgumentException("no such support session: " + grantId));
        return build(grant, UUID.randomUUID().toString());
    }

    /**
     * Produces the outstanding reports for a company's ended sessions.
     *
     * @return the reports produced, for delivery to every master
     */
    @Transactional
    public List<TemporaryMasterSessionReport> closeEndedSessions(String companyId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<TemporaryMasterSessionReport> produced = new ArrayList<TemporaryMasterSessionReport>();
        for (TemporaryMasterGrantRow grant : grants.findByCompanyIdAndSessionReportIdIsNull(companyId)) {
            if (grant.isActiveAt(now)) {
                continue;
            }
            String reportId = UUID.randomUUID().toString();
            produced.add(build(grant, reportId));
            grant.attachSessionReport(reportId);
            grants.save(grant);
        }
        return produced;
    }

    private TemporaryMasterSessionReport build(TemporaryMasterGrantRow grant, String reportId) {
        List<AuditLogRow> actions = audit.trailForGrant(grant.id());
        boolean revoked = grant.revokedAt() != null;
        return new TemporaryMasterSessionReport(
                reportId,
                grant,
                revoked ? grant.revokedAt() : grant.expiresAt(),
                revoked ? TemporaryMasterSessionReport.REVOKED : TemporaryMasterSessionReport.EXPIRED,
                actions);
    }
}
