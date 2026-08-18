package com.coreintra.auth.totp;

import com.coreintra.compat.Texts;

/**
 * Verifies a submitted TOTP code: drift tolerance, replay rejection, and
 * constant-time comparison.
 *
 * <p>Split from {@link TotpGenerator} because this is policy and that is
 * arithmetic. The split also means the RFC vectors test the arithmetic without
 * a replay store in the way.
 *
 * <h2>Replay rejection is the part that matters</h2>
 *
 * <p>A TOTP code stays valid for a whole time step, and with drift tolerance
 * for three. Anyone who observes a code — over the shoulder, in a screenshot,
 * in a proxy log — can use it again within that window unless the server
 * refuses a counter it has already accepted. So a successful verification
 * <em>consumes</em> {@code (accountId, timeStep)}, and a second presentation of
 * that same step fails even though the code is still arithmetically correct.
 *
 * <p>This is why {@link #verify} takes a {@link ConsumedStepStore} rather than
 * being a pure function: without it, TOTP is a password that changes every
 * thirty seconds, which is not the same thing as a one-time password.
 *
 * <h2>Drift</h2>
 *
 * <p>±1 step by default, so a phone up to 30 seconds out still works. Widening
 * this multiplies the replay window and the guessing surface; it is a setting
 * rather than a constant only because some client sites have genuinely bad
 * clocks.
 */
public class TotpVerifier {

    /** Where consumed counters are remembered. */
    public interface ConsumedStepStore {

        /** True when this account has already used this counter. */
        boolean isConsumed(String accountId, long timeStep);

        /**
         * Records a counter as used.
         *
         * <p>Implementations must be atomic against concurrent verification of
         * the same code, or two simultaneous requests both succeed and the
         * replay protection is decorative.
         */
        void consume(String accountId, long timeStep);
    }

    /** Why a verification failed. Never shown to the user in this detail. */
    public enum Failure {
        /** The code does not match any step in the drift window. */
        INCORRECT,
        /** The code is arithmetically valid but this counter was already used. */
        REPLAYED,
        /** The submitted value was not a well-formed code at all. */
        MALFORMED
    }

    /** The outcome, with enough detail for the audit log and none for the attacker. */
    public static final class Result {
        private final boolean success;
        private final Failure failure;
        private final long timeStep;

        private Result(boolean success, Failure failure, long timeStep) {
            this.success = success;
            this.failure = failure;
            this.timeStep = timeStep;
        }

        static Result ok(long timeStep) {
            return new Result(true, null, timeStep);
        }

        static Result failed(Failure failure) {
            return new Result(false, failure, -1L);
        }

        public boolean isSuccess() {
            return success;
        }

        public Failure failure() {
            return failure;
        }

        /** The counter that matched, for the audit trail. -1 on failure. */
        public long timeStep() {
            return timeStep;
        }
    }

    private final TotpGenerator generator;
    private final int driftSteps;

    public TotpVerifier(TotpGenerator generator, int driftSteps) {
        if (driftSteps < 0) {
            throw new IllegalArgumentException("driftSteps must not be negative");
        }
        if (driftSteps > 4) {
            // Two minutes either side. Beyond that the operator should fix the
            // clock rather than widen the window every attacker also gets.
            throw new IllegalArgumentException(
                    "driftSteps above 4 widens the replay and guessing window unacceptably; "
                            + "fix the client clock instead. Got " + driftSteps);
        }
        this.generator = generator;
        this.driftSteps = driftSteps;
    }

    public TotpVerifier() {
        this(new TotpGenerator(), 1);
    }

    /**
     * Verifies and, on success, consumes the matched counter.
     *
     * @param accountId whose replay history to consult
     * @param secret    the account's TOTP secret
     * @param submitted what the user typed
     * @param nowEpochSeconds current time
     */
    public Result verify(String accountId, byte[] secret, String submitted, long nowEpochSeconds,
            ConsumedStepStore store) {
        if (Texts.isBlank(submitted)) {
            return Result.failed(Failure.MALFORMED);
        }
        String cleaned = Texts.strip(submitted).replace(" ", "").replace("-", "");
        if (cleaned.length() != generator.digits()) {
            return Result.failed(Failure.MALFORMED);
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (cleaned.charAt(i) < '0' || cleaned.charAt(i) > '9') {
                return Result.failed(Failure.MALFORMED);
            }
        }

        long currentStep = generator.timeStepAt(nowEpochSeconds);
        boolean matchedButReplayed = false;

        // Every candidate step is checked even after a match, so the work done
        // does not depend on which step matched. A loop that returned early
        // would leak the clock skew through timing.
        long matchedStep = Long.MIN_VALUE;
        for (long offset = -driftSteps; offset <= driftSteps; offset++) {
            long candidate = currentStep + offset;
            String expected = generator.generateForStep(secret, candidate);
            if (constantTimeEquals(expected, cleaned) && matchedStep == Long.MIN_VALUE) {
                matchedStep = candidate;
            }
        }

        if (matchedStep == Long.MIN_VALUE) {
            return Result.failed(Failure.INCORRECT);
        }
        if (store.isConsumed(accountId, matchedStep)) {
            matchedButReplayed = true;
        }
        if (matchedButReplayed) {
            return Result.failed(Failure.REPLAYED);
        }
        store.consume(accountId, matchedStep);
        return Result.ok(matchedStep);
    }

    /**
     * Length-independent, content-constant comparison.
     *
     * <p>{@code String.equals} short-circuits on the first differing character,
     * which leaks how much of a guess was correct.
     */
    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int difference = expected.length() ^ actual.length();
        for (int i = 0; i < expected.length() && i < actual.length(); i++) {
            difference |= expected.charAt(i) ^ actual.charAt(i);
        }
        return difference == 0;
    }

    public int driftSteps() {
        return driftSteps;
    }

    public TotpGenerator generator() {
        return generator;
    }
}
