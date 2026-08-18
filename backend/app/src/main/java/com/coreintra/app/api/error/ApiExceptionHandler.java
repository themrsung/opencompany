package com.coreintra.app.api.error;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.SessionService;
import com.coreintra.businesstime.BusinessInstantParseException;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.service.RecordNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into RFC 7807 problem+json.
 *
 * <p>Two rules this enforces that are easy to lose one endpoint at a time:
 * every validation failure comes back in one response, and a permission denial
 * names the permission that was missing rather than returning a bare 403 that
 * leaves an admin guessing.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 401, not 403.
     *
     * <p>The distinction matters to the client: a 401 means "refresh your access
     * token and try again", which the browser client does automatically once,
     * for everybody. Returning 403 here would make an ordinary expired token
     * look like a permission problem and show the user an alarming message
     * instead of quietly working.
     */
    @ExceptionHandler(CurrentPrincipal.UnauthenticatedException.class)
    public ResponseEntity<ProblemDetail> onUnauthenticated(CurrentPrincipal.UnauthenticatedException e) {
        return problem(ProblemDetail.of(HttpStatus.UNAUTHORIZED.value(), "unauthenticated",
                "Not signed in", e.getMessage()));
    }

    /**
     * A failed sign-in, and an invalid session, answer the same way.
     *
     * <p>The service already refuses to say which of "no such user", "wrong
     * code" and "code already used" applied; this must not undo that by mapping
     * them to different statuses or different codes.
     */
    @ExceptionHandler({AuthenticationService.AuthenticationFailedException.class,
            SessionService.InvalidSessionException.class})
    public ResponseEntity<ProblemDetail> onAuthenticationFailed(RuntimeException e) {
        return problem(ProblemDetail.of(HttpStatus.UNAUTHORIZED.value(), "authentication_failed",
                "Sign-in failed", e.getMessage()));
    }

    /**
     * 429 with {@code Retry-After}.
     *
     * <p>The header is not decoration: without it a client either hammers the
     * endpoint or backs off for an arbitrary time, and the progressive lockout
     * is there to slow an attacker down, not the person who mistyped a code.
     */
    @ExceptionHandler(AuthenticationService.ThrottledException.class)
    public ResponseEntity<ProblemDetail> onThrottled(AuthenticationService.ThrottledException e) {
        long seconds = Math.max(1, e.retryAfter().getSeconds());
        Map<String, Object> extensions =
                Immutables.<String, Object>mapOf("retryAfterSeconds", Long.valueOf(seconds));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(seconds))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemDetail("about:blank", "Too many attempts",
                        HttpStatus.TOO_MANY_REQUESTS.value(), e.getMessage(), "rate_limited",
                        null, extensions));
    }

    @ExceptionHandler(ETags.PreconditionRequiredException.class)
    public ResponseEntity<ProblemDetail> onPreconditionRequired(ETags.PreconditionRequiredException e) {
        return problem(ProblemDetail.of(HttpStatus.PRECONDITION_REQUIRED.value(),
                "precondition_required", "If-Match required", e.getMessage()));
    }

    /**
     * 412, with the current tag attached.
     *
     * <p>Handing back the tag the caller needs turns a retry into one round trip
     * for a client that can re-derive its change, instead of forcing a re-read
     * it may not be able to do safely.
     */
    @ExceptionHandler(ETags.PreconditionFailedException.class)
    public ResponseEntity<ProblemDetail> onPreconditionFailed(ETags.PreconditionFailedException e) {
        Map<String, Object> extensions =
                Immutables.<String, Object>mapOf("currentEtag", e.currentTag());
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .eTag(e.currentTag())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemDetail("about:blank", "Changed by someone else",
                        HttpStatus.PRECONDITION_FAILED.value(), e.getMessage(),
                        "precondition_failed", null, extensions));
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ProblemDetail> onPermissionDenied(PermissionDeniedException e) {
        // Naming the missing permission is safe: the caller already knows what
        // they tried to do, and without it every denial becomes a support
        // ticket. The grant chain is NOT included - that is the explainer
        // endpoint's job, and it is itself permission-checked.
        Map<String, Object> extensions = Immutables.<String, Object>mapOf(
                "requiredPermission", String.valueOf(e.requiredPermission()));
        return problem(new ProblemDetail("about:blank", "Permission denied",
                HttpStatus.FORBIDDEN.value(), e.getMessage(), "permission_denied",
                null, extensions));
    }

    @ExceptionHandler(BusinessInstantParseException.class)
    public ResponseEntity<ProblemDetail> onBadBusinessInstant(BusinessInstantParseException e) {
        List<ProblemDetail.Violation> violations = new ArrayList<ProblemDetail.Violation>();
        violations.add(new ProblemDetail.Violation(
                "businessInstant", "invalid_business_instant", e.getMessage()));
        return problem(new ProblemDetail("about:blank", "Invalid business instant",
                HttpStatus.BAD_REQUEST.value(), e.getMessage(), "invalid_business_instant",
                violations, Immutables.<String, Object>mapOf("input", e.input())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onInvalidBody(MethodArgumentNotValidException e) {
        List<ProblemDetail.Violation> violations = new ArrayList<ProblemDetail.Violation>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            violations.add(new ProblemDetail.Violation(
                    error.getField(),
                    error.getCode() == null ? "invalid" : error.getCode(),
                    error.getDefaultMessage()));
        }
        // Every failure, in one response.
        return problem(new ProblemDetail("about:blank", "Validation failed",
                HttpStatus.BAD_REQUEST.value(),
                violations.size() + " field(s) failed validation", "validation_failed",
                violations, null));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> onConstraintViolation(ConstraintViolationException e) {
        List<ProblemDetail.Violation> violations = new ArrayList<ProblemDetail.Violation>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            violations.add(new ProblemDetail.Violation(
                    String.valueOf(violation.getPropertyPath()), "invalid", violation.getMessage()));
        }
        return problem(new ProblemDetail("about:blank", "Validation failed",
                HttpStatus.BAD_REQUEST.value(),
                violations.size() + " value(s) failed validation", "validation_failed",
                violations, null));
    }

    /**
     * 404, not 400.
     *
     * <p>{@link RecordNotFoundException} extends {@code IllegalArgumentException},
     * so without this it lands on the handler below and every missing row comes
     * back as "bad request" — which sends the caller looking for a mistake in
     * their payload that is not there. Declared above that handler because Spring
     * picks the most specific match, but the ordering is worth not relying on
     * implicitly.
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> onNotFound(RecordNotFoundException e) {
        return problem(ProblemDetail.of(HttpStatus.NOT_FOUND.value(), "not_found",
                "Not found", e.getMessage()));
    }

    /**
     * 409, not 500.
     *
     * <p>Refusing to demote the last master is the system working, not failing.
     * Unmapped it surfaces as a 500, which tells an administrator the product is
     * broken at the exact moment it is protecting them.
     */
    @ExceptionHandler(MasterAccountService.LastMasterException.class)
    public ResponseEntity<ProblemDetail> onLastMaster(MasterAccountService.LastMasterException e) {
        return problem(ProblemDetail.of(HttpStatus.CONFLICT.value(), "last_master",
                "There must always be a master", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> onIllegalArgument(IllegalArgumentException e) {
        return problem(ProblemDetail.of(HttpStatus.BAD_REQUEST.value(), "bad_request",
                "Bad request", e.getMessage()));
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail detail) {
        return ResponseEntity.status(detail.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail);
    }
}
