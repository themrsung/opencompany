package com.coreintra.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.businesstime.BusinessInstant;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LeaveAndAttendanceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(DAY, hour, 0, 0);
    }

    @Nested
    @DisplayName("attendance intervals")
    class Intervals {

        @Test
        @DisplayName("a shift crossing midnight stays on one business day")
        void midnightCrossingStaysOnOneDay() {
            // 22:00 to 03:00 is one interval on the 30th: 22:00 to 27:00.
            AttendanceInterval nightShift = AttendanceInterval.closed(
                    BusinessInstant.parse("2026-08-30T22:00:00.000"),
                    BusinessInstant.parse("2026-08-30T27:00:00.000"));

            assertThat(nightShift.businessDate()).isEqualTo(DAY);
            assertThat(nightShift.durationSeconds()).isEqualTo(5 * 3600L);
            assertThat(nightShift.endedAt().absoluteDateTime())
                    .as("the wall clock still says 03:00 the next morning")
                    .isEqualTo(java.time.LocalDateTime.of(2026, 8, 31, 3, 0));
        }

        @Test
        @DisplayName("an interval spanning two business dates is refused, with the fix named")
        void twoBusinessDatesRefused() {
            assertThatThrownBy(() -> AttendanceInterval.closed(
                    BusinessInstant.parse("2026-08-30T22:00:00.000"),
                    BusinessInstant.parse("2026-08-31T03:00:00.000")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("22:00:00 to 27:00:00");
        }

        @Test
        @DisplayName("a pre-shift briefing before the day opens is an ordinary interval")
        void negativeOffsetInterval() {
            AttendanceInterval briefing = AttendanceInterval.closed(
                    BusinessInstant.parse("2026-08-30T-02:00:00.000"),
                    BusinessInstant.parse("2026-08-30T-01:00:00.000"));
            assertThat(briefing.durationSeconds()).isEqualTo(3600L);
        }

        @Test
        @DisplayName("touching intervals do not overlap — a handover is not a clash")
        void touchingIsNotOverlapping() {
            AttendanceInterval morning = AttendanceInterval.closed(at(9), at(18));
            AttendanceInterval evening = AttendanceInterval.closed(at(18), at(22));
            assertThat(morning.overlaps(evening)).isFalse();
            assertThat(evening.overlaps(morning)).isFalse();
        }

        @Test
        @DisplayName("genuinely overlapping intervals are detected both ways")
        void overlapIsSymmetric() {
            AttendanceInterval working = AttendanceInterval.closed(at(9), at(18));
            AttendanceInterval away = AttendanceInterval.closed(at(12), at(13));
            assertThat(working.overlaps(away)).isTrue();
            assertThat(away.overlaps(working)).isTrue();
        }

        @Test
        @DisplayName("a forgotten clock-out still conflicts with a later status")
        void openIntervalExtendsToEndOfDay() {
            AttendanceInterval forgotten = AttendanceInterval.open(at(9));
            AttendanceInterval later = AttendanceInterval.closed(at(14), at(15));
            assertThat(forgotten.overlaps(later))
                    .as("an open interval must not silently coexist with everything after it")
                    .isTrue();
            assertThat(forgotten.durationSeconds()).isEqualTo(-1L);
        }

        @Test
        void endBeforeStartRefused() {
            assertThatThrownBy(() -> AttendanceInterval.closed(at(18), at(9)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot end");
        }
    }

    @Nested
    @DisplayName("status behaviour")
    class Behaviour {

        @Test
        @DisplayName("a status that spends leave must also require approval")
        void deductingImpliesApproval() {
            assertThatThrownBy(() -> StatusBehaviour.builder()
                    .deductsLeaveBalance(true)
                    .requiresApproval(false)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nobody having agreed to it");
        }

        @Test
        @DisplayName("자리비움 expires automatically so the board does not go stale")
        void awayExpires() {
            StatusBehaviour away = StatusBehaviour.builder()
                    .countsAsWorking(false)
                    .autoExpiresAfter(Duration.ofHours(2))
                    .build();
            assertThat(away.expiresAutomatically()).isTrue();
            assertThat(away.autoExpiresAfter()).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("재택 and 외근 count as working; 자리비움 does not")
        void workingFlags() {
            assertThat(StatusBehaviour.builder().countsAsWorking(true).build().countsAsWorking())
                    .isTrue();
            assertThat(StatusBehaviour.builder().countsAsWorking(false).build().countsAsWorking())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the leave ledger")
    class Ledger {

        private LeaveLedger.Transaction grant(String id, String days, int day) {
            return new LeaveLedger.Transaction(id, LeaveLedger.TransactionKind.GRANT,
                    new BigDecimal(days),
                    BusinessInstant.of(LocalDate.of(2026, 1, day), 9, 0, 0),
                    null, LocalDate.of(2026, 12, 31), null, null, "system");
        }

        private LeaveLedger.Transaction use(String id, String days, int month, int day) {
            return new LeaveLedger.Transaction(id, LeaveLedger.TransactionKind.USE,
                    new BigDecimal(days),
                    BusinessInstant.of(LocalDate.of(2026, month, day), 9, 0, 0),
                    null, null, null, "doc-1", "acc-1");
        }

        @Test
        @DisplayName("the balance is the sum of the rows, computed not stored")
        void balanceIsDerived() {
            List<LeaveLedger.Transaction> rows = new ArrayList<LeaveLedger.Transaction>();
            rows.add(grant("t1", "15", 1));
            rows.add(use("t2", "1.5", 3, 10));
            rows.add(use("t3", "0.5", 6, 20));
            LeaveLedger ledger = new LeaveLedger("emp-1", rows);

            assertThat(ledger.balanceOn(LocalDate.of(2026, 12, 31)))
                    .isEqualByComparingTo(new BigDecimal("13.0"));
        }

        @Test
        @DisplayName("half and quarter days are exact, with no binary drift")
        void fractionalDaysAreExact() {
            List<LeaveLedger.Transaction> rows = new ArrayList<LeaveLedger.Transaction>();
            rows.add(grant("t1", "1", 1));
            // 0.1 is not representable in binary floating point. Four quarter-days
            // must come to exactly one day, not 0.9999999999999999.
            for (int i = 0; i < 4; i++) {
                rows.add(use("u" + i, "0.25", 2, i + 1));
            }
            LeaveLedger ledger = new LeaveLedger("emp-1", rows);
            assertThat(ledger.balanceOn(LocalDate.of(2026, 12, 31)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a grant that is not yet effective does not count toward the balance")
        void futureGrantExcluded() {
            List<LeaveLedger.Transaction> rows = new ArrayList<LeaveLedger.Transaction>();
            rows.add(new LeaveLedger.Transaction("t1", LeaveLedger.TransactionKind.GRANT,
                    new BigDecimal("15"), BusinessInstant.of(LocalDate.of(2026, 1, 1), 9, 0, 0),
                    LocalDate.of(2027, 1, 1), null, null, null, "system"));
            LeaveLedger ledger = new LeaveLedger("emp-1", rows);

            assertThat(ledger.balanceOn(LocalDate.of(2026, 6, 1))).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledger.balanceOn(LocalDate.of(2027, 1, 1)))
                    .isEqualByComparingTo(new BigDecimal("15"));
        }

        @Test
        @DisplayName("days are always a positive magnitude; direction comes from the kind")
        void signComesFromTheKind() {
            assertThatThrownBy(() -> new LeaveLedger.Transaction("x",
                    LeaveLedger.TransactionKind.USE, new BigDecimal("-1"),
                    BusinessInstant.of(DAY, 9, 0, 0), null, null, null, null, "acc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("direction comes from the kind");
        }

        @Test
        @DisplayName("a manual adjustment without a reason is refused")
        void adjustmentNeedsReason() {
            assertThatThrownBy(() -> new LeaveLedger.Transaction("x",
                    LeaveLedger.TransactionKind.ADJUSTMENT, BigDecimal.ONE,
                    BusinessInstant.of(DAY, 9, 0, 0), null, null, "  ", null, "acc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("indistinguishable from a bug");
        }

        @Test
        void affordabilityAndUsage() {
            List<LeaveLedger.Transaction> rows = new ArrayList<LeaveLedger.Transaction>();
            rows.add(grant("t1", "15", 1));
            rows.add(use("t2", "3", 3, 10));
            LeaveLedger ledger = new LeaveLedger("emp-1", rows);

            assertThat(ledger.canAfford(new BigDecimal("12"), LocalDate.of(2026, 12, 31))).isTrue();
            assertThat(ledger.canAfford(new BigDecimal("12.5"), LocalDate.of(2026, 12, 31))).isFalse();
            assertThat(ledger.usedBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                    .isEqualByComparingTo(new BigDecimal("3"));
        }
    }

    @Nested
    @DisplayName("accrual policy (configuration, not statute in code)")
    class Accrual {

        private final LeaveAccrualPolicy korean =
                LeaveAccrualPolicy.koreanAnnualLeaveSeed("seed-ko");

        @Test
        @DisplayName("first year accrues one day per completed month")
        void firstYearMonthlyAccrual() {
            LocalDate hired = LocalDate.of(2026, 1, 1);
            assertThat(korean.entitlementFor(hired, LocalDate.of(2026, 1, 31)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(korean.entitlementFor(hired, LocalDate.of(2026, 4, 1)))
                    .isEqualByComparingTo(new BigDecimal("3"));
            assertThat(korean.entitlementFor(hired, LocalDate.of(2026, 12, 1)))
                    .isEqualByComparingTo(new BigDecimal("11"));
        }

        @Test
        @DisplayName("from the second year the annual grant applies")
        void annualGrantFromSecondYear() {
            LocalDate hired = LocalDate.of(2026, 1, 1);
            assertThat(korean.entitlementFor(hired, LocalDate.of(2027, 1, 1)))
                    .isEqualByComparingTo(new BigDecimal("15"));
        }

        @Test
        @DisplayName("tenure increments stack and are capped")
        void tenureIncrementsStackAndCap() {
            LocalDate hired = LocalDate.of(2010, 1, 1);
            // 15 base + increments at 3/5/7/9 years, capped at 25.
            assertThat(korean.entitlementFor(hired, LocalDate.of(2013, 1, 1)))
                    .isEqualByComparingTo(new BigDecimal("16"));
            assertThat(korean.entitlementFor(hired, LocalDate.of(2026, 1, 1)))
                    .as("capped at the policy maximum, not accumulating forever")
                    .isEqualByComparingTo(new BigDecimal("25"));
        }

        @Test
        @DisplayName("the seed is data: a client can define a completely different policy")
        void policyIsConfiguration() {
            // A more generous scheme, expressible without touching any code.
            LeaveAccrualPolicy generous = LeaveAccrualPolicy.builder("custom")
                    .name("전직원 25일", "25 days for everyone")
                    .annualGrantDays(new BigDecimal("25"))
                    .annualGrantAfterCompletedYears(0)
                    .minimumBookableUnitDays(new BigDecimal("0.25"))
                    .build();

            assertThat(generous.entitlementFor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)))
                    .isEqualByComparingTo(new BigDecimal("25"));
            assertThat(generous.isBookable(new BigDecimal("0.25"))).isTrue();
            assertThat(korean.isBookable(new BigDecimal("0.25")))
                    .as("the Korean seed books in half-days, so a quarter-day is not valid there")
                    .isFalse();
        }

        @Test
        @DisplayName("requests round UP to the bookable unit, never down")
        void roundsUpNeverDown() {
            // Rounding down would book less leave than is actually taken, and
            // the difference accrues in the employee's favour by accident.
            assertThat(korean.roundToBookableUnit(new BigDecimal("0.3")))
                    .isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(korean.roundToBookableUnit(new BigDecimal("1.1")))
                    .isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(korean.roundToBookableUnit(new BigDecimal("1.0")))
                    .isEqualByComparingTo(new BigDecimal("1.0"));
        }

        @Test
        @DisplayName("carry-over honours the configured limit")
        void carryOverLimit() {
            LeaveAccrualPolicy capped = LeaveAccrualPolicy.builder("capped")
                    .carryOverLimitDays(new BigDecimal("5"))
                    .carryOverExpiryMonths(6)
                    .build();
            assertThat(capped.carryOverFrom(new BigDecimal("9")))
                    .isEqualByComparingTo(new BigDecimal("5"));
            assertThat(capped.carryOverFrom(new BigDecimal("3")))
                    .isEqualByComparingTo(new BigDecimal("3"));
            assertThat(capped.carryOverExpiryFrom(LocalDate.of(2026, 1, 1)))
                    .isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(korean.carryOverFrom(new BigDecimal("9")))
                    .as("the Korean seed sets no carry-over cap")
                    .isEqualByComparingTo(new BigDecimal("9"));
        }
    }
}
