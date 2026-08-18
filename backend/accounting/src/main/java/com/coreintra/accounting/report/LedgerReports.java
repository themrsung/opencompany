package com.coreintra.accounting.report;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.AccountType;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports as pure functions over posted entries.
 *
 * <p>Nothing here is persisted and nothing is plugged. A stored report is a
 * second source of truth that drifts from the journal, and the drift is
 * discovered by an auditor rather than by us.
 *
 * <h2>What is excluded, always</h2>
 *
 * <p>Draft and voided entries contribute to nothing. That is applied once, in
 * {@link #isReportable}, rather than per report — the alternative is a report
 * somebody adds later that forgets, and the discrepancy looks like a data
 * problem rather than a missing filter.
 *
 * <h2>The balanced flag is an alarm, never a plug</h2>
 *
 * <p>Every entry is balanced by construction, so a trial balance that does not
 * balance means data reached the tables without going through {@link Entry} —
 * a bad migration, a manual SQL fix, a bug. The flag reports that. It never
 * inserts a balancing figure to make the report look right, because the report
 * looking right is exactly what would stop anyone investigating.
 */
public final class LedgerReports {

    private LedgerReports() {
    }

    /** Draft and void contribute to nothing, anywhere. */
    public static boolean isReportable(Entry entry) {
        return entry.status() == Entry.EntryStatus.POSTED;
    }

    /**
     * Whether a batch's entries are excluded from the income statement.
     *
     * <p>Closing batches are: a closed year must still report what it earned.
     * Including the closing entries would net income to zero and make the year
     * look as though nothing happened.
     */
    public interface BatchKindLookup {
        boolean isClosingBatch(String batchId);
    }

    /** One line of a trial balance. */
    public static final class TrialBalanceLine implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String accountId;
        private final Amount debit;
        private final Amount credit;

        TrialBalanceLine(String accountId, Amount debit, Amount credit) {
            this.accountId = accountId;
            this.debit = debit;
            this.credit = credit;
        }

        public String accountId() {
            return accountId;
        }

        public Amount debit() {
            return debit;
        }

        public Amount credit() {
            return credit;
        }

        /** Debits minus credits. Positive is a net debit. */
        public Amount net() {
            return debit.subtract(credit);
        }
    }

    public static final class TrialBalance implements Serializable {
        private static final long serialVersionUID = 1L;

        private final List<TrialBalanceLine> lines;
        private final Amount totalDebits;
        private final Amount totalCredits;

        TrialBalance(List<TrialBalanceLine> lines, Amount totalDebits, Amount totalCredits) {
            this.lines = Immutables.copyOf(lines);
            this.totalDebits = totalDebits;
            this.totalCredits = totalCredits;
        }

        public List<TrialBalanceLine> lines() {
            return lines;
        }

        public Amount totalDebits() {
            return totalDebits;
        }

        public Amount totalCredits() {
            return totalCredits;
        }

        /**
         * A data-integrity alarm.
         *
         * <p>False means something bypassed {@link Entry}. Investigate; do not
         * adjust the report.
         */
        public boolean isBalanced() {
            return totalDebits.isEqualTo(totalCredits);
        }

        public Amount imbalance() {
            return totalDebits.subtract(totalCredits);
        }
    }

    /** Trial balance as of a business date, inclusive. */
    public static TrialBalance trialBalance(List<Entry> entries, LocalDate asOf) {
        Map<String, Amount> debits = new LinkedHashMap<String, Amount>();
        Map<String, Amount> credits = new LinkedHashMap<String, Amount>();
        Amount totalDebits = Amount.ZERO;
        Amount totalCredits = Amount.ZERO;

        for (Entry entry : entries) {
            if (!isReportable(entry) || entry.postedAt().businessDate().isAfter(asOf)) {
                continue;
            }
            for (Posting posting : entry.postings()) {
                Amount base = posting.baseAmount();
                if (base.isPositive()) {
                    debits.put(posting.accountId(),
                            orZero(debits, posting.accountId()).add(base));
                    totalDebits = totalDebits.add(base);
                } else {
                    Amount magnitude = base.negate();
                    credits.put(posting.accountId(),
                            orZero(credits, posting.accountId()).add(magnitude));
                    totalCredits = totalCredits.add(magnitude);
                }
            }
        }

        List<TrialBalanceLine> lines = new ArrayList<TrialBalanceLine>();
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (String accountId : debits.keySet()) {
            seen.put(accountId, Boolean.TRUE);
            lines.add(new TrialBalanceLine(accountId, orZero(debits, accountId),
                    orZero(credits, accountId)));
        }
        for (String accountId : credits.keySet()) {
            if (!seen.containsKey(accountId)) {
                lines.add(new TrialBalanceLine(accountId, Amount.ZERO,
                        orZero(credits, accountId)));
            }
        }
        return new TrialBalance(lines, totalDebits, totalCredits);
    }

    /** The balance of one account as of a date, in its natural direction. */
    public static Amount balanceOf(List<Entry> entries, Account account, LocalDate asOf) {
        Amount net = Amount.ZERO;
        for (Entry entry : entries) {
            if (!isReportable(entry) || entry.postedAt().businessDate().isAfter(asOf)) {
                continue;
            }
            for (Posting posting : entry.postings()) {
                if (posting.accountId().equals(account.id())) {
                    net = net.add(posting.baseAmount());
                }
            }
        }
        // Presented positive when it sits on the account's normal side, so a
        // credit-normal account does not report a negative balance in the
        // ordinary case.
        return account.isDebitNormal() ? net : net.negate();
    }

    /** Income statement for a period, closing batches excluded. */
    public static IncomeStatement incomeStatement(List<Entry> entries, LocalDate from,
            LocalDate to, BatchKindLookup batches) {
        Amount income = Amount.ZERO;
        Amount expense = Amount.ZERO;

        for (Entry entry : entries) {
            if (!isReportable(entry)) {
                continue;
            }
            LocalDate on = entry.postedAt().businessDate();
            if (on.isBefore(from) || on.isAfter(to)) {
                continue;
            }
            // A closed year must still report what it earned.
            if (entry.batchId() != null && batches != null
                    && batches.isClosingBatch(entry.batchId())) {
                continue;
            }
            for (Posting posting : entry.postings()) {
                AccountType type = AccountType.fromAccountId(posting.accountId());
                if (type == AccountType.INCOME) {
                    income = income.add(posting.baseAmount().negate());
                } else if (type == AccountType.EXPENSE) {
                    expense = expense.add(posting.baseAmount());
                }
            }
        }
        return new IncomeStatement(from, to, income, expense);
    }

    public static final class IncomeStatement implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate from;
        private final LocalDate to;
        private final Amount income;
        private final Amount expense;

        IncomeStatement(LocalDate from, LocalDate to, Amount income, Amount expense) {
            this.from = from;
            this.to = to;
            this.income = income;
            this.expense = expense;
        }

        public LocalDate from() {
            return from;
        }

        public LocalDate to() {
            return to;
        }

        public Amount income() {
            return income;
        }

        public Amount expense() {
            return expense;
        }

        public Amount netIncome() {
            return income.subtract(expense);
        }
    }

    /** Balance sheet as of a date. */
    public static BalanceSheet balanceSheet(List<Entry> entries, LocalDate asOf,
            BatchKindLookup batches) {
        Amount assets = Amount.ZERO;
        Amount liabilities = Amount.ZERO;
        Amount equity = Amount.ZERO;

        for (Entry entry : entries) {
            if (!isReportable(entry) || entry.postedAt().businessDate().isAfter(asOf)) {
                continue;
            }
            for (Posting posting : entry.postings()) {
                AccountType type = AccountType.fromAccountId(posting.accountId());
                Amount base = posting.baseAmount();
                if (type == AccountType.ASSET) {
                    assets = assets.add(base);
                } else if (type == AccountType.LIABILITY) {
                    liabilities = liabilities.add(base.negate());
                } else if (type == AccountType.EQUITY) {
                    equity = equity.add(base.negate());
                }
            }
        }
        // The only derived line, and it is labelled as such rather than folded
        // into equity where nobody could see it.
        Amount unclosedNetIncome = incomeStatement(entries, LocalDate.MIN, asOf, batches)
                .netIncome();
        return new BalanceSheet(asOf, assets, liabilities, equity, unclosedNetIncome);
    }

    public static final class BalanceSheet implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate asOf;
        private final Amount assets;
        private final Amount liabilities;
        private final Amount equity;
        private final Amount unclosedNetIncome;

        BalanceSheet(LocalDate asOf, Amount assets, Amount liabilities, Amount equity,
                Amount unclosedNetIncome) {
            this.asOf = asOf;
            this.assets = assets;
            this.liabilities = liabilities;
            this.equity = equity;
            this.unclosedNetIncome = unclosedNetIncome;
        }

        public LocalDate asOf() {
            return asOf;
        }

        public Amount assets() {
            return assets;
        }

        public Amount liabilities() {
            return liabilities;
        }

        public Amount equity() {
            return equity;
        }

        /** The one derived line, clearly labelled and never hidden inside equity. */
        public Amount unclosedNetIncome() {
            return unclosedNetIncome;
        }

        /**
         * A data-integrity alarm, never a balancing plug.
         *
         * <p>False means data reached the tables without passing through
         * {@link Entry}. The report says so instead of inserting a figure to
         * make itself look right.
         */
        public boolean isBalanced() {
            return assets.isEqualTo(liabilities.add(equity).add(unclosedNetIncome));
        }

        public Amount imbalance() {
            return assets.subtract(liabilities.add(equity).add(unclosedNetIncome));
        }
    }

    private static Amount orZero(Map<String, Amount> map, String key) {
        Amount value = map.get(key);
        return value == null ? Amount.ZERO : value;
    }
}
