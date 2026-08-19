package com.coreintra.app.api.approval;

import com.coreintra.app.api.http.ETags;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of an inbox or an outbox: enough to triage, not enough to decide.
 *
 * <p>The 결재함 is read many times a day and mostly skimmed, so the list row
 * carries the four things a person scans for — who wants what, how much, and
 * where it has got to — and nothing that would need a second query. The line and
 * the trail are on {@link ApprovalDocumentDetail}, one click away.
 */
@Schema(name = "ApprovalDocumentSummary",
        description = "An approval document as it appears in a list.")
public class ApprovalDocumentSummary {

    private final String id;
    private final String companyId;
    private final String documentType;
    private final String title;
    private final String drafterAccountId;
    private final String drafterOrgUnitId;
    private final String templateId;
    private final String state;
    private final String amount;
    private final String currencyCode;
    private final String submittedAt;
    private final String recordedAt;
    private final String etag;

    ApprovalDocumentSummary(ApprovalDocumentEntity document) {
        this.id = document.id();
        this.companyId = document.companyId();
        this.documentType = document.documentType();
        this.title = document.title();
        this.drafterAccountId = document.drafterAccountId();
        this.drafterOrgUnitId = document.drafterOrgUnitId();
        this.templateId = document.templateId();
        this.state = document.state().name();
        this.amount = ApiWire.decimal(document.amount());
        this.currencyCode = document.currencyCode();
        this.submittedAt = ApiWire.wire(document.submittedAt());
        this.recordedAt = ApiWire.utc(document.createdAt());
        this.etag = tagOf(document);
    }

    public static ApprovalDocumentSummary from(ApprovalDocumentEntity document) {
        return new ApprovalDocumentSummary(document);
    }

    /**
     * The tag a caller must send back in {@code If-Match} to edit this draft.
     *
     * <p>Derived from the fields a draft edit writes, because the entity carries
     * no version counter; see {@link ContentVersions}.
     */
    public static String tagOf(ApprovalDocumentEntity document) {
        return ETags.of(document.id(), ContentVersions.of(
                document.state(), document.title(), document.amount(),
                document.currencyCode(), document.submittedSnapshotHash()));
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    @Schema(description = "Decides which 결재선 template applies.", example = "EXPENSE_CLAIM")
    public String getDocumentType() {
        return documentType;
    }

    @Schema(description = "What an approver sees before opening anything.")
    public String getTitle() {
        return title;
    }

    public String getDrafterAccountId() {
        return drafterAccountId;
    }

    @Schema(description = "The unit the line resolved against, snapshotted at submission.")
    public String getDrafterOrgUnitId() {
        return drafterOrgUnitId;
    }

    public String getTemplateId() {
        return templateId;
    }

    @Schema(description = "DRAFTING, IN_PROGRESS, PARTIALLY_APPROVED, ON_HOLD, APPROVED, "
            + "RETURNED or RECALLED. PARTIALLY_APPROVED is a 공동대표 document that some but "
            + "not all of the required representatives have signed.",
            example = "IN_PROGRESS")
    public String getState() {
        return state;
    }

    @Schema(description = "An exact decimal string, never a JSON number. Null when the "
            + "document carries no money. Drives the amount thresholds on the 결재선, so a "
            + "rounding error changes who has to sign.", example = "5000000.00")
    public String getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ", no timezone. "
            + "Null while the document is still a draft.", example = ApiWire.INSTANT_EXAMPLE)
    public String getSubmittedAt() {
        return submittedAt;
    }

    @Schema(description = "The real UTC moment the row was written. A separate fact from "
            + "submittedAt and never a substitute for it.")
    public String getRecordedAt() {
        return recordedAt;
    }

    @Schema(description = "Send back as If-Match to edit this draft.")
    public String getEtag() {
        return etag;
    }
}
