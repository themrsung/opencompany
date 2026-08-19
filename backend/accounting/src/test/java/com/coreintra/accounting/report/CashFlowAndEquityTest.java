package com.coreintra.accounting.report;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two statements §9 names that the engine did not have: cash flow and changes in equity.
 *
 * <p>Both are pure functions over posted entries, so all of this runs in memory. What is being
 * held to here is not arithmetic - it is the same two rules the other reports keep: draft and
 * voided entries contribute nothing, closing batches are out of the income figure, and the
 * reconciliation flag is an alarm that reports a discrepancy rather than a plug that hides one.
 */
class CashFlowAndEquityTest {

    private static final LocalDate OPENING = LocalDate.of(2025, 12, 31);
    private static final LocalDate JAN = LocalDate.of(2026, 1, 15);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 15);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 15);
    private static final LocalDate YEAR_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 12, 31);

    /** Every closing batch in these tests is this one. */
    private static final LedgerReports.BatchKindLookup CLOSING =
            new LedgerReports.BatchKindLookup() {
                @Override
                public boolean isClosingBatch(String batchId) {
                    return "closing-batch".equals(batchId);
                }
            };

    private static Entry entry(String id, LocalDate on, String batchId, Posting... postings) {
        return Entry.post(id, "book", BusinessInstant.of(on, 9, 0, 0), "test",
                new ArrayList<Posting>(Arrays.asList(postings)), batchId);
    }

    /**
     * 1100 cash, 1200 receivables, 1500 prepaid, 1700 equipment, 2300 borrowings, 3100 capital,
     * 3900 retained earnings, 4100 revenue, 5100 salaries, 5900 tax.
     */
    private static ChartOfAccounts chart() {
        List<Account> accounts = new ArrayList<Account>();
        Map<String, ChartOfAccounts.Attributes> attributes =
                new LinkedHashMap<String, ChartOfAccounts.Attributes>();

        accounts.add(new Account("1100", null, "현금", "Cash", null, false));
        attributes.put("1100", new ChartOfAccounts.Attributes(null,
                ChartOfAccounts.Category.CASH));

        accounts.add(new Account("1200", null, "매출채권", "Receivables", null, false));
        attributes.put("1200", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.OPERATING, ChartOfAccounts.Category.RECEIVABLE));

        accounts.add(new Account("1700", null, "비품", "Equipment", null, false));
        attributes.put("1700", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.INVESTING,
                ChartOfAccounts.Category.OPERATING_ASSET));

        accounts.add(new Account("2300", null, "차입금", "Borrowings", null, false));
        attributes.put("2300", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.FINANCING, null));

        accounts.add(new Account("3100", null, "자본금", "Share capital", null, false));
        attributes.put("3100", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.FINANCING, null));

        accounts.add(new Account("3900", null, "이익잉여금", "Retained earnings", null, false));
        attributes.put("3900", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.FINANCING, null));

        accounts.add(new Account("4100", null, "매출", "Revenue", null, false));
        attributes.put("4100", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.OPERATING, null));

        accounts.add(new Account("5100", null, "급여", "Salaries", null, false));
        attributes.put("5100", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.OPERATING, null));

        accounts.add(new Account("5900", null, "법인세", "Corporate tax", null, false));
        attributes.put("5900", new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.TAX, null));

        // Left deliberately unclassified: the report has to say so rather than assume operating.
        accounts.add(new Account("5950", null, "잡비", "Sundry", null, false));

        return ChartOfAccounts.of(accounts, attributes);
    }

    /**
     * Cash 500,000 brought forward, then: a credit sale collected, a wage bill, equipment bought,
     * a loan drawn, tax paid.
     */
    private List<Entry> year() {
        List<Entry> entries = new ArrayList<Entry>();
        entries.add(entry("opening", OPENING, null,
                Posting.debit("1100", Amount.parse("500000")),
                Posting.credit("3100", Amount.parse("500000"))));
        entries.add(entry("sale", JAN, null,
                Posting.debit("1200", Amount.parse("1000000")),
                Posting.credit("4100", Amount.parse("1000000"))));
        entries.add(entry("collection", FEB, null,
                Posting.debit("1100", Amount.parse("1000000")),
                Posting.credit("1200", Amount.parse("1000000"))));
        entries.add(entry("wages", FEB, null,
                Posting.debit("5100", Amount.parse("300000")),
                Posting.credit("1100", Amount.parse("300000"))));
        entries.add(entry("equipment", FEB, null,
                Posting.debit("1700", Amount.parse("200000")),
                Posting.credit("1100", Amount.parse("200000"))));
        entries.add(entry("loan", MAR, null,
                Posting.debit("1100", Amount.parse("400000")),
                Posting.credit("2300", Amount.parse("400000"))));
        entries.add(entry("tax", MAR, null,
                Posting.debit("5900", Amount.parse("70000")),
                Posting.credit("1100", Amount.parse("70000"))));
        return entries;
    }

    @Nested
    @DisplayName("cash flow")
    class CashFlow {

        @Test
        @DisplayName("opening cash plus the classified sections equals closing cash, exactly")
        void reconciles() {
            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(year(), YEAR_START, YEAR_END, chart());

            assertThat(statement.openingCash()).isEqualTo(Amount.parse("500000"));
            assertThat(statement.closingCash()).isEqualTo(Amount.parse("1330000"));
            assertThat(statement.reconciles()).isTrue();
            assertThat(statement.discrepancy()).isEqualTo(Amount.ZERO);
            assertThat(statement.netMovement()).isEqualTo(Amount.parse("830000"));
        }

        @Test
        @DisplayName("each movement lands in the section of the account on the other side")
        void classifiesByCounterpart() {
            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(year(), YEAR_START, YEAR_END, chart());

            // Collecting a receivable and paying wages are both operating; the receivable is
            // classified operating and so is the salary account.
            assertThat(statement.section(LedgerReports.CashFlowSection.OPERATING))
                    .isEqualTo(Amount.parse("700000"));
            assertThat(statement.section(LedgerReports.CashFlowSection.INVESTING))
                    .as("equipment bought for cash")
                    .isEqualTo(Amount.parse("-200000"));
            assertThat(statement.section(LedgerReports.CashFlowSection.FINANCING))
                    .as("the loan drawn; the opening capital is before the period")
                    .isEqualTo(Amount.parse("400000"));
            assertThat(statement.section(LedgerReports.CashFlowSection.TAX))
                    .isEqualTo(Amount.parse("-70000"));
        }

        @Test
        @DisplayName("an entry that moves no cash contributes nothing")
        void nonCashEntriesAreIgnored() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("sale", JAN, null,
                    Posting.debit("1200", Amount.parse("1000000")),
                    Posting.credit("4100", Amount.parse("1000000"))));

            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(entries, YEAR_START, YEAR_END, chart());

            assertThat(statement.netMovement()).isEqualTo(Amount.ZERO);
            assertThat(statement.reconciles()).isTrue();
        }

        @Test
        @DisplayName("a movement with no classification is reported as unclassified, "
                + "never folded into operating")
        void unclassifiedIsNamed() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("sundry", JAN, null,
                    Posting.debit("5950", Amount.parse("12345")),
                    Posting.credit("1100", Amount.parse("12345"))));

            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(entries, YEAR_START, YEAR_END, chart());

            assertThat(statement.section(LedgerReports.CashFlowSection.UNCLASSIFIED))
                    .isEqualTo(Amount.parse("-12345"));
            assertThat(statement.section(LedgerReports.CashFlowSection.OPERATING))
                    .as("silently calling it operating would make the statement lie neatly")
                    .isEqualTo(Amount.ZERO);
            assertThat(statement.reconciles()).isTrue();
        }

        @Test
        @DisplayName("voided and draft entries move no cash")
        void excludesVoidAndDraft() {
            List<Entry> entries = year();
            Entry mistake = entry("mistake", MAR, null,
                    Posting.debit("5100", Amount.parse("999999")),
                    Posting.credit("1100", Amount.parse("999999")));
            mistake.voidEntry("중복", "acc-1", BusinessInstant.of(MAR, 10, 0, 0));
            entries.add(mistake);
            entries.add(Entry.draft("draft", "book", BusinessInstant.of(MAR, 9, 0, 0), "작성중",
                    Immutables.listOf(Posting.debit("5100", Amount.parse("888888")),
                            Posting.credit("1100", Amount.parse("888888")))));

            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(entries, YEAR_START, YEAR_END, chart());

            assertThat(statement.closingCash()).isEqualTo(Amount.parse("1330000"));
            assertThat(statement.section(LedgerReports.CashFlowSection.OPERATING))
                    .isEqualTo(Amount.parse("700000"));
        }

        @Test
        @DisplayName("cash moved between two cash accounts nets to nothing and is not a section")
        void cashToCashIsInvisible() {
            List<Account> accounts = new ArrayList<Account>();
            Map<String, ChartOfAccounts.Attributes> attributes =
                    new LinkedHashMap<String, ChartOfAccounts.Attributes>();
            accounts.add(new Account("1100", null, "현금", "Cash", null, false));
            attributes.put("1100", new ChartOfAccounts.Attributes(null,
                    ChartOfAccounts.Category.CASH));
            accounts.add(new Account("1110", null, "보통예금", "Bank", null, false));
            attributes.put("1110", new ChartOfAccounts.Attributes(null,
                    ChartOfAccounts.Category.CASH_EQUIVALENT));
            ChartOfAccounts twoCashAccounts = ChartOfAccounts.of(accounts, attributes);

            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("deposit", JAN, null,
                    Posting.debit("1110", Amount.parse("100000")),
                    Posting.credit("1100", Amount.parse("100000"))));

            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(entries, YEAR_START, YEAR_END, twoCashAccounts);

            assertThat(statement.netMovement()).isEqualTo(Amount.ZERO);
            assertThat(statement.closingCash()).isEqualTo(Amount.ZERO);
            assertThat(statement.reconciles()).isTrue();
        }

        @Test
        @DisplayName("the period boundaries are respected on both sides")
        void respectsThePeriod() {
            LedgerReports.CashFlowStatement february = LedgerReports.cashFlow(year(),
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), chart());

            assertThat(february.openingCash())
                    .as("January moved no cash, so February opens on the brought-forward figure")
                    .isEqualTo(Amount.parse("500000"));
            assertThat(february.closingCash()).isEqualTo(Amount.parse("1000000"));
            assertThat(february.reconciles()).isTrue();
            assertThat(february.section(LedgerReports.CashFlowSection.FINANCING))
                    .as("March's loan is not February's cash").isEqualTo(Amount.ZERO);
        }
    }

    @Nested
    @DisplayName("statement of changes in equity")
    class Equity {

        @Test
        @DisplayName("opening plus the period's movements equals closing, and the equation holds")
        void rollsForward() {
            LedgerReports.EquityStatement statement =
                    LedgerReports.equityStatement(year(), YEAR_START, YEAR_END, CLOSING);

            assertThat(statement.openingEquity())
                    .as("the capital introduced on 31 December is opening equity, not a movement")
                    .isEqualTo(Amount.parse("500000"));
            assertThat(statement.netIncome())
                    .as("1,000,000 revenue less 300,000 wages and 70,000 tax")
                    .isEqualTo(Amount.parse("630000"));
            assertThat(statement.ownerMovements()).isEqualTo(Amount.ZERO);
            assertThat(statement.closingAdjustments()).isEqualTo(Amount.ZERO);
            assertThat(statement.closingEquity()).isEqualTo(Amount.parse("1130000"));
            assertThat(statement.isBalanced())
                    .as("assets minus liabilities, computed independently, agrees")
                    .isTrue();
            assertThat(statement.imbalance()).isEqualTo(Amount.ZERO);
        }

        @Test
        @DisplayName("capital introduced in the period is an owner movement, not income")
        void ownerMovementsAreTheirOwnLine() {
            List<Entry> entries = year();
            entries.add(entry("issue", MAR, null,
                    Posting.debit("1100", Amount.parse("250000")),
                    Posting.credit("3100", Amount.parse("250000"))));

            LedgerReports.EquityStatement statement =
                    LedgerReports.equityStatement(entries, YEAR_START, YEAR_END, CLOSING);

            assertThat(statement.ownerMovements()).isEqualTo(Amount.parse("250000"));
            assertThat(statement.netIncome())
                    .as("issuing shares is not trading profitably")
                    .isEqualTo(Amount.parse("630000"));
            assertThat(statement.closingEquity()).isEqualTo(Amount.parse("1380000"));
            assertThat(statement.isBalanced()).isTrue();
        }

        @Test
        @DisplayName("closing the year moves the result into retained earnings "
                + "and leaves total equity exactly where it was")
        void closingTransferIsNeutralAndVisible() {
            List<Entry> entries = year();
            LedgerReports.EquityStatement before =
                    LedgerReports.equityStatement(entries, YEAR_START, YEAR_END, CLOSING);

            entries.add(entry("close", YEAR_END, "closing-batch",
                    Posting.debit("4100", Amount.parse("1000000")),
                    Posting.credit("5100", Amount.parse("300000")),
                    Posting.credit("5900", Amount.parse("70000")),
                    Posting.credit("3900", Amount.parse("630000"))));

            LedgerReports.EquityStatement after =
                    LedgerReports.equityStatement(entries, YEAR_START, YEAR_END, CLOSING);

            assertThat(after.netIncome())
                    .as("a closed year still reports what it earned")
                    .isEqualTo(Amount.parse("630000"));
            assertThat(after.transferredToEquityByClosing())
                    .as("the gross figure is still visible to a reader")
                    .isEqualTo(Amount.parse("630000"));
            assertThat(after.closingAdjustments())
                    .as("but it changed nothing: closing reclassifies equity, it does not create "
                            + "it, and counting it as a movement would report the year twice")
                    .isEqualTo(Amount.ZERO);
            assertThat(after.closingEquity())
                    .as("so total equity is what it was before the books were closed")
                    .isEqualTo(before.closingEquity());
            assertThat(after.isBalanced())
                    .as("and the accounting equation still holds afterwards")
                    .isTrue();
        }

        @Test
        @DisplayName("a closing batch takes the result off the income accounts, "
                + "so the balance sheet's unclosed line goes to zero and it still balances")
        void balanceSheetAfterClosing() {
            List<Entry> entries = year();
            entries.add(entry("close", YEAR_END, "closing-batch",
                    Posting.debit("4100", Amount.parse("1000000")),
                    Posting.credit("5100", Amount.parse("300000")),
                    Posting.credit("5900", Amount.parse("70000")),
                    Posting.credit("3900", Amount.parse("630000"))));

            LedgerReports.BalanceSheet sheet = LedgerReports.balanceSheet(entries, YEAR_END);

            assertThat(sheet.unclosedNetIncome())
                    .as("nothing is left unclosed once it has been closed — that is what the "
                            + "line means, and excluding closing batches from it would leave the "
                            + "same profit reported in equity and here at the same time")
                    .isEqualTo(Amount.ZERO);
            assertThat(sheet.equity()).isEqualTo(Amount.parse("1130000"));
            assertThat(sheet.isBalanced())
                    .as("the integrity alarm must not fire on 1 January of every year")
                    .isTrue();
        }

        @Test
        @DisplayName("voided and draft entries change no equity")
        void excludesVoidAndDraft() {
            List<Entry> entries = year();
            Entry mistake = entry("mistake", MAR, null,
                    Posting.debit("1100", Amount.parse("111111")),
                    Posting.credit("3100", Amount.parse("111111")));
            mistake.voidEntry("잘못", "acc-1", BusinessInstant.of(MAR, 10, 0, 0));
            entries.add(mistake);
            entries.add(Entry.draft("draft", "book", BusinessInstant.of(MAR, 9, 0, 0), "작성중",
                    Immutables.listOf(Posting.debit("1100", Amount.parse("222222")),
                            Posting.credit("4100", Amount.parse("222222")))));

            LedgerReports.EquityStatement statement =
                    LedgerReports.equityStatement(entries, YEAR_START, YEAR_END, CLOSING);

            assertThat(statement.closingEquity()).isEqualTo(Amount.parse("1130000"));
            assertThat(statement.isBalanced()).isTrue();
        }

        @Test
        @DisplayName("closing equity always agrees with the balance sheet's equity section")
        void agreesWithTheBalanceSheet() {
            List<Entry> entries = year();
            LedgerReports.BalanceSheet sheet = LedgerReports.balanceSheet(entries, YEAR_END);
            LedgerReports.EquityStatement statement =
                    LedgerReports.equityStatement(entries, YEAR_START, YEAR_END, CLOSING);

            assertThat(statement.closingEquity())
                    .as("two reports, one ledger; a difference here means one of them is derived")
                    .isEqualTo(sheet.equity().add(sheet.unclosedNetIncome()));
            assertThat(sheet.isBalanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("an account missing from the chart is visible, not silently cash or operating")
    class MissingFromTheChart {

        @Test
        @DisplayName("a movement against an unknown account is reported as unclassified")
        void unknownAccountIsUnclassified() {
            // The chart handed to the report omits 1110 entirely - a report run against a chart
            // snapshot that predates the account, which is exactly what an as-of report does.
            List<Account> accounts = new ArrayList<Account>();
            Map<String, ChartOfAccounts.Attributes> attributes =
                    new LinkedHashMap<String, ChartOfAccounts.Attributes>();
            accounts.add(new Account("1100", null, "현금", "Cash", null, false));
            attributes.put("1100", new ChartOfAccounts.Attributes(null,
                    ChartOfAccounts.Category.CASH));
            ChartOfAccounts partial = ChartOfAccounts.of(accounts, attributes);

            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("deposit", JAN, null,
                    Posting.debit("1110", Amount.parse("100000")),
                    Posting.credit("1100", Amount.parse("100000"))));

            LedgerReports.CashFlowStatement statement =
                    LedgerReports.cashFlow(entries, YEAR_START, YEAR_END, partial);

            assertThat(statement.section(LedgerReports.CashFlowSection.UNCLASSIFIED))
                    .as("the money left 1100 and the report says where it cannot account for it")
                    .isEqualTo(Amount.parse("-100000"));
            assertThat(statement.reconciles())
                    .as("classified as unclassified still reconciles; dropping it would not")
                    .isTrue();
        }
    }

    /**
     * The flags themselves.
     *
     * <p>An unbalanced {@link Entry} cannot be constructed, and both loaders re-prove the
     * invariant on the way in, so no list assembled through the public API can make these flags
     * fire. That is the design working. What is being tested here is therefore the flag's own
     * behaviour when it <em>is</em> handed inconsistent figures — the state a bad migration or a
     * hand-written SQL fix leaves behind — and the property that matters is that it reports the
     * difference rather than adjusting a line to swallow it. The report objects are constructed
     * directly, which this test can do because it sits in the same package.
     */
    @Nested
    @DisplayName("the reconciliation flags are alarms, not plugs")
    class Alarms {

        @Test
        @DisplayName("a cash-flow statement that does not add up says so, and changes no line")
        void cashFlowReportsADiscrepancy() {
            Map<LedgerReports.CashFlowSection, Amount> sections =
                    new LinkedHashMap<LedgerReports.CashFlowSection, Amount>();
            sections.put(LedgerReports.CashFlowSection.OPERATING, Amount.parse("100000"));

            LedgerReports.CashFlowStatement statement = new LedgerReports.CashFlowStatement(
                    YEAR_START, YEAR_END, Amount.parse("500000"), Amount.parse("650000"), sections);

            assertThat(statement.reconciles()).isFalse();
            assertThat(statement.discrepancy())
                    .as("the size of the problem, so somebody can go and find it")
                    .isEqualTo(Amount.parse("50000"));
            assertThat(statement.netMovement())
                    .as("and the sections are left exactly as computed — no plug line appears")
                    .isEqualTo(Amount.parse("100000"));
            assertThat(statement.closingCash()).isEqualTo(Amount.parse("650000"));
        }

        @Test
        @DisplayName("an equity roll-forward that disagrees with the equation says so")
        void equityReportsAnImbalance() {
            LedgerReports.EquityStatement statement = new LedgerReports.EquityStatement(
                    YEAR_START, YEAR_END,
                    Amount.parse("500000"), Amount.parse("630000"), Amount.ZERO, Amount.ZERO,
                    Amount.ZERO,
                    Amount.parse("1580000"), Amount.parse("400000"));

            assertThat(statement.closingEquity()).isEqualTo(Amount.parse("1130000"));
            assertThat(statement.isBalanced()).isFalse();
            assertThat(statement.imbalance())
                    .as("assets less liabilities exceed the rolled-forward equity by this much")
                    .isEqualTo(Amount.parse("50000"));
            assertThat(statement.openingEquity())
                    .as("and no line was quietly adjusted to close the gap")
                    .isEqualTo(Amount.parse("500000"));
        }

        @Test
        @DisplayName("a balance sheet whose sides differ reports the difference")
        void balanceSheetReportsAnImbalance() {
            LedgerReports.BalanceSheet sheet = new LedgerReports.BalanceSheet(YEAR_END,
                    Amount.parse("1000000"), Amount.parse("400000"), Amount.parse("500000"),
                    Amount.parse("60000"));

            assertThat(sheet.isBalanced()).isFalse();
            assertThat(sheet.imbalance()).isEqualTo(Amount.parse("40000"));
            assertThat(sheet.unclosedNetIncome())
                    .as("the one derived line is what it was computed to be, not a plug")
                    .isEqualTo(Amount.parse("60000"));
        }
    }
}
