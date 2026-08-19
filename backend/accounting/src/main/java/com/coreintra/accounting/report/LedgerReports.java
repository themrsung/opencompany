package com.coreintra.accounting.report;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.AccountType;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.ChartOfAccounts;
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

    /**
     * Balance sheet as of a date.
     *
     * <h2>Why closing batches are <em>not</em> excluded here</h2>
     *
     * <p>They are excluded from the income statement, so that a closed year still reports what it
     * earned. They must not be excluded from this report's one derived line, and the difference is
     * the whole reason the line is called <em>unclosed</em> net income.
     *
     * <p>The figure is the balance still sitting on the income and expense accounts. A closing
     * batch is what takes it off them and puts it into equity. Compute it with closing entries
     * filtered out and it keeps reporting a profit that has already been transferred, so equity
     * holds the result and this line holds it too — and {@link BalanceSheet#isBalanced()} goes
     * false on 1 January of every year, for every client, by design rather than by accident. An
     * alarm that fires annually for a healthy reason is an alarm people learn to silence, which
     * costs exactly the one it was installed to catch.
     */
    public static BalanceSheet balanceSheet(List<Entry> entries, LocalDate asOf) {
        Amount assets = Amount.ZERO;
        Amount liabilities = Amount.ZERO;
        Amount equity = Amount.ZERO;
        Amount income = Amount.ZERO;
        Amount expense = Amount.ZERO;

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
                } else if (type == AccountType.INCOME) {
                    income = income.add(base.negate());
                } else if (type == AccountType.EXPENSE) {
                    expense = expense.add(base);
                }
            }
        }
        // The only derived line, and it is labelled as such rather than folded
        // into equity where nobody could see it.
        return new BalanceSheet(asOf, assets, liabilities, equity, income.subtract(expense));
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

    // ------------------------------------------------------------------
    // Cash flow
    // ------------------------------------------------------------------

    /**
     * Where a movement of cash belongs.
     *
     * <p>The six named sections are the account classifications; the seventh is not. A movement
     * whose counterpart account has no classification, and inherits none from any ancestor, is
     * reported as {@link #UNCLASSIFIED} rather than being quietly folded into operating. Folding
     * it in would make the statement add up while telling the reader something untrue, and the
     * cure - setting a classification on one parent account - is a minute's work that nobody does
     * if the report never asks for it.
     */
    public enum CashFlowSection {
        OPERATING, INVESTING, FINANCING, TAX, FX, OTHER, UNCLASSIFIED;

        static CashFlowSection of(ChartOfAccounts.Classification classification) {
            if (classification == null) {
                return UNCLASSIFIED;
            }
            switch (classification) {
                case OPERATING: return OPERATING;
                case INVESTING: return INVESTING;
                case FINANCING: return FINANCING;
                case TAX: return TAX;
                case FX: return FX;
                default: return OTHER;
            }
        }
    }

    /**
     * A cash-flow statement for a period, built directly from the movements of the cash accounts.
     *
     * <h2>Direct, not indirect, and never a plug</h2>
     *
     * <p>The indirect method starts from net income and adjusts, which means it needs a working
     * capital model and an accruals model, and it is traditionally made to balance by putting the
     * difference in a line called "other". This one adds up the actual movements of the accounts
     * categorised {@code CASH} or {@code CASH_EQUIVALENT} and attributes each movement to the
     * classification of the account on the other side of the entry. There is nothing left over to
     * plug.
     *
     * <h2>Why attribution is exact</h2>
     *
     * <p>Every entry balances, so for an entry that touches cash the sum of its cash legs is
     * exactly the negative of the sum of its non-cash legs. Attributing each non-cash leg's amount
     * (negated) to that leg's own section therefore partitions the cash movement precisely, with
     * no proportional split and so no rounding. An entry that moves cash between two cash accounts
     * has no non-cash leg, attributes nothing, and correctly nets to zero.
     *
     * <p>Closing batches need no special handling here: closing entries move income into equity
     * and touch no cash account, so they contribute nothing.
     *
     * @param chart the account snapshot the classifications and categories are read from, with
     *     nearest-ancestor inheritance already resolved by {@link ChartOfAccounts}
     */
    public static CashFlowStatement cashFlow(List<Entry> entries, LocalDate from, LocalDate to,
            ChartOfAccounts chart) {
        Map<CashFlowSection, Amount> sections = new LinkedHashMap<CashFlowSection, Amount>();
        for (CashFlowSection section : CashFlowSection.values()) {
            sections.put(section, Amount.ZERO);
        }
        Amount opening = Amount.ZERO;
        Amount closing = Amount.ZERO;

        for (Entry entry : entries) {
            if (!isReportable(entry)) {
                continue;
            }
            LocalDate on = entry.postedAt().businessDate();
            boolean before = on.isBefore(from);
            boolean upToEnd = !on.isAfter(to);
            boolean inPeriod = !before && upToEnd;

            boolean touchesCash = false;
            for (Posting posting : entry.postings()) {
                if (!isCash(chart, posting.accountId())) {
                    continue;
                }
                touchesCash = true;
                if (before) {
                    opening = opening.add(posting.baseAmount());
                }
                if (upToEnd) {
                    closing = closing.add(posting.baseAmount());
                }
            }
            if (!touchesCash || !inPeriod) {
                continue;
            }
            for (Posting posting : entry.postings()) {
                if (isCash(chart, posting.accountId())) {
                    continue;
                }
                CashFlowSection section =
                        CashFlowSection.of(chart.classificationOf(posting.accountId()));
                sections.put(section, sections.get(section).add(posting.baseAmount().negate()));
            }
        }
        return new CashFlowStatement(from, to, opening, closing, sections);
    }

    private static boolean isCash(ChartOfAccounts chart, String accountId) {
        ChartOfAccounts.Category category = chart == null ? null : chart.categoryOf(accountId);
        return category == ChartOfAccounts.Category.CASH
                || category == ChartOfAccounts.Category.CASH_EQUIVALENT;
    }

    /** Cash in, cash out, and where it went. */
    public static final class CashFlowStatement implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate from;
        private final LocalDate to;
        private final Amount openingCash;
        private final Amount closingCash;
        private final Map<CashFlowSection, Amount> sections;

        CashFlowStatement(LocalDate from, LocalDate to, Amount openingCash, Amount closingCash,
                Map<CashFlowSection, Amount> sections) {
            this.from = from;
            this.to = to;
            this.openingCash = openingCash;
            this.closingCash = closingCash;
            this.sections = Immutables.mapCopyOf(sections);
        }

        public LocalDate from() {
            return from;
        }

        public LocalDate to() {
            return to;
        }

        /** Cash held the moment before the period opened. */
        public Amount openingCash() {
            return openingCash;
        }

        public Amount closingCash() {
            return closingCash;
        }

        /** Every section, always all seven, so a zero is visibly zero rather than missing. */
        public Map<CashFlowSection, Amount> sections() {
            return sections;
        }

        public Amount section(CashFlowSection section) {
            Amount value = sections.get(section);
            return value == null ? Amount.ZERO : value;
        }

        /** The sum of the sections: what the classified movements say the period did. */
        public Amount netMovement() {
            Amount total = Amount.ZERO;
            for (Amount amount : sections.values()) {
                total = total.add(amount);
            }
            return total;
        }

        /**
         * A data-integrity alarm, never a balancing plug.
         *
         * <p>False means the movement the cash accounts actually recorded is not the movement the
         * classified sections describe — which, since every entry balances by construction, can
         * only happen if something reached the tables without passing through {@link Entry}.
         * The statement says so; it does not add a line to make itself add up.
         */
        public boolean reconciles() {
            return openingCash.add(netMovement()).isEqualTo(closingCash);
        }

        /** Closing cash minus what the sections account for. Zero when the report reconciles. */
        public Amount discrepancy() {
            return closingCash.subtract(openingCash.add(netMovement()));
        }
    }

    // ------------------------------------------------------------------
    // Equity statement
    // ------------------------------------------------------------------

    /**
     * How total equity moved over a period, opening to closing.
     *
     * <h2>Total equity, not the equity accounts</h2>
     *
     * <p>"Equity" here means what the balance sheet means by it: the equity accounts <em>plus</em>
     * the unclosed net income that has not yet been transferred into them. Reporting only the
     * accounts would show a company that has traded profitably all year as having identical equity
     * on 1 January and 31 December, with the entire year's result invisible until somebody ran the
     * closing batch — and then jumping by a year's profit on the day they did.
     *
     * <h2>The movements, kept apart</h2>
     *
     * <ul>
     *   <li><b>Net income</b> for the period, closing batches excluded, exactly as the income
     *       statement reports it. A closed year still reports what it earned.</li>
     *   <li><b>Owner movements</b> — capital introduced, dividends, buybacks: postings to equity
     *       accounts that are not part of a closing batch.</li>
     *   <li><b>Closing adjustments</b> — the <em>net</em> effect of the closing batches on total
     *       equity, which for a well-formed close is exactly zero.</li>
     * </ul>
     *
     * <h2>Why closing is one net line and not two gross ones</h2>
     *
     * <p>A closing batch debits the income accounts and credits retained earnings. Both halves are
     * equity as this report defines it, so the batch moves nothing: it reclassifies a result from
     * "unclosed" to "retained". Showing the credit to retained earnings as a movement while net
     * income already reports the same profit would count the year twice, and the roll-forward
     * would exceed the balance sheet by exactly the amount closed. So the two halves are added
     * together into one line that is normally zero — and, when it is not, says that a batch marked
     * {@code CLOSING} did something other than close, which is worth seeing.
     * {@link #transferredToEquityByClosing()} still reports the gross figure, as information.
     *
     * <p>Nothing is derived by subtraction. Closing equity is the sum of the lines above, and
     * {@link EquityStatement#isBalanced()} then checks it against the accounting equation computed
     * independently from the asset and liability accounts.
     */
    public static EquityStatement equityStatement(List<Entry> entries, LocalDate from,
            LocalDate to, BatchKindLookup batches) {
        Amount openingEquity = Amount.ZERO;
        Amount ownerMovements = Amount.ZERO;
        Amount transferredToEquity = Amount.ZERO;
        Amount closingIncomeEffect = Amount.ZERO;
        Amount netIncome = Amount.ZERO;
        Amount assets = Amount.ZERO;
        Amount liabilities = Amount.ZERO;

        for (Entry entry : entries) {
            if (!isReportable(entry)) {
                continue;
            }
            LocalDate on = entry.postedAt().businessDate();
            if (on.isAfter(to)) {
                continue;
            }
            boolean before = on.isBefore(from);
            boolean closingBatch = entry.batchId() != null && batches != null
                    && batches.isClosingBatch(entry.batchId());
            for (Posting posting : entry.postings()) {
                AccountType type = AccountType.fromAccountId(posting.accountId());
                Amount base = posting.baseAmount();
                if (type == AccountType.ASSET) {
                    assets = assets.add(base);
                    continue;
                }
                if (type == AccountType.LIABILITY) {
                    liabilities = liabilities.add(base.negate());
                    continue;
                }
                if (type != AccountType.EQUITY && type != AccountType.INCOME
                        && type != AccountType.EXPENSE) {
                    continue;
                }
                // Income and expense contribute to equity as the unclosed result; equity accounts
                // contribute directly. Both are credit-positive here.
                Amount contribution = base.negate();
                if (before) {
                    openingEquity = openingEquity.add(contribution);
                } else if (closingBatch) {
                    if (type == AccountType.EQUITY) {
                        transferredToEquity = transferredToEquity.add(contribution);
                    } else {
                        closingIncomeEffect = closingIncomeEffect.add(contribution);
                    }
                } else if (type == AccountType.EQUITY) {
                    ownerMovements = ownerMovements.add(contribution);
                } else {
                    netIncome = netIncome.add(contribution);
                }
            }
        }
        return new EquityStatement(from, to, openingEquity, netIncome, ownerMovements,
                transferredToEquity, closingIncomeEffect, assets, liabilities);
    }

    /** Opening equity, what happened to it, and closing equity. */
    public static final class EquityStatement implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate from;
        private final LocalDate to;
        private final Amount openingEquity;
        private final Amount netIncome;
        private final Amount ownerMovements;
        private final Amount transferredToEquityByClosing;
        private final Amount closingIncomeEffect;
        private final Amount assets;
        private final Amount liabilities;

        EquityStatement(LocalDate from, LocalDate to, Amount openingEquity, Amount netIncome,
                Amount ownerMovements, Amount transferredToEquityByClosing,
                Amount closingIncomeEffect, Amount assets, Amount liabilities) {
            this.from = from;
            this.to = to;
            this.openingEquity = openingEquity;
            this.netIncome = netIncome;
            this.ownerMovements = ownerMovements;
            this.transferredToEquityByClosing = transferredToEquityByClosing;
            this.closingIncomeEffect = closingIncomeEffect;
            this.assets = assets;
            this.liabilities = liabilities;
        }

        public LocalDate from() {
            return from;
        }

        public LocalDate to() {
            return to;
        }

        /** Equity accounts plus unclosed net income, the moment before the period opened. */
        public Amount openingEquity() {
            return openingEquity;
        }

        /** The period's result, closing batches excluded — the income statement's figure. */
        public Amount netIncome() {
            return netIncome;
        }

        /** Capital introduced, dividends, buybacks — equity movements outside a closing batch. */
        public Amount ownerMovements() {
            return ownerMovements;
        }

        /**
         * What the closing batches did to total equity. Zero for a well-formed close.
         *
         * <p>Non-zero means a batch marked {@code CLOSING} changed the size of equity rather than
         * only moving it between components. That is not necessarily wrong, but it is never
         * routine, and hiding it inside another line is how it would stop being noticed.
         */
        public Amount closingAdjustments() {
            return transferredToEquityByClosing.add(closingIncomeEffect);
        }

        /**
         * The gross amount the closing batches credited to equity accounts.
         *
         * <p>Information, not a movement: it is matched by an equal debit to the income accounts,
         * which is why {@link #closingAdjustments()} nets to zero.
         */
        public Amount transferredToEquityByClosing() {
            return transferredToEquityByClosing;
        }

        /** Opening plus every movement. Added up, never derived by subtraction. */
        public Amount closingEquity() {
            return openingEquity.add(netIncome).add(ownerMovements).add(closingAdjustments());
        }

        /** Total assets as of the period end, for the accounting-equation check. */
        public Amount assets() {
            return assets;
        }

        public Amount liabilities() {
            return liabilities;
        }

        /**
         * A data-integrity alarm, never a balancing plug.
         *
         * <p>Checks the roll-forward against the accounting equation computed from the asset and
         * liability accounts, which is a genuinely independent route to the same number. False
         * means data reached the tables without passing through {@link Entry}. The report says
         * so instead of inserting a figure to make itself look right.
         */
        public boolean isBalanced() {
            return assets.subtract(liabilities).isEqualTo(closingEquity());
        }

        public Amount imbalance() {
            return assets.subtract(liabilities).subtract(closingEquity());
        }
    }

    private static Amount orZero(Map<String, Amount> map, String key) {
        Amount value = map.get(key);
        return value == null ? Amount.ZERO : value;
    }
}
