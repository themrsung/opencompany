package com.coreintra.businesstime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessInstantFormatTest {

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        void ordinaryInDayTime() {
            BusinessInstant instant = BusinessInstant.parse("2026-08-30T09:30:00.000");
            assertThat(instant.businessDate()).isEqualTo(LocalDate.of(2026, 8, 30));
            assertThat(instant.offsetSeconds()).isEqualTo(9 * 3600 + 30 * 60);
            assertThat(instant.isOutsideCalendarDay()).isFalse();
        }

        @Test
        @DisplayName("a shift-end past midnight, stamped on the day it belongs to")
        void pastMidnight() {
            BusinessInstant instant = BusinessInstant.parse("2026-08-30T27:00:00.000");
            assertThat(instant.offsetSeconds()).isEqualTo(27 * 3600);
            assertThat(instant.isOutsideCalendarDay()).isTrue();
            assertThat(instant.absoluteDateTime())
                    .isEqualTo(java.time.LocalDateTime.of(2026, 8, 31, 3, 0));
        }

        @Test
        @DisplayName("a pre-shift briefing the evening before")
        void beforeTheDayOpened() {
            BusinessInstant instant = BusinessInstant.parse("2026-08-31T-02:00:00.000");
            assertThat(instant.offsetSeconds()).isEqualTo(-2 * 3600);
            assertThat(instant.absoluteDateTime())
                    .isEqualTo(java.time.LocalDateTime.of(2026, 8, 30, 22, 0));
        }

        @Test
        void bothBoundsInclusive() {
            assertThat(BusinessInstant.parse("2026-08-30T-24:00:00.000").offsetSeconds())
                    .isEqualTo(BusinessInstant.MIN_OFFSET_SECONDS);
            assertThat(BusinessInstant.parse("2026-08-30T48:00:00.000").offsetSeconds())
                    .isEqualTo(BusinessInstant.MAX_OFFSET_SECONDS);
        }

        @Test
        @DisplayName("negative zero is accepted and canonicalised away")
        void negativeZeroCanonicalises() {
            BusinessInstant instant = BusinessInstant.parse("2026-08-30T-00:00:00.000");
            assertThat(instant.offsetSeconds()).isZero();
            assertThat(instant.toWireString()).isEqualTo("2026-08-30T00:00:00.000");
        }

        @Test
        void surroundingWhitespace() {
            assertThat(BusinessInstant.parse("  2026-08-30T09:30:00.000  ").offsetSeconds())
                    .isEqualTo(9 * 3600 + 30 * 60);
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-08-30T09:30:00.000Z",
                "2026-08-30T09:30:00.000z",
                "2026-08-30T09:30:00.000+09:00",
                "2026-08-30T09:30:00.000+0900",
                "2026-08-30T09:30:00.000 UTC",
        })
        @DisplayName("any timezone designator, by name rather than as a shape mismatch")
        void timezoneDesignators(String input) {
            assertThatThrownBy(() -> BusinessInstant.parse(input))
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining("no timezone")
                    .hasMessageContaining("must not be conflated");
        }

        @Test
        @DisplayName("non-zero milliseconds, rather than silently truncating them")
        void nonZeroMillis() {
            assertThatThrownBy(() -> BusinessInstant.parse("2026-08-30T09:30:00.500"))
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining("second-granularity")
                    .hasMessageContaining("discard precision");
        }

        @Test
        void offsetsOutsideTheWindow() {
            assertThatThrownBy(() -> BusinessInstant.parse("2026-08-30T48:00:01.000"))
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining("72-hour window");
            assertThatThrownBy(() -> BusinessInstant.parse("2026-08-30T-24:00:01.000"))
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining("72-hour window");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-08-30T09:30:00",       // no millis
                "2026-08-30T09:30:00.00",    // two millis digits
                "2026-08-30 09:30:00.000",   // space instead of T
                "2026-08-30T+09:30:00.000",  // explicit plus is not in the grammar
                "2026-8-30T09:30:00.000",    // unpadded month
                "2026-08-30T9:30:00.000",    // unpadded hour
                "",
                "not a date",
        })
        void malformedShapes(String input) {
            assertThatThrownBy(() -> BusinessInstant.parse(input))
                    .isInstanceOf(BusinessInstantParseException.class);
        }

        @Test
        @DisplayName("a date that does not exist, rather than rolling it over")
        void impossibleDate() {
            assertThatThrownBy(() -> BusinessInstant.parse("2026-02-30T09:00:00.000"))
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining("not a real calendar date");
        }

        @Test
        void outOfRangeMinutesAndSeconds() {
            assertThatThrownBy(() -> BusinessInstant.parse("2026-08-30T09:60:00.000"))
                    .hasMessageContaining("minute field");
            assertThatThrownBy(() -> BusinessInstant.parse("2026-08-30T09:00:60.000"))
                    .hasMessageContaining("second field");
        }

        @Test
        @DisplayName("null, with the input echoed back")
        void nullInput() {
            assertThatThrownBy(() -> BusinessInstant.parse(null))
                    .isInstanceOf(BusinessInstantParseException.class);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        @DisplayName("the business date never rolls over on its own")
        void neverRollsOver() {
            BusinessInstant late = BusinessInstant.parse("2026-08-30T23:00:00.000");
            assertThat(late.plusHours(3).toWireString()).isEqualTo("2026-08-30T26:00:00.000");
            assertThat(late.plusHours(3).businessDate()).isEqualTo(LocalDate.of(2026, 8, 30));
        }

        @Test
        @DisplayName("leaving the window is an error, not a silent day change")
        void leavingTheWindowThrows() {
            BusinessInstant edge = BusinessInstant.parse("2026-08-30T48:00:00.000");
            assertThatThrownBy(() -> edge.plusSeconds(1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("+48:00:00");
        }
    }
}
