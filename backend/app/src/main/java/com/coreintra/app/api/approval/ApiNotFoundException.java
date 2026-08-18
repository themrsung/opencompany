package com.coreintra.app.api.approval;

/**
 * Nothing here to return.
 *
 * <h2>Why not {@code RecordNotFoundException}</h2>
 *
 * <p>{@code com.coreintra.core.service.RecordNotFoundException} extends
 * {@link IllegalArgumentException}, so it arrives at {@code ApiExceptionHandler}
 * as a 400. For the org endpoints that is survivable; for "is there a 취업규칙 in
 * force on this date?" it is actively misleading, because the request was
 * perfectly well formed and the honest answer is that there is not one. A client
 * that sees 400 retries with different input; a client that sees 404 shows the
 * empty state.
 *
 * <p>This is deliberately a {@link RuntimeException} and not a subclass of
 * {@code IllegalArgumentException}, so that it cannot be picked up by the 400
 * handler by accident.
 */
public class ApiNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiNotFoundException(String message) {
        super(message);
    }
}
