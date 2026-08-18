package com.coreintra.app.api.documents;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.BlobEntity;
import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.font.FontResolver;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.ConversionJobService;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.RenderMetadataCodec;
import com.coreintra.documents.service.RenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exporting a document to DOCX, DOC, PDF, HWP, HWPX, HTML or mdv.
 *
 * <h2>Conversion never occupies a request thread</h2>
 *
 * <p>§6.4 is unambiguous: conversion is slow and memory-hungry and belongs in a
 * separate process with a bounded worker pool. Nothing here converts anything.
 * The three things that can happen are:
 *
 * <ul>
 *   <li><b>Nothing to do.</b> A DOCX document exported as DOCX is the stored
 *       bytes. Answered immediately, and deliberately not sent round LibreOffice,
 *       which would burn a worker slot to produce bytes that differ from what was
 *       approved.</li>
 *   <li><b>The archive already has it.</b> Answered immediately from the archived
 *       render, which §6.4 makes authoritative over any re-render.</li>
 *   <li><b>A worker has to make it.</b> A job is queued and the caller polls. A
 *       small document may ask to wait a bounded number of milliseconds for the
 *       worker to finish, which is the synchronous path — but the waiting is a
 *       bounded poll, and the conversion itself still happens in the worker.</li>
 * </ul>
 *
 * <p>The response says which of the three it was, in {@code mode}, and the HTTP
 * status agrees: 200 when there are bytes, 202 when there is a job.
 *
 * <h2>A failed conversion never produces a file</h2>
 *
 * <p>§13 requires that the conversion worker being down produce a clear,
 * actionable error and never a corrupted or truncated file. The queue records
 * either a completed render or an error and has no state in between, so the
 * download route has nothing half-written to serve; when the job is spent, this
 * answers 409 with the worker's own error code and what to do about it, and the
 * download answers 409 as well rather than an empty 200.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents — export",
        description = "Export and print. Conversion runs in the worker, never on the request "
                + "thread, and the fidelity row for the direction comes back with the answer.")
public class DocumentExportController {

    /**
     * How long a caller may ask to wait for a worker.
     *
     * <p>Five seconds is what a person will stare at a spinner for. Past that
     * the honest answer is a job id, because a request thread held open for a
     * minute is a request thread not serving the rest of the company.
     */
    static final long MAX_WAIT_MILLIS = 5_000L;

    /**
     * The size below which waiting is offered at all.
     *
     * <p>§6.4's "small documents". Two megabytes of DOCX is roughly forty pages
     * with images; above that LibreOffice will not be finished inside any wait a
     * user tolerates, so offering the wait would only make them wait twice.
     */
    static final long SYNC_MAX_SOURCE_BYTES = 2L * 1024L * 1024L;

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final DocumentService documents;
    private final RenderService renders;
    private final ConversionJobService jobs;
    private final BlobService blobs;
    private final FontStoreService fonts;
    private final DocumentAccess access;
    private final CurrentPrincipal current;

    public DocumentExportController(DocumentService documents, RenderService renders,
            ConversionJobService jobs, BlobService blobs, FontStoreService fonts,
            DocumentAccess access, CurrentPrincipal current) {
        this.documents = documents;
        this.renders = renders;
        this.jobs = jobs;
        this.blobs = blobs;
        this.fonts = fonts;
        this.access = access;
        this.current = current;
    }

    /** What to export, and how long the caller is prepared to wait for it. */
    public static class ExportRequest {
        private Integer versionNo;
        @NotBlank
        private String format;
        private String locale;
        private Long waitMillis;
        private List<String> requiredFonts;

        /** Defaults to the current version. */
        public Integer getVersionNo() {
            return versionNo;
        }

        public void setVersionNo(Integer value) {
            this.versionNo = value;
        }

        /** PDF, DOCX, DOC, HWP, HWPX, HTML or MDV. */
        public String getFormat() {
            return format;
        }

        public void setFormat(String value) {
            this.format = value;
        }

        public String getLocale() {
            return locale;
        }

        public void setLocale(String value) {
            this.locale = value;
        }

        /**
         * How long to wait for the worker before answering with a job id.
         *
         * <p>Zero, or omitted, means answer immediately. Capped, and ignored for
         * a document too big for the wait to be worth offering.
         */
        public Long getWaitMillis() {
            return waitMillis;
        }

        public void setWaitMillis(Long value) {
            this.waitMillis = value;
        }

        /**
         * The font families the document asks for, from the editor that already
         * knows them.
         *
         * <p>The API does not parse the document to find out: reading a 30 MB
         * HWPX on a request thread to list its fonts is the work §6.4 keeps off
         * the request thread. What is sent here is resolved against the font
         * store and every substitution comes back named.
         */
        public List<String> getRequiredFonts() {
            return requiredFonts;
        }

        public void setRequiredFonts(List<String> value) {
            this.requiredFonts = value;
        }
    }

    @PostMapping("/{documentId}/export")
    @Operation(summary = "Export a document, synchronously or as a job",
            description = "Requires documents.document:export. Answers 200 with status=ready "
                    + "when the bytes exist — the stored bytes for a native format, or the "
                    + "archived render — and 202 with status=queued and a jobId otherwise. A "
                    + "small document may set waitMillis to wait for the worker; the wait is "
                    + "capped and the conversion still runs in the worker. The response carries "
                    + "the fidelity row for the direction and every font substitution by name.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = ExportView.class)))
    @ApiResponse(responseCode = "202", content = @Content(
            schema = @Schema(implementation = ExportView.class)))
    public ResponseEntity<Object> export(@PathVariable String documentId,
            @Valid @RequestBody ExportRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_EXPORT, on, "exporting document " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        DocumentEntity document = found.get();

        int versionNo = body.getVersionNo() == null
                ? currentVersionOf(document)
                : body.getVersionNo().intValue();
        Optional<DocumentVersionEntity> versionRow = documents.version(documentId, versionNo);
        if (!versionRow.isPresent()) {
            return DocumentProblems.notFound("version", documentId + " v" + versionNo);
        }
        DocumentVersionEntity version = versionRow.get();
        RenderFormat target = ExportFormats.renderFormat(body.getFormat());

        ExportView.Builder view = base(document, version, target, body.getRequiredFonts());

        // 1. Nothing to convert: the stored bytes already are the export.
        if (ExportFormats.isNativeDownload(version.format(), target)) {
            return ResponseEntity.ok((Object) view
                    .ready("immediate", ExportRequests.nativeContentUrl(documentId, versionNo))
                    .build());
        }

        // 2. The archive is authoritative. A hit here is the answer, not a cache
        //    optimisation: re-rendering the same document can only differ.
        List<DocumentRenderEntity> archived = archivedRenders(documentId, versionNo, target);
        if (!archived.isEmpty()) {
            return ResponseEntity.ok((Object) served(view, document, versionNo, target, archived,
                    "immediate").build());
        }

        // 3. A worker has to make it.
        ConversionJobEntity job = jobs.enqueue(document.companyId(), documentId, versionNo,
                version.blobSha256(), version.format(), target,
                ExportRequests.fingerprint(document, version, target, body.getLocale()),
                caller.accountId());

        job = waitBriefly(job, version, body.getWaitMillis());

        if (job.state() == ConversionJobEntity.State.SUCCEEDED) {
            List<DocumentRenderEntity> produced = archivedRenders(documentId, versionNo, target);
            if (!produced.isEmpty()) {
                return ResponseEntity.ok((Object) served(view, document, versionNo, target,
                        produced, "synchronous").build());
            }
        }
        if (job.state() == ConversionJobEntity.State.ABANDONED) {
            return abandoned(job);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body((Object) view
                .queued(job.id(), job.state().name(), ExportRequests.pollUrl(documentId, job.id()))
                .job(job.id(), job.state().name(), job.attemptCount(), job.maxAttempts(),
                        job.errorCode(), job.errorDetail())
                .build());
    }

    @GetMapping("/{documentId}/export/jobs/{jobId}")
    @Operation(summary = "Poll a conversion job",
            description = "Requires documents.document:export. 200 with status=ready once the "
                    + "render is archived, 202 while the job is still claimable — including "
                    + "after a failed attempt that will be retried, which reports the error "
                    + "without pretending the export is finished — and 409 when the attempts are "
                    + "spent and a person has to look.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = ExportView.class)))
    public ResponseEntity<Object> job(@PathVariable String documentId, @PathVariable String jobId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_EXPORT, on, "export job on " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        Optional<ConversionJobEntity> jobRow = jobs.find(jobId);
        if (!jobRow.isPresent() || !jobRow.get().documentId().equals(documentId)) {
            // Checked against the document in the path so that a job id cannot be
            // used to read the state of another company's conversion.
            return DocumentProblems.notFound("conversion job", jobId);
        }
        ConversionJobEntity job = jobRow.get();

        Optional<DocumentVersionEntity> version =
                documents.version(documentId, job.versionNo().intValue());
        if (!version.isPresent()) {
            return DocumentProblems.notFound("version",
                    documentId + " v" + job.versionNo());
        }
        ExportView.Builder view = base(found.get(), version.get(), job.targetFormat(), null)
                .job(job.id(), job.state().name(), job.attemptCount(), job.maxAttempts(),
                        job.errorCode(), job.errorDetail());

        List<DocumentRenderEntity> archived = archivedRenders(documentId,
                job.versionNo().intValue(), job.targetFormat());
        if (!archived.isEmpty()) {
            return ResponseEntity.ok((Object) served(view, found.get(),
                    job.versionNo().intValue(), job.targetFormat(), archived, "asynchronous")
                    .build());
        }
        if (job.state() == ConversionJobEntity.State.ABANDONED) {
            return abandoned(job);
        }
        if (job.state() == ConversionJobEntity.State.SUCCEEDED) {
            // Success with no archived render is not success. The job names a
            // render that cannot be found, which is a worker bug, and answering
            // a cheerful "ready" with no bytes behind it is how a truncated
            // download reaches a person.
            return DocumentProblems.conflict("render_missing",
                    "The conversion reported success but produced no render",
                    "Job " + job.id() + " says it finished and names render " + job.renderId()
                            + ", but no archived render for " + job.targetFormat()
                            + " exists on this version. Nothing is served rather than something "
                            + "incomplete. This is a fault in the conversion worker and an "
                            + "operator has to look at it.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body((Object) view
                .queued(job.id(), job.state().name(), ExportRequests.pollUrl(documentId, job.id()))
                .build());
    }

    @GetMapping("/{documentId}/versions/{versionNo}/export/content")
    @Operation(summary = "Download an archived export",
            description = "Requires documents.document:export. Serves the archived render, which "
                    + "§6.4 makes authoritative: if a regenerated PDF ever differs from this one, "
                    + "this one is what was approved. 409 when no render exists yet, naming the "
                    + "job state, because an empty 200 is indistinguishable from a truncated file.")
    public ResponseEntity<Object> content(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam("format") String format,
            @RequestParam(name = "renderId", required = false) String renderId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_EXPORT, on, "downloading an export of " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        RenderFormat target = ExportFormats.renderFormat(format);
        List<DocumentRenderEntity> archived = archivedRenders(documentId, versionNo, target);
        if (archived.isEmpty()) {
            return DocumentProblems.conflict("export_not_ready", "That export does not exist yet",
                    "No " + target.name() + " has been archived for " + documentId + " v"
                            + versionNo + ". Ask for the export first and poll the job; there is "
                            + "deliberately nothing partial to download.");
        }
        DocumentRenderEntity render = pick(archived, renderId);
        if (render == null) {
            return DocumentProblems.notFound("render", renderId);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExportFormats.mediaTypeOf(target)))
                .header("Content-Disposition",
                        ExportFormats.attachment(found.get().title(), target.name()))
                .header("X-CoreIntra-Renderer-Version", String.valueOf(render.rendererVersion()))
                .body((Object) renders.outputOf(render));
    }

    /** The common half of every answer: what was asked for, and what it costs. */
    private ExportView.Builder base(DocumentEntity document, DocumentVersionEntity version,
            RenderFormat target, List<String> requiredFonts) {

        ExportView.Builder view = ExportView.builder()
                .document(document.id(), version.versionNo().intValue(), target.name())
                .legacy(ExportFormats.isLegacy(target), ExportFormats.legacyNoteKo(target),
                        ExportFormats.legacyNoteEn(target))
                .fidelity(FidelityMatrixSource.row(ExportFormats.pivotFormatId(version.format()),
                        ExportFormats.pivotFormatId(target), target.name()));

        if (requiredFonts != null && !requiredFonts.isEmpty()) {
            FontResolver resolver = fonts.resolverFor(document.companyId());
            for (String family : requiredFonts) {
                if (Texts.isBlank(family)) {
                    continue;
                }
                // Every resolution is reported, not only the failures: a client
                // that only ever sees warnings cannot tell "checked and fine"
                // from "not checked".
                view.fontWarning(new FontWarningView(resolver.resolve(Texts.strip(family), null)));
            }
        }
        return view;
    }

    private ExportView.Builder served(ExportView.Builder view, DocumentEntity document,
            int versionNo, RenderFormat target, List<DocumentRenderEntity> archived, String mode) {

        DocumentRenderEntity authoritative = archived.get(0);
        view.ready(mode, ExportRequests.exportContentUrl(document.id(), versionNo, target))
                .render(authoritative.id(), String.valueOf(authoritative.renderedAt()),
                        authoritative.rendererVersion(), authoritative.outputSha256())
                .recordedSubstitutions(
                        RenderMetadataCodec.decodeSubstitutions(authoritative.substitutions()))
                .archives(archived.size(), archived.size() > 1
                        ? "There are " + archived.size() + " archived renders of this version "
                                + "under different configurations. The earliest is served, "
                                + "because that is the one that was approved; ask for a specific "
                                + "renderId to see another."
                        : null);
        return view;
    }

    /**
     * Archived renders for one version and format, earliest first.
     *
     * <p>Earliest, not newest: §6.4 says that where a regenerated render differs
     * from the archived one the archive is authoritative, and after a LibreOffice
     * upgrade the newest is precisely the one nobody approved.
     */
    private List<DocumentRenderEntity> archivedRenders(String documentId, int versionNo,
            RenderFormat target) {
        List<DocumentRenderEntity> matching = new ArrayList<DocumentRenderEntity>();
        for (DocumentRenderEntity render : renders.rendersOf(documentId, versionNo)) {
            if (render.format() == target) {
                matching.add(render);
            }
        }
        java.util.Collections.sort(matching,
                new java.util.Comparator<DocumentRenderEntity>() {
                    @Override
                    public int compare(DocumentRenderEntity left, DocumentRenderEntity right) {
                        if (left.renderedAt() == null || right.renderedAt() == null) {
                            return left.id().compareTo(right.id());
                        }
                        int byTime = left.renderedAt().compareTo(right.renderedAt());
                        return byTime != 0 ? byTime : left.id().compareTo(right.id());
                    }
                });
        return matching;
    }

    private static DocumentRenderEntity pick(List<DocumentRenderEntity> archived, String renderId) {
        if (Texts.isBlank(renderId)) {
            return archived.get(0);
        }
        for (DocumentRenderEntity render : archived) {
            if (render.id().equals(renderId)) {
                return render;
            }
        }
        return null;
    }

    /**
     * Waits for the worker, but only for a small document and only briefly.
     *
     * <p>This is §6.4's synchronous path, and it is a bounded poll rather than a
     * blocking call into a converter: the conversion is happening in another
     * process either way, and the worst this can cost is one request thread for
     * {@link #MAX_WAIT_MILLIS}. When the worker is down the loop simply runs out
     * and the caller gets a job id, which is the honest answer.
     */
    private ConversionJobEntity waitBriefly(ConversionJobEntity job, DocumentVersionEntity version,
            Long requested) {
        if (requested == null || requested.longValue() <= 0L) {
            return job;
        }
        if (sourceBytes(version) > SYNC_MAX_SOURCE_BYTES) {
            return job;
        }
        long budget = Math.min(requested.longValue(), MAX_WAIT_MILLIS);
        long deadline = System.currentTimeMillis() + budget;
        ConversionJobEntity latest = job;
        while (System.currentTimeMillis() < deadline) {
            if (latest.state() == ConversionJobEntity.State.SUCCEEDED
                    || latest.state() == ConversionJobEntity.State.ABANDONED) {
                return latest;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                // Restore the flag and stop waiting: a shutting-down container
                // should answer with the job id rather than hold the thread.
                Thread.currentThread().interrupt();
                return latest;
            }
            Optional<ConversionJobEntity> refreshed = jobs.find(latest.id());
            if (!refreshed.isPresent()) {
                return latest;
            }
            latest = refreshed.get();
        }
        return latest;
    }

    private long sourceBytes(DocumentVersionEntity version) {
        Optional<BlobEntity> blob = blobs.describe(version.blobSha256());
        // Unknown size is treated as too big. Guessing small would offer a wait
        // that cannot be honoured, which is worse than not offering it.
        return blob.isPresent() ? blob.get().sizeBytes() : Long.MAX_VALUE;
    }

    private static ResponseEntity<Object> abandoned(ConversionJobEntity job) {
        String code = Texts.isBlank(job.errorCode()) ? "unknown" : job.errorCode();
        String detail = Texts.isBlank(job.errorDetail()) ? "no detail recorded" : job.errorDetail();
        return DocumentProblems.conflict("conversion_failed",
                "The conversion did not produce a file",
                "Job " + job.id() + " used all " + job.maxAttempts() + " attempts and stopped "
                        + "with " + code + ": " + detail + ". Nothing partial was written, so "
                        + "there is no file to download. Check that the conversion worker is "
                        + "running and reachable, then ask for the export again — a new attempt "
                        + "needs the job to be requeued by an operator.");
    }

    private static int currentVersionOf(DocumentEntity document) {
        Integer current = document.currentVersionNo();
        if (current == null) {
            throw new IllegalStateException("document " + document.id()
                    + " has no version yet, so there is nothing to export");
        }
        return current.intValue();
    }

}
