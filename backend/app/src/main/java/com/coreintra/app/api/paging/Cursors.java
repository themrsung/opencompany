package com.coreintra.app.api.paging;

import com.coreintra.businesstime.BusinessInstant;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Encodes and decodes the opaque page cursor.
 *
 * <h2>Why it is opaque</h2>
 *
 * <p>The cursor is base64 of {@code <sortKey>|<id>}, which is trivially
 * readable, and that is fine — it is not a secret and it carries nothing the
 * caller could not already see. It is <em>opaque by contract</em> so that the
 * sort key can change without breaking every client that had started parsing
 * it. The encoding exists to stop that habit forming, not to hide anything.
 *
 * <h2>Why the id is part of it</h2>
 *
 * <p>The sort key alone is not unique. Two approval actions stamped at the same
 * {@link BusinessInstant} — which happens constantly, because approvals are
 * stamped to the second and people click in batches — would make
 * {@code WHERE key < :cursor} either skip the second row or return the first
 * one again forever. Pairing the key with the row id gives a total order.
 *
 * <h2>Business time in a cursor</h2>
 *
 * <p>A business instant is encoded as {@code date:offsetSeconds}, in that order,
 * and compared date first. Encoding the wire string instead would sort
 * {@code -03:22} before {@code 26:01} lexically and page through the collection
 * in the wrong order — the exact bug the business ordering exists to prevent.
 */
public final class Cursors {

    private static final char SEPARATOR = '|';
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursors() {
    }

    /** A decoded cursor: where to resume from. */
    public static final class Position {
        private final String sortKey;
        private final String id;

        Position(String sortKey, String id) {
            this.sortKey = sortKey;
            this.id = id;
        }

        public String sortKey() {
            return sortKey;
        }

        public String id() {
            return id;
        }
    }

    public static String encode(String sortKey, String id) {
        if (sortKey == null || id == null) {
            throw new IllegalArgumentException("a cursor needs both a sort key and a row id");
        }
        if (sortKey.indexOf(SEPARATOR) >= 0) {
            // Splitting on the first separator would silently truncate the key.
            throw new IllegalArgumentException("sort key must not contain '" + SEPARATOR + "'");
        }
        return ENCODER.encodeToString((sortKey + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Sortable, date first then offset — never the wire string. */
    public static String sortKey(BusinessInstant instant) {
        // Offset is padded and biased so that -86400 through +172800 all sort as
        // positive fixed-width text. Without the bias, '-' sorts below every
        // digit and a pre-shift briefing would page ahead of the whole day.
        long biased = (long) instant.offsetSeconds() + 86_400L;
        return instant.businessDate().toString() + ':' + pad(biased);
    }

    public static String sortKey(LocalDate date) {
        return date.toString();
    }

    private static String pad(long value) {
        String text = Long.toString(value);
        StringBuilder padded = new StringBuilder(6);
        for (int i = text.length(); i < 6; i++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }

    /**
     * @param cursor the value the client sent, or null for the first page
     * @return null when there is no cursor
     * @throws IllegalArgumentException if the cursor is malformed, which is a
     *         400 rather than a silent restart from the beginning — quietly
     *         serving page 1 for a corrupt cursor makes an infinite loop look
     *         like a working client
     */
    public static Position decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is not a valid page cursor", e);
        }
        int separator = decoded.indexOf(SEPARATOR);
        if (separator <= 0 || separator == decoded.length() - 1) {
            throw new IllegalArgumentException("cursor is not a valid page cursor");
        }
        return new Position(decoded.substring(0, separator), decoded.substring(separator + 1));
    }

    /**
     * Clamps a requested page size.
     *
     * <p>A cap is not politeness: without one, {@code ?limit=1000000} is a
     * denial-of-service anybody with a valid session can perform by accident
     * from a shell script.
     */
    public static int pageSize(Integer requested, int defaultSize, int maxSize) {
        if (requested == null) {
            return defaultSize;
        }
        if (requested.intValue() < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(requested.intValue(), maxSize);
    }
}
