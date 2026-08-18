package com.coreintra.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Conformance against RFC 6238's own published test vectors.
 *
 * <p>A homegrown TOTP that is subtly wrong fails in the worst possible way:
 * it works in testing against itself and rejects real authenticator apps in
 * production, at which point nobody can sign in. The RFC's vectors are the only
 * check that means anything, so they come first.
 */
class TotpGeneratorTest {

    /** RFC 6238 Appendix B: the ASCII string "12345678901234567890". */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("RFC 6238 Appendix B vectors (HMAC-SHA1)")
    class RfcVectors {

        @ParameterizedTest(name = "T={0} -> {1}")
        @CsvSource({
                "59,          94287082",
                "1111111109,  07081804",
                "1111111111,  14050471",
                "1234567890,  89005924",
                "2000000000,  69279037",
                "20000000000, 65353130",
        })
        @DisplayName("eight-digit codes match the RFC exactly")
        void eightDigitVectors(long epochSeconds, String expected) {
            TotpGenerator generator = new TotpGenerator(30, 8);
            assertThat(generator.generateAt(RFC_SECRET, epochSeconds)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "T={0} -> {1}")
        @CsvSource({
                "59,          287082",
                "1111111109,  081804",
                "1111111111,  050471",
                "1234567890,  005924",
                "2000000000,  279037",
                "20000000000, 353130",
        })
        @DisplayName("six-digit codes are the low six digits of the same truncation")
        void sixDigitVectors(long epochSeconds, String expected) {
            // Not a separate algorithm: RFC 4226 takes the dynamic-truncation
            // result modulo 10^digits, so six digits is the eight-digit value's
            // tail. Asserting both catches a modulo applied at the wrong step.
            TotpGenerator generator = new TotpGenerator(30, 6);
            assertThat(generator.generateAt(RFC_SECRET, epochSeconds)).isEqualTo(expected);
        }

        @Test
        @DisplayName("codes keep leading zeros")
        void leadingZerosPreserved() {
            // 1111111109 produces 081804. Returning "81804" would be rejected by
            // every authenticator app, and an int return type would have done
            // exactly that.
            assertThat(new TotpGenerator(30, 6).generateAt(RFC_SECRET, 1111111109L))
                    .isEqualTo("081804")
                    .hasSize(6);
        }
    }

    @Nested
    class TimeSteps {

        @Test
        @DisplayName("the step counter advances every 30 seconds and is stable within one")
        void stepBoundaries() {
            TotpGenerator generator = new TotpGenerator();
            assertThat(generator.timeStepAt(0)).isZero();
            assertThat(generator.timeStepAt(29)).isZero();
            assertThat(generator.timeStepAt(30)).isEqualTo(1L);
            assertThat(generator.timeStepAt(59)).isEqualTo(1L);
            assertThat(generator.timeStepAt(1111111109L)).isEqualTo(37037036L);
        }

        @Test
        @DisplayName("the code is constant across a step and changes at the boundary")
        void codeIsConstantWithinAStep() {
            TotpGenerator generator = new TotpGenerator();
            String at30 = generator.generateAt(RFC_SECRET, 30);
            String at59 = generator.generateAt(RFC_SECRET, 59);
            String at60 = generator.generateAt(RFC_SECRET, 60);
            assertThat(at30).isEqualTo(at59);
            assertThat(at60).isNotEqualTo(at30);
        }
    }

    @Nested
    class Configuration {

        @Test
        @DisplayName("digit counts outside RFC 4226's range are refused")
        void digitRange() {
            assertThatThrownBy(() -> new TotpGenerator(30, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("6-8");
            assertThatThrownBy(() -> new TotpGenerator(30, 9))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void emptySecretRefused() {
            assertThatThrownBy(() -> new TotpGenerator().generateAt(new byte[0], 59))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("secret is empty");
        }
    }

    @Nested
    @DisplayName("enrolment URI")
    class EnrolmentUri {

        @Test
        @DisplayName("carries the parameters an authenticator app needs")
        void uriShape() {
            String uri = new TotpGenerator().enrolmentUri("에이스전자", "minjun", RFC_SECRET);
            assertThat(uri)
                    .startsWith("otpauth://totp/")
                    .contains("algorithm=SHA1")
                    .contains("digits=6")
                    .contains("period=30")
                    .contains("secret=" + Base32.encode(RFC_SECRET));
        }

        @Test
        @DisplayName("percent-encodes a Korean issuer rather than emitting raw bytes")
        void koreanIssuerEncoded() {
            String uri = new TotpGenerator().enrolmentUri("에이스전자", "김민준", RFC_SECRET);
            assertThat(uri).doesNotContain("에이스전자");
            assertThat(uri).contains("%EC%97%90");
        }
    }
}
