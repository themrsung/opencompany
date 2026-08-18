package com.coreintra.app.api.error;

import com.coreintra.businesstime.BusinessInstantParseException;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionDeniedException;
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
