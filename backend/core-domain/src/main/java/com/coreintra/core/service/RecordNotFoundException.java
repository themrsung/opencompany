package com.coreintra.core.service;

/**
 * Thrown when an id in a request names nothing.
 *
 * <p>Extends {@link IllegalArgumentException} on purpose: the id arrived from
 * the caller, so this is a bad request, and the API layer already maps
 * {@code IllegalArgumentException} to a 400 problem+json body. Having its own
 * type means the REST layer can later map it to 404 without the services
 * changing, and without anyone having to string-match a message.
 *
 * <p>Ids are random UUIDs, so answering "no such unit" before the permission
 * check runs does not hand out anything guessable; the alternative - denying
 * before we know what the target is - would mean building a permission target
 * out of an id we have not resolved, which is how a check ends up asking about
 * the wrong company.
 */
public class RecordNotFoundException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public RecordNotFoundException(String message) {
        super(message);
    }

    static RecordNotFoundException of(String what, String id) {
        return new RecordNotFoundException("no such " + what + ": " + id);
    }
}
