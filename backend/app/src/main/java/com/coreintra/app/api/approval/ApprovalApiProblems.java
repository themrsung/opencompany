package com.coreintra.app.api.approval;

import com.coreintra.app.api.error.ProblemDetail;
import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalLineTemplateService;
import com.coreintra.approval.service.ApproverDirectory;
import com.coreintra.attendance.service.AttendanceRecordService;
import com.coreintra.attendance.service.AttendanceStatusService;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.compat.Immutables;
import com.coreintra.runtime.idempotency.IdempotencyConflictException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Domain refusals from 결재 and 근태, as problem+json.
 *
 * <h2>Why these are not 500s and not 400s</h2>
 *
 * <p>Every exception handled here is an {@link IllegalStateException} subtype
 * that the domain throws when a request is well-formed and the caller is
 * entitled to make it, but the resource is not in a state that allows it: the
 * document has already been submitted, the 회수 comes after the first approval,
 * the leave balance will not cover the request. That is a 409, and the
 * distinction is one the client acts on — a 400 tells it to fix the request,
 * which will not help, and a 500 tells it the server is broken, which is worse
 * because it is untrue.
 *
 * <p>Nothing here is handled by {@code ApiExceptionHandler}, which covers the
 * cross-cutting failures — authentication, permission, validation, business
 * instants, preconditions — and which this advice deliberately does not
 * duplicate or shadow. It declares concrete types only. A blanket
 * {@code IllegalStateException} handler would turn a genuine bug into a
 * plausible-looking 409 and hide it.
 */
@RestControllerAdvice(basePackages = {
        "com.coreintra.app.api.approval",
        "com.coreintra.app.api.attendance"})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApprovalApiProblems {

    /**
     * 428 would be the tempting answer, by analogy with {@code If-Match}. It is
     * the wrong one: 428 says "resend with a precondition", and a client that
     * resends the same POST with a key it invented after the fact has still
     * posted twice. 400 says the request as constructed is not one this endpoint
     * accepts, which is the truth.
     */
    @ExceptionHandler(WriteOnce.KeyRequiredException.class)
    public ResponseEntity<ProblemDetail> onKeyRequired(WriteOnce.KeyRequiredException e) {
        return problem(HttpStatus.BAD_REQUEST, "idempotency_key_required",
                "Idempotency-Key required", e.getMessage());
    }

    /**
     * A reused key, or one whose first request has not finished.
     *
     * <p>The code is carried through from the service so a client can tell the
     * two apart: one means "you are confused about which operation you are
     * retrying" and needs a code change, the other means "wait and retry" and
     * needs patience.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> onIdempotencyConflict(IdempotencyConflictException e) {
        Map<String, Object> extensions =
                Immutables.<String, Object>mapOf("idempotencyKey", e.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemDetail("about:blank", "Idempotency key conflict",
                        HttpStatus.CONFLICT.value(), e.getMessage(), e.code(), null, extensions));
    }

    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<ProblemDetail> onNotFound(ApiNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "Nothing here", e.getMessage());
    }

    @ExceptionHandler(ApprovalDocumentService.DocumentStateException.class)
    public ResponseEntity<ProblemDetail> onDocumentState(
            ApprovalDocumentService.DocumentStateException e) {
        return problem(HttpStatus.CONFLICT, "document_state_conflict",
                "The document is not in a state that allows this", e.getMessage());
    }

    /**
     * The state machine refusing an act — a 회수 after the first approval, a
     * second signature from the same approver, a 전결 that would skip nothing.
     */
    @ExceptionHandler(ApprovalLine.ApprovalRuleException.class)
    public ResponseEntity<ProblemDetail> onApprovalRule(ApprovalLine.ApprovalRuleException e) {
        return problem(HttpStatus.CONFLICT, "approval_rule_violated",
                "The 결재선 does not allow this", e.getMessage());
    }

    /**
     * A required step resolved to nobody.
     *
     * <p>Refusing the submission is the right answer and 409 is the right code:
     * the document is fine and the caller is entitled, but the org chart as of
     * this document's business date has nobody in the post the line names. The
     * fix is an org change, not a request change.
     */
    @ExceptionHandler(ApproverDirectory.UnresolvableRoleException.class)
    public ResponseEntity<ProblemDetail> onUnresolvableRole(
            ApproverDirectory.UnresolvableRoleException e) {
        return problem(HttpStatus.CONFLICT, "approval_role_unresolvable",
                "A step in the 결재선 resolves to nobody", e.getMessage());
    }

    @ExceptionHandler(ApprovalLineTemplateService.NoTemplateException.class)
    public ResponseEntity<ProblemDetail> onNoTemplate(
            ApprovalLineTemplateService.NoTemplateException e) {
        return problem(HttpStatus.CONFLICT, "approval_line_template_missing",
                "No 결재선 covers this document type", e.getMessage());
    }

    /**
     * 403, and it is the most important 403 in the system.
     *
     * <p>취업규칙 cannot be created, amended or repealed without 대표자 결재 under
     * the representation mode in force. No administrative grant reaches it, and
     * neither does a master account — which is why this is a forbidden rather
     * than a conflict: the caller is not merely early, they are not permitted,
     * and no amount of retrying changes that.
     */
    @ExceptionHandler(EmploymentRules.RepresentativeApprovalRequiredException.class)
    public ResponseEntity<ProblemDetail> onRepresentativeApprovalRequired(
            EmploymentRules.RepresentativeApprovalRequiredException e) {
        return problem(HttpStatus.FORBIDDEN, "representative_approval_required",
                "대표자 결재가 필요합니다", e.getMessage());
    }

    @ExceptionHandler(AttendanceRecordService.AttendanceRefusedException.class)
    public ResponseEntity<ProblemDetail> onAttendanceRefused(
            AttendanceRecordService.AttendanceRefusedException e) {
        return problem(HttpStatus.CONFLICT, "attendance_refused",
                "The attendance record cannot be written", e.getMessage());
    }

    @ExceptionHandler(AttendanceStatusService.StatusDefinitionException.class)
    public ResponseEntity<ProblemDetail> onStatusDefinition(
            AttendanceStatusService.StatusDefinitionException e) {
        return problem(HttpStatus.CONFLICT, "attendance_status_definition",
                "The status definition cannot be applied", e.getMessage());
    }

    @ExceptionHandler(LeaveService.LeaveRefusedException.class)
    public ResponseEntity<ProblemDetail> onLeaveRefused(LeaveService.LeaveRefusedException e) {
        return problem(HttpStatus.CONFLICT, "leave_refused",
                "The leave operation is not allowed", e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code,
            String title, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.of(status.value(), code, title, detail));
    }
}
