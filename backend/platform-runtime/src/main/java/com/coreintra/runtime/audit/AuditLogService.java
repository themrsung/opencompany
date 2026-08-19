package com.coreintra.runtime.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Writes the trail, and reads it back.
 *
 * <p>There is no delete and no update, here or anywhere below. That is the
 * whole design: §8 hands a client's data to a support engineer for a few hours,
 * and the only thing that makes it survivable is that afterwards every row the
 * engineer touched - including the rows they only looked at - can be read back
 * by the client, and neither the engineer nor the client's own master can edit
 * the record afterwards.
 *
 * <p>A promise enforced in a service is a promise that lasts until the next
 * endpoint is added, so the trigger in {@code V9__platform_runtime.sql} is the
 * real enforcement. This class is the part that makes the trigger unnecessary in
 * practice: no code path exists that would fire it.
 *
 * <h2>Why {@code REQUIRES_NEW}</h2>
 *
 * <p>A denied request usually ends in a rollback. If the audit write joined that
 * transaction, the record of a refusal would roll back with it, and the log
 * would contain only the successes - the exact shape of a log that hides an
 * attack. Each row is written in its own transaction so that it survives the
 * failure of the thing it describes.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository log;
    private final AuditRetentionPolicyRepository policies;
    private final Clock clock;

    /**
     * The clock is injected rather than read from {@code now()}.
     *
     * <p>Every other service here keeps a package-private constructor for its
     * own tests; this one is public because the audit log is written by
     * collaborators in other packages, and they have to be able to build it
     * against a fixed clock to assert on what was recorded. See
     * {@code PlatformRuntimeConfiguration} for the bean.
     */
    public AuditLogService(AuditLogRepository log, AuditRetentionPolicyRepository policies,
                           Clock clock) {
        this.log = log;
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * Records one event and returns its id.
     *
     * <p>Reads are recorded too. §8 doubles the auditing for support sessions,
     * and "which employees did the engineer open?" is not answerable from a log
     * that only keeps mutations.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String record(AuditEvent event) {
        String id = UUID.randomUUID().toString();
        log.save(new AuditLogRow(id, event, OffsetDateTime.now(clock)));
        return id;
    }

    @Transactional(readOnly = true)
    public List<AuditLogRow> trailFor(String companyId, String resource, String resourceId) {
        return log.findByCompanyIdAndResourceAndResourceIdOrderByCreatedAtDesc(
                companyId, resource, resourceId);
    }

    /** Everything done under one support session, oldest first. */
    @Transactional(readOnly = true)
    public List<AuditLogRow> trailForGrant(String grantId) {
        return log.findByTemporaryMasterGrantIdOrderByCreatedAtAsc(grantId);
    }

    @Transactional(readOnly = true)
    public int retentionDays(String companyId) {
        return policies.findById(companyId)
                .map(AuditRetentionPolicyRow::retentionDays)
                .orElse(AuditRetentionPolicyRow.FLOOR_DAYS);
    }

    /**
     * Lengthens how long the trail is kept.
     *
     * @throws IllegalArgumentException if the caller is trying to shorten it. A
     *         shorter retention destroys a trail without deleting a row, so it
     *         is refused for the same reason a delete is (§12).
     */
    @Transactional
    public void raiseRetention(String companyId, int retentionDays, String byAccountId) {
        AuditRetentionPolicyRow existing = policies.findById(companyId).orElse(null);
        if (existing == null) {
            policies.save(new AuditRetentionPolicyRow(
                    companyId, retentionDays, OffsetDateTime.now(clock), byAccountId));
            return;
        }
        existing.raiseTo(retentionDays, OffsetDateTime.now(clock), byAccountId);
        policies.save(existing);
    }
}
