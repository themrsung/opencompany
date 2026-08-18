package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

import com.coreintra.compat.Texts;

/**
 * A queued conversion (brief 6.4). Conversion is slow and memory-hungry and must never
 * occupy a request thread, so it is a job with a lease.
 *
 * <p>Bounded: {@link #attemptCount} against {@link #maxAttempts}, so a document that
 * crashes the worker is abandoned with an error a person can read rather than retried
 * until the end of time.
 *
 * <p>Idempotent: {@code idempotencyKey} is unique, so a user pressing Export twice joins
 * the existing job instead of starting a second LibreOffice.
 */
@Entity
@Table(name = "conversion_job")
public class ConversionJobEntity {

    /** Which worker takes the job. Two processes, two failure modes. */
    public enum Kind {

        /** Headless LibreOffice, driven by the conversion container. */
        LIBREOFFICE,

        /** The Node mdv exporter. Byte-deterministic, unlike the above. */
        MDV;

        /**
         * The worker a source format belongs to.
         *
         * <p>Derived rather than passed, because a caller that gets it wrong queues work
         * for a process that cannot do it and the job sits until its attempts run out.
         */
        public static Kind forSource(DocumentFormat sourceFormat) {
            return sourceFormat == DocumentFormat.MDV ? MDV : LIBREOFFICE;
        }
    }

    /** Where a job is. A lease that lapses returns a RUNNING job to the claimable set. */
    public enum State {

        QUEUED,

        /** Claimed by a worker until {@code leaseExpiresAt}. A worker that dies holds nothing. */
        RUNNING,

        SUCCEEDED,

        /** Failed with attempts left. Claimable again. */
        FAILED,

        /** Failed for the last time. Nobody will retry it; someone has to look. */
        ABANDONED
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /**
     * The bytes to convert, named directly.
     *
     * <p>The worker does not resolve a document to a version to a blob: three lookups it
     * could get wrong, on a document that may have gained a version since the job was
     * queued.
     */
    @Column(name = "source_blob_sha256", nullable = false, length = 64)
    private String sourceBlobSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_format", nullable = false, length = 8)
    private RenderFormat targetFormat;

    @Column(name = "config_fingerprint", nullable = false, length = 64)
    private String configFingerprint;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "lease_owner", length = 200)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "render_id", length = 36)
    private String renderId;

    /** Ours, and translatable. The UI says something useful in Korean from this. */
    @Column(name = "error_code", length = 60)
    private String errorCode;

    /** The worker's own words. Not translatable, and shown to an operator, not a user. */
    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "requested_by_account_id", length = 36)
    private String requestedByAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected ConversionJobEntity() {
    }

    public ConversionJobEntity(String id, String companyId, Kind kind, String documentId,
            int versionNo, String sourceBlobSha256, RenderFormat targetFormat,
            String configFingerprint, String idempotencyKey, String requestedByAccountId,
            int maxAttempts) {
        this.id = id;
        this.companyId = companyId;
        this.kind = kind;
        this.sourceBlobSha256 = sourceBlobSha256;
        this.documentId = documentId;
        this.versionNo = Integer.valueOf(versionNo);
        this.targetFormat = targetFormat;
        this.configFingerprint = configFingerprint;
        this.idempotencyKey = idempotencyKey;
        this.requestedByAccountId = requestedByAccountId;
        this.maxAttempts = maxAttempts;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** True when this job is waiting for a worker: queued, failed with attempts left, or lapsed. */
    public boolean isClaimableAt(OffsetDateTime now) {
        if (attemptCount >= maxAttempts) {
            return false;
        }
        if (state == State.QUEUED || state == State.FAILED) {
            return true;
        }
        return state == State.RUNNING && leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
    }

    /**
     * Claims the job for one worker until the lease expires.
     *
     * @throws IllegalStateException if the job is not claimable - a second worker taking a
     *         live lease is two LibreOffices on one document, which is the cost this queue
     *         exists to avoid
     */
    public void lease(String owner, OffsetDateTime expiresAt, OffsetDateTime now) {
        if (Texts.isBlank(owner)) {
            throw new IllegalArgumentException("a lease is held by a named worker");
        }
        if (!isClaimableAt(now)) {
            throw new IllegalStateException(
                    "job " + id + " is " + state + " with " + attemptCount + " of " + maxAttempts
                    + " attempts used and cannot be leased");
        }
        this.state = State.RUNNING;
        this.leaseOwner = owner;
        this.leaseExpiresAt = expiresAt;
        this.attemptCount = attemptCount + 1;
        this.errorCode = null;
        this.errorDetail = null;
        this.updatedAt = now;
    }

    /** The render landed. The job holds the render's id, which is how a poller finds it. */
    public void succeed(String renderId, OffsetDateTime now) {
        if (Texts.isBlank(renderId)) {
            throw new IllegalArgumentException(
                    "a job that succeeded without producing a render is a lie: the export UI "
                    + "would then have to resolve by re-rendering, silently");
        }
        this.state = State.SUCCEEDED;
        this.renderId = renderId;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /**
     * Records a failure. Abandons the job when the attempts are spent.
     *
     * @param errorCode ours and translatable, e.g. {@code WORKER_UNREACHABLE}
     * @param errorDetail the worker's own words, for an operator rather than a user
     * @throws IllegalArgumentException if the code is blank - "it failed" is not an error
     *         message, and this pair is the only thing a person gets
     */
    public void fail(String errorCode, String errorDetail, OffsetDateTime now) {
        if (Texts.isBlank(errorCode)) {
            throw new IllegalArgumentException("a failed job must say what went wrong");
        }
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        if (attemptCount >= maxAttempts) {
            this.state = State.ABANDONED;
            this.finishedAt = now;
        } else {
            this.state = State.FAILED;
        }
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public RenderFormat targetFormat() {
        return targetFormat;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public State state() {
        return state;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public String leaseOwner() {
        return leaseOwner;
    }

    public OffsetDateTime leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String renderId() {
        return renderId;
    }

    public Kind kind() {
        return kind;
    }

    public String sourceBlobSha256() {
        return sourceBlobSha256;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorDetail() {
        return errorDetail;
    }

    public String requestedByAccountId() {
        return requestedByAccountId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public OffsetDateTime finishedAt() {
        return finishedAt;
    }
}
