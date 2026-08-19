package com.coreintra.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SecretCryptoTest {

    @Nested
    @DisplayName("SecretHasher")
    class Hashing {

        @Test
        void hashesVerifyAndRejectWrongInput() {
            String stored = SecretHasher.hash("recovery-code-abc");
            assertThat(SecretHasher.matches("recovery-code-abc", stored)).isTrue();
            assertThat(SecretHasher.matches("recovery-code-abd", stored)).isFalse();
        }

        @Test
        @DisplayName("identical inputs produce different rows, because the salt is per-value")
        void saltIsPerValue() {
            String first = SecretHasher.hash("same");
            String second = SecretHasher.hash("same");
            assertThat(first).isNotEqualTo(second);
            assertThat(SecretHasher.matches("same", first)).isTrue();
            assertThat(SecretHasher.matches("same", second)).isTrue();
        }

        @Test
        @DisplayName("a corrupt stored value fails closed rather than throwing")
        void corruptValueFailsClosed() {
            // A crash here would take down the sign-in path for everyone.
            assertThat(SecretHasher.matches("x", "not-a-hash")).isFalse();
            assertThat(SecretHasher.matches("x", "s256$!!!$!!!")).isFalse();
            assertThat(SecretHasher.matches("x", null)).isFalse();
            assertThat(SecretHasher.matches(null, "s256$a$b")).isFalse();
        }

        @Test
        @DisplayName("a token below 128 bits of entropy is refused")
        void tokenEntropyFloor() {
            assertThatThrownBy(() -> SecretHasher.randomToken(8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("128 bits");
            assertThat(SecretHasher.randomToken(32)).isNotBlank();
        }

        @Test
        void tokensAreUnique() {
            assertThat(SecretHasher.randomToken(32)).isNotEqualTo(SecretHasher.randomToken(32));
        }
    }

    @Nested
    @DisplayName("SecretCipher")
    class Encryption {

        private final SecretCipher cipher =
                new SecretCipher("a-test-key-that-is-long-enough-to-be-accepted");

        @Test
        void roundTrips() {
            byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
            String stored = cipher.encrypt(secret);
            assertThat(cipher.decrypt(stored)).isEqualTo(secret);
        }

        @Test
        @DisplayName("the same secret encrypts differently every time")
        void nonceIsFresh() {
            byte[] secret = cipher.newTotpSecret();
            assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
        }

        @Test
        @DisplayName("tampering is detected rather than yielding plausible garbage")
        void tamperingDetected() {
            String stored = cipher.encrypt(cipher.newTotpSecret());
            String[] parts = stored.split("\\$");
            // Flip a character in the ciphertext.
            String corruptedTail = parts[2].charAt(0) == 'A'
                    ? 'B' + parts[2].substring(1)
                    : 'A' + parts[2].substring(1);
            String tampered = parts[0] + "$" + parts[1] + "$" + corruptedTail;

            assertThatThrownBy(() -> cipher.decrypt(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tampered with");
        }

        @Test
        @DisplayName("a different key cannot decrypt, and says so actionably")
        void wrongKeyFailsLoudly() {
            String stored = cipher.encrypt(cipher.newTotpSecret());
            SecretCipher other = new SecretCipher("a-completely-different-key-of-sufficient-length");
            assertThatThrownBy(() -> other.decrypt(stored))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must re-enrol");
        }

        @Test
        @DisplayName("a short key is refused at construction, with the fix in the message")
        void shortKeyRefused() {
            assertThatThrownBy(() -> new SecretCipher("too-short"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("openssl rand");
        }

        @Test
        @DisplayName("generated TOTP secrets are 160 bits, RFC 4226's recommended minimum")
        void secretLength() {
            assertThat(cipher.newTotpSecret()).hasSize(20);
        }

        @Test
        void wipeClearsMaterial() {
            byte[] secret = cipher.newTotpSecret();
            SecretCipher.wipe(secret);
            assertThat(secret).containsOnly((byte) 0);
        }
    }

    @Nested
    @DisplayName("Base32")
    class Base32Coding {

        @Test
        @DisplayName("round-trips, and matches the known RFC 4648 encoding")
        void roundTrip() {
            byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
            String encoded = com.coreintra.auth.totp.Base32.encode(secret);
            assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
            assertThat(com.coreintra.auth.totp.Base32.decode(encoded)).isEqualTo(secret);
        }

        @Test
        @DisplayName("accepts what users actually retype: lowercase, spaces, padding")
        void tolerantDecoding() {
            byte[] expected = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
            assertThat(com.coreintra.auth.totp.Base32.decode("gezdgnbvgy3tqojqgezdgnbvgy3tqojq"))
                    .isEqualTo(expected);
            assertThat(com.coreintra.auth.totp.Base32.decode("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ"))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("names the offending character rather than failing vaguely")
        void namesBadCharacter() {
            assertThatThrownBy(() -> com.coreintra.auth.totp.Base32.decode("GEZD1NBV"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'1' is not a base32 character");
        }
    }
}
