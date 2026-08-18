package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.service.NoSuchAccountingRecordException;
import com.coreintra.app.api.error.ProblemDetail;
import com.coreintra.compat.Immutables;
import com.coreintra.runtime.idempotency.IdempotencyConflictException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The three failures this surface has that the application-wide handler does not know about.
 *
 * <p>Scoped to this package rather than added to {@code ApiExceptionHandler}, because each of these
 * is a fact about accounting and not about the API as a whole. The general handler keeps its
 * general rules — every validation failure at once, a denial that names its permission — and this
 * adds the module's own vocabulary on top. Spring prefers the more specific advice, so nothing here
 * changes any other endpoint's behaviour.
 *
 * <h2>Why an unbalanced entry is a 422 and not a 400</h2>
 *
 * <p>The request was well formed: every field parsed, every amount was an exact decimal, every
 * account exists. What was wrong was the transaction it described. 422 says "I understood you and
 * I will not do it", which is the difference between a client with a bug in its serialisation and
 * a bookkeeper who has mistyped a figure — and the body carries the exact difference, because that
 * is the number the bookkeeper needs to find the mistake.
 */
@RestControllerAdvice(basePackages = "com.coreintra.app.api.accounting")
@AccountingEnabled
public class AccountingApiAdvice {

    /**
     * 409, naming which of the two conflicts it was.
     *
     * <p>A replayed key with a different body and a request still in flight are both refusals, and
     * a client can act on them differently: the first is a bug to fix, the second is a wait.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> onIdempotencyConflict(IdempotencyConflictException e) {
        Map<String, Object> extensions =
                Immutables.<String, Object>mapOf("idempotencyKey", e.idempotencyKey());
        return problem(new ProblemDetail("about:blank", "Idempotency key conflict",
                HttpStatus.CONFLICT.value(), e.getMessage(), e.code(), null, extensions));
    }

    /**
     * 422 with the difference attached.
     *
     * <p>Debits equal credits exactly or the entry does not exist — so there is nothing to return
     * a location for and nothing was written. What the caller gets instead is the size of the gap,
     * as an exact decimal string like every other amount on this wire.
     */
    @ExceptionHandler(Entry.UnbalancedEntryException.class)
    public ResponseEntity<ProblemDetail> onUnbalanced(Entry.UnbalancedEntryException e) {
        Map<String, Object> extensions = Immutables.<String, Object>mapOf(
                "difference", Amounts.wire(e.difference()));
        return problem(new ProblemDetail("about:blank", "Debits do not equal credits",
                HttpStatus.UNPROCESSABLE_ENTITY.value(), e.getMessage(), "entry_unbalanced",
                null, extensions));
    }

    /** 404. The id is not a bad value, it is a row that is not there. */
    @ExceptionHandler(NoSuchAccountingRecordException.class)
    public ResponseEntity<ProblemDetail> onMissing(NoSuchAccountingRecordException e) {
        Map<String, Object> extensions = Immutables.<String, Object>mapOf(
                "kind", e.kind(), "id", e.id());
        return problem(new ProblemDetail("about:blank", "Not found",
                HttpStatus.NOT_FOUND.value(), e.getMessage(), "not_found", null, extensions));
    }

    /**
     * 409 for a rule about the current state of the row.
     *
     * <p>"This account has children so it is a subtotal", "this entry is already void", "this
     * currency is the book's base currency". Every one of them is a request that would have been
     * fine a moment earlier or against a different row, which is what 409 means. Without this they
     * would reach the container as a 500 and read as a server fault.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> onIllegalState(IllegalStateException e) {
        return problem(ProblemDetail.of(HttpStatus.CONFLICT.value(), "conflict",
                "That cannot be done to this record as it stands", e.getMessage()));
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail detail) {
        return ResponseEntity.status(detail.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail);
    }
}
