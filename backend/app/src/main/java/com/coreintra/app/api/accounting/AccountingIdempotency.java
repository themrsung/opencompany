package com.coreintra.app.api.accounting;

import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.runtime.idempotency.IdempotencyDecision;
import com.coreintra.runtime.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The idempotency key handling for the POSTs in this package that create money.
 *
 * <h2>Why the stored response is replayed as bytes</h2>
 *
 * <p>A replay returns the JSON the first call returned, parsed back into a tree and re-emitted
 * unchanged — not the DTO re-serialised from a fresh read of the database. Two reasons, and the
 * second is the one that matters.
 *
 * <p>The first is honesty: a client retrying a dropped response is asking "what did my request
 * do?", and the answer is what the response said, including the entry id it minted. The second is
 * that re-deriving the answer would make the replay a second read of a ledger that has moved on.
 * The entry may have been corrected in between; replaying the current state under an idempotency
 * key would tell a retrying client that its POST created something it did not create.
 *
 * <h2>The three outcomes</h2>
 *
 * <ul>
 *   <li><b>New key</b> — the work runs and its response is stored against the key.</li>
 *   <li><b>Same key, same body</b> — the stored response, with {@code Idempotent-Replay: true} so
 *       a client can tell the difference if it wants to.</li>
 *   <li><b>Same key, different body</b> — refused loudly by {@link IdempotencyService}, never
 *       answered with the first result. A client sending a second, different request under one key
 *       is confused about which operation it is retrying, and handing it the old answer would
 *       leave it believing something happened that did not.</li>
 * </ul>
 *
 * <p>{@link IdempotencyService} is the platform's, deliberately: a second mechanism living in the
 * accounting package would be a second table, a second retention policy and a second answer to
 * "has this already run".
 */
@Component
@AccountingEnabled
public class AccountingIdempotency {

    /** Long enough to be unguessable in a header, short enough to read in a log. */
    static final int MIN_KEY_LENGTH = 8;

    private final IdempotencyService keys;
    private final ObjectMapper json;

    public AccountingIdempotency(IdempotencyService keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    /**
     * Runs {@code work} once per key, or replays what it returned last time.
     *
     * @param companyId the company the money belongs to; keys are scoped to a caller within one
     * @param endpoint  a stable label for the operation, recorded beside the key so a support
     *                  session can see which endpoint a key was used against
     * @param request   the request body, whose fingerprint decides whether a replay is the same
     *                  request or a different one
     * @param created   the status a fresh call answers with
     */
    public ResponseEntity<Object> once(PermissionPrincipal caller, String companyId,
            String idempotencyKey, String endpoint, Object request, HttpStatus created,
            Supplier<Object> work) {
        Outcome outcome = run(caller, companyId, idempotencyKey, endpoint, request,
                created.value(), work);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        if (outcome.isReplayed()) {
            response = response.header("Idempotent-Replay", "true");
        }
        return response.body(outcome.body());
    }

    /**
     * The same guarantee without an HTTP response around it, for the MCP surface.
     *
     * <p>MCP has no headers to carry a key in, so the write tool takes one as an argument and
     * comes through here. It matters more there, not less: a model retrying a tool call it is not
     * sure completed is the ordinary case, and posting the entry twice is the ordinary
     * consequence.
     */
    public Outcome run(PermissionPrincipal caller, String companyId, String idempotencyKey,
            String endpoint, Object request, int createdStatus, Supplier<Object> work) {
        requireUsableKey(idempotencyKey);
        IdempotencyDecision decision = keys.begin(companyId, caller.accountId(), idempotencyKey,
                endpoint, fingerprint(request));
        if (decision.isReplay()) {
            return new Outcome(reread(decision.body()),
                    decision.status() == null ? createdStatus : decision.status().intValue(),
                    true);
        }
        Object body = work.get();
        keys.complete(decision.recordId(), createdStatus, fingerprint(body));
        return new Outcome(body, createdStatus, false);
    }

    /** What happened: the answer, the status it was answered with, and whether it is a replay. */
    public static final class Outcome {
        private final Object body;
        private final int status;
        private final boolean replayed;

        Outcome(Object body, int status, boolean replayed) {
            this.body = body;
            this.status = status;
            this.replayed = replayed;
        }

        /** The DTO on a fresh call; the stored JSON tree, verbatim, on a replay. */
        public Object body() {
            return body;
        }

        public int status() {
            return status;
        }

        public boolean isReplayed() {
            return replayed;
        }
    }

    /**
     * A missing key is a client that has not implemented the requirement, and it is refused rather
     * than allowed through unprotected. §10 does not say "idempotency keys where convenient".
     */
    private static void requireUsableKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("this endpoint creates money, so it requires an "
                    + "Idempotency-Key header of at least " + MIN_KEY_LENGTH + " characters. "
                    + "Without one a dropped response cannot be retried safely, and the retry "
                    + "posts the entry twice.");
        }
    }

    private String fingerprint(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // The DTOs here are plain getters over strings; if one cannot be written the bug is
            // in the DTO, and pretending the key matched would be worse than failing.
            throw new IllegalStateException("could not serialise the request for its "
                    + "idempotency fingerprint", e);
        }
    }

    private Object reread(String storedBody) {
        if (storedBody == null) {
            return null;
        }
        try {
            return json.readTree(storedBody);
        } catch (IOException e) {
            throw new IllegalStateException("the stored idempotent response is not JSON", e);
        }
    }
}
