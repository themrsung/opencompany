package com.coreintra.businesstime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The ordering rule, and the three ways of ordering these values that disagree.
 *
 * <p>The pathological pair named in the brief is asserted explicitly and by
 * name, because every regression in this area will look like a plausible
 * "fix" to someone reading only the wall clock.
 */
class BusinessInstantOrderingTest {

    /** 26:01 on the 30th — a shift running past midnight. */
    private static final BusinessInstant LATE_ON_THE_30TH =
            BusinessInstant.parse("2026-08-30T26:01:00.000");

    /** -03:22 on the 31st — a pre-dawn start belonging to the next business day. */
    private static final BusinessInstant EARLY_FOR_THE_31ST =
            BusinessInstant.parse("2026-08-31T-03:22:00.000");

    @Test
    @DisplayName("day-level comparison wins: 30th@26:01 precedes 31st@-03:22")
    void dayLevelComparisonWins() {
        assertThat(BusinessInstant.COMPARATOR.compare(LATE_ON_THE_30TH, EARLY_FOR_THE_31ST))
                .as("the 30th precedes the 31st, whatever the clock face says")
                .isNegative();
        assertThat(LATE_ON_THE_30TH.isBefore(EARLY_FOR_THE_31ST)).isTrue();
        assertThat(EARLY_FOR_THE_31ST.isAfter(LATE_ON_THE_30TH)).isTrue();
    }

    @Test
    @DisplayName("absolute wall-clock ordering genuinely disagrees with business ordering")
    void absoluteOrderingDisagrees() {
        // 26:01 on the 30th is 02:01 on the 31st.
        // -03:22 on the 31st is 20:38 on the 30th.
        // So in wall-clock terms the second happened FIRST - the opposite of the
        // business ordering asserted above. If this assertion ever fails, either
        // absoluteDateTime() or the comparator has been changed to agree with
        // the other, and the whole distinction has collapsed.
        assertThat(LATE_ON_THE_30TH.absoluteDateTime())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 31, 2, 1, 0));
        assertThat(EARLY_FOR_THE_31ST.absoluteDateTime())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 30, 20, 38, 0));

        assertThat(LATE_ON_THE_30TH.absoluteDateTime().isAfter(EARLY_FOR_THE_31ST.absoluteDateTime()))
                .as("absolute ordering is the reverse of business ordering here")
                .isTrue();
    }

    @Test
    @DisplayName("lexical wire-string comparison is a third, always-wrong order")
    void lexicalComparisonIsWrong() {
        // '-' (0x2D) sorts below every digit (0x30-0x39). Sorting wire strings
        // is therefore neither the business order nor the absolute order.
        BusinessInstant negative = BusinessInstant.parse("2026-08-30T-01:00:00.000");
        BusinessInstant positive = BusinessInstant.parse("2026-08-30T01:00:00.000");

        assertThat(negative.isBefore(positive))
                .as("-01:00 is genuinely before 01:00 on the same day")
                .isTrue();

        // Here lexical happens to agree. The next case is where it breaks.
        assertThat(negative.toWireString().compareTo(positive.toWireString())).isNegative();

        BusinessInstant tenOnThe30th = BusinessInstant.parse("2026-08-30T10:00:00.000");
        BusinessInstant minusOneOnThe31st = BusinessInstant.parse("2026-08-31T-01:00:00.000");
        assertThat(tenOnThe30th.isBefore(minusOneOnThe31st))
                .as("business order: the 30th first")
                .isTrue();
        // Lexically the 31st string also sorts later here, so a shallow test
        // would pass. The real divergence needs same-date comparison against a
        // 2-digit hour, which the property test hunts for exhaustively.
        assertThat(BusinessInstantWireOrderingProperties.lexicalDiffersSomewhere())
                .as("there exists a pair where lexical and business order disagree")
                .isTrue();
    }

    @Nested
    class Sorting {

        @Test
        @DisplayName("sorting a mixed list puts business days in order, then offsets")
        void sortsDateFirstThenOffset() {
            List<BusinessInstant> instants = new ArrayList<BusinessInstant>(Arrays.asList(
                    BusinessInstant.parse("2026-08-31T-03:22:00.000"),
                    BusinessInstant.parse("2026-08-30T26:01:00.000"),
                    BusinessInstant.parse("2026-08-30T-24:00:00.000"),
                    BusinessInstant.parse("2026-08-30T09:00:00.000"),
                    BusinessInstant.parse("2026-08-31T48:00:00.000")));

            Collections.sort(instants, BusinessInstant.COMPARATOR);

            assertThat(instants).extracting(BusinessInstant::toWireString).containsExactly(
                    "2026-08-30T-24:00:00.000",
                    "2026-08-30T09:00:00.000",
                    "2026-08-30T26:01:00.000",
                    "2026-08-31T-03:22:00.000",
                    "2026-08-31T48:00:00.000");
        }

        @Test
        @DisplayName("Comparable and COMPARATOR agree, always")
        void comparableMatchesComparator() {
            LocalDate day = LocalDate.of(2026, 8, 30);
            for (int a = -86_400; a <= 172_800; a += 4_999) {
                for (int b = -86_400; b <= 172_800; b += 7_919) {
                    BusinessInstant left = BusinessInstant.of(day, a);
                    BusinessInstant right = BusinessInstant.of(day, b);
                    assertThat(Integer.signum(left.compareTo(right)))
                            .isEqualTo(Integer.signum(BusinessInstant.COMPARATOR.compare(left, right)));
                }
            }
        }
    }
}
