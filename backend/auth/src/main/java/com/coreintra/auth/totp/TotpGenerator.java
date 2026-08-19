package com.coreintra.auth.totp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords.
 *
 * <p>Implemented directly rather than pulled in: the algorithm is HMAC plus a
 * documented truncation, all of it available in the JDK, and a dependency here
 * would be a dependency in the authentication path of a system running on an
 * EOL framework. Correctness is pinned by the RFC's own published test vectors.
 *
 * <h2>SHA-1 is correct here</h2>
 *
 * <p>RFC 6238's default is HMAC-SHA1, and it is what every authenticator app
 * implements. This is a MAC construction, not a collision-resistance claim —
 * SHA-1's collision weakness does not apply, and HMAC-SHA1 remains unbroken.
 * Choosing SHA-256 would be a compatibility bug dressed as a security
 * improvement, because most authenticator apps silently assume SHA-1.
 *
 * <p>This class only <em>computes</em> codes. Drift tolerance, replay
 * rejection and lockout live in {@link TotpVerifier}, because those are policy
 * and this is arithmetic.
 *
 * <p>Stateless and thread-safe.
 */
public final class TotpGenerator {

    /** RFC 6238 section 4: T0 = 0, the Unix epoch. */
    public static final long EPOCH_SECONDS = 0L;

    /** RFC 6238 default time step. */
    public static final int DEFAULT_STEP_SECONDS = 30;

    /** RFC 4226 default. Six is what authenticator apps show. */
    public static final int DEFAULT_DIGITS = 6;

    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final int[] POWERS_OF_TEN = {
            1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000
    };

    private final int stepSeconds;
    private final int digits;

    public TotpGenerator() {
        this(DEFAULT_STEP_SECONDS, DEFAULT_DIGITS);
    }

    public TotpGenerator(int stepSeconds, int digits) {
        if (stepSeconds <= 0) {
            throw new IllegalArgumentException("stepSeconds must be positive, got " + stepSeconds);
        }
        if (digits < 6 || digits > 8) {
            // RFC 4226 permits 6-8. Below 6 is trivially guessable; above 8
            // exceeds what the truncation produces.
            throw new IllegalArgumentException("digits must be 6-8, got " + digits);
        }
        this.stepSeconds = stepSeconds;
        this.digits = digits;
    }

    /** The counter for a moment: {@code floor((epochSeconds - T0) / step)}. */
    public long timeStepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds - EPOCH_SECONDS, (long) stepSeconds);
    }

    /** The code for a given counter value. */
    public String generateForStep(byte[] secret, long timeStep) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret is empty");
        }
        byte[] counter = new byte[8];
        long value = timeStep;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }

        byte[] hash = hmacSha1(secret, counter);

        // RFC 4226 section 5.3: dynamic truncation.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        int otp = binary % POWERS_OF_TEN[digits];
        return zeroPad(otp, digits);
    }

    public String generateAt(byte[] secret, long epochSeconds) {
        return generateForStep(secret, timeStepAt(epochSeconds));
    }

    private static byte[] hmacSha1(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret, HMAC_SHA1));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException e) {
            // HmacSHA1 is required of every conformant JRE.
            throw new IllegalStateException("HmacSHA1 unavailable in this JRE", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid TOTP secret", e);
        }
    }

    private static String zeroPad(int otp, int digits) {
        StringBuilder text = new StringBuilder(Integer.toString(otp));
        while (text.length() < digits) {
            text.insert(0, '0');
        }
        return text.toString();
    }

    public int stepSeconds() {
        return stepSeconds;
    }

    public int digits() {
        return digits;
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans.
     *
     * <p>Contains the shared secret, so it is generated on demand, shown once,
     * and never stored, logged, or sent anywhere but the enrolling user's own
     * screen.
     */
    public String enrolmentUri(String issuer, String accountName, byte[] secret) {
        String encodedIssuer = urlEncode(issuer);
        String label = encodedIssuer + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1"
                + "&digits=" + digits
                + "&period=" + stepSeconds;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }
}
