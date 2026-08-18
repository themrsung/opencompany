package com.coreintra.app.api.approval;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.approval.service.ApprovalActionService;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six things a person can do to a document that has been submitted:
 * 승인, 반려, 보류, 전결, 대결 and 회수.
 *
 * <h2>Who may act is not a permission</h2>
 *
 * <p>There is deliberately no {@code approval.document:act} grant. The line's
 * role expressions were resolved to people at submission and snapshotted onto
 * the document, and that resolved set <em>is</em> the authorisation. A grant
 * would be wrong in both directions: given to someone outside the set it would
 * let them sign a step they were never routed, and required in addition it would
 * let a forgotten grant silently stall a document with no honest explanation to
 * offer the person waiting on it.
 *
 * <p>So these endpoints do not check a permission of their own, and the 403 they
 * can return comes from the domain refusing an actor the line does not name.
 * That is the single gate, in the place that knows the answer.
 *
 * <h2>Reasons</h2>
 *
 * <p>반려, 보류, 전결 and 대결 all carry a mandatory reason and the API refuses
 * a blank one rather than storing an empty string. A 반려 with no explanation is
 * re-submitted unchanged and the whole loop repeats, which is how an approval
 * queue turns into a war of attrition.
 */
@RestController
@RequestMapping("/api/v1/approvals/{documentId}")
@Tag(name = "결재 — actions",
        description = "승인 / 반려 / 보류 / 전결 / 대결 / 회수. Each appends one immutable "
                + "entry to the trail: who, when in business time, what, why, and the digest "
                + "of the document as it stood at that moment.")
public class ApprovalActionController {

    private final ApprovalActionService actions;
    private final ApprovalDocumentService documents;
    private final CurrentPrincipal currentPrincipal;
    private final WriteOnce writeOnce;

    public ApprovalActionController(ApprovalActionService actions,
            ApprovalDocumentService documents, CurrentPrincipal currentPrincipal,
            WriteOnce writeOnce) {
        this.actions = actions;
        this.documents = documents;
        this.currentPrincipal = currentPrincipal;
        this.writeOnce = writeOnce;
    }

    @PostMapping("/steps/{stepId}/approve")
    @Operation(summary = "승인 — approve this step",
            description = "Passes the document to the next step, or approves it outright if "
                    + "this was the last. Under 공동대표 the document becomes "
                    + "PARTIALLY_APPROVED until the quorum is met: one representative cannot "
                    + "finish a joint-representation document, and the partial state is "
                    + "visible rather than looking like 'still waiting'. A comment is "
                    + "optional here and mandatory on every other action.")
    public ResponseEntity<ApprovalDocumentDetail> approve(
            @PathVariable String documentId,
            @PathVariable String stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentedActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "approve", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after = actions.approve(caller, documentId, stepId,
                actedAt(body), body.getComment());
        reservation.created(documentId);
        return detail(after, caller);
    }

    @PostMapping("/steps/{stepId}/return")
    @Operation(summary = "반려 — send it back to the drafter",
            description = "The reason is mandatory and a blank one is refused with a "
                    + "validation error naming the field. Returning a document with no "
                    + "explanation gets it re-submitted unchanged.")
    public ResponseEntity<ApprovalDocumentDetail> returnToDrafter(
            @PathVariable String documentId,
            @PathVariable String stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonedActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "return", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after = actions.returnToDrafter(caller, documentId, stepId,
                actedAt(body), body.getReason());
        reservation.created(documentId);
        return detail(after, caller);
    }

    @PostMapping("/steps/{stepId}/hold")
    @Operation(summary = "보류 — hold at this step",
            description = "Leaves the document visibly waiting on this person rather than "
                    + "silently stalled. The reason is mandatory.")
    public ResponseEntity<ApprovalDocumentDetail> hold(
            @PathVariable String documentId,
            @PathVariable String stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonedActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "hold", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after = actions.hold(caller, documentId, stepId,
                actedAt(body), body.getReason());
        reservation.created(documentId);
        return detail(after, caller);
    }

    @PostMapping("/steps/{stepId}/delegate-final")
    @Operation(summary = "전결 — finalise now, skipping the remaining 결재 steps",
            description = "Refused when nothing would be skipped: that is an ordinary 승인 "
                    + "wearing a label that would tell an auditor the 대표's signature had "
                    + "been delegated when it was never needed. The skipped steps are recorded "
                    + "as SKIPPED, not approved — nobody signed them. The reason is mandatory.")
    public ResponseEntity<ApprovalDocumentDetail> delegateFinal(
            @PathVariable String documentId,
            @PathVariable String stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonedActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "delegate-final", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after = actions.delegateFinal(caller, documentId, stepId,
                actedAt(body), body.getReason());
        reservation.created(documentId);
        return detail(after, caller);
    }

    @PostMapping("/steps/{stepId}/act-for")
    @Operation(summary = "대결 — approve in place of an absent approver",
            description = "Recorded as the actor acting for the absentee, never as the "
                    + "absentee approving: the trail says '김대리 acted for 박부장'. The named "
                    + "absentee must themselves be one of this step's snapshotted approvers, "
                    + "and where an absence directory is wired the absence is checked rather "
                    + "than taken on trust. The reason is mandatory.")
    public ResponseEntity<ApprovalDocumentDetail> actFor(
            @PathVariable String documentId,
            @PathVariable String stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ActingActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "act-for", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after = actions.actFor(caller, documentId, stepId,
                body.getAbsentAccountId(), actedAt(body), body.getReason());
        reservation.created(documentId);
        return detail(after, caller);
    }

    @PostMapping("/recall")
    @Operation(summary = "회수 — the drafter withdraws the document",
            description = "Only before the first approval. Afterwards it is refused: "
                    + "withdrawing would erase a decision that was really made, and the "
                    + "drafter must ask an approver to 반려 instead so that the decision stays "
                    + "on the record.")
    public ResponseEntity<ApprovalDocumentDetail> recall(
            @PathVariable String documentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentedActionRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        WriteOnce.Reservation reservation =
                guard(caller, documentId, idempotencyKey, "recall", body);
        if (reservation.isReplay()) {
            return detail(documents.read(caller, documentId), caller);
        }
        ApprovalDocumentView after =
                actions.recall(caller, documentId, actedAt(body), body.getComment());
        reservation.created(documentId);
        return detail(after, caller);
    }

    /**
     * Reserves an idempotency key when the caller offered one.
     *
     * <p>Optional rather than required, unlike drafting and submitting. A second
     * 승인 by the same person at the same step is already refused by the domain
     * with an explanation, so the key buys a replayed answer rather than
     * protection against a duplicate signature. The company is read from the
     * document rather than taken from the caller, which costs one authorised
     * read — and only on the requests that asked for the guarantee.
     */
    private WriteOnce.Reservation guard(PermissionPrincipal caller, String documentId,
            String idempotencyKey, String action, Object body) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return writeOnce.optional(caller, null, null, action, body);
        }
        String companyId = documents.read(caller, documentId).document().companyId();
        return writeOnce.optional(caller, companyId, idempotencyKey,
                "POST /api/v1/approvals/{documentId}/" + action, body);
    }

    private static BusinessInstant actedAt(ActionRequest body) {
        return ApiWire.instant(body.getActedAt(), "actedAt");
    }

    private static ResponseEntity<ApprovalDocumentDetail> detail(ApprovalDocumentView view,
            PermissionPrincipal caller) {
        return ResponseEntity.ok()
                .eTag(ApprovalDocumentSummary.tagOf(view.document()))
                .body(ApprovalDocumentDetail.from(view, caller.accountId()));
    }

    /** What every action carries: when, in business time. */
    public abstract static class ActionRequest {

        @NotBlank(message = "결재 시각을 입력해 주십시오. (An action needs the business instant it "
                + "was taken at, as YYYY-MM-DDT[-]HH:MM:SS.mmm with no timezone.)")
        private String actedAt;

        @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ". No "
                + "timezone and no trailing Z — an approval given at 02:00 during a shift "
                + "that began the previous evening belongs to that evening's business day "
                + "and is sent as 26:00 on it.",
                example = ApiWire.INSTANT_EXAMPLE, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getActedAt() {
            return actedAt;
        }

        public void setActedAt(String value) {
            this.actedAt = value;
        }
    }

    /** 승인 and 회수: the note is optional. */
    @Schema(name = "CommentedApprovalActionRequest")
    public static class CommentedActionRequest extends ActionRequest {

        @Size(max = 2000)
        private String comment;

        @Schema(description = "Optional. Recorded on the trail as written.")
        public String getComment() {
            return comment;
        }

        public void setComment(String value) {
            this.comment = value;
        }
    }

    /** 반려, 보류 and 전결: the reason is not optional. */
    @Schema(name = "ReasonedApprovalActionRequest")
    public static class ReasonedActionRequest extends ActionRequest {

        @NotBlank(message = "반려 사유를 입력해 주십시오. 무엇을 고쳐야 하는지 기안자가 알 수 있도록 "
                + "구체적으로 적어 주십시오. (Write why you are sending this back, and what the "
                + "drafter should change. A document returned with no explanation is "
                + "re-submitted unchanged and the loop repeats.)")
        @Size(max = 2000)
        private String reason;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }
    }

    /** 대결: also names the person whose place is being taken. */
    @Schema(name = "ActingApprovalActionRequest")
    public static class ActingActionRequest extends ReasonedActionRequest {

        @NotBlank(message = "대결은 누구를 대신하는지 반드시 기록해야 합니다. (대결 must record whom "
                + "it was performed on behalf of; otherwise the trail implies the absent "
                + "approver signed personally.)")
        private String absentAccountId;

        @Schema(description = "Must be one of this step's snapshotted approvers. Acting for "
                + "someone the document was never routed to would be a signature with no "
                + "authority behind it.", requiredMode = Schema.RequiredMode.REQUIRED)
        public String getAbsentAccountId() {
            return absentAccountId;
        }

        public void setAbsentAccountId(String value) {
            this.absentAccountId = value;
        }
    }
}
