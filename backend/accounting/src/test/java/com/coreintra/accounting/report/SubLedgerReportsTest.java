package com.coreintra.accounting.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The 거래처 sub-ledger: ageing and prepaid schedules, read off the general ledger.
 *
 * <p>Both reports exist because the dimension was already stored on every posting and drove
 * nothing. What is worth holding to here is that they agree with the ledger rather than with a
 * second table: the ageing总 for an account is its balance, and a prepayment's unexpired figure is
 * the balance on the prepaid account. If either could drift, the report would be a third opinion.
 */
class SubLedgerReportsTest {

    private static final LocalDate DEC_LAST_YEAR = LocalDate.of(2025, 12, 1);
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MAR_20 = LocalDate.of(2026, 3, 20);
    private static final LocalDate APR_1 = LocalDate.of(2026, 4, 1);

    private static final String ALPHA = "client-alpha";
    private static final String BETA = "client-beta";
    private static final String GAMMA = "client-gamma";

    private static Entry entry(String id, LocalDate on, String batchId, Posting... postings) {
        return Entry.post(id, "book", BusinessInstant.of(on, 9, 0, 0), "test",
                new ArrayList<Posting>(Arrays.asList(postings)), batchId);
    }

    /** 1100 cash, 1200 receivables, 1500 prepaid, 4100 revenue, 5100 expense. */
    private static ChartOfAccounts chart() {
        List<Account> accounts = new ArrayList<Account>();
        Map<String, ChartOfAccounts.Attributes> attributes =
                new LinkedHashMap<String, ChartOfAccounts.Attributes>();

        accounts.add(new Account("1100", null, "현금", "Cash", null, false));
        attributes.put("1100", new ChartOfAccounts.Attributes(null,
                ChartOfAccounts.Category.CASH));
        // The category is set on the parent and inherited, which is how a real chart is marked up.
        accounts.add(new Account("1200", null, "채권", "Receivables", null, false));
        attributes.put("1200", new ChartOfAccounts.Attributes(null,
                ChartOfAccounts.Category.RECEIVABLE));
        accounts.add(new Account("1210", "1200", "외상매출금", "Trade receivables", null, false));
        accounts.add(new Account("1500", null, "선급비용", "Prepaid expenses", null, false));
        attributes.put("1500", new ChartOfAccounts.Attributes(null,
                ChartOfAccounts.Category.PREPAID_EXPENSE));
        accounts.add(new Account("4100", null, "매출", "Revenue", null, false));
        accounts.add(new Account("5100", null, "보험료", "Insurance", null, false));

        return ChartOfAccounts.of(accounts, attributes);
    }

    private static Posting receivable(String amount, String clientId) {
        Posting posting = Posting.debit("1210", Amount.parse(amount));
        return clientId == null ? posting : posting.withClient(clientId);
    }

    private static Posting settlement(String amount, String clientId) {
        Posting posting = Posting.credit("1210", Amount.parse(amount));
        return clientId == null ? posting : posting.withClient(clientId);
    }

    @Nested
    @DisplayName("receivables ageing")
    class Ageing {

        /**
         * Alpha: invoiced 100,000 on 1 January and 50,000 on 1 March, paid 120,000 on 20 March.
         * Beta: invoiced 200,000 on 1 February, unpaid. Gamma: invoiced 50,000 last December,
         * unpaid. One 10,000 invoice with no 거래처 at all.
         */
        private List<Entry> ledger() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("a1", JAN_1, null, receivable("100000", ALPHA),
                    Posting.credit("4100", Amount.parse("100000"))));
            entries.add(entry("a2", MAR_1, null, receivable("50000", ALPHA),
                    Posting.credit("4100", Amount.parse("50000"))));
            entries.add(entry("a3", MAR_20, null, Posting.debit("1100", Amount.parse("120000")),
                    settlement("120000", ALPHA)));
            entries.add(entry("b1", FEB_1, null, receivable("200000", BETA),
                    Posting.credit("4100", Amount.parse("200000"))));
            entries.add(entry("g1", DEC_LAST_YEAR, null, receivable("50000", GAMMA),
                    Posting.credit("4100", Amount.parse("50000"))));
            entries.add(entry("u1", MAR_20, null, receivable("10000", null),
                    Posting.credit("4100", Amount.parse("10000"))));
            return entries;
        }

        private SubLedgerReports.AgeingLine lineFor(SubLedgerReports.ReceivablesAgeing report,
                String clientId) {
            for (SubLedgerReports.AgeingLine line : report.lines()) {
                if (clientId == null ? line.clientId() == null : clientId.equals(line.clientId())) {
                    return line;
                }
            }
            throw new AssertionError("no line for " + clientId + " in " + report.lines().size()
                    + " lines");
        }

        @Test
        @DisplayName("settlement is applied oldest first, so the age of what is left is right")
        void oldestFirst() {
            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(ledger(), APR_1, chart());

            SubLedgerReports.AgeingLine alpha = lineFor(report, ALPHA);
            assertThat(alpha.outstanding())
                    .as("150,000 invoiced, 120,000 paid")
                    .isEqualTo(Amount.parse("30000"));
            assertThat(alpha.buckets().get(1))
                    .as("what is left is the 1 March invoice, 31 days old, not the January one")
                    .isEqualTo(Amount.parse("30000"));
            assertThat(alpha.buckets().get(2))
                    .as("the January invoice is settled and must not still be ageing")
                    .isEqualTo(Amount.ZERO);
            assertThat(alpha.oldestItemDays()).isEqualTo(31L);
        }

        @Test
        @DisplayName("each item falls in the band its own business date puts it in")
        void bandsByAge() {
            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(ledger(), APR_1, chart());

            assertThat(report.bandDays()).containsExactly(
                    Integer.valueOf(30), Integer.valueOf(60), Integer.valueOf(90));
            assertThat(lineFor(report, BETA).buckets())
                    .as("59 days old: the second band")
                    .containsExactly(Amount.ZERO, Amount.parse("200000"), Amount.ZERO,
                            Amount.ZERO);
            assertThat(lineFor(report, GAMMA).buckets().get(3))
                    .as("121 days old: past the last band")
                    .isEqualTo(Amount.parse("50000"));
            assertThat(report.total()).isEqualTo(Amount.parse("290000"));
        }

        @Test
        @DisplayName("outstanding on postings with no 거래처 is reported, never dropped")
        void unassignedIsNamed() {
            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(ledger(), APR_1, chart());

            assertThat(report.unassigned())
                    .as("omitting untagged lines would understate the debt and give no clue why")
                    .isEqualTo(Amount.parse("10000"));
            assertThat(lineFor(report, null).outstanding()).isEqualTo(Amount.parse("10000"));
        }

        @Test
        @DisplayName("the total is the account balance, so the report cannot drift from the ledger")
        void agreesWithTheLedger() {
            List<Entry> entries = ledger();
            Account receivables = new Account("1210", "1200", "외상매출금", "Trade receivables",
                    null, false);

            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(entries, APR_1, chart());

            assertThat(report.total().subtract(report.totalUnappliedCredits()))
                    .as("there is no sub-ledger to reconcile: this is the same rows, grouped")
                    .isEqualTo(LedgerReports.balanceOf(entries, receivables, APR_1));
        }

        @Test
        @DisplayName("an overpayment is reported as an unapplied credit, not a negative age")
        void overpaymentIsNotAged() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("c1", JAN_1, null, receivable("50000", GAMMA),
                    Posting.credit("4100", Amount.parse("50000"))));
            entries.add(entry("c2", FEB_1, null, Posting.debit("1100", Amount.parse("70000")),
                    settlement("70000", GAMMA)));

            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(entries, APR_1, chart());

            assertThat(report.total()).isEqualTo(Amount.ZERO);
            assertThat(report.totalUnappliedCredits())
                    .as("a deposit or an overpayment is a real thing and reads as one")
                    .isEqualTo(Amount.parse("20000"));
            for (Amount bucket : report.lines().get(0).buckets()) {
                assertThat(bucket)
                        .as("a negative figure in an ageing column means nothing to a reader")
                        .isEqualTo(Amount.ZERO);
            }
        }

        @Test
        @DisplayName("voided and draft entries are owed by nobody")
        void excludesVoidAndDraft() {
            List<Entry> entries = ledger();
            Entry mistake = entry("m1", MAR_1, null, receivable("999999", BETA),
                    Posting.credit("4100", Amount.parse("999999")));
            mistake.voidEntry("중복 청구", "acc-1", BusinessInstant.of(MAR_20, 10, 0, 0));
            entries.add(mistake);
            entries.add(Entry.draft("d1", "book", BusinessInstant.of(MAR_1, 9, 0, 0), "작성중",
                    Immutables.listOf(receivable("888888", BETA),
                            Posting.credit("4100", Amount.parse("888888")))));

            SubLedgerReports.ReceivablesAgeing report =
                    SubLedgerReports.receivablesAgeing(entries, APR_1, chart());

            assertThat(report.total()).isEqualTo(Amount.parse("290000"));
        }

        @Test
        @DisplayName("the answer does not depend on the order the entries arrive in")
        void orderIndependent() {
            List<Entry> forwards = ledger();
            List<Entry> backwards = new ArrayList<Entry>(ledger());
            Collections.reverse(backwards);

            assertThat(SubLedgerReports.receivablesAgeing(backwards, APR_1, chart()).total())
                    .as("oldest-first settlement is only oldest-first if the report sorts")
                    .isEqualTo(SubLedgerReports.receivablesAgeing(forwards, APR_1, chart())
                            .total());
            assertThat(SubLedgerReports.receivablesAgeing(backwards, APR_1, chart())
                    .lines().size()).isEqualTo(4);
        }

        @Test
        @DisplayName("the bands are a parameter, and overlapping ones are refused")
        void bandsAreAParameter() {
            SubLedgerReports.ReceivablesAgeing onFortyFiveDayTerms =
                    SubLedgerReports.receivablesAgeing(ledger(), APR_1, chart(),
                            Immutables.listOf(Integer.valueOf(45), Integer.valueOf(90)));

            assertThat(onFortyFiveDayTerms.bandDays()).hasSize(2);
            assertThat(lineFor(onFortyFiveDayTerms, BETA).buckets().get(1))
                    .as("59 days is over 45 and under 90")
                    .isEqualTo(Amount.parse("200000"));

            assertThatThrownBy(() -> SubLedgerReports.receivablesAgeing(ledger(), APR_1, chart(),
                    Immutables.listOf(Integer.valueOf(60), Integer.valueOf(30))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ascending");
        }

        @Test
        @DisplayName("an account that is not marked a receivable is not aged")
        void onlyReceivableAccounts() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("p1", JAN_1, null,
                    Posting.debit("1500", Amount.parse("120000")).withClient(ALPHA),
                    Posting.credit("1100", Amount.parse("120000"))));

            assertThat(SubLedgerReports.receivablesAgeing(entries, APR_1, chart()).lines())
                    .as("a prepayment is not a debt owed to us")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("prepaid schedules")
    class Prepaid {

        /** 1,200,000 prepaid on 1 January, released 100,000 at the end of each month. */
        private List<Entry> ledger() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("p0", JAN_1, null,
                    Posting.debit("1500", Amount.parse("1200000")).withClient(ALPHA),
                    Posting.credit("1100", Amount.parse("1200000"))));
            for (int month = 1; month <= 12; month++) {
                LocalDate on = LocalDate.of(2026, month, 1).withDayOfMonth(
                        LocalDate.of(2026, month, 1).lengthOfMonth());
                entries.add(entry("amort-" + month, on, "amortisation-batch",
                        Posting.debit("5100", Amount.parse("100000")),
                        Posting.credit("1500", Amount.parse("100000")).withClient(ALPHA)));
            }
            return entries;
        }

        @Test
        @DisplayName("capitalised, recognised and unexpired are what the account says they are")
        void unexpiredIsTheBalance() {
            LocalDate endOfMarch = LocalDate.of(2026, 3, 31);
            SubLedgerReports.PrepaidSchedules report =
                    SubLedgerReports.prepaidSchedules(ledger(), endOfMarch, chart());

            assertThat(report.lines()).hasSize(1);
            SubLedgerReports.PrepaidLine line = report.lines().get(0);
            assertThat(line.capitalised()).isEqualTo(Amount.parse("1200000"));
            assertThat(line.recognised())
                    .as("January, February and March have been released")
                    .isEqualTo(Amount.parse("300000"));
            assertThat(line.unexpired()).isEqualTo(Amount.parse("900000"));
            assertThat(line.clientId()).isEqualTo(ALPHA);

            Account prepaid = new Account("1500", null, "선급비용", "Prepaid expenses", null, false);
            assertThat(line.unexpired())
                    .as("and it is the account balance, not a parallel calculation")
                    .isEqualTo(LedgerReports.balanceOf(ledger(), prepaid, endOfMarch));
        }

        @Test
        @DisplayName("the remaining instalments are entries that already exist, quoted by id")
        void scheduledInstalmentsAreRealEntries() {
            SubLedgerReports.PrepaidSchedules report = SubLedgerReports.prepaidSchedules(
                    ledger(), LocalDate.of(2026, 3, 31), chart());
            SubLedgerReports.PrepaidLine line = report.lines().get(0);

            assertThat(line.scheduled()).hasSize(9);
            assertThat(line.scheduledTotal()).isEqualTo(Amount.parse("900000"));
            assertThat(line.fullyScheduled())
                    .as("the posted schedule releases exactly what is left")
                    .isTrue();
            assertThat(line.scheduledThrough()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(line.scheduled().get(0).entryId())
                    .as("nothing is forecast here: every instalment is already in the journal")
                    .isEqualTo("amort-4");
            assertThat(line.scheduled().get(0).batchId()).isEqualTo("amortisation-batch");
            assertThat(report.unscheduled()).isEqualTo(Amount.ZERO);
        }

        @Test
        @DisplayName("a prepayment nobody has set up an amortisation for shows an empty schedule")
        void unscheduledPrepaymentIsVisible() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("p0", JAN_1, null,
                    Posting.debit("1500", Amount.parse("600000")).withClient(BETA),
                    Posting.credit("1100", Amount.parse("600000"))));

            SubLedgerReports.PrepaidSchedules report =
                    SubLedgerReports.prepaidSchedules(entries, APR_1, chart());

            assertThat(report.lines().get(0).scheduled()).isEmpty();
            assertThat(report.lines().get(0).fullyScheduled()).isFalse();
            assertThat(report.unscheduled())
                    .as("the figure somebody has to act on before the year end finds it")
                    .isEqualTo(Amount.parse("600000"));
        }

        @Test
        @DisplayName("voided instalments are neither recognised nor scheduled")
        void excludesVoidAndDraft() {
            List<Entry> entries = ledger();
            Entry mistake = entry("m1", LocalDate.of(2026, 2, 28), "amortisation-batch",
                    Posting.debit("5100", Amount.parse("100000")),
                    Posting.credit("1500", Amount.parse("100000")).withClient(ALPHA));
            mistake.voidEntry("중복 상각", "acc-1", BusinessInstant.of(MAR_1, 10, 0, 0));
            entries.add(mistake);

            SubLedgerReports.PrepaidSchedules report = SubLedgerReports.prepaidSchedules(
                    entries, LocalDate.of(2026, 3, 31), chart());

            assertThat(report.lines().get(0).recognised()).isEqualTo(Amount.parse("300000"));
            assertThat(report.totalUnexpired()).isEqualTo(Amount.parse("900000"));
        }
    }
}
