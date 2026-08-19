package com.coreintra.app.api.documents;

import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.paging.Cursors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Cursor pages over a collection {@link DocumentAccess} has already filtered.
 *
 * <p>Paging happens here rather than in SQL because the rows were filtered one
 * at a time by the permission evaluator, and a {@code LIMIT} applied before that
 * filter would return a page that is mostly holes. The trade is deliberate and
 * is the same one {@code api/org/Pages} makes for the org chart; the two are
 * near-identical and should collapse into {@code api/paging} once one owner has
 * both files.
 *
 * <p>Offset paging is still not on offer. §10 rules it out everywhere, and the
 * reason survives the in-memory implementation: a document created between two
 * requests shifts every offset by one, so a walk skips a row it was entitled to
 * see and nothing tells the client.
 */
public final class DocumentPages {

    /** Enough for a table view. A client that wants more must page. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** {@code ?limit=1000000} is a denial of service, not a preference. */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * A pipe would truncate the cursor, so it cannot survive into one. U+00A6
     * sorts after every ASCII character, which changes the relative order only
     * of keys that contain a pipe — and a 400 on a document whose title happens
     * to contain one would be a failure the user cannot act on.
     */
    private static final char PIPE = '|';
    private static final char PIPE_SUBSTITUTE = '¦';

    private DocumentPages() {
    }

    /** How a row is ordered, and what makes that order total. */
    public interface Keys<T> {

        /** The business key the collection is ordered by. Never null. */
        String sortKey(T row);

        /** The row id, which breaks ties so the walk cannot loop or skip. */
        String id(T row);
    }

    public static <T, V> CursorPage<V> page(List<T> rows, Keys<T> keys, Function<T, V> view,
            String cursor, Integer limit) {
        // Decoded before the empty check, deliberately. Answering an empty page
        // for a corrupt cursor would make a client looping on a broken cursor
        // look like a client that had reached the end, and the collection being
        // empty today is not a reason to accept a cursor that can never work.
        Cursors.Position from = Cursors.decode(cursor);
        int size = Cursors.pageSize(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        if (rows == null || rows.isEmpty()) {
            return CursorPage.empty();
        }
        List<T> sorted = new ArrayList<T>(rows);
        Collections.sort(sorted, order(keys));

        List<V> items = new ArrayList<V>();
        T last = null;
        boolean more = false;
        for (T row : sorted) {
            if (from != null && !isAfter(keys, row, from)) {
                continue;
            }
            if (items.size() == size) {
                // A cursor is handed back only when a row beyond this page
                // exists. The null cursor is the end-of-collection signal and
                // must never be a guess.
                more = true;
                break;
            }
            items.add(view.apply(row));
            last = row;
        }
        String next = more && last != null
                ? Cursors.encode(safe(keys.sortKey(last)), keys.id(last))
                : null;
        return new CursorPage<V>(items, next);
    }

    /** Left-pads so that 9 sorts before 10 as text. */
    public static String number(int value) {
        StringBuilder padded = new StringBuilder();
        String text = Integer.toString(Math.abs(value));
        for (int i = text.length(); i < 9; i++) {
            padded.append('0');
        }
        return (value < 0 ? "-" : "0") + padded.append(text).toString();
    }

    private static <T> Comparator<T> order(final Keys<T> keys) {
        return new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                int byKey = safe(keys.sortKey(left)).compareTo(safe(keys.sortKey(right)));
                return byKey != 0
                        ? byKey
                        : safe(keys.id(left)).compareTo(safe(keys.id(right)));
            }
        };
    }

    private static <T> boolean isAfter(Keys<T> keys, T row, Cursors.Position from) {
        int byKey = safe(keys.sortKey(row)).compareTo(safe(from.sortKey()));
        if (byKey != 0) {
            return byKey > 0;
        }
        return safe(keys.id(row)).compareTo(safe(from.id())) > 0;
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf(PIPE) < 0 ? value : value.replace(PIPE, PIPE_SUBSTITUTE);
    }
}
