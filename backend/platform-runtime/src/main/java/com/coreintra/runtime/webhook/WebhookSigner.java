package com.coreintra.runtime.webhook;

import com.coreintra.compat.Texts;
import com.coreintra.runtime.crypto.Digests;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Signs deliveries so a receiver can tell ours from anyone else's (§10).
 *
 * <h2>The timestamp is inside the signature</h2>
 *
 * <p>Signing the body alone produces a value that stays valid forever, so a
 * recorded delivery can be replayed at a receiver months later and will verify.
 * The signed string is {@code <unix seconds>.<payload>}, and the header carries
 * the timestamp beside the signature so the receiver can both recompute it and
 * refuse one that is too old. That refusal is the receiver's, but the format
 * has to make it possible.
 *
 * <p>Header form, one line, deliberately boring:
 * <pre>{@code X-CoreIntra-Signature: t=1755500400,v1=<hex hmac-sha256>}</pre>
 * The {@code v1} label is there so a future scheme can be added beside it
 * rather than by breaking every installed receiver at once.
 */
public final class WebhookSigner {

    public static final String HEADER = "X-CoreIntra-Signature";
    public static final String VERSION = "v1";

    /** Generous enough for a slow queue, short enough that a capture goes stale. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSigner() {
    }

    /** The full header value for a payload signed at {@code at}. */
    public static String sign(String secret, String payload, OffsetDateTime at) {
        long seconds = at.toEpochSecond();
        return "t=" + seconds + "," + VERSION + "=" + signature(secret, payload, seconds);
    }

    /** Just the hex HMAC, over {@code <seconds>.<payload>}. */
    public static String signature(String secret, String payload, long epochSeconds) {
        return Digests.hmacSha256Hex(secret, epochSeconds + "." + (payload == null ? "" : payload));
    }

    /**
     * Recomputes the signature and compares it in constant time.
     *
     * <p>A tampered body, a wrong secret, a malformed header and a stale
     * timestamp all return false. The caller is told only that verification
     * failed: which of the four it was is information an attacker would use.
     */
    public static boolean verify(String secret, String payload, String header,
                                 OffsetDateTime now, Duration tolerance) {
        if (Texts.isBlank(header) || Texts.isBlank(secret)) {
            return false;
        }
        Long timestamp = null;
        String presented = null;
        for (String part : header.split(",")) {
            String piece = Texts.strip(part);
            if (piece.startsWith("t=")) {
                timestamp = parse(piece.substring(2));
            } else if (piece.startsWith(VERSION + "=")) {
                presented = piece.substring(VERSION.length() + 1);
            }
        }
        if (timestamp == null || presented == null) {
            return false;
        }
        Duration window = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        long skew = Math.abs(now.toEpochSecond() - timestamp.longValue());
        if (skew > window.getSeconds()) {
            // Correctly signed, but old enough that this is a replay rather than a delivery.
            return false;
        }
        return Digests.constantTimeEquals(
                signature(secret, payload, timestamp.longValue()), presented);
    }

    public static boolean verify(String secret, String payload, String header, OffsetDateTime now) {
        return verify(secret, payload, header, now, DEFAULT_TOLERANCE);
    }

    private static Long parse(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
