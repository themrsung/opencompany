package com.coreintra.app.api.approval;

import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.paging.Cursors;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * How the 결재 and 근태 endpoints put values on the wire, and take them off it
 * again.
 *
 * <h2>Business instants are strings in the DTO, deliberately</h2>
 *
 * <p>{@code BusinessTimeJacksonModule} can serialise a {@link BusinessInstant}
 * directly, and for a response that is exactly what happens underneath
 * {@link #wire(BusinessInstant)}. Requests are different: a malformed instant
 * thrown from inside Jackson arrives at the handler wrapped in
 * {@code HttpMessageNotReadableException}, which nothing maps, so a trailing
 * {@code Z} would come back as a 500 with no explanation. Parsing in the
 * controller lets {@link com.coreintra.businesstime.BusinessInstantParseException}
 * reach {@code ApiExceptionHandler} intact, which answers 400 with
 * {@code invalid_business_instant} and the offending input attached — the
 * difference between "the server broke" and "your timestamp has a timezone on
 * it and must not".
 *
 * <p>It also makes the generated OpenAPI honest: the field is a string with a
 * documented pattern, rather than an object springdoc invented from the two
 * accessors on {@code BusinessInstant}.
 *
 * <h2>Amounts never become JSON numbers</h2>
 *
 * <p>An approval document's amount decides which threshold rule adds a 이사
 * step. A value that has been through a double on the way in is a different
 * value, and the line it produces is a different line (ADR 0004). So amounts
 * cross the wire as exact decimal strings in both directions, and
 * {@link #amount(String, String)} builds the {@link BigDecimal} from the string
 * it was given.
 *
 * <h2>Paging</h2>
 *
 * <p>{@link #page} slices a collection the services have already filtered row by
 * row through the evaluator. Paging in SQL would mean a repository query in the
 * API layer, which ArchUnit forbids, and which would have to re-implement the
 * filtering the evaluator just did — differently, and eventually wrongly.
 */
public final class ApiWire {

    /** Enough for one screen of an inbox or a who's-in board. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** {@code ?limit=1000000} is a denial of service, not a preference. */
    public static final int MAX_PAGE_SIZE = 200;

    /** Quoted in every {@code @Schema} that carries a business instant. */
    public static final String INSTANT_PATTERN = "YYYY-MM-DDT[-]HH:MM:SS.mmm";

    /** A shift that began at 18:00 and ended at 03:00 the next morning. */
    public static final String INSTANT_EXAMPLE = "2026-08-30T27:00:00.000";

    private ApiWire() {
    }

    /**
     * Parses a required business instant.
     *
     * @throws com.coreintra.businesstime.BusinessInstantParseException if the
     *         value carries a timezone, or is otherwise not the wire form. A
     *         {@code Z} is rejected rather than stripped: an instant that has
     *         been through a timezone is an instant about a different moment,
     *         and silently accepting one would put a shift on the wrong
     *         business day for the rest of its life.
     */
    public static BusinessInstant instant(String wire, String field) {
        if (Texts.isBlank(wire)) {
            throw new IllegalArgumentException("'" + field + "' is required, as "
                    + INSTANT_PATTERN + " with no timezone (for example " + INSTANT_EXAMPLE + ")");
        }
        return BusinessInstant.parse(Texts.strip(wire));
    }

    /** As {@link #instant}, but null when the caller did not supply one. */
    public static BusinessInstant optionalInstant(String wire) {
        return Texts.isBlank(wire) ? null : BusinessInstant.parse(Texts.strip(wire));
    }

    /**
     * The wire form of an instant, or null.
     *
     * <p>Never {@code toString()}: a shift that ended at 03:00 on the business
     * day it began comes back as {@code 27:00:00.000} on that day, and any
     * helpful normalisation to the following calendar date would silently move
     * the work to a day nobody worked it.
     */
    public static String wire(BusinessInstant instant) {
        return instant == null ? null : instant.toWireString();
    }

    /** UTC {@code created_at}, which is a separate fact from the business instant. */
    public static String utc(OffsetDateTime recordedAt) {
        return recordedAt == null ? null : recordedAt.toString();
    }

    /**
     * The business date a request is asking about, defaulting to today.
     *
     * <p>The fallback belongs here rather than inside a service. A service that
     * called {@code LocalDate.now()} for itself would answer a different
     * question at 02:00 than at 14:00 during the 72-hour business day, and
     * nobody reading the call site would know it had (ADR 0002).
     */
    public static LocalDate onDate(LocalDate requested) {
        return requested == null ? LocalDate.now() : requested;
    }

    /**
     * Builds an amount from the exact decimal string the caller sent.
     *
     * @param field named in the failure, because a form with two amounts on it
     *        cannot act on "invalid amount"
     */
    public static BigDecimal amount(String decimal, String field) {
        if (Texts.isBlank(decimal)) {
            return null;
        }
        try {
            return new BigDecimal(Texts.strip(decimal));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + field + "' must be an exact decimal string, "
                    + "for example \"5000000.00\"; got \"" + decimal + "\"");
        }
    }

    /** An amount as an exact decimal string, unrounded, or null. */
    public static String decimal(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    /** How a row is ordered and identified, so that the order is total. */
    public interface Keys<T> {

        /** The business key this collection is ordered by. Never null. */
        String sortKey(T row);

        /** The row id, which breaks ties between two equal sort keys. */
        String id(T row);
    }

    /**
     * Slices one cursor page out of a collection the service already authorised.
     *
     * @param rows   the whole collection, already permission-filtered
     * @param keys   how to order it
     * @param view   the row-to-DTO mapping, applied only to the rows returned
     * @param cursor the opaque cursor from the previous page, or null to start
     * @param limit  the requested page size, or null for {@link #DEFAULT_PAGE_SIZE}
     */
    public static <T, V> CursorPage<V> page(List<T> rows, final Keys<T> keys, Function<T, V> view,
            String cursor, Integer limit) {

        if (rows == null || rows.isEmpty()) {
            return CursorPage.empty();
        }
        List<T> sorted = new ArrayList<T>(rows);
        Collections.sort(sorted, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                int byKey = key(keys.sortKey(left)).compareTo(key(keys.sortKey(right)));
                return byKey != 0 ? byKey : key(keys.id(left)).compareTo(key(keys.id(right)));
            }
        });

        Cursors.Position from = Cursors.decode(cursor);
        int size = Cursors.pageSize(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        List<V> items = new ArrayList<V>();
        T last = null;
        boolean more = false;
        for (T row : sorted) {
            if (from != null && !isAfter(keys, row, from)) {
                continue;
            }
            if (items.size() == size) {
                // A cursor is only handed back when a row genuinely follows this
                // page. A null nextCursor is the sole end-of-collection signal
                // and must be a fact rather than a guess.
                more = true;
                break;
            }
            items.add(view.apply(row));
            last = row;
        }
        String next = more && last != null
                ? Cursors.encode(key(keys.sortKey(last)), keys.id(last))
                : null;
        return new CursorPage<V>(items, next);
    }

    /**
     * A sort key that reads newest first while the comparator still runs
     * ascending.
     *
     * <p>An outbox and a trail are read most-recent-first, but a cursor walk has
     * to move in one direction through a total order. Taking the complement of
     * the timestamp gives an ascending key whose natural order is descending
     * time, which keeps the cursor contract intact — rows inserted behind a walk
     * that has already passed them are not shown twice.
     */
    public static String newestFirstKey(OffsetDateTime recordedAt) {
        long millis = recordedAt == null ? 0L : recordedAt.toInstant().toEpochMilli();
        return pad(Long.MAX_VALUE - millis, 19);
    }

    /** Left-pads a number so that 9 sorts before 10 as text. */
    public static String number(long value) {
        return pad(value, 12);
    }

    private static String pad(long value, int width) {
        String text = Long.toString(Math.abs(value));
        StringBuilder padded = new StringBuilder(width + 1);
        // '-' sorts below every digit, so negatives land ahead of positives.
        padded.append(value < 0 ? '-' : '0');
        for (int i = text.length(); i < width; i++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }

    /**
     * A pipe would truncate the cursor when it is decoded, so it cannot survive
     * into a sort key. U+00A6 sorts after every ASCII character, which changes
     * the relative order only of keys that contain a pipe — and answering a 400
     * the client cannot act on would be worse.
     */
    private static String key(String value) {
        return value == null ? "" : value.replace('|', '¦');
    }

    private static <T> boolean isAfter(Keys<T> keys, T row, Cursors.Position from) {
        int byKey = key(keys.sortKey(row)).compareTo(key(from.sortKey()));
        if (byKey != 0) {
            return byKey > 0;
        }
        return key(keys.id(row)).compareTo(key(from.id())) > 0;
    }
}
