package com.coreintra.businesstime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based cover for the ordering and wire-format invariants.
 *
 * <p>The example-based tests pin the cases we already know break. These
 * properties hunt for the ones we do not.
 */
class BusinessInstantWireOrderingProperties {

    @Provide
    Arbitrary<BusinessInstant> instants() {
        Arbitrary<LocalDate> dates = Arbitraries.integers()
                .between(-3_650, 3_650)
                .map(new java.util.function.Function<Integer, LocalDate>() {
                    @Override
                    public LocalDate apply(Integer days) {
                        return LocalDate.of(2026, 1, 1).plusDays(days.longValue());
                    }
                });
        Arbitrary<Integer> offsets = Arbitraries.integers()
                .between(BusinessInstant.MIN_OFFSET_SECONDS, BusinessInstant.MAX_OFFSET_SECONDS);
        return Combinators.combine(dates, offsets).as(
                new Combinators.F2<LocalDate, Integer, BusinessInstant>() {
                    @Override
                    public BusinessInstant apply(LocalDate date, Integer offset) {
                        return BusinessInstant.of(date, offset.intValue());
                    }
                });
    }

    @Property(tries = 2000)
    void wireFormRoundTripsExactly(@ForAll("instants") BusinessInstant instant) {
        assertThat(BusinessInstant.parse(instant.toWireString())).isEqualTo(instant);
    }

    @Property(tries = 2000)
    void formattingIsCanonicalAndIdempotent(@ForAll("instants") BusinessInstant instant) {
        String once = instant.toWireString();
        String twice = BusinessInstant.parse(once).toWireString();
        assertThat(twice).isEqualTo(once);
    }

    @Property(tries = 2000)
    void comparatorIsAntisymmetric(@ForAll("instants") BusinessInstant a, @ForAll("instants") BusinessInstant b) {
        int forward = Integer.signum(BusinessInstant.COMPARATOR.compare(a, b));
        int backward = Integer.signum(BusinessInstant.COMPARATOR.compare(b, a));
        assertThat(forward).isEqualTo(-backward);
    }

    @Property(tries = 1000)
    void comparatorIsTransitive(
            @ForAll("instants") BusinessInstant a,
            @ForAll("instants") BusinessInstant b,
            @ForAll("instants") BusinessInstant c) {
        List<BusinessInstant> sorted = new ArrayList<BusinessInstant>(java.util.Arrays.asList(a, b, c));
        Collections.sort(sorted, BusinessInstant.COMPARATOR);
        assertThat(BusinessInstant.COMPARATOR.compare(sorted.get(0), sorted.get(1))).isLessThanOrEqualTo(0);
        assertThat(BusinessInstant.COMPARATOR.compare(sorted.get(1), sorted.get(2))).isLessThanOrEqualTo(0);
        assertThat(BusinessInstant.COMPARATOR.compare(sorted.get(0), sorted.get(2))).isLessThanOrEqualTo(0);
    }

    @Property(tries = 2000)
    void comparingEqualIsConsistentWithEquals(
            @ForAll("instants") BusinessInstant a, @ForAll("instants") BusinessInstant b) {
        boolean comparesEqual = BusinessInstant.COMPARATOR.compare(a, b) == 0;
        assertThat(comparesEqual).isEqualTo(a.equals(b));
    }

    @Property(tries = 2000)
    void businessOrderIgnoresTheWallClock(
            @ForAll("instants") BusinessInstant a, @ForAll("instants") BusinessInstant b) {
        // Whenever the business dates differ, business order follows the dates
        // regardless of what absoluteDateTime() says. This is the invariant the
        // whole 72-hour model rests on.
        if (!a.businessDate().equals(b.businessDate())) {
            int byDate = a.businessDate().compareTo(b.businessDate());
            assertThat(Integer.signum(BusinessInstant.COMPARATOR.compare(a, b)))
                    .isEqualTo(Integer.signum(byDate));
        }
    }

    @Property(tries = 500)
    void offsetsOutsideTheWindowAreRejected(@ForAll @IntRange(min = 1, max = 500_000) int excess) {
        LocalDate day = LocalDate.of(2026, 8, 30);
        try {
            BusinessInstant.of(day, BusinessInstant.MAX_OFFSET_SECONDS + excess);
            throw new AssertionError("accepted an offset beyond +48:00:00");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("+48:00:00");
        }
        try {
            BusinessInstant.of(day, BusinessInstant.MIN_OFFSET_SECONDS - excess);
            throw new AssertionError("accepted an offset before -24:00:00");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("-24:00:00");
        }
    }

    /**
     * Searches for a concrete pair on which lexical wire-string ordering
     * disagrees with the business ordering.
     *
     * <p>Used by {@link BusinessInstantOrderingTest} so that the "never compare
     * wire strings" rule is backed by a demonstrated counterexample rather than
     * by assertion. If this ever returns false, the wire format has changed
     * shape and the review rule needs revisiting, not deleting.
     */
    static boolean lexicalDiffersSomewhere() {
        LocalDate day = LocalDate.of(2026, 8, 30);
        for (int a = BusinessInstant.MIN_OFFSET_SECONDS; a <= BusinessInstant.MAX_OFFSET_SECONDS; a += 137) {
            for (int b = a + 1; b <= BusinessInstant.MAX_OFFSET_SECONDS; b += 4_099) {
                BusinessInstant left = BusinessInstant.of(day, a);
                BusinessInstant right = BusinessInstant.of(day, b);
                int business = Integer.signum(BusinessInstant.COMPARATOR.compare(left, right));
                int lexical = Integer.signum(left.toWireString().compareTo(right.toWireString()));
                if (business != lexical) {
                    return true;
                }
            }
        }
        return false;
    }
}
