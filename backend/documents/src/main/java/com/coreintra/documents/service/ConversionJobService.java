package com.coreintra.documents.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.compat.Texts;
import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.repository.ConversionJobRepository;

/**
 * The queue in front of LibreOffice and the mdv worker (brief 6.4).
 *
 * <p>Conversion is slow and memory-hungry and must never occupy a request thread, so it is
 * a job with a lease rather than a call. Three properties make the queue safe to leave
 * running unattended:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The key is derived from what is being converted, so a user
 *       pressing Export twice joins the first job instead of starting a second
 *       LibreOffice.</li>
 *   <li><b>Bounded.</b> Attempts are counted against a maximum, so a document that crashes
 *       the worker is abandoned with an error a person can read rather than retried until
 *       the end of time.</li>
 *   <li><b>Leased.</b> A worker that dies holds nothing once its lease expires. Without the
 *       expiry, one crash parks a job forever and the user watches a spinner.</li>
 * </ul>
 *
 * <p>What the queue deliberately does not do is produce a partial artefact. A job either
 * names a completed render or it names an error; there is no state in which a truncated
 * file is downloadable.
 */
@Service
public class ConversionJobService {

    /** Three attempts. Enough for a restart or a transient OOM, few enough to notice. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ConversionJobRepository jobs;

    public ConversionJobService(ConversionJobRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * Queues a conversion, or returns the one already queued for it.
     *
     * <p>The key is the hash of (document, version, target format, render config), which is
     * exactly the set of things that decide what the output is. Two requests that would
     * produce the same bytes are the same job.
     */
    @Transactional
    public ConversionJobEntity enqueue(String companyId, String documentId, int versionNo,
            String sourceBlobSha256, DocumentFormat sourceFormat, RenderFormat targetFormat,
            String configFingerprint, String requestedByAccountId) {
        return enqueue(companyId, documentId, versionNo, sourceBlobSha256, sourceFormat,
                targetFormat, configFingerprint, requestedByAccountId, DEFAULT_MAX_ATTEMPTS);
    }

    @Transactional
    public ConversionJobEntity enqueue(String companyId, String documentId, int versionNo,
            String sourceBlobSha256, DocumentFormat sourceFormat, RenderFormat targetFormat,
            String configFingerprint, String requestedByAccountId, int maxAttempts) {
        String key = idempotencyKeyFor(documentId, versionNo, targetFormat, configFingerprint);
        Optional<ConversionJobEntity> existing = jobs.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        return jobs.save(new ConversionJobEntity(UUID.randomUUID().toString(), companyId,
                ConversionJobEntity.Kind.forSource(sourceFormat), documentId, versionNo,
                sourceBlobSha256, targetFormat, configFingerprint, key, requestedByAccountId,
                maxAttempts));
    }

    /**
     * Claims the oldest claimable job for one worker.
     *
     * <p>Oldest first so nothing starves behind newer work, and bounded by the attempt count
     * so an abandoned job is never handed out again. Empty means there is nothing to do -
     * which is a different thing from the worker being down, and the caller should not
     * conflate them.
     */
    @Transactional
    public Optional<ConversionJobEntity> lease(String workerName, Duration leaseFor,
            OffsetDateTime now) {
        if (Texts.isBlank(workerName)) {
            throw new IllegalArgumentException(
                    "a lease is held by a named worker; an anonymous lease cannot be reclaimed "
                    + "from the worker that stopped answering");
        }
        List<ConversionJobEntity> claimable = jobs.findClaimable(now, PageRequest.of(0, 1));
        if (claimable.isEmpty()) {
            return Optional.empty();
        }
        ConversionJobEntity job = claimable.get(0);
        job.lease(workerName, now.plus(leaseFor), now);
        return Optional.of(jobs.save(job));
    }

    /**
     * Records success, naming the render the job produced.
     *
     * @throws IllegalArgumentException if no render is named - a job that succeeded without
     *         producing one is a lie the export UI would have to resolve by re-rendering,
     *         silently
     */
    @Transactional
    public ConversionJobEntity complete(String jobId, String renderId, OffsetDateTime now) {
        ConversionJobEntity job = require(jobId);
        job.succeed(renderId, now);
        return jobs.save(job);
    }

    /**
     * Records a failure, abandoning the job when its attempts are spent.
     *
     * <p>The error code is mandatory and ends up in front of a person: "the conversion
     * worker being down produces a clear, actionable error and never a corrupted or
     * truncated file" is an acceptance test, and this is the half of it that produces the
     * error. The other half is that no output blob is ever written by this path - a failed
     * job names no render, so there is nothing half-written for the export UI to serve.
     */
    @Transactional
    public ConversionJobEntity fail(String jobId, String errorCode, String errorDetail,
            OffsetDateTime now) {
        ConversionJobEntity job = require(jobId);
        job.fail(errorCode, errorDetail, now);
        return jobs.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<ConversionJobEntity> find(String jobId) {
        return jobs.findById(jobId);
    }

    /** Jobs nobody will retry. Someone has to look at these; they do not resolve themselves. */
    @Transactional(readOnly = true)
    public List<ConversionJobEntity> abandoned() {
        return jobs.findByStateOrderByCreatedAtAsc(ConversionJobEntity.State.ABANDONED);
    }

    /**
     * The key two identical export requests share.
     *
     * <p>Hashed rather than concatenated so it fits the column whatever the ids look like,
     * and so a fingerprint change is visible as a different key rather than as a truncation.
     */
    public static String idempotencyKeyFor(String documentId, int versionNo,
            RenderFormat targetFormat, String configFingerprint) {
        String material = documentId + "|" + versionNo + "|" + targetFormat.name() + "|"
                + configFingerprint;
        return LocalFileBlobStore.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
    }

    private ConversionJobEntity require(String jobId) {
        return jobs.findById(jobId).orElseThrow(
                () -> new IllegalArgumentException("no such conversion job: " + jobId));
    }
}
