package com.coreintra.app.api.paging;

import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * One page of a collection, addressed by cursor rather than by offset.
 *
 * <p>Offset pagination is not offered anywhere in this API. Under concurrent
 * writes it both skips and duplicates rows: insert one document while someone
 * is reading page 2 and every subsequent row shifts by one, so a row that moved
 * from position 40 to 41 is never returned at all. On an approval inbox that is
 * a document nobody ever sees, and it fails silently — the client has no way to
 * notice. A cursor anchored to the sort key cannot do that.
 *
 * <p>{@link #getNextCursor()} being null is the only end-of-collection signal.
 * Clients must not infer the end from a short page: a page can legitimately come
 * back short when rows were filtered out by a permission check after they were
 * read.
 *
 * @param <T> the row type, which is a response DTO and never an entity
 */
public class CursorPage<T> {

    private final List<T> items;
    private final String nextCursor;

    public CursorPage(List<T> items, String nextCursor) {
        this.items = items == null ? Immutables.<T>listOf() : Immutables.copyOf(items);
        this.nextCursor = nextCursor;
    }

    /** An empty last page. */
    public static <T> CursorPage<T> empty() {
        return new CursorPage<T>(Immutables.<T>listOf(), null);
    }

    public List<T> getItems() {
        return items;
    }

    /** Opaque. Pass back as {@code ?cursor=}. Null means this was the last page. */
    public String getNextCursor() {
        return nextCursor;
    }
}
