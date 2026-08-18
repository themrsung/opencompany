package com.coreintra.app.api.approval;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.approval.service.DraftRequest;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.function.Function;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drafting, editing, submitting and reading a 결재 문서.
 *
 * <h2>The lifecycle this enforces, and where</h2>
 *
 * <p>A draft is private and mutable; a submitted document is routed and
 * immutable. That line is drawn in {@code ApprovalDocumentService}, not here —
 * editing a submitted document is refused by the domain, because an amount
 * changed after the 부장 signed would make their signature a lie. The API's job
 * is to make the refusal legible and to stop two people overwriting each other
 * on the mutable side of the line, which is what {@code If-Match} on the PATCH
 * is for.
 *
 * <h2>Business dates are the drafter's, never the server's</h2>
 *
 * <p>Someone filing a 지출결의서 at 01:00 on the 31st for the shift that began
 * on the 30th is drafting a document that belongs to the 30th. That date decides
 * which template applies, which org chart the line resolves against, and which
 * permissions are checked, so it is a parameter.
 */
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "결재 — documents",
        description = "Draft, edit, submit and read approval documents with their full trail.")
public class ApprovalDocumentController {

    private final ApprovalDocumentService documents;
    private final CurrentPrincipal currentPrincipal;
    private final WriteOnce writeOnce;

    public ApprovalDocumentController(ApprovalDocumentService documents,
            CurrentPrincipal currentPrincipal, WriteOnce writeOnce) {
        this.documents = documents;
        this.currentPrincipal = currentPrincipal;
        this.writeOnce = writeOnce;
    }

    @PostMapping
    @Operation(summary = "Draft a document",
            description = "Creates a 기안 in DRAFTING. Nothing is routed and nobody is "
                    + "notified until it is submitted. Requires an Idempotency-Key header: a "
                    + "retry after a dropped response must not draft a second document. The "
                    + "amount is an exact decimal string and drives the 결재선 amount "
                    + "thresholds, so it is never a JSON number.")
    public ResponseEntity<ApprovalDocumentSummary> draft(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DraftApprovalRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation = writeOnce.require(caller, body.getCompanyId(),
                idempotencyKey, "POST /api/v1/approvals", body);
        if (reservation.isReplay()) {
            return tagged(documents.read(caller, reservation.replayedId()).document(),
                    HttpStatus.OK);
        }

        ApprovalDocumentEntity drafted = documents.draft(caller, new DraftRequest(
                body.getCompanyId(), body.getDocumentType(), body.getTitle(),
                ApiWire.amount(body.getAmount(), "amount"), body.getCurrencyCode(),
                ApiWire.onDate(body.getBusinessDate())));
        reservation.created(drafted.id());
        return tagged(drafted, HttpStatus.CREATED);
    }

    @PatchMapping("/{documentId}")
    @Operation(summary = "Edit a draft",
            description = "Refused once the document has been submitted: the submitted "
                    + "artefact is immutable, and the fix is to recall it or have an approver "
                    + "return it. Requires If-Match carrying the ETag from the last read, so "
                    + "that two people editing the same draft do not silently discard one "
                    + "another's work.")
    public ResponseEntity<ApprovalDocumentSummary> edit(
            @PathVariable String documentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EditDraftRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        // Read first, and through the service: the current tag has to come from
        // the row as it stands, and the read is permission-checked on the way.
        ApprovalDocumentEntity current = documents.read(caller, documentId).document();
        ETags.require(ifMatch, ApprovalDocumentSummary.tagOf(current),
                "approval document \"" + current.title() + "\"");

        return tagged(documents.updateDraft(caller, documentId, body.getTitle(),
                ApiWire.amount(body.getAmount(), "amount"), body.getCurrencyCode(),
                ApiWire.onDate(body.getBusinessDate())), HttpStatus.OK);
    }

    @PostMapping("/{documentId}/submit")
    @Operation(summary = "상신 — submit a draft into its 결재선",
            description = "Resolves the line's role expressions to people and snapshots them "
                    + "onto the document, so a later reorg cannot change who an in-flight "
                    + "document is waiting on. Refused if a required step resolves to nobody: "
                    + "better a refused submission than a document that quietly skipped a "
                    + "signature. Requires an Idempotency-Key header.")
    public ResponseEntity<ApprovalDocumentDetail> submit(
            @PathVariable String documentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitApprovalRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        ApprovalDocumentEntity draft = documents.read(caller, documentId).document();
        WriteOnce.Reservation reservation = writeOnce.require(caller, draft.companyId(),
                idempotencyKey, "POST /api/v1/approvals/{documentId}/submit", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }

        BusinessInstant submittedAt = ApiWire.instant(body.getSubmittedAt(), "submittedAt");
        ApprovalDocumentView submitted =
                documents.submit(caller, documentId, submittedAt, body.getBodyDigest());
        reservation.created(documentId);
        return detail(submitted, caller);
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "A document, its line and its trail",
            description = "One request, because a 결재 screen renders all three at once and "
                    + "a line loaded a moment later can already have been signed. The ETag on "
                    + "the response is what a subsequent edit must send back in If-Match.")
    public ResponseEntity<ApprovalDocumentDetail> read(@PathVariable String documentId) {
        PermissionPrincipal caller = currentPrincipal.require();
        return detail(documents.read(caller, documentId), caller);
    }

    @GetMapping("/drafts")
    @Operation(summary = "내가 올린 문서 — documents an account drafted",
            description = "Newest first, cursor-paged. Reading somebody else's outbox needs "
                    + "approval.document:read: it lists what they have been spending and "
                    + "asking for.")
    public CursorPage<ApprovalDocumentSummary> draftedBy(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        PermissionPrincipal caller = currentPrincipal.require();
        String subject = Texts.isBlank(accountId) ? caller.accountId() : Texts.strip(accountId);
        return ApiWire.page(documents.draftedBy(caller, subject), NEWEST_FIRST,
                new Function<ApprovalDocumentEntity, ApprovalDocumentSummary>() {
                    @Override
                    public ApprovalDocumentSummary apply(ApprovalDocumentEntity row) {
                        return ApprovalDocumentSummary.from(row);
                    }
                }, cursor, limit);
    }

    /**
     * An outbox reads newest first, and a cursor walk needs one direction
     * through a total order; the complement of the creation time gives both.
     */
    private static final ApiWire.Keys<ApprovalDocumentEntity> NEWEST_FIRST =
            new ApiWire.Keys<ApprovalDocumentEntity>() {
                @Override
                public String sortKey(ApprovalDocumentEntity row) {
                    return ApiWire.newestFirstKey(row.createdAt());
                }

                @Override
                public String id(ApprovalDocumentEntity row) {
                    return row.id();
                }
            };

    private static ResponseEntity<ApprovalDocumentSummary> tagged(ApprovalDocumentEntity document,
            HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(ApprovalDocumentSummary.tagOf(document))
                .body(ApprovalDocumentSummary.from(document));
    }

    private static ResponseEntity<ApprovalDocumentDetail> detail(ApprovalDocumentView view,
            PermissionPrincipal caller) {
        return ResponseEntity.ok()
                .eTag(ApprovalDocumentSummary.tagOf(view.document()))
                .body(ApprovalDocumentDetail.from(view, caller.accountId()));
    }

    /** A new 기안. */
    @Schema(name = "DraftApprovalRequest")
    public static class DraftApprovalRequest {

        @NotBlank
        private String companyId;

        @NotBlank(message = "documentType decides which 결재선 applies; a document without "
                + "one cannot be routed")
        @Size(max = 64)
        private String documentType;

        @NotBlank(message = "제목을 입력해 주십시오. (A document needs a title: it is what an "
                + "approver sees in their inbox before opening anything.)")
        @Size(max = 200)
        private String title;

        private String amount;
        private String currencyCode;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        @Schema(example = "EXPENSE_CLAIM")
        public String getDocumentType() {
            return documentType;
        }

        public void setDocumentType(String value) {
            this.documentType = value;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String value) {
            this.title = value;
        }

        @Schema(description = "An exact decimal string, never a JSON number. Omit for a "
                + "document with no money on it. An amount without a currency is not money "
                + "and is refused.", example = "5000000.00")
        public String getAmount() {
            return amount;
        }

        public void setAmount(String value) {
            this.amount = value;
        }

        @Schema(example = "KRW")
        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(String value) {
            this.currencyCode = value;
        }

        @Schema(description = "The drafter's business date, YYYY-MM-DD. Defaults to today. "
                + "Decides which template applies and which org chart the line resolves "
                + "against, so a document filed at 01:00 for yesterday's shift must say so.")
        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }

    /** An edit to a draft. Every field is replaced by what is sent. */
    @Schema(name = "EditDraftRequest")
    public static class EditDraftRequest {

        @Size(max = 200)
        private String title;

        private String amount;
        private String currencyCode;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        @Schema(description = "Left unchanged when omitted or blank.")
        public String getTitle() {
            return title;
        }

        public void setTitle(String value) {
            this.title = value;
        }

        @Schema(description = "An exact decimal string. Sending null clears the amount, "
                + "which is a real change and one a concurrent editor should lose a race "
                + "over.", example = "7250000.00")
        public String getAmount() {
            return amount;
        }

        public void setAmount(String value) {
            this.amount = value;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(String value) {
            this.currencyCode = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }

    /** 상신. */
    @Schema(name = "SubmitApprovalRequest")
    public static class SubmitApprovalRequest {

        @NotBlank
        private String submittedAt;

        private String bodyDigest;

        @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ", no "
                + "timezone and no trailing Z. A shift that runs past midnight submits at "
                + "26:30 on the day it began, not 02:30 on the next one.",
                example = ApiWire.INSTANT_EXAMPLE)
        public String getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(String value) {
            this.submittedAt = value;
        }

        @Schema(description = "The documents module's digest of the body, folded into the "
                + "snapshot hash that every signature is taken against. Omit for a document "
                + "that is only header fields.")
        public String getBodyDigest() {
            return bodyDigest;
        }

        public void setBodyDigest(String value) {
            this.bodyDigest = value;
        }
    }
}
