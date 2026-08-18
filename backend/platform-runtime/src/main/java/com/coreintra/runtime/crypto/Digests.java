package com.coreintra.runtime.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The two hashes this module needs, and the comparison that goes with them.
 *
 * <p>SHA-256 of a request body decides whether a retry is a retry; HMAC-SHA256
 * of a webhook payload is what lets the receiver believe it. Both are JDK
 * primitives - there is deliberately no crypto library here, because a Java 8
 * baseline (ADR 0001) means a dependency that drifts is a dependency that
 * breaks on a client's JRE.
 *
 * <h2>Why the comparison is its own method</h2>
 *
 * <p>{@code String.equals} on a signature leaks, through timing, how many
 * leading characters were right. That is enough to forge a signature one
 * character at a time given enough attempts, and webhook endpoints are exactly
 * the place with enough attempts. {@link #constantTimeEquals} is not an
 * optimisation to skip.
 */
public final class Digests {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Digests() {
    }

    /** Hex SHA-256 of the UTF-8 bytes of {@code text}. Empty and null hash alike. */
    public static String sha256Hex(String text) {
        return hex(sha256(utf8(text == null ? "" : text)));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256. If this one does not, nothing else here works either.
            throw new IllegalStateException("SHA-256 is unavailable on this JRE", e);
        }
    }

    /** Hex HMAC-SHA256, the form webhook receivers compare against. */
    public static String hmacSha256Hex(String secret, String payload) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("an unsigned webhook is a webhook nobody can trust");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(utf8(secret), "HmacSHA256"));
            return hex(mac.doFinal(utf8(payload == null ? "" : payload)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable on this JRE", e);
        }
    }

    /**
     * Compares two hex strings without returning early on the first difference.
     *
     * @return true when both are non-null, the same length, and equal
     */
    public static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        byte[] a = utf8(expected);
        byte[] b = utf8(presented);
        if (a.length != b.length) {
            // Length is not a secret: the signature format is public and fixed.
            return false;
        }
        int difference = 0;
        for (int i = 0; i < a.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return difference == 0;
    }

    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable on this JRE", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(out);
    }
}
