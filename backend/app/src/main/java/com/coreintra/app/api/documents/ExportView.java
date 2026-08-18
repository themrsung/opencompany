package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;

/**
 * The answer to an export request, in the one shape both paths use.
 *
 * <h2>The client must be able to tell which path it got</h2>
 *
 * <p>§6.4 asks for a synchronous export with a timeout for small documents and
 * an asynchronous one with a job id and polling for everything else. Two
 * response shapes would mean two client code paths and, eventually, a client
 * that handles only the one it saw in development. So there is one shape:
 * {@link #getStatus()} is {@code ready} or {@code queued}, {@link #getMode()}
 * says whether the answer came back immediately or from the queue, and the HTTP
 * status agrees with both — 200 when the bytes exist, 202 when a worker still
 * has to produce them.
 *
 * <h2>What is never here</h2>
 *
 * <p>The bytes. A ready export carries {@link #getContentUrl()}, and the
 * download is a separate request with its own content type and its own
 * {@code Content-Disposition}. Inlining a PDF as base64 would make an export
 * response unreadable in a log and would double the memory cost of the thing
 * §6.4 already calls memory-hungry.
 */
public class ExportView {

    /** The bytes exist and can be downloaded now. */
    public static final String READY = "ready";

    /** A worker has to produce them. Poll. */
    public static final String QUEUED = "queued";

    private final String documentId;
    private final int versionNo;
    private final String format;
    private final String status;
    private final String mode;
    private final String contentUrl;
    private final String renderId;
    private final String renderedAt;
    private final String rendererVersion;
    private final String outputSha256;
    private final int archivedRenderCount;
    private final String archiveNote;
    private final String jobId;
    private final String jobState;
    private final String pollUrl;
    private final Integer attempt;
    private final Integer maxAttempts;
    private final String lastErrorCode;
    private final String lastErrorDetail;
    private final boolean legacy;
    private final String legacyNoteKo;
    private final String legacyNoteEn;
    private final FidelityRowView fidelity;
    private final List<FontWarningView> fontWarnings;
    private final List<String> recordedSubstitutions;

    ExportView(Builder builder) {
        this.documentId = builder.documentId;
        this.versionNo = builder.versionNo;
        this.format = builder.format;
        this.status = builder.status;
        this.mode = builder.mode;
        this.contentUrl = builder.contentUrl;
        this.renderId = builder.renderId;
        this.renderedAt = builder.renderedAt;
        this.rendererVersion = builder.rendererVersion;
        this.outputSha256 = builder.outputSha256;
        this.archivedRenderCount = builder.archivedRenderCount;
        this.archiveNote = builder.archiveNote;
        this.jobId = builder.jobId;
        this.jobState = builder.jobState;
        this.pollUrl = builder.pollUrl;
        this.attempt = builder.attempt;
        this.maxAttempts = builder.maxAttempts;
        this.lastErrorCode = builder.lastErrorCode;
        this.lastErrorDetail = builder.lastErrorDetail;
        this.legacy = builder.legacy;
        this.legacyNoteKo = builder.legacyNoteKo;
        this.legacyNoteEn = builder.legacyNoteEn;
        this.fidelity = builder.fidelity;
        this.fontWarnings = Immutables.copyOf(builder.fontWarnings);
        this.recordedSubstitutions = Immutables.copyOf(builder.recordedSubstitutions);
    }

    static Builder builder() {
        return new Builder();
    }

    public String getDocumentId() {
        return documentId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getFormat() {
        return format;
    }

    /** {@code ready} or {@code queued}. Branch on this, not on the HTTP status alone. */
    public String getStatus() {
        return status;
    }

    /**
     * {@code immediate} when no conversion was needed or the archive already had
     * it; {@code synchronous} when the caller waited and the worker finished
     * inside the timeout; {@code asynchronous} when it did not.
     */
    public String getMode() {
        return mode;
    }

    /** Where to download the bytes. Null while the status is queued. */
    public String getContentUrl() {
        return contentUrl;
    }

    public String getRenderId() {
        return renderId;
    }

    public String getRenderedAt() {
        return renderedAt;
    }

    /** The pinned LibreOffice or mdv version that produced these bytes (§6.4). */
    public String getRendererVersion() {
        return rendererVersion;
    }

    public String getOutputSha256() {
        return outputSha256;
    }

    /** How many archived renders exist for this version and format. */
    public int getArchivedRenderCount() {
        return archivedRenderCount;
    }

    /** Set when more than one archive exists, saying which one is being served and why. */
    public String getArchiveNote() {
        return archiveNote;
    }

    public String getJobId() {
        return jobId;
    }

    /** QUEUED, RUNNING, SUCCEEDED, FAILED or ABANDONED. */
    public String getJobState() {
        return jobState;
    }

    public String getPollUrl() {
        return pollUrl;
    }

    /** Attempts used so far. A retryable failure has already used one. */
    public Integer getAttempt() {
        return attempt;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    /** Set when an attempt has failed but the job is still claimable. */
    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorDetail() {
        return lastErrorDetail;
    }

    /** True for .doc. The UI is required to say legacy and lossy (§6.4). */
    public boolean isLegacy() {
        return legacy;
    }

    public String getLegacyNoteKo() {
        return legacyNoteKo;
    }

    public String getLegacyNoteEn() {
        return legacyNoteEn;
    }

    /** The fidelity matrix row for this direction, for the export dialog (§6.5). */
    public FidelityRowView getFidelity() {
        return fidelity;
    }

    /** Named, never silent: what each requested family resolved to (§6.9). */
    public List<FontWarningView> getFontWarnings() {
        return fontWarnings;
    }

    /** Substitutions recorded in the archived render's own metadata, if there is one. */
    public List<String> getRecordedSubstitutions() {
        return recordedSubstitutions;
    }

    static final class Builder {
        private String documentId;
        private int versionNo;
        private String format;
        private String status;
        private String mode;
        private String contentUrl;
        private String renderId;
        private String renderedAt;
        private String rendererVersion;
        private String outputSha256;
        private int archivedRenderCount;
        private String archiveNote;
        private String jobId;
        private String jobState;
        private String pollUrl;
        private Integer attempt;
        private Integer maxAttempts;
        private String lastErrorCode;
        private String lastErrorDetail;
        private boolean legacy;
        private String legacyNoteKo;
        private String legacyNoteEn;
        private FidelityRowView fidelity;
        private final List<FontWarningView> fontWarnings = new ArrayList<FontWarningView>();
        private final List<String> recordedSubstitutions = new ArrayList<String>();

        Builder document(String id, int versionNo, String format) {
            this.documentId = id;
            this.versionNo = versionNo;
            this.format = format;
            return this;
        }

        Builder ready(String mode, String contentUrl) {
            this.status = READY;
            this.mode = mode;
            this.contentUrl = contentUrl;
            return this;
        }

        Builder queued(String jobId, String jobState, String pollUrl) {
            this.status = QUEUED;
            this.mode = "asynchronous";
            this.jobId = jobId;
            this.jobState = jobState;
            this.pollUrl = pollUrl;
            return this;
        }

        Builder render(String renderId, String renderedAt, String rendererVersion,
                String outputSha256) {
            this.renderId = renderId;
            this.renderedAt = renderedAt;
            this.rendererVersion = rendererVersion;
            this.outputSha256 = outputSha256;
            return this;
        }

        Builder archives(int count, String note) {
            this.archivedRenderCount = count;
            this.archiveNote = note;
            return this;
        }

        Builder job(String jobId, String jobState, int attempt, int maxAttempts,
                String errorCode, String errorDetail) {
            this.jobId = jobId;
            this.jobState = jobState;
            this.attempt = Integer.valueOf(attempt);
            this.maxAttempts = Integer.valueOf(maxAttempts);
            this.lastErrorCode = errorCode;
            this.lastErrorDetail = errorDetail;
            return this;
        }

        Builder legacy(boolean legacy, String noteKo, String noteEn) {
            this.legacy = legacy;
            this.legacyNoteKo = noteKo;
            this.legacyNoteEn = noteEn;
            return this;
        }

        Builder fidelity(FidelityRowView row) {
            this.fidelity = row;
            return this;
        }

        Builder fontWarning(FontWarningView warning) {
            this.fontWarnings.add(warning);
            return this;
        }

        Builder recordedSubstitutions(List<String> substitutions) {
            if (substitutions != null) {
                this.recordedSubstitutions.addAll(substitutions);
            }
            return this;
        }

        ExportView build() {
            return new ExportView(this);
        }
    }
}
