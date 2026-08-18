package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.accounting.report.SubLedgerReports;
import com.coreintra.accounting.service.LedgerReportService;
import com.coreintra.app.api.permission.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every report, as a pure function over the posted entries of one book.
 *
 * <h2>Nothing here is stored, cached or plugged</h2>
 *
 * <p>Each request recomputes from the journal. A stored report is a second source of truth that
 * drifts, and the drift is discovered by an auditor rather than by us. Two consequences are
 * visible on this surface: there is no report id and no "generated at" field, because there is no
 * generated report — and asking twice for the same as-of date gives the same answer whatever has
 * been posted since, provided it was dated after that day.
 *
 * <h2>The balanced flags are alarms</h2>
 *
 * <p>{@code balanced} and {@code reconciles} report a discrepancy; they never make one go away.
 * Every entry balances by construction, so a false flag means something reached the tables without
 * passing through the engine — a bad migration, a hand-written SQL fix during a support session.
 * The report says so, with the size of the gap, and adjusts no line.
 *
 * <h2>Draft, void and closing</h2>
 *
 * <p>Draft and voided entries contribute to nothing, anywhere. Closing batches are excluded from
 * the income statement, so a closed year still reports what it earned — and are <em>not</em>
 * excluded from the balance sheet's unclosed-net-income line, because a closed result is no longer
 * unclosed and pretending otherwise would put the same profit in two places.
 */
@RestController
@RequestMapping("/api/v1/accounting/books/{bookId}/reports")
@AccountingEnabled
@Tag(name = "Accounting: reports",
        description = "Trial balance, balance sheet, income statement, cash flow, changes in "
                + "equity, receivables ageing and prepaid schedules. All amounts are exact "
                + "decimal strings. All require accounting.report:read.")
public class AccountingReportsController {

    private final LedgerReportService reports;
    private final CurrentPrincipal current;

    public AccountingReportsController(LedgerReportService reports, CurrentPrincipal current) {
        this.reports = reports;
        this.current = current;
    }

    @GetMapping("/trial-balance")
    @Operation(summary = "Trial balance as of a business date, inclusive",
            description = "The balanced flag is a data-integrity alarm. False means something "
                    + "bypassed the engine; investigate rather than adjust.")
    public TrialBalanceResponse trialBalance(@PathVariable("bookId") String bookId,
            @Parameter(description = "Inclusive. Defaults to today.")
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate on = Amounts.businessDateOrToday(asOf);
        return new TrialBalanceResponse(reports.trialBalance(current.require(), bookId, on), on);
    }

    @GetMapping("/balance-sheet")
    @Operation(summary = "Balance sheet as of a business date",
            description = "The only derived line is unclosedNetIncome, and it is labelled rather "
                    + "than folded into equity. Closing batches are not excluded from it: a "
                    + "closed result has left the income accounts, which is what the line "
                    + "measures.")
    public BalanceSheetResponse balanceSheet(@PathVariable("bookId") String bookId,
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate on = Amounts.businessDateOrToday(asOf);
        return new BalanceSheetResponse(reports.balanceSheet(current.require(), bookId, on));
    }

    @GetMapping("/income-statement")
    @Operation(summary = "Income statement for a period",
            description = "Closing batches are excluded, so a closed year still reports what it "
                    + "earned.")
    public IncomeStatementResponse incomeStatement(@PathVariable("bookId") String bookId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new IncomeStatementResponse(
                reports.incomeStatement(current.require(), bookId, from, to));
    }

    @GetMapping("/cash-flow")
    @Operation(summary = "Cash flow for a period, by the direct method",
            description = "Built from the movements of the accounts categorised CASH or "
                    + "CASH_EQUIVALENT, attributed to the classification of the account on the "
                    + "other side of each entry. A movement whose counterpart has no "
                    + "classification is reported as UNCLASSIFIED rather than assumed to be "
                    + "operating.")
    public CashFlowResponse cashFlow(@PathVariable("bookId") String bookId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new CashFlowResponse(reports.cashFlow(current.require(), bookId, from, to));
    }

    @GetMapping("/equity-statement")
    @Operation(summary = "Changes in equity over a period",
            description = "Equity means the equity accounts plus unclosed net income, as on the "
                    + "balance sheet. closingAdjustments is the net effect of the closing "
                    + "batches, which for a well-formed close is zero: closing reclassifies "
                    + "equity, it does not create it.")
    public EquityStatementResponse equityStatement(@PathVariable("bookId") String bookId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new EquityStatementResponse(
                reports.equityStatement(current.require(), bookId, from, to));
    }

    @GetMapping("/receivables-ageing")
    @Operation(summary = "Receivables by 거래처 and age",
            description = "Settlement is applied oldest-first per counterparty and account, "
                    + "because the engine has no invoice-level matching. Outstanding on postings "
                    + "that named no 거래처 is reported separately rather than dropped.")
    public ReceivablesAgeingResponse receivablesAgeing(@PathVariable("bookId") String bookId,
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @Parameter(description = "Band boundaries in days, ascending. Defaults to 30,60,90.")
            @RequestParam(value = "bandDays", required = false) List<Integer> bandDays) {
        LocalDate on = Amounts.businessDateOrToday(asOf);
        return new ReceivablesAgeingResponse(
                reports.receivablesAgeing(current.require(), bookId, on, bandDays));
    }

    @GetMapping("/prepaid-schedules")
    @Operation(summary = "Prepayments, and the instalments already posted against them",
            description = "The scheduled instalments are entries that already exist in the "
                    + "journal, dated after the as-of date — not a forecast. An unexpired balance "
                    + "with an empty schedule is a prepayment nobody has set an amortisation up "
                    + "for.")
    public PrepaidSchedulesResponse prepaidSchedules(@PathVariable("bookId") String bookId,
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate on = Amounts.businessDateOrToday(asOf);
        return new PrepaidSchedulesResponse(
                reports.prepaidSchedules(current.require(), bookId, on));
    }

    // ------------------------------------------------------------------
    // Wire types. Every amount is a String; see Amounts.
    // ------------------------------------------------------------------

    /** One account's debits and credits. */
    public static class TrialBalanceLineResponse {
        private final String accountId;
        private final String debit;
        private final String credit;
        private final String net;

        TrialBalanceLineResponse(LedgerReports.TrialBalanceLine line) {
            this.accountId = line.accountId();
            this.debit = Amounts.wire(line.debit());
            this.credit = Amounts.wire(line.credit());
            this.net = Amounts.wire(line.net());
        }

        public String getAccountId() {
            return accountId;
        }

        public String getDebit() {
            return debit;
        }

        public String getCredit() {
            return credit;
        }

        /** Debits minus credits. Positive is a net debit. */
        public String getNet() {
            return net;
        }
    }

    /** A trial balance. */
    public static class TrialBalanceResponse {
        private final LocalDate asOf;
        private final List<TrialBalanceLineResponse> lines;
        private final String totalDebits;
        private final String totalCredits;
        private final boolean balanced;
        private final String imbalance;

        public TrialBalanceResponse(LedgerReports.TrialBalance report, LocalDate asOf) {
            this.asOf = asOf;
            this.lines = new ArrayList<TrialBalanceLineResponse>();
            for (LedgerReports.TrialBalanceLine line : report.lines()) {
                this.lines.add(new TrialBalanceLineResponse(line));
            }
            this.totalDebits = Amounts.wire(report.totalDebits());
            this.totalCredits = Amounts.wire(report.totalCredits());
            this.balanced = report.isBalanced();
            this.imbalance = Amounts.wire(report.imbalance());
        }

        public LocalDate getAsOf() {
            return asOf;
        }

        public List<TrialBalanceLineResponse> getLines() {
            return lines;
        }

        public String getTotalDebits() {
            return totalDebits;
        }

        public String getTotalCredits() {
            return totalCredits;
        }

        /** An alarm. False means data bypassed the engine; nothing has been adjusted. */
        public boolean isBalanced() {
            return balanced;
        }

        /** Zero when it balances. The size of the problem when it does not. */
        public String getImbalance() {
            return imbalance;
        }
    }

    /** A balance sheet. */
    public static class BalanceSheetResponse {
        private final LocalDate asOf;
        private final String assets;
        private final String liabilities;
        private final String equity;
        private final String unclosedNetIncome;
        private final boolean balanced;
        private final String imbalance;

        public BalanceSheetResponse(LedgerReports.BalanceSheet sheet) {
            this.asOf = sheet.asOf();
            this.assets = Amounts.wire(sheet.assets());
            this.liabilities = Amounts.wire(sheet.liabilities());
            this.equity = Amounts.wire(sheet.equity());
            this.unclosedNetIncome = Amounts.wire(sheet.unclosedNetIncome());
            this.balanced = sheet.isBalanced();
            this.imbalance = Amounts.wire(sheet.imbalance());
        }

        public LocalDate getAsOf() {
            return asOf;
        }

        public String getAssets() {
            return assets;
        }

        public String getLiabilities() {
            return liabilities;
        }

        public String getEquity() {
            return equity;
        }

        /** The one derived line, clearly labelled and never hidden inside equity. */
        public String getUnclosedNetIncome() {
            return unclosedNetIncome;
        }

        public boolean isBalanced() {
            return balanced;
        }

        public String getImbalance() {
            return imbalance;
        }
    }

    /** An income statement. */
    public static class IncomeStatementResponse {
        private final LocalDate from;
        private final LocalDate to;
        private final String income;
        private final String expense;
        private final String netIncome;

        public IncomeStatementResponse(LedgerReports.IncomeStatement statement) {
            this.from = statement.from();
            this.to = statement.to();
            this.income = Amounts.wire(statement.income());
            this.expense = Amounts.wire(statement.expense());
            this.netIncome = Amounts.wire(statement.netIncome());
        }

        public LocalDate getFrom() {
            return from;
        }

        public LocalDate getTo() {
            return to;
        }

        public String getIncome() {
            return income;
        }

        public String getExpense() {
            return expense;
        }

        public String getNetIncome() {
            return netIncome;
        }
    }

    /** A cash-flow statement. */
    public static class CashFlowResponse {
        private final LocalDate from;
        private final LocalDate to;
        private final String openingCash;
        private final String closingCash;
        private final Map<String, String> sections;
        private final String netMovement;
        private final boolean reconciles;
        private final String discrepancy;

        public CashFlowResponse(LedgerReports.CashFlowStatement statement) {
            this.from = statement.from();
            this.to = statement.to();
            this.openingCash = Amounts.wire(statement.openingCash());
            this.closingCash = Amounts.wire(statement.closingCash());
            this.sections = new LinkedHashMap<String, String>();
            for (Map.Entry<LedgerReports.CashFlowSection, Amount> section
                    : statement.sections().entrySet()) {
                this.sections.put(section.getKey().name(), Amounts.wire(section.getValue()));
            }
            this.netMovement = Amounts.wire(statement.netMovement());
            this.reconciles = statement.reconciles();
            this.discrepancy = Amounts.wire(statement.discrepancy());
        }

        public LocalDate getFrom() {
            return from;
        }

        public LocalDate getTo() {
            return to;
        }

        public String getOpeningCash() {
            return openingCash;
        }

        public String getClosingCash() {
            return closingCash;
        }

        /**
         * All seven sections, always, so a zero is visibly zero rather than a missing key.
         *
         * <p>OPERATING, INVESTING, FINANCING, TAX, FX, OTHER, UNCLASSIFIED. Every value is an
         * exact decimal string, including inside this map.
         */
        public Map<String, String> getSections() {
            return sections;
        }

        public String getNetMovement() {
            return netMovement;
        }

        /** An alarm: false means the cash accounts moved by something the sections do not explain. */
        public boolean isReconciles() {
            return reconciles;
        }

        public String getDiscrepancy() {
            return discrepancy;
        }
    }

    /** A statement of changes in equity. */
    public static class EquityStatementResponse {
        private final LocalDate from;
        private final LocalDate to;
        private final String openingEquity;
        private final String netIncome;
        private final String ownerMovements;
        private final String closingAdjustments;
        private final String transferredToEquityByClosing;
        private final String closingEquity;
        private final String assets;
        private final String liabilities;
        private final boolean balanced;
        private final String imbalance;

        public EquityStatementResponse(LedgerReports.EquityStatement statement) {
            this.from = statement.from();
            this.to = statement.to();
            this.openingEquity = Amounts.wire(statement.openingEquity());
            this.netIncome = Amounts.wire(statement.netIncome());
            this.ownerMovements = Amounts.wire(statement.ownerMovements());
            this.closingAdjustments = Amounts.wire(statement.closingAdjustments());
            this.transferredToEquityByClosing =
                    Amounts.wire(statement.transferredToEquityByClosing());
            this.closingEquity = Amounts.wire(statement.closingEquity());
            this.assets = Amounts.wire(statement.assets());
            this.liabilities = Amounts.wire(statement.liabilities());
            this.balanced = statement.isBalanced();
            this.imbalance = Amounts.wire(statement.imbalance());
        }

        public LocalDate getFrom() {
            return from;
        }

        public LocalDate getTo() {
            return to;
        }

        /** Equity accounts plus unclosed net income, the moment before the period opened. */
        public String getOpeningEquity() {
            return openingEquity;
        }

        /** The period's result, closing batches excluded. */
        public String getNetIncome() {
            return netIncome;
        }

        /** Capital introduced, dividends, buybacks. */
        public String getOwnerMovements() {
            return ownerMovements;
        }

        /** The net effect of the closing batches. Zero for a well-formed close. */
        public String getClosingAdjustments() {
            return closingAdjustments;
        }

        /** The gross amount the closing batches credited to equity. Information, not a movement. */
        public String getTransferredToEquityByClosing() {
            return transferredToEquityByClosing;
        }

        /** Opening plus every movement. Added up, never derived by subtraction. */
        public String getClosingEquity() {
            return closingEquity;
        }

        public String getAssets() {
            return assets;
        }

        public String getLiabilities() {
            return liabilities;
        }

        /** An alarm: assets less liabilities, computed independently, against the roll-forward. */
        public boolean isBalanced() {
            return balanced;
        }

        public String getImbalance() {
            return imbalance;
        }
    }

    /** What one counterparty owes on one account, split by age. */
    public static class AgeingLineResponse {
        private final String clientId;
        private final String accountId;
        private final String outstanding;
        private final List<String> buckets;
        private final String unappliedCredits;
        private final long oldestItemDays;

        AgeingLineResponse(SubLedgerReports.AgeingLine line) {
            this.clientId = line.clientId();
            this.accountId = line.accountId();
            this.outstanding = Amounts.wire(line.outstanding());
            this.buckets = new ArrayList<String>();
            for (Amount bucket : line.buckets()) {
                this.buckets.add(Amounts.wire(bucket));
            }
            this.unappliedCredits = Amounts.wire(line.unappliedCredits());
            this.oldestItemDays = line.oldestItemDays();
        }

        /** Null when the postings carried no 거래처. */
        public String getClientId() {
            return clientId;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getOutstanding() {
            return outstanding;
        }

        /** One more than there are bands: the last is everything older than the last band. */
        public List<String> getBuckets() {
            return buckets;
        }

        /** Credits that settled nothing — an overpayment or a deposit, not a negative debt. */
        public String getUnappliedCredits() {
            return unappliedCredits;
        }

        public long getOldestItemDays() {
            return oldestItemDays;
        }
    }

    /** Receivables ageing. */
    public static class ReceivablesAgeingResponse {
        private final LocalDate asOf;
        private final List<Integer> bandDays;
        private final List<AgeingLineResponse> lines;
        private final String total;
        private final String totalUnappliedCredits;
        private final String unassigned;

        public ReceivablesAgeingResponse(SubLedgerReports.ReceivablesAgeing report) {
            this.asOf = report.asOf();
            this.bandDays = report.bandDays();
            this.lines = new ArrayList<AgeingLineResponse>();
            for (SubLedgerReports.AgeingLine line : report.lines()) {
                this.lines.add(new AgeingLineResponse(line));
            }
            this.total = Amounts.wire(report.total());
            this.totalUnappliedCredits = Amounts.wire(report.totalUnappliedCredits());
            this.unassigned = Amounts.wire(report.unassigned());
        }

        public LocalDate getAsOf() {
            return asOf;
        }

        /** The band boundaries this report was aged on, so the columns can be labelled. */
        public List<Integer> getBandDays() {
            return bandDays;
        }

        public List<AgeingLineResponse> getLines() {
            return lines;
        }

        public String getTotal() {
            return total;
        }

        public String getTotalUnappliedCredits() {
            return totalUnappliedCredits;
        }

        /** Outstanding on postings that named no 거래처. Reported, never dropped. */
        public String getUnassigned() {
            return unassigned;
        }
    }

    /** One instalment already in the journal, waiting for its business date. */
    public static class ScheduledRecognitionResponse {
        private final LocalDate businessDate;
        private final String amount;
        private final String entryId;
        private final String batchId;
        private final String description;

        ScheduledRecognitionResponse(SubLedgerReports.ScheduledRecognition recognition) {
            this.businessDate = recognition.businessDate();
            this.amount = Amounts.wire(recognition.amount());
            this.entryId = recognition.entryId();
            this.batchId = recognition.batchId();
            this.description = recognition.description();
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public String getAmount() {
            return amount;
        }

        /** Quotable because the entry already exists. */
        public String getEntryId() {
            return entryId;
        }

        public String getBatchId() {
            return batchId;
        }

        public String getDescription() {
            return description;
        }
    }

    /** One prepayment. */
    public static class PrepaidLineResponse {
        private final String clientId;
        private final String accountId;
        private final String capitalised;
        private final String recognised;
        private final String unexpired;
        private final String scheduledTotal;
        private final boolean fullyScheduled;
        private final LocalDate scheduledThrough;
        private final List<ScheduledRecognitionResponse> scheduled;

        PrepaidLineResponse(SubLedgerReports.PrepaidLine line) {
            this.clientId = line.clientId();
            this.accountId = line.accountId();
            this.capitalised = Amounts.wire(line.capitalised());
            this.recognised = Amounts.wire(line.recognised());
            this.unexpired = Amounts.wire(line.unexpired());
            this.scheduledTotal = Amounts.wire(line.scheduledTotal());
            this.fullyScheduled = line.fullyScheduled();
            this.scheduledThrough = line.scheduledThrough();
            this.scheduled = new ArrayList<ScheduledRecognitionResponse>();
            for (SubLedgerReports.ScheduledRecognition recognition : line.scheduled()) {
                this.scheduled.add(new ScheduledRecognitionResponse(recognition));
            }
        }

        public String getClientId() {
            return clientId;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getCapitalised() {
            return capitalised;
        }

        public String getRecognised() {
            return recognised;
        }

        /** What is still on the balance sheet. Equals the account balance for this 거래처. */
        public String getUnexpired() {
            return unexpired;
        }

        public String getScheduledTotal() {
            return scheduledTotal;
        }

        /** True when the posted schedule releases exactly what is left, and no more. */
        public boolean isFullyScheduled() {
            return fullyScheduled;
        }

        /** The date the last posted instalment falls on, or null when none is posted. */
        public LocalDate getScheduledThrough() {
            return scheduledThrough;
        }

        @ArraySchema(schema = @Schema(implementation = ScheduledRecognitionResponse.class))
        public List<ScheduledRecognitionResponse> getScheduled() {
            return scheduled;
        }
    }

    /** Prepaid schedules. */
    public static class PrepaidSchedulesResponse {
        private final LocalDate asOf;
        private final List<PrepaidLineResponse> lines;
        private final String totalUnexpired;
        private final String totalScheduled;
        private final String unscheduled;

        public PrepaidSchedulesResponse(SubLedgerReports.PrepaidSchedules report) {
            this.asOf = report.asOf();
            this.lines = new ArrayList<PrepaidLineResponse>();
            for (SubLedgerReports.PrepaidLine line : report.lines()) {
                this.lines.add(new PrepaidLineResponse(line));
            }
            this.totalUnexpired = Amounts.wire(report.totalUnexpired());
            this.totalScheduled = Amounts.wire(report.totalScheduled());
            this.unscheduled = Amounts.wire(report.unscheduled());
        }

        public LocalDate getAsOf() {
            return asOf;
        }

        public List<PrepaidLineResponse> getLines() {
            return lines;
        }

        public String getTotalUnexpired() {
            return totalUnexpired;
        }

        public String getTotalScheduled() {
            return totalScheduled;
        }

        /** Unexpired balance with no posted instalment behind it — the figure to act on. */
        public String getUnscheduled() {
            return unscheduled;
        }
    }
}
