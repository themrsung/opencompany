package com.coreintra.app.api.org;

import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.paging.Cursors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Cursor pages over a list the domain services already authorised.
 *
 * <p>The org services answer with whole collections that have been filtered
 * row by row by the permission evaluator — {@code list}, {@code tree},
 * {@code history}. Paging therefore happens here rather than in SQL, and that
 * is a deliberate trade: the alternative is a repository query in the API
 * layer, which ArchUnit forbids and which would have to re-implement the
 * filtering the evaluator just did, differently, and eventually wrongly.
 *
 * <p>The page is still a <b>cursor</b> page and not an offset one. §10 rules
 * offset pagination out everywhere, and the reason survives the in-memory
 * implementation: a row created between two requests shifts every offset by
 * one, so a client walking pages skips a row it was entitled to see. A cursor
 * anchored to the sort key cannot do that — rows inserted before the cursor are
 * simply not shown to a walk that has already passed them, and rows after it
 * appear exactly once.
 *
 * <h2>Sort keys</h2>
 *
 * <p>Every collection is ordered by a business key (code, employee number,
 * materialised path, seniority) with the row id as the tiebreaker, so the order
 * is total and stable: two ranks with the same seniority still page correctly.
 *
 * @see Cursors
 */
public final class Pages {

    /** Enough for a table view; a client that wants more must page. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** {@code ?limit=1000000} is a denial of service, not a preference. */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * A separator inside a sort key would truncate the cursor, so it cannot
     * survive into one. U+00A6 sorts after every ASCII character, which changes
     * the relative order only of keys that contain a pipe — a case this schema
     * does not produce, and one that would otherwise be a 400 the client cannot
     * act on.
     */
    private static final char PIPE = '|';
    private static final char PIPE_SUBSTITUTE = '¦';

    private Pages() {
    }

    /** How a row is ordered and identified. */
    public interface Keys<T> {

        /** The business key this collection is ordered by. Never null. */
        String sortKey(T row);

        /** The row id, which makes the order total. */
        String id(T row);
    }

    /**
     * Slices one page out of an authorised collection.
     *
     * @param rows the whole collection, already permission-filtered
     * @param keys how to order it
     * @param view the row-to-DTO mapping, applied only to the rows returned
     * @param cursor the opaque cursor from the previous page, or null to start
     * @param limit the requested page size, or null for the default
     */
    public static <T, V> CursorPage<V> page(List<T> rows, Keys<T> keys, Function<T, V> view,
            String cursor, Integer limit) {
        if (rows == null || rows.isEmpty()) {
            return CursorPage.empty();
        }
        List<T> sorted = new ArrayList<T>(rows);
        Collections.sort(sorted, order(keys));

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
                // There is at least one row beyond this page, which is the only
                // thing that justifies handing back a cursor: a null nextCursor
                // is the end-of-collection signal and must not be a guess.
                more = true;
                break;
            }
            items.add(view.apply(row));
            last = row;
        }
        String next = more && last != null
                ? Cursors.encode(safeKey(keys.sortKey(last)), keys.id(last))
                : null;
        return new CursorPage<V>(items, next);
    }

    /** Left-pads a number so that 9 sorts before 10 as a string. */
    public static String number(int value) {
        StringBuilder padded = new StringBuilder();
        String text = Integer.toString(Math.abs(value));
        for (int i = text.length(); i < 9; i++) {
            padded.append('0');
        }
        // '-' sorts below every digit, so negatives land before positives -
        // the same trick Cursors uses for a pre-shift briefing.
        return (value < 0 ? "-" : "0") + padded.append(text).toString();
    }

    private static <T> Comparator<T> order(final Keys<T> keys) {
        return new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                int byKey = safeKey(keys.sortKey(left)).compareTo(safeKey(keys.sortKey(right)));
                return byKey != 0 ? byKey : safeKey(keys.id(left)).compareTo(safeKey(keys.id(right)));
            }
        };
    }

    private static <T> boolean isAfter(Keys<T> keys, T row, Cursors.Position from) {
        int byKey = safeKey(keys.sortKey(row)).compareTo(safeKey(from.sortKey()));
        if (byKey != 0) {
            return byKey > 0;
        }
        return safeKey(keys.id(row)).compareTo(safeKey(from.id())) > 0;
    }

    private static String safeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf(PIPE) < 0 ? value : value.replace(PIPE, PIPE_SUBSTITUTE);
    }
}
