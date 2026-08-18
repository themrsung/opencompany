package com.coreintra.app.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.api.paging.CursorPage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The paging rules, without a database in the way.
 *
 * <p>The integration tests prove the endpoints page correctly; this proves the
 * arithmetic underneath does, including the cases a seeded fixture is unlikely
 * to contain — a negative sort key, ties on the sort key, and a walk over a
 * collection that changes shape between pages.
 */
class PagesTest {

    private static final Function<String, String> AS_IS = new Function<String, String>() {
        @Override
        public String apply(String row) {
            return row;
        }
    };

    /** Rows are "key/id" so a test reads as the thing it is asserting. */
    private static final Pages.Keys<String> KEYS = new Pages.Keys<String>() {
        @Override
        public String sortKey(String row) {
            return row.substring(0, row.indexOf('/'));
        }

        @Override
        public String id(String row) {
            return row.substring(row.indexOf('/') + 1);
        }
    };

    @Test
    @DisplayName("a numeric sort key compares the way the number does, negatives included")
    void numbersSortNumerically() {
        assertThat(Pages.number(9).compareTo(Pages.number(10)))
                .as("the string trap: '9' would otherwise sort after '10'")
                .isNegative();
        assertThat(Pages.number(-50).compareTo(Pages.number(-10)))
                .as("the sign trap: a minus sign sorts below every digit, which would put "
                        + "-10 before -50 and invert a ladder ordered by negated seniority")
                .isNegative();
        assertThat(Pages.number(-1).compareTo(Pages.number(0))).isNegative();
        assertThat(Pages.number(Integer.MIN_VALUE).compareTo(Pages.number(Integer.MAX_VALUE)))
                .isNegative();
    }

    @Test
    @DisplayName("the sort key preserves numeric order across the whole int range")
    void numberKeyIsOrderPreserving() {
        // Every pair drawn from the boundaries and a spread of ordinary values.
        // jqwik would express this better, but it is not a dependency of this
        // module and adding one is not this agent's to do; the interesting
        // cases here are the boundaries, and they are all enumerated.
        int[] values = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1000000, -100, -10, -1, 0, 1,
                9, 10, 50, 100, 1000000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int left : values) {
            for (int right : values) {
                assertThat(Integer.signum(Pages.number(left).compareTo(Pages.number(right))))
                        .as("%d vs %d", Integer.valueOf(left), Integer.valueOf(right))
                        .isEqualTo(Integer.signum(Integer.compare(left, right)));
            }
        }
    }

    @Test
    @DisplayName("a walk over an unchanging collection returns every row exactly once")
    void walkIsComplete() {
        List<String> rows = rows("b/2", "a/1", "d/4", "c/3", "e/5");

        List<String> seen = walk(rows, 2);

        assertThat(seen).containsExactly("a/1", "b/2", "c/3", "d/4", "e/5");
    }

    @Test
    @DisplayName("rows sharing a sort key are still returned exactly once")
    void tiesDoNotRepeatOrVanish() {
        // Two ranks at the same seniority, two people hired the same day. The
        // row id is what makes the order total; without it the cursor either
        // skips the second row or returns the first for ever.
        List<String> rows = rows("a/1", "a/2", "a/3", "b/4");

        List<String> seen = walk(rows, 1);

        assertThat(seen).containsExactly("a/1", "a/2", "a/3", "b/4");
    }

    @Test
    @DisplayName("a row inserted ahead of the cursor is seen; one behind it is not")
    void insertionMidWalk() {
        List<String> rows = rows("a/1", "b/2", "c/3");
        Set<String> seen = new HashSet<String>();

        CursorPage<String> first = Pages.page(rows, KEYS, AS_IS, null, Integer.valueOf(2));
        seen.addAll(first.getItems());

        rows.add("a0/9");
        rows.add("z/8");

        CursorPage<String> second =
                Pages.page(rows, KEYS, AS_IS, first.getNextCursor(), Integer.valueOf(10));
        seen.addAll(second.getItems());

        assertThat(seen).contains("a/1", "b/2", "c/3", "z/8");
        assertThat(seen)
                .as("the walk has already passed this position; re-serving it would duplicate")
                .doesNotContain("a0/9");
        assertThat(second.getNextCursor())
                .as("nothing is left, so the end signal is null and not a short page")
                .isNull();
    }

    @Test
    @DisplayName("an empty collection is a last page, not a cursor to nowhere")
    void emptyCollection() {
        CursorPage<String> page =
                Pages.page(new ArrayList<String>(), KEYS, AS_IS, null, Integer.valueOf(10));

        assertThat(page.getItems()).isEmpty();
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("a requested page size beyond the cap is clamped, and zero is refused")
    void pageSizeIsBounded() {
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < Pages.MAX_PAGE_SIZE + 10; i++) {
            rows.add(Pages.number(i) + "/" + i);
        }

        assertThat(Pages.page(rows, KEYS, AS_IS, null, Integer.valueOf(100000)).getItems())
                .hasSize(Pages.MAX_PAGE_SIZE);

        final List<String> fixed = rows;
        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                Pages.page(fixed, KEYS, AS_IS, null, Integer.valueOf(0));
            }
        })
                .as("a page of nothing would loop for ever rather than fail")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sort key containing the cursor separator does not truncate the cursor")
    void separatorInAKeyIsSurvivable() {
        // A 직무 called "영업|해외" is unlikely but not impossible, and the cursor
        // encoder rejects a pipe outright, so the key has to be neutralised
        // before it gets there rather than producing a 400 nobody can act on.
        List<String> rows = rows("a|b/1", "c/2");

        CursorPage<String> page = Pages.page(rows, KEYS, AS_IS, null, Integer.valueOf(1));

        assertThat(page.getItems()).containsExactly("a|b/1");
        assertThat(page.getNextCursor()).isNotNull();
        assertThat(Pages.page(rows, KEYS, AS_IS, page.getNextCursor(), Integer.valueOf(10))
                .getItems())
                .containsExactly("c/2");
    }

    private static List<String> rows(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }

    private static List<String> walk(List<String> rows, int pageSize) {
        List<String> seen = new ArrayList<String>();
        String cursor = null;
        int guard = 0;
        do {
            CursorPage<String> page =
                    Pages.page(rows, KEYS, AS_IS, cursor, Integer.valueOf(pageSize));
            seen.addAll(page.getItems());
            cursor = page.getNextCursor();
            guard++;
        } while (cursor != null && guard < 100);
        return seen;
    }
}
