package com.coreintra.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TotpVerifierTest {

    private static final byte[] SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String ACCOUNT = "acc-1";
    /** Mid-step, so ±1 drift is symmetric around it. */
    private static final long NOW = 1_700_000_015L;

    private InMemoryConsumedSteps store;
    private TotpGenerator generator;
    private TotpVerifier verifier;

    @BeforeEach
    void setUp() {
        store = new InMemoryConsumedSteps();
        generator = new TotpGenerator();
        verifier = new TotpVerifier(generator, 1);
    }

    private String codeAt(long epochSeconds) {
        return generator.generateAt(SECRET, epochSeconds);
    }

    @Nested
    @DisplayName("replay protection")
    class Replay {

        @Test
        @DisplayName("a correct code works exactly once")
        void codeWorksOnce() {
            String code = codeAt(NOW);

            TotpVerifier.Result first = verifier.verify(ACCOUNT, SECRET, code, NOW, store);
            assertThat(first.isSuccess()).isTrue();

            TotpVerifier.Result second = verifier.verify(ACCOUNT, SECRET, code, NOW, store);
            assertThat(second.isSuccess())
                    .as("the same code inside its own window must not work twice")
                    .isFalse();
            assertThat(second.failure()).isEqualTo(TotpVerifier.Failure.REPLAYED);
        }

        @Test
        @DisplayName("consuming a step for one account does not lock out another")
        void consumptionIsPerAccount() {
            String code = codeAt(NOW);
            assertThat(verifier.verify(ACCOUNT, SECRET, code, NOW, store).isSuccess()).isTrue();
            // A different account that happens to share a secret in this test is
            // still a different replay history.
            assertThat(verifier.verify("acc-2", SECRET, code, NOW, store).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("the next step's code still works after the previous one was consumed")
        void nextStepStillWorks() {
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW), NOW, store).isSuccess()).isTrue();
            long nextStep = NOW + 30;
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(nextStep), nextStep, store).isSuccess())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("drift")
    class Drift {

        @Test
        @DisplayName("one step either side is accepted")
        void oneStepEitherSide() {
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW - 30), NOW, store).isSuccess())
                    .as("a phone 30s slow")
                    .isTrue();
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW + 30), NOW, store).isSuccess())
                    .as("a phone 30s fast")
                    .isTrue();
        }

        @Test
        @DisplayName("two steps out is rejected")
        void twoStepsRejected() {
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW - 60), NOW, store).isSuccess())
                    .isFalse();
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW + 60), NOW, store).isSuccess())
                    .isFalse();
        }

        @Test
        @DisplayName("an unreasonably wide drift window is refused at construction")
        void driftIsBounded() {
            assertThatThrownBy(() -> new TotpVerifier(generator, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fix the client clock");
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {

        @Test
        void rejectsWrongLengthAndNonDigits() {
            assertThat(verifier.verify(ACCOUNT, SECRET, "12345", NOW, store).failure())
                    .isEqualTo(TotpVerifier.Failure.MALFORMED);
            assertThat(verifier.verify(ACCOUNT, SECRET, "1234567", NOW, store).failure())
                    .isEqualTo(TotpVerifier.Failure.MALFORMED);
            assertThat(verifier.verify(ACCOUNT, SECRET, "12a456", NOW, store).failure())
                    .isEqualTo(TotpVerifier.Failure.MALFORMED);
            assertThat(verifier.verify(ACCOUNT, SECRET, "", NOW, store).failure())
                    .isEqualTo(TotpVerifier.Failure.MALFORMED);
        }

        @Test
        @DisplayName("spaces and dashes are tolerated, because users type what they see")
        void toleratesSeparators() {
            String code = codeAt(NOW);
            String spaced = code.substring(0, 3) + " " + code.substring(3);
            assertThat(verifier.verify(ACCOUNT, SECRET, spaced, NOW, store).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a malformed code does not consume a step")
        void malformedDoesNotConsume() {
            verifier.verify(ACCOUNT, SECRET, "abcdef", NOW, store);
            assertThat(store.consumed).isEmpty();
            assertThat(verifier.verify(ACCOUNT, SECRET, codeAt(NOW), NOW, store).isSuccess()).isTrue();
        }
    }

    @Nested
    class ConstantTimeComparison {

        @Test
        @DisplayName("differs only in the result, not in where the difference is")
        void comparesWholeString() {
            assertThat(TotpVerifier.constantTimeEquals("123456", "123456")).isTrue();
            assertThat(TotpVerifier.constantTimeEquals("123456", "123457")).isFalse();
            assertThat(TotpVerifier.constantTimeEquals("123456", "923456")).isFalse();
            assertThat(TotpVerifier.constantTimeEquals("123456", "12345")).isFalse();
            assertThat(TotpVerifier.constantTimeEquals("123456", null)).isFalse();
        }
    }

    /** Not thread-safe; the production store is a database row with a unique key. */
    private static final class InMemoryConsumedSteps implements TotpVerifier.ConsumedStepStore {
        final Set<String> consumed = new HashSet<String>();

        @Override
        public boolean isConsumed(String accountId, long timeStep) {
            return consumed.contains(accountId + "/" + timeStep);
        }

        @Override
        public void consume(String accountId, long timeStep) {
            consumed.add(accountId + "/" + timeStep);
        }
    }
}
