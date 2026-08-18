package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.report.LedgerReports;
import java.time.LocalDate;
import java.util.List;

/**
 * Loads a book's posted entries and hands them to the pure report functions.
 *
 * <h2>The loader is the only thing that touches the database</h2>
 *
 * <p>{@link LedgerReports} stays a set of pure functions over a supplied collection. No query is
 * pushed into them, deliberately: a report that queries is a report that cannot be tested without
 * a database, cannot be run over a hypothetical set of entries, and quietly acquires a second way
 * of deciding what "posted" means. Here there is one loader, and every report sees exactly the
 * same entries.
 *
 * <p>Nothing is cached and nothing is stored. A stored report is a second source of truth that
 * drifts from the journal, and the drift is found by an auditor rather than by us.
 */
public class LedgerReportService {

    private final JournalService journal;
    private final BatchService batches;

    public LedgerReportService(JournalService journal, BatchService batches) {
        this.journal = journal;
        this.batches = batches;
    }

    /**
     * The entries every report below is computed from: posted only, in business order.
     *
     * <p>Public because a caller with a question no report answers should still ask it of the same
     * set, rather than inventing a second definition of what counts.
     */
    public List<Entry> postedEntries(String bookId) {
        return journal.postedEntries(bookId);
    }

    public LedgerReports.TrialBalance trialBalance(String bookId, LocalDate asOf) {
        return LedgerReports.trialBalance(postedEntries(bookId), asOf);
    }

    /** As-of, with the unclosed net income shown as its own line rather than folded into equity. */
    public LedgerReports.BalanceSheet balanceSheet(String bookId, LocalDate asOf) {
        return LedgerReports.balanceSheet(postedEntries(bookId), asOf,
                batches.closingBatchesOf(bookId));
    }

    /** For a period, with this book's closing batches excluded. */
    public LedgerReports.IncomeStatement incomeStatement(String bookId, LocalDate from,
            LocalDate to) {
        return LedgerReports.incomeStatement(postedEntries(bookId), from, to,
                batches.closingBatchesOf(bookId));
    }

    /** One account's balance, in its natural direction. */
    public Amount balanceOf(String bookId, Account account, LocalDate asOf) {
        return LedgerReports.balanceOf(postedEntries(bookId), account, asOf);
    }
}
