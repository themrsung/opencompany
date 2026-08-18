package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.attendance.service.LeaveQuote;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 연차 on the wire.
 *
 * <h2>Days are decimal strings, for the same reason money is</h2>
 *
 * <p>Half-days and quarter-days are ordinary in Korean practice and 0.25 is not
 * representable in binary floating point. A balance that drifts by a
 * ten-millionth of a day over a year is a support ticket nobody can explain, so
 * days never become JSON numbers on the way out any more than they do in the
 * ledger.
 */
public final class LeaveViews {

    private LeaveViews() {
    }

    /** What is left, and what it is made of. */
    @Schema(name = "LeaveBalance", description = "A leave balance on a date.")
    public static class Balance {
        private final String employeeId;
        private final String policyId;
        private final String asOf;
        private final String balanceDays;

        Balance(String employeeId, String policyId, LocalDate asOf, BigDecimal balanceDays) {
            this.employeeId = employeeId;
            this.policyId = policyId;
            this.asOf = asOf.toString();
            this.balanceDays = ApiWire.decimal(balanceDays);
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public String getPolicyId() {
            return policyId;
        }

        @Schema(description = "Grants that had not yet vested on this date, and days that "
                + "had already lapsed, are both excluded.", example = "2026-08-30")
        public String getAsOf() {
            return asOf;
        }

        @Schema(description = "An exact decimal string. Computed from the ledger rows every "
                + "time rather than stored, so it cannot drift away from them.",
                example = "11.50")
        public String getBalanceDays() {
            return balanceDays;
        }
    }

    /** One ledger row. */
    @Schema(name = "LeaveTransaction", description = "One immutable movement of leave days.")
    public static class Transaction {
        private final String id;
        private final String kind;
        private final String days;
        private final String signedDays;
        private final String occurredAt;
        private final String effectiveFrom;
        private final String expiresOn;
        private final String reason;
        private final String sourceDocumentId;
        private final String actorAccountId;

        Transaction(LeaveLedger.Transaction row) {
            this.id = row.id();
            this.kind = row.kind().name();
            this.days = ApiWire.decimal(row.days());
            this.signedDays = ApiWire.decimal(row.signedDays());
            this.occurredAt = ApiWire.wire(row.occurredAt());
            this.effectiveFrom = row.effectiveFrom() == null
                    ? null : row.effectiveFrom().toString();
            this.expiresOn = row.expiresOn() == null ? null : row.expiresOn().toString();
            this.reason = row.reason();
            this.sourceDocumentId = row.sourceDocumentId();
            this.actorAccountId = row.actorAccountId();
        }

        public String getId() {
            return id;
        }

        @Schema(description = "GRANT (연차 발생), CARRY_OVER (이월), USE (사용), EXPIRY (소멸), "
                + "ADJUSTMENT (조정) or CANCELLATION (취소).", example = "USE")
        public String getKind() {
            return kind;
        }

        @Schema(description = "The magnitude, always positive. The direction comes from the "
                + "kind.", example = "1.00")
        public String getDays() {
            return days;
        }

        @Schema(description = "The magnitude with the kind's sign applied, so a client can "
                + "sum the column without knowing the taxonomy.", example = "-1.00")
        public String getSignedDays() {
            return signedDays;
        }

        @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ".",
                example = ApiWire.INSTANT_EXAMPLE)
        public String getOccurredAt() {
            return occurredAt;
        }

        @Schema(description = "When the days become spendable. A grant dated forward is on "
                + "the ledger before it counts towards a balance.")
        public String getEffectiveFrom() {
            return effectiveFrom;
        }

        @Schema(description = "When unused days lapse under policy, or null if they do not.")
        public String getExpiresOn() {
            return expiresOn;
        }

        @Schema(description = "Always present on an adjustment. A correction with no stated "
                + "reason is indistinguishable from a mistake.")
        public String getReason() {
            return reason;
        }

        @Schema(description = "The 휴가 결재 document that caused this row, where there was one.")
        public String getSourceDocumentId() {
            return sourceDocumentId;
        }

        public String getActorAccountId() {
            return actorAccountId;
        }
    }

    /** The whole ledger — the "where did my days go?" screen. */
    @Schema(name = "LeaveLedger")
    public static class Ledger {
        private final String employeeId;
        private final String policyId;
        private final String asOf;
        private final String balanceDays;
        private final List<Transaction> transactions;

        Ledger(String policyId, LocalDate asOf, LeaveLedger ledger) {
            this.employeeId = ledger.employeeId();
            this.policyId = policyId;
            this.asOf = asOf.toString();
            this.balanceDays = ApiWire.decimal(ledger.balanceOn(asOf));
            List<Transaction> rows = new ArrayList<Transaction>();
            for (LeaveLedger.Transaction row : ledger.transactions()) {
                rows.add(new Transaction(row));
            }
            this.transactions = Immutables.copyOf(rows);
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public String getPolicyId() {
            return policyId;
        }

        public String getAsOf() {
            return asOf;
        }

        public String getBalanceDays() {
            return balanceDays;
        }

        @Schema(description = "In business-time order: date first, then offset.")
        public List<Transaction> getTransactions() {
            return transactions;
        }
    }

    /** Advice before booking, not a reservation. */
    @Schema(name = "LeaveQuote")
    public static class Quote {
        private final String requestedDays;
        private final String bookableDays;
        private final String balanceDays;
        private final boolean affordable;
        private final boolean rounded;

        Quote(LeaveQuote quote) {
            this.requestedDays = ApiWire.decimal(quote.requestedDays());
            this.bookableDays = ApiWire.decimal(quote.bookableDays());
            this.balanceDays = ApiWire.decimal(quote.balanceDays());
            this.affordable = quote.isAffordable();
            this.rounded = quote.isRounded();
        }

        public String getRequestedDays() {
            return requestedDays;
        }

        @Schema(description = "What it rounds to under the policy's minimum unit, rounded up "
                + "rather than down: booking less leave than is actually taken puts the "
                + "difference nowhere.")
        public String getBookableDays() {
            return bookableDays;
        }

        public String getBalanceDays() {
            return balanceDays;
        }

        @Schema(description = "Whether the balance covers the bookable amount. Advice only "
                + "— the same arithmetic runs again and is authoritative when the 휴가 is "
                + "approved, and a colleague booking the same days in between changes the "
                + "answer.")
        public boolean isAffordable() {
            return affordable;
        }

        @Schema(description = "True when the request had to be rounded up, so the UI can say "
                + "so before the person is surprised by it.")
        public boolean isRounded() {
            return rounded;
        }
    }

    /** Grants that will lapse — the "your 연차 expires soon" reminder. */
    @Schema(name = "ExpiringLeave")
    public static class Expiring {
        private final String employeeId;
        private final String policyId;
        private final String by;
        private final String asOf;
        private final List<Transaction> grants;

        Expiring(String employeeId, String policyId, LocalDate by, LocalDate asOf,
                List<LeaveLedger.Transaction> rows) {
            this.employeeId = employeeId;
            this.policyId = policyId;
            this.by = by.toString();
            this.asOf = asOf.toString();
            List<Transaction> views = new ArrayList<Transaction>();
            for (LeaveLedger.Transaction row : rows) {
                views.add(new Transaction(row));
            }
            this.grants = Immutables.copyOf(views);
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public String getPolicyId() {
            return policyId;
        }

        @Schema(description = "The horizon that was asked about.")
        public String getBy() {
            return by;
        }

        public String getAsOf() {
            return asOf;
        }

        public List<Transaction> getGrants() {
            return grants;
        }
    }
}
