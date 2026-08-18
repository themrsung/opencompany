package com.coreintra.app.api.approval;

import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.runtime.idempotency.IdempotencyDecision;
import com.coreintra.runtime.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Makes a retried POST create one approval instead of two.
 *
 * <h2>What a dropped response looks like from the client</h2>
 *
 * <p>The document was created and the connection died before the 201 arrived.
 * The client cannot tell that from "the request never landed", so it retries —
 * correctly. Without a key the retry drafts a second 지출결의서 and somebody
 * approves both.
 *
 * <p>{@link IdempotencyService} is the one mechanism for this; there is no
 * second one here. This class is the thin part: pull the header, hash the body,
 * and turn the decision into either "go ahead" or "you already did this".
 *
 * <h2>Why the record stores an id rather than the response bytes</h2>
 *
 * <p>Replaying stored JSON would need every response DTO to be deserialisable,
 * which would mean adding setters to types that are immutable on purpose. So the
 * record holds the id of the thing that was created, and a replay re-renders it
 * through the same permission-checked read the first call used.
 *
 * <p>That has a consequence worth stating: a replay reflects the resource as it
 * is now, not as it was when the first call answered. For a create that is the
 * better answer — the client wants the document, not a photograph of it — and
 * the guarantee that matters, that the operation ran once, is untouched. A
 * replay with the same key and a <em>different</em> body is still refused
 * loudly by the service, because that means the client is confused about which
 * operation it is retrying.
 */
@Component
public class WriteOnce {

    /** Thrown when a POST that creates an approval arrives without a key. */
    public static class KeyRequiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public KeyRequiredException(String endpoint) {
            super("This request needs an Idempotency-Key header. " + endpoint + " creates an "
                    + "approval, and without a key a retry after a dropped response would "
                    + "create a second one. Send any value that is unique to this attempt — a "
                    + "UUID is the usual choice — and reuse it on every retry of the same "
                    + "intent.");
        }
    }

    /** A reserved key: either permission to run, or the answer from last time. */
    public static final class Reservation {

        private final IdempotencyService keys;
        private final IdempotencyDecision decision;

        private Reservation(IdempotencyService keys, IdempotencyDecision decision) {
            this.keys = keys;
            this.decision = decision;
        }

        /** No reservation was asked for; the caller runs unguarded. */
        static Reservation unguarded() {
            return new Reservation(null, null);
        }

        public boolean isReplay() {
            return decision != null && decision.isReplay();
        }

        /** The id created by the call that already ran. Only valid on a replay. */
        public String replayedId() {
            return decision == null ? null : decision.body();
        }

        /**
         * Records what was created, so every later retry gets the same answer.
         *
         * @param resourceId the id of the created document
         */
        public void created(String resourceId) {
            if (decision != null && !decision.isReplay()) {
                keys.complete(decision.recordId(), 201, resourceId);
            }
        }
    }

    private final IdempotencyService keys;
    private final ObjectMapper json;

    public WriteOnce(IdempotencyService keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    /**
     * Reserves the key, insisting on one.
     *
     * @throws KeyRequiredException when the header is absent or blank
     */
    public Reservation require(PermissionPrincipal caller, String companyId, String key,
            String endpoint, Object requestBody) {
        if (Texts.isBlank(key)) {
            throw new KeyRequiredException(endpoint);
        }
        return reserve(caller, companyId, key, endpoint, requestBody);
    }

    /**
     * Reserves the key when the caller offered one, and otherwise gets out of
     * the way.
     *
     * <p>Used by the action endpoints. A second 승인 by the same person at the
     * same step is already refused by the domain with an explanation, so a key
     * is protection a careful client may ask for rather than something the
     * server has to insist on.
     */
    public Reservation optional(PermissionPrincipal caller, String companyId, String key,
            String endpoint, Object requestBody) {
        if (Texts.isBlank(key)) {
            return Reservation.unguarded();
        }
        return reserve(caller, companyId, key, endpoint, requestBody);
    }

    private Reservation reserve(PermissionPrincipal caller, String companyId, String key,
            String endpoint, Object requestBody) {
        return new Reservation(keys, keys.begin(companyId, caller.accountId(), Texts.strip(key),
                endpoint, canonical(requestBody)));
    }

    /**
     * The request as one string, so that two retries of the same intent hash the
     * same and a changed field does not.
     */
    private String canonical(Object requestBody) {
        if (requestBody == null) {
            return "";
        }
        try {
            return json.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            // The body was accepted by the same mapper on the way in, so this
            // cannot be a client fault; failing loudly beats hashing "" and
            // letting two different requests share a key.
            throw new IllegalStateException("could not canonicalise the request body", e);
        }
    }
}
