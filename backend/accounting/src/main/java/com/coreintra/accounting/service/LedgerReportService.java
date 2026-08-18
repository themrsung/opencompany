package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.accounting.report.SubLedgerReports;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.util.List;

/**
 * Loads a book's posted entries and hands them to the pure report functions.
 *
 * <h2>The loader is the only thing that touches the database</h2>
 *
 * <p>{@link LedgerReports} and {@link SubLedgerReports} stay sets of pure functions over a supplied
 * collection. No query is pushed into them, deliberately: a report that queries is a report that
 * cannot be tested without a database, cannot be run over a hypothetical set of entries, and
 * quietly acquires a second way of deciding what "posted" means. Here there is one loader, and
 * every report sees exactly the same entries.
 *
 * <p>Nothing is cached and nothing is stored. A stored report is a second source of truth that
 * drifts from the journal, and the drift is found by an auditor rather than by us.
 *
 * <h2>Checked on the report's own date, once</h2>
 *
 * <p>Every method checks {@code accounting.report:read} against the book's company as of the date
 * the report is <em>about</em> — the as-of date, or the last day of the period — rather than
 * today's. A March balance sheet is judged against March's org chart, so re-running last quarter's
 * pack after a reorganisation gives the same answer about who was entitled to see it.
 *
 * <p>The check happens here and not again inside the loaders. Reporting is one permission, not
 * two: a manager entitled to the income statement is not thereby entitled to read every posting
 * memo behind it, and a report that also demanded {@code accounting.entry:read} would force the
 * two to be granted together and make the finer distinction pointless.
 */
public class LedgerReportService {

    private final JournalService journal;
    private final BatchService batches;
    private final ChartOfAccountsService chart;
    private final AccountingGate gate;

    public LedgerReportService(JournalService journal, BatchService batches,
            ChartOfAccountsService chart, AccountingGate gate) {
        this.journal = journal;
        this.batches = batches;
        this.chart = chart;
        this.gate = gate;
    }

    /**
     * The entries every report below is computed from: posted only, in business order.
     *
     * <p>Public because a caller with a question no report answers should still ask it of the same
     * set, rather than inventing a second definition of what counts.
     */
    public List<Entry> postedEntries(PermissionPrincipal caller, String bookId, LocalDate asOf) {
        require(caller, bookId, asOf, "read the posted entries behind a report");
        return journal.postedEntriesOf(bookId);
    }

    public LedgerReports.TrialBalance trialBalance(PermissionPrincipal caller, String bookId,
            LocalDate asOf) {
        require(caller, bookId, asOf, "run a trial balance");
        return LedgerReports.trialBalance(journal.postedEntriesOf(bookId), asOf);
    }

    /** As-of, with the unclosed net income shown as its own line rather than folded into equity. */
    public LedgerReports.BalanceSheet balanceSheet(PermissionPrincipal caller, String bookId,
            LocalDate asOf) {
        require(caller, bookId, asOf, "run a balance sheet");
        // No closing-batch filter, deliberately: see LedgerReports.balanceSheet. A balance sheet
        // reports what is on the accounts, and a closed result is no longer on them.
        return LedgerReports.balanceSheet(journal.postedEntriesOf(bookId), asOf);
    }

    /** For a period, with this book's closing batches excluded. */
    public LedgerReports.IncomeStatement incomeStatement(PermissionPrincipal caller, String bookId,
            LocalDate from, LocalDate to) {
        require(caller, bookId, to, "run an income statement");
        return LedgerReports.incomeStatement(journal.postedEntriesOf(bookId), from, to,
                batches.closingBatchesOf(bookId));
    }

    /**
     * Cash in and out over a period, classified by the account on the other side of each entry.
     *
     * <p>Needs the chart as well as the journal, because "is this account cash" and "is this
     * movement operating" are properties of the chart with nearest-ancestor inheritance, not of
     * the posting.
     */
    public LedgerReports.CashFlowStatement cashFlow(PermissionPrincipal caller, String bookId,
            LocalDate from, LocalDate to) {
        require(caller, bookId, to, "run a cash-flow statement");
        return LedgerReports.cashFlow(journal.postedEntriesOf(bookId), from, to,
                chart.chartOf(bookId));
    }

    public LedgerReports.EquityStatement equityStatement(PermissionPrincipal caller, String bookId,
            LocalDate from, LocalDate to) {
        require(caller, bookId, to, "run a statement of changes in equity");
        return LedgerReports.equityStatement(journal.postedEntriesOf(bookId), from, to,
                batches.closingBatchesOf(bookId));
    }

    /** The 거래처 sub-ledger, aged. */
    public SubLedgerReports.ReceivablesAgeing receivablesAgeing(PermissionPrincipal caller,
            String bookId, LocalDate asOf, List<Integer> bandDays) {
        require(caller, bookId, asOf, "age the receivables");
        return SubLedgerReports.receivablesAgeing(journal.postedEntriesOf(bookId), asOf,
                chart.chartOf(bookId), bandDays);
    }

    public SubLedgerReports.PrepaidSchedules prepaidSchedules(PermissionPrincipal caller,
            String bookId, LocalDate asOf) {
        require(caller, bookId, asOf, "list the prepaid schedules");
        return SubLedgerReports.prepaidSchedules(journal.postedEntriesOf(bookId), asOf,
                chart.chartOf(bookId));
    }

    /** One account's balance, in its natural direction. */
    public Amount balanceOf(PermissionPrincipal caller, String bookId, Account account,
            LocalDate asOf) {
        require(caller, bookId, asOf, "read the balance of account " + account.id());
        return LedgerReports.balanceOf(journal.postedEntriesOf(bookId), account, asOf);
    }

    private void require(PermissionPrincipal caller, String bookId, LocalDate asOf, String what) {
        gate.requireOnBook(caller, AccountingPermissions.REPORT_READ, bookId, asOf,
                what + " for book " + bookId);
    }
}
