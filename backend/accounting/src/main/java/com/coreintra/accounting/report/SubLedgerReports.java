package com.coreintra.accounting.report;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 거래처 sub-ledger: what is owed, how old it is, and what is prepaid.
 *
 * <p>Pure functions over posted entries, like {@link LedgerReports}, and for the same reasons:
 * nothing is persisted, nothing is cached, and a stored ageing report is a second source of truth
 * that drifts from the journal and is discovered by an auditor rather than by us. Draft and voided
 * entries contribute to nothing, applied through {@link LedgerReports#isReportable}.
 *
 * <h2>There is no sub-ledger, and that is the point</h2>
 *
 * <p>A conventional accounts-receivable module keeps its own table of invoices and reconciles it
 * against the control account, which means two records of the same fact and a monthly ritual of
 * finding out why they differ. Here the 거래처 is a dimension on the posting, so the ageing report
 * <em>is</em> the general ledger, read a different way. It cannot disagree with the balance sheet
 * because it is computed from the same rows.
 *
 * <h2>Which accounts are read, and why not by name</h2>
 *
 * <p>Receivables are the accounts whose category resolves to {@code RECEIVABLE}, and prepayments
 * the ones that resolve to {@code PREPAID_EXPENSE} - resolved by nearest ancestor, so a client
 * marks one parent and every child inherits. Nothing here matches on an account name or an id
 * prefix beyond the type: 외상매출금 is not a magic string, and a client whose receivables sit under
 * an account called something else entirely is not working around anything.
 *
 * <h2>Settlement is applied oldest-first</h2>
 *
 * <p>The engine has no invoice-level matching - a payment is a credit to the control account, not
 * a pointer at the debit it settles. Ageing therefore applies credits against outstanding debits
 * oldest-first, per 거래처 and per account. That is the ordinary convention, it is exact (a partial
 * settlement reduces an item, it does not round it), and where a caller disagrees the underlying
 * postings are all still there to be read another way. Credits that exceed the debits they could
 * settle are reported separately as unapplied rather than being netted into a bucket, because a
 * negative figure in the "over 90 days" column means nothing to a reader.
 */
public final class SubLedgerReports {

    private SubLedgerReports() {
    }

    /**
     * The usual bands, in days: 0-30, 31-60, 61-90, over 90.
     *
     * <p>A default rather than a rule. Ageing bands are a commercial decision - a business on
     * 45-day terms wants different ones - so the boundaries are a parameter and this is only what
     * they are when nobody has said otherwise.
     */
    public static final List<Integer> DEFAULT_AGEING_DAYS =
            Immutables.listOf(Integer.valueOf(30), Integer.valueOf(60), Integer.valueOf(90));

    /**
     * Outstanding receivables as of a date, aged by the business date they arose on.
     *
     * @param bucketDays the upper bound of each band except the last, ascending; the last band is
     *     everything older. Null or empty means {@link #DEFAULT_AGEING_DAYS}.
     */
    public static ReceivablesAgeing receivablesAgeing(List<Entry> entries, LocalDate asOf,
            ChartOfAccounts chart, List<Integer> bucketDays) {
        List<Integer> bands = normaliseBands(bucketDays);
        Map<String, OpenItems> byKey = new LinkedHashMap<String, OpenItems>();

        for (Entry entry : inBusinessOrder(entries, asOf)) {
            for (Posting posting : entry.postings()) {
                if (categoryOf(chart, posting.accountId())
                        != ChartOfAccounts.Category.RECEIVABLE) {
                    continue;
                }
                String key = key(posting.clientId(), posting.accountId());
                OpenItems items = byKey.get(key);
                if (items == null) {
                    items = new OpenItems(posting.clientId(), posting.accountId());
                    byKey.put(key, items);
                }
                items.apply(entry.postedAt().businessDate(), posting.baseAmount());
            }
        }

        List<AgeingLine> lines = new ArrayList<AgeingLine>();
        Amount total = Amount.ZERO;
        Amount unapplied = Amount.ZERO;
        Amount unassigned = Amount.ZERO;
        for (OpenItems items : byKey.values()) {
            AgeingLine line = items.age(asOf, bands);
            if (line.outstanding().isZero() && line.unappliedCredits().isZero()) {
                // A counterparty that has paid in full is not news; leaving it in would bury the
                // handful of lines the report exists to show.
                continue;
            }
            lines.add(line);
            total = total.add(line.outstanding());
            unapplied = unapplied.add(line.unappliedCredits());
            if (line.clientId() == null) {
                unassigned = unassigned.add(line.outstanding());
            }
        }
        return new ReceivablesAgeing(asOf, bands, lines, total, unapplied, unassigned);
    }

    /** {@link #receivablesAgeing(List, LocalDate, ChartOfAccounts, List)} with the usual bands. */
    public static ReceivablesAgeing receivablesAgeing(List<Entry> entries, LocalDate asOf,
            ChartOfAccounts chart) {
        return receivablesAgeing(entries, asOf, chart, DEFAULT_AGEING_DAYS);
    }

    /**
     * Prepayments: what was capitalised, what has been recognised, and what is already scheduled.
     *
     * <h2>The future instalments are not a forecast</h2>
     *
     * <p>Amortisation in this system is compute-and-preview: a person accepts a schedule and it is
     * written as ordinary entries, some of them dated in the future. So "what is still to be
     * recognised" is not something this report calculates - it is a list of entries that already
     * exist in the journal, dated after the as-of date, which is why they can be quoted with their
     * entry ids. An unexpired balance with no scheduled recognitions behind it is a prepayment
     * nobody has set up an amortisation for, and the report shows that by showing an empty
     * schedule rather than by guessing what the instalments would have been.
     */
    public static PrepaidSchedules prepaidSchedules(List<Entry> entries, LocalDate asOf,
            ChartOfAccounts chart) {
        Map<String, Prepaid> byKey = new LinkedHashMap<String, Prepaid>();
        for (Entry entry : inBusinessOrder(entries, null)) {
            for (Posting posting : entry.postings()) {
                if (categoryOf(chart, posting.accountId())
                        != ChartOfAccounts.Category.PREPAID_EXPENSE) {
                    continue;
                }
                String key = key(posting.clientId(), posting.accountId());
                Prepaid prepaid = byKey.get(key);
                if (prepaid == null) {
                    prepaid = new Prepaid(posting.clientId(), posting.accountId());
                    byKey.put(key, prepaid);
                }
                prepaid.apply(entry, posting, asOf);
            }
        }

        List<PrepaidLine> lines = new ArrayList<PrepaidLine>();
        Amount unexpired = Amount.ZERO;
        Amount scheduled = Amount.ZERO;
        for (Prepaid prepaid : byKey.values()) {
            PrepaidLine line = prepaid.toLine();
            if (line.unexpired().isZero() && line.scheduled().isEmpty()) {
                continue;
            }
            lines.add(line);
            unexpired = unexpired.add(line.unexpired());
            scheduled = scheduled.add(line.scheduledTotal());
        }
        return new PrepaidSchedules(asOf, lines, unexpired, scheduled);
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    /**
     * Reportable entries in business order, oldest first.
     *
     * <p>Sorted here rather than relying on the caller's query, because oldest-first settlement is
     * only oldest-first if the order is guaranteed, and a pure function that silently depends on
     * how it was called is a bug waiting for the first caller who holds entries in a set.
     *
     * @param upTo entries after this business date are dropped; null keeps all of them, which is
     *     what the prepaid report needs in order to see the future instalments
     */
    private static List<Entry> inBusinessOrder(List<Entry> entries, LocalDate upTo) {
        List<Entry> ordered = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (!LedgerReports.isReportable(entry)) {
                continue;
            }
            if (upTo != null && entry.postedAt().businessDate().isAfter(upTo)) {
                continue;
            }
            ordered.add(entry);
        }
        Collections.sort(ordered, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                int byInstant = BusinessInstant.COMPARATOR.compare(left.postedAt(),
                        right.postedAt());
                // The id breaks the tie so the order is total: two entries stamped at the same
                // business instant is normal, and an unstable order would make an ageing report
                // that differs between two runs over identical data.
                return byInstant != 0 ? byInstant : left.id().compareTo(right.id());
            }
        });
        return ordered;
    }

    private static ChartOfAccounts.Category categoryOf(ChartOfAccounts chart, String accountId) {
        return chart == null ? null : chart.categoryOf(accountId);
    }

    /** {@code null} 거래처 is a key of its own, not an omission: unassigned balances must show. */
    private static String key(String clientId, String accountId) {
        return (clientId == null ? "" : clientId) + ' ' + accountId;
    }

    private static List<Integer> normaliseBands(List<Integer> bucketDays) {
        if (bucketDays == null || bucketDays.isEmpty()) {
            return DEFAULT_AGEING_DAYS;
        }
        int previous = 0;
        for (Integer day : bucketDays) {
            if (day == null || day.intValue() <= previous) {
                throw new IllegalArgumentException("ageing bands must be positive and ascending, "
                        + "got " + bucketDays + ". Overlapping bands would let one invoice be "
                        + "counted twice.");
            }
            previous = day.intValue();
        }
        return Immutables.copyOf(bucketDays);
    }

    // ------------------------------------------------------------------
    // Receivables
    // ------------------------------------------------------------------

    /** One outstanding debit, and how much of it is left after settlements were applied. */
    private static final class OpenItem {
        private final LocalDate arose;
        private Amount outstanding;

        OpenItem(LocalDate arose, Amount outstanding) {
            this.arose = arose;
            this.outstanding = outstanding;
        }
    }

    /** The running state of one (거래처, account) pair while the journal is walked. */
    private static final class OpenItems {
        private final String clientId;
        private final String accountId;
        private final List<OpenItem> items = new ArrayList<OpenItem>();
        private Amount unapplied = Amount.ZERO;

        OpenItems(String clientId, String accountId) {
            this.clientId = clientId;
            this.accountId = accountId;
        }

        void apply(LocalDate on, Amount base) {
            if (base.isPositive()) {
                items.add(new OpenItem(on, base));
                return;
            }
            Amount remaining = base.negate();
            for (OpenItem item : items) {
                if (remaining.isZero()) {
                    break;
                }
                if (item.outstanding.isZero()) {
                    continue;
                }
                if (item.outstanding.compareTo(remaining) <= 0) {
                    remaining = remaining.subtract(item.outstanding);
                    item.outstanding = Amount.ZERO;
                } else {
                    item.outstanding = item.outstanding.subtract(remaining);
                    remaining = Amount.ZERO;
                }
            }
            // Whatever is left settled nothing. A credit balance on a receivable is a real thing -
            // an overpayment, a deposit taken - and it is reported as such rather than aged.
            unapplied = unapplied.add(remaining);
        }

        AgeingLine age(LocalDate asOf, List<Integer> bands) {
            List<Amount> buckets = new ArrayList<Amount>();
            for (int index = 0; index <= bands.size(); index++) {
                buckets.add(Amount.ZERO);
            }
            Amount outstanding = Amount.ZERO;
            long oldest = 0;
            for (OpenItem item : items) {
                if (item.outstanding.isZero()) {
                    continue;
                }
                long age = ChronoUnit.DAYS.between(item.arose, asOf);
                if (age > oldest) {
                    oldest = age;
                }
                int bucket = bands.size();
                for (int index = 0; index < bands.size(); index++) {
                    if (age <= bands.get(index).longValue()) {
                        bucket = index;
                        break;
                    }
                }
                buckets.set(bucket, buckets.get(bucket).add(item.outstanding));
                outstanding = outstanding.add(item.outstanding);
            }
            return new AgeingLine(clientId, accountId, outstanding, buckets, unapplied, oldest);
        }
    }

    /** What one counterparty owes on one account, split by age. */
    public static final class AgeingLine implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String clientId;
        private final String accountId;
        private final Amount outstanding;
        private final List<Amount> buckets;
        private final Amount unappliedCredits;
        private final long oldestItemDays;

        AgeingLine(String clientId, String accountId, Amount outstanding, List<Amount> buckets,
                Amount unappliedCredits, long oldestItemDays) {
            this.clientId = clientId;
            this.accountId = accountId;
            this.outstanding = outstanding;
            this.buckets = Immutables.copyOf(buckets);
            this.unappliedCredits = unappliedCredits;
            this.oldestItemDays = oldestItemDays;
        }

        /** Null when the postings carried no 거래처. See {@link ReceivablesAgeing#unassigned()}. */
        public String clientId() {
            return clientId;
        }

        public String accountId() {
            return accountId;
        }

        public Amount outstanding() {
            return outstanding;
        }

        /** One more entry than there are bands: the last is everything older than the last band. */
        public List<Amount> buckets() {
            return buckets;
        }

        /** Credits that settled nothing — an overpayment or a deposit, not a negative debt. */
        public Amount unappliedCredits() {
            return unappliedCredits;
        }

        /** Age in days of the oldest item still outstanding. Zero when nothing is. */
        public long oldestItemDays() {
            return oldestItemDays;
        }
    }

    /** Receivables as of a date, per counterparty and account. */
    public static final class ReceivablesAgeing implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate asOf;
        private final List<Integer> bandDays;
        private final List<AgeingLine> lines;
        private final Amount total;
        private final Amount totalUnappliedCredits;
        private final Amount unassigned;

        ReceivablesAgeing(LocalDate asOf, List<Integer> bandDays, List<AgeingLine> lines,
                Amount total, Amount totalUnappliedCredits, Amount unassigned) {
            this.asOf = asOf;
            this.bandDays = Immutables.copyOf(bandDays);
            this.lines = Immutables.copyOf(lines);
            this.total = total;
            this.totalUnappliedCredits = totalUnappliedCredits;
            this.unassigned = unassigned;
        }

        public LocalDate asOf() {
            return asOf;
        }

        /** The band boundaries this report was aged on, echoed so the columns can be labelled. */
        public List<Integer> bandDays() {
            return bandDays;
        }

        public List<AgeingLine> lines() {
            return lines;
        }

        public Amount total() {
            return total;
        }

        public Amount totalUnappliedCredits() {
            return totalUnappliedCredits;
        }

        /**
         * Outstanding on postings that named no 거래처.
         *
         * <p>Reported as its own figure rather than dropped. A receivables report that silently
         * omitted the lines nobody tagged would understate the debt and give no clue why.
         */
        public Amount unassigned() {
            return unassigned;
        }
    }

    // ------------------------------------------------------------------
    // Prepayments
    // ------------------------------------------------------------------

    private static final class Prepaid {
        private final String clientId;
        private final String accountId;
        private Amount capitalised = Amount.ZERO;
        private Amount recognised = Amount.ZERO;
        private final List<ScheduledRecognition> scheduled = new ArrayList<ScheduledRecognition>();

        Prepaid(String clientId, String accountId) {
            this.clientId = clientId;
            this.accountId = accountId;
        }

        void apply(Entry entry, Posting posting, LocalDate asOf) {
            LocalDate on = entry.postedAt().businessDate();
            if (on.isAfter(asOf)) {
                if (posting.baseAmount().isNegative()) {
                    scheduled.add(new ScheduledRecognition(on, posting.baseAmount().negate(),
                            entry.id(), entry.batchId(), entry.description()));
                }
                // A capitalisation dated in the future is neither unexpired yet nor a scheduled
                // recognition, so it is deliberately not counted in either figure.
                return;
            }
            if (posting.baseAmount().isPositive()) {
                capitalised = capitalised.add(posting.baseAmount());
            } else {
                recognised = recognised.add(posting.baseAmount().negate());
            }
        }

        PrepaidLine toLine() {
            return new PrepaidLine(clientId, accountId, capitalised, recognised, scheduled);
        }
    }

    /** One instalment already in the journal, waiting for its business date to arrive. */
    public static final class ScheduledRecognition implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate businessDate;
        private final Amount amount;
        private final String entryId;
        private final String batchId;
        private final String description;

        ScheduledRecognition(LocalDate businessDate, Amount amount, String entryId, String batchId,
                String description) {
            this.businessDate = businessDate;
            this.amount = amount;
            this.entryId = entryId;
            this.batchId = batchId;
            this.description = description;
        }

        public LocalDate businessDate() {
            return businessDate;
        }

        public Amount amount() {
            return amount;
        }

        /** The entry that will recognise it — quotable because it already exists. */
        public String entryId() {
            return entryId;
        }

        /** The amortisation batch it came from, or null for a hand-written instalment. */
        public String batchId() {
            return batchId;
        }

        public String description() {
            return description;
        }
    }

    /** One prepayment: what is left on the balance sheet and what will take it off. */
    public static final class PrepaidLine implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String clientId;
        private final String accountId;
        private final Amount capitalised;
        private final Amount recognised;
        private final List<ScheduledRecognition> scheduled;

        PrepaidLine(String clientId, String accountId, Amount capitalised, Amount recognised,
                List<ScheduledRecognition> scheduled) {
            this.clientId = clientId;
            this.accountId = accountId;
            this.capitalised = capitalised;
            this.recognised = recognised;
            this.scheduled = Immutables.copyOf(scheduled);
        }

        public String clientId() {
            return clientId;
        }

        public String accountId() {
            return accountId;
        }

        /** Everything debited to this prepayment on or before the as-of date. */
        public Amount capitalised() {
            return capitalised;
        }

        /** Everything released to expense on or before the as-of date. */
        public Amount recognised() {
            return recognised;
        }

        /** What is still on the balance sheet. Equals the account balance for this 거래처. */
        public Amount unexpired() {
            return capitalised.subtract(recognised);
        }

        /** Instalments already posted and dated after the as-of date, oldest first. */
        public List<ScheduledRecognition> scheduled() {
            return scheduled;
        }

        public Amount scheduledTotal() {
            Amount total = Amount.ZERO;
            for (ScheduledRecognition recognition : scheduled) {
                total = total.add(recognition.amount());
            }
            return total;
        }

        /**
         * True when the posted schedule releases exactly what is left, and no more.
         *
         * <p>Not an error when false — a prepayment can legitimately be amortised by hand, or only
         * part-scheduled — but it is the question a reviewer asks first, so the report answers it
         * rather than making them subtract two columns for every row.
         */
        public boolean fullyScheduled() {
            return scheduledTotal().isEqualTo(unexpired());
        }

        /** The business date the last posted instalment falls on, or null when none is posted. */
        public LocalDate scheduledThrough() {
            return scheduled.isEmpty()
                    ? null
                    : scheduled.get(scheduled.size() - 1).businessDate();
        }
    }

    /** Prepayments as of a date, per account and counterparty. */
    public static final class PrepaidSchedules implements Serializable {
        private static final long serialVersionUID = 1L;

        private final LocalDate asOf;
        private final List<PrepaidLine> lines;
        private final Amount totalUnexpired;
        private final Amount totalScheduled;

        PrepaidSchedules(LocalDate asOf, List<PrepaidLine> lines, Amount totalUnexpired,
                Amount totalScheduled) {
            this.asOf = asOf;
            this.lines = Immutables.copyOf(lines);
            this.totalUnexpired = totalUnexpired;
            this.totalScheduled = totalScheduled;
        }

        public LocalDate asOf() {
            return asOf;
        }

        public List<PrepaidLine> lines() {
            return lines;
        }

        public Amount totalUnexpired() {
            return totalUnexpired;
        }

        public Amount totalScheduled() {
            return totalScheduled;
        }

        /**
         * Unexpired balance with no posted instalment behind it.
         *
         * <p>The figure somebody has to act on: a prepayment that nobody has set an amortisation
         * up for will sit on the balance sheet until it is noticed at the year end.
         */
        public Amount unscheduled() {
            return totalUnexpired.subtract(totalScheduled);
        }
    }
}
