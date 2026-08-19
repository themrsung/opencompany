package com.coreintra.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The leave ledger, and the acceptance test from §13: <b>approving a leave
 * request writes exactly one balance transaction, and a failure writes none</b>.
 *
 * <p>The other thing these tests are for is proving that no entitlement in this
 * module is decided by code. Every number below comes from a policy row, and the
 * Korean 연차 seed is exercised as one policy among others rather than as the
 * behaviour of the system.
 */
class LeaveServiceTest {

    private static final String ANNUAL = "policy-annual";
    private static final String DOCUMENT = "doc-leave-1";

    private AttendanceTestWorld world;
    private LeaveService service;
    private OrgUnit team;
    private PermissionPrincipal employee;
    private PermissionPrincipal hr;

    @BeforeEach
    void setUp() {
        world = new AttendanceTestWorld();
        team = world.unit("DEV");
        employee = world.person("김사원", team);
        hr = world.person("인사담당", team);

        world.policies.add("ANNUAL", LeaveAccrualPolicy.koreanAnnualLeaveSeed(ANNUAL));
        world.grants.grant(employee, AttendancePermissions.LEAVE_READ, PermissionScope.SELF);
        world.grants.grant(hr, AttendancePermissions.LEAVE_READ, PermissionScope.COMPANY);
        world.grants.grant(hr, AttendancePermissions.LEAVE_WRITE, PermissionScope.COMPANY);
        service = world.leaveService();
    }

    private static BusinessInstant at(LocalDate date) {
        return BusinessInstant.of(date, 9, 0, 0);
    }

    /** Puts days on the ledger the way the accrual job would. */
    private void grantDays(String days, LocalDate on) {
        service.credit(hr, AttendanceTestWorld.COMPANY, employee.employeeId(), ANNUAL,
                new BigDecimal(days), at(on), "기초 잔액 설정");
    }

    private ApprovedLeave request(String days, LocalDate on, String documentId) {
        return new ApprovedLeave(employee.employeeId(), ANNUAL, new BigDecimal(days), at(on),
                documentId, "acc-박부장", "연차 사용");
    }

    @Nested
    @DisplayName("an approval writes the balance")
    class ApprovalWritesTheBalance {

        @BeforeEach
        void fifteenDaysOnTheLedger() {
            grantDays("15", LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("approving a leave request writes exactly one balance transaction")
        void exactlyOneTransaction() {
            int before = world.ledgers.size();

            LeaveLedger.Transaction written = service.recordApprovedLeave(
                    request("1", LocalDate.of(2026, 8, 30), DOCUMENT));

            assertThat(world.ledgers.size() - before)
                    .as("one approval, one row — not none, and not two")
                    .isEqualTo(1);
            assertThat(written.kind()).isEqualTo(LeaveLedger.TransactionKind.USE);
            assertThat(written.days()).isEqualByComparingTo(new BigDecimal("1"));
            assertThat(written.sourceDocumentId())
                    .as("the row says which 결재 spent the day")
                    .isEqualTo(DOCUMENT);
            assertThat(service.balanceOn(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    ANNUAL, LocalDate.of(2026, 8, 30)))
                    .isEqualByComparingTo(new BigDecimal("14"));
        }

        @Test
        @DisplayName("a failure writes none — an insufficient balance refuses before appending")
        void insufficientBalanceWritesNothing() {
            int before = world.ledgers.size();

            assertThatThrownBy(() -> service.recordApprovedLeave(
                    request("20", LocalDate.of(2026, 8, 30), DOCUMENT)))
                    .isInstanceOf(LeaveService.LeaveRefusedException.class)
                    .hasMessageContaining("잔여 연차가 부족합니다");

            assertThat(world.ledgers.size())
                    .as("the refusal left the ledger exactly as it found it")
                    .isEqualTo(before);
            assertThat(service.balanceOn(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    ANNUAL, LocalDate.of(2026, 8, 30)))
                    .isEqualByComparingTo(new BigDecimal("15"));
        }

        @Test
        @DisplayName("a request that is not a whole bookable unit writes none, and says the fix")
        void unbookableAmountWritesNothing() {
            int before = world.ledgers.size();

            assertThatThrownBy(() -> service.recordApprovedLeave(
                    request("0.3", LocalDate.of(2026, 8, 30), DOCUMENT)))
                    .isInstanceOf(LeaveService.LeaveRefusedException.class)
                    .hasMessageContaining("Adjust the request to 0.5 days");

            assertThat(world.ledgers.size()).isEqualTo(before);
        }

        @Test
        @DisplayName("a retried approval deducts once, not twice")
        void idempotentOnTheDocumentId() {
            LeaveLedger.Transaction first = service.recordApprovedLeave(
                    request("1", LocalDate.of(2026, 8, 30), DOCUMENT));
            int after = world.ledgers.size();

            LeaveLedger.Transaction again = service.recordApprovedLeave(
                    request("1", LocalDate.of(2026, 8, 30), DOCUMENT));

            assertThat(again.id()).isEqualTo(first.id());
            assertThat(world.ledgers.size())
                    .as("an approval retried after a rollback must not spend the day twice")
                    .isEqualTo(after);
        }

        @Test
        @DisplayName("leave cannot be deducted with no 결재 document behind it")
        void noDocumentNoDeduction() {
            assertThatThrownBy(() -> new ApprovedLeave(employee.employeeId(), ANNUAL,
                    BigDecimal.ONE, at(LocalDate.of(2026, 8, 30)), null, "acc-박부장", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("결재된 문서로만");
        }

        @Test
        @DisplayName("a recalled approval gives the days back as a cancellation, once")
        void cancellationGivesTheDaysBack() {
            service.recordApprovedLeave(request("2", LocalDate.of(2026, 8, 30), DOCUMENT));

            Optional<LeaveLedger.Transaction> cancelled = service.cancelApprovedLeave(
                    employee.employeeId(), ANNUAL, DOCUMENT,
                    at(LocalDate.of(2026, 8, 31)), null, "acc-김사원");

            assertThat(cancelled).isPresent();
            assertThat(cancelled.get().kind())
                    .as("the leave really was booked and really was given back; both belong on "
                            + "the record")
                    .isEqualTo(LeaveLedger.TransactionKind.CANCELLATION);
            assertThat(service.balanceOn(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    ANNUAL, LocalDate.of(2026, 8, 31)))
                    .isEqualByComparingTo(new BigDecimal("15"));

            assertThat(service.cancelApprovedLeave(employee.employeeId(), ANNUAL, DOCUMENT,
                    at(LocalDate.of(2026, 9, 1)), null, "acc-김사원"))
                    .as("cancelling twice does not credit twice")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("accrual is configuration, not statute in code")
    class AccrualIsConfiguration {

        @Test
        @DisplayName("the seeded Korean policy accrues monthly in the first year")
        void monthlyAccrualInTheFirstYear() {
            LeaveAccrualPolicy policy = world.policies.findById(ANNUAL).get();

            assertThat(policy.entitlementFor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1)))
                    .isEqualByComparingTo(new BigDecimal("6"));
        }

        @Test
        @DisplayName("and grants fifteen days from the second year, rising with tenure to a cap")
        void annualGrantAndTenureLadder() {
            LeaveAccrualPolicy policy = world.policies.findById(ANNUAL).get();
            LocalDate hired = LocalDate.of(2010, 1, 1);

            assertThat(policy.entitlementFor(hired, LocalDate.of(2011, 6, 1)))
                    .isEqualByComparingTo(new BigDecimal("15"));
            assertThat(policy.entitlementFor(hired, LocalDate.of(2015, 6, 1)))
                    .as("+1 at three years and +2 at five, cumulatively")
                    .isEqualByComparingTo(new BigDecimal("18"));
            assertThat(policy.entitlementFor(hired, LocalDate.of(2040, 6, 1)))
                    .as("capped by the policy's own maximum")
                    .isEqualByComparingTo(new BigDecimal("25"));
        }

        @Test
        @DisplayName("a client policy with different numbers behaves identically")
        void aClientPolicyIsJustAnotherRow() {
            LeaveAccrualPolicy generous = LeaveAccrualPolicy.builder("policy-generous")
                    .name("무제한에 가까운 연차", "Generous leave")
                    .annualGrantDays(new BigDecimal("30"))
                    .annualGrantAfterCompletedYears(0)
                    .minimumBookableUnitDays(new BigDecimal("0.25"))
                    .build();
            world.policies.add("GENEROUS", generous);

            ApprovedLeave quarterDay = new ApprovedLeave(employee.employeeId(),
                    "policy-generous", new BigDecimal("0.25"), at(LocalDate.of(2026, 8, 30)),
                    "doc-quarter", "acc-박부장", null);
            service.credit(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    "policy-generous", new BigDecimal("30"), at(LocalDate.of(2026, 1, 1)),
                    "기초 잔액");

            assertThat(service.recordApprovedLeave(quarterDay).days())
                    .as("quarter-day units work because the policy row says so, not because "
                            + "anything in the code knows about quarter days")
                    .isEqualByComparingTo(new BigDecimal("0.25"));
        }

        @Test
        @DisplayName("granting entitlement twice grants it once")
        void grantEntitlementIsIdempotent() {
            LocalDate periodStart = LocalDate.of(2026, 1, 1);
            LocalDate asOf = LocalDate.of(2026, 8, 30);

            Optional<LeaveLedger.Transaction> first = service.grantEntitlement(hr,
                    AttendanceTestWorld.COMPANY, employee.employeeId(), ANNUAL,
                    LocalDate.of(2020, 1, 1), at(asOf), periodStart, null);
            Optional<LeaveLedger.Transaction> second = service.grantEntitlement(hr,
                    AttendanceTestWorld.COMPANY, employee.employeeId(), ANNUAL,
                    LocalDate.of(2020, 1, 1), at(asOf), periodStart, null);

            assertThat(first).isPresent();
            assertThat(first.get().days())
                    .as("six completed years: fifteen, plus one at three years and two at five")
                    .isEqualByComparingTo(new BigDecimal("18"));
            assertThat(second)
                    .as("an accrual job that fires twice after a restart must not double anyone's "
                            + "leave")
                    .isEmpty();
        }

        @Test
        @DisplayName("closing a period carries what the policy allows and lapses the rest")
        void carryOverAndExpiry() {
            LeaveAccrualPolicy capped = LeaveAccrualPolicy.builder("policy-capped")
                    .name("이월 제한", "Capped carry-over")
                    .annualGrantDays(new BigDecimal("15"))
                    .carryOverLimitDays(new BigDecimal("3"))
                    .carryOverExpiryMonths(6)
                    .build();
            world.policies.add("CAPPED", capped);
            service.credit(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    "policy-capped", new BigDecimal("10"), at(LocalDate.of(2026, 1, 1)), "기초");

            service.closePeriod(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    "policy-capped", at(LocalDate.of(2026, 12, 31)));

            assertThat(service.balanceOn(hr, AttendanceTestWorld.COMPANY, employee.employeeId(),
                    "policy-capped", LocalDate.of(2026, 12, 31)))
                    .as("three of the ten carry; seven lapse")
                    .isEqualByComparingTo(new BigDecimal("3"));

            LeaveLedger ledger = service.ledger(hr, AttendanceTestWorld.COMPANY,
                    employee.employeeId(), "policy-capped", LocalDate.of(2026, 12, 31));
            assertThat(ledger.expiringBy(LocalDate.of(2027, 7, 1), LocalDate.of(2026, 12, 31)))
                    .as("carried days are re-booked with their own expiry, so the reminder can "
                            + "find them")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("quotes and authorisation")
    class QuotesAndAuthorisation {

        @Test
        @DisplayName("a quote rounds up to the bookable unit and says whether it is affordable")
        void quoteRoundsUp() {
            grantDays("1", LocalDate.of(2026, 1, 1));

            LeaveQuote quote = service.quote(employee, AttendanceTestWorld.COMPANY,
                    employee.employeeId(), ANNUAL, new BigDecimal("0.3"),
                    LocalDate.of(2026, 8, 30));

            assertThat(quote.bookableDays()).isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(quote.isRounded()).isTrue();
            assertThat(quote.isAffordable()).isTrue();

            LeaveQuote tooMuch = service.quote(employee, AttendanceTestWorld.COMPANY,
                    employee.employeeId(), ANNUAL, new BigDecimal("2"),
                    LocalDate.of(2026, 8, 30));
            assertThat(tooMuch.isAffordable()).isFalse();
        }

        @Test
        @DisplayName("someone else's balance is a sensitive read and needs a grant")
        void othersBalanceNeedsAGrant() {
            assertThatThrownBy(() -> service.balanceOn(employee, AttendanceTestWorld.COMPANY,
                    hr.employeeId(), ANNUAL, LocalDate.of(2026, 8, 30)))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("hr.leave:read");
        }

        @Test
        @DisplayName("an employee cannot credit their own balance")
        void selfCreditRefused() {
            assertThatThrownBy(() -> service.credit(employee, AttendanceTestWorld.COMPANY,
                    employee.employeeId(), ANNUAL, new BigDecimal("5"),
                    at(LocalDate.of(2026, 8, 30)), "제가 좀 더 쓰겠습니다"))
                    .as("reading your own balance is self-service; writing it is not")
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("an adjustment without a reason is refused by the ledger itself")
        void adjustmentNeedsAReason() {
            assertThatThrownBy(() -> service.credit(hr, AttendanceTestWorld.COMPANY,
                    employee.employeeId(), ANNUAL, new BigDecimal("5"),
                    at(LocalDate.of(2026, 8, 30)), "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must state a reason");
        }
    }
}
