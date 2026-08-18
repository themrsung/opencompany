package com.coreintra.accounting.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LedgerReportsTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 15);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 15);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 31);

    private static Entry entry(String id, LocalDate on, String batchId, Posting... postings) {
        return Entry.post(id, "book", BusinessInstant.of(on, 9, 0, 0), "test", batchId == null
                ? new ArrayList<Posting>(Arrays.asList(postings))
                : new ArrayList<Posting>(Arrays.asList(postings)), batchId);
    }

    /** Sale of 1,000,000 on credit, then cash collected. */
    private List<Entry> sampleLedger() {
        List<Entry> entries = new ArrayList<Entry>();
        entries.add(entry("e1", JAN, null,
                Posting.debit("1200", Amount.parse("1000000")),
                Posting.credit("4100", Amount.parse("1000000"))));
        entries.add(entry("e2", FEB, null,
                Posting.debit("1100", Amount.parse("1000000")),
                Posting.credit("1200", Amount.parse("1000000"))));
        entries.add(entry("e3", FEB, null,
                Posting.debit("5100", Amount.parse("300000")),
                Posting.credit("1100", Amount.parse("300000"))));
        return entries;
    }

    @Nested
    @DisplayName("trial balance")
    class TrialBalance {

        @Test
        @DisplayName("balances, and the flag is an integrity alarm rather than a plug")
        void balances() {
            LedgerReports.TrialBalance balance =
                    LedgerReports.trialBalance(sampleLedger(), DEC);

            assertThat(balance.isBalanced()).isTrue();
            assertThat(balance.imbalance()).isEqualTo(Amount.ZERO);
            assertThat(balance.totalDebits()).isEqualTo(Amount.parse("2300000"));
            assertThat(balance.totalCredits()).isEqualTo(Amount.parse("2300000"));
        }

        @Test
        @DisplayName("respects the as-of date")
        void asOfDate() {
            LedgerReports.TrialBalance january =
                    LedgerReports.trialBalance(sampleLedger(), LocalDate.of(2026, 1, 31));
            assertThat(january.totalDebits()).isEqualTo(Amount.parse("1000000"));
        }

        @Test
        @DisplayName("voided and draft entries contribute nothing")
        void excludesVoidAndDraft() {
            List<Entry> entries = sampleLedger();
            entries.get(2).voidEntry("잘못 입력", "acc-1", BusinessInstant.of(FEB, 10, 0, 0));
            entries.add(Entry.draft("e4", "book", BusinessInstant.of(FEB, 9, 0, 0), "작성중",
                    new ArrayList<Posting>(Arrays.asList(
                            Posting.debit("5100", Amount.parse("999")),
                            Posting.credit("1100", Amount.parse("999"))))));

            LedgerReports.TrialBalance balance = LedgerReports.trialBalance(entries, DEC);
            assertThat(balance.totalDebits())
                    .as("the voided 300,000 and the draft 999 are both excluded")
                    .isEqualTo(Amount.parse("2000000"));
            assertThat(balance.isBalanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("account balances")
    class Balances {

        @Test
        @DisplayName("a credit-normal account reports a positive balance in the ordinary case")
        void creditNormalIsPositive() {
            Account revenue = new Account("4100", "4000", "매출", "Revenue", null, false);
            assertThat(LedgerReports.balanceOf(sampleLedger(), revenue, DEC))
                    .isEqualTo(Amount.parse("1000000"));
        }

        @Test
        @DisplayName("a debit-normal account nets across entries")
        void debitNormalNets() {
            Account cash = new Account("1100", "1000", "현금", "Cash", null, false);
            // Received 1,000,000, spent 300,000.
            assertThat(LedgerReports.balanceOf(sampleLedger(), cash, DEC))
                    .isEqualTo(Amount.parse("700000"));
        }

        @Test
        @DisplayName("a contra account reports on its own normal side")
        void contraAccountDirection() {
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("d1", FEB, null,
                    Posting.debit("5300", Amount.parse("100000")),
                    Posting.credit("1590", Amount.parse("100000"))));

            Account accumulated = new Account("1590", "1500", "감가상각누계액",
                    "Accumulated depreciation", null, true);
            assertThat(LedgerReports.balanceOf(entries, accumulated, DEC))
                    .as("a contra asset is credit-normal, so its balance reads positive")
                    .isEqualTo(Amount.parse("100000"));
        }
    }

    @Nested
    @DisplayName("income statement")
    class Income {

        @Test
        @DisplayName("reports income less expense for the period")
        void netIncome() {
            LedgerReports.IncomeStatement statement = LedgerReports.incomeStatement(
                    sampleLedger(), LocalDate.of(2026, 1, 1), DEC, null);

            assertThat(statement.income()).isEqualTo(Amount.parse("1000000"));
            assertThat(statement.expense()).isEqualTo(Amount.parse("300000"));
            assertThat(statement.netIncome()).isEqualTo(Amount.parse("700000"));
        }

        @Test
        @DisplayName("a closed year still reports what it earned")
        void closingBatchesExcluded() {
            // Including the closing entries would net income to zero and make
            // the year look as though nothing happened.
            List<Entry> entries = sampleLedger();
            entries.add(entry("close", DEC, "batch-closing",
                    Posting.debit("4100", Amount.parse("1000000")),
                    Posting.credit("3200", Amount.parse("1000000"))));

            LedgerReports.BatchKindLookup closing = new LedgerReports.BatchKindLookup() {
                @Override
                public boolean isClosingBatch(String batchId) {
                    return "batch-closing".equals(batchId);
                }
            };

            assertThat(LedgerReports.incomeStatement(entries, LocalDate.of(2026, 1, 1), DEC, closing)
                    .netIncome())
                    .as("the closed year still reports its 700,000")
                    .isEqualTo(Amount.parse("700000"));

            LedgerReports.IncomeStatement unfiltered =
                    LedgerReports.incomeStatement(entries, LocalDate.of(2026, 1, 1), DEC, null);
            assertThat(unfiltered.income())
                    .as("the closing entry cancels the year's revenue")
                    .isEqualTo(Amount.ZERO);
            assertThat(unfiltered.netIncome())
                    .as("so without the closing filter a profitable year reports a loss of its "
                            + "own expenses — which is why closing batches are excluded")
                    .isEqualTo(Amount.parse("-300000"));
        }
    }

    @Nested
    @DisplayName("balance sheet")
    class Balance {

        @Test
        @DisplayName("balances, with net income shown as its own labelled line")
        void balancesWithUnclosedIncome() {
            LedgerReports.BalanceSheet sheet =
                    LedgerReports.balanceSheet(sampleLedger(), DEC, null);

            assertThat(sheet.assets()).isEqualTo(Amount.parse("700000"));
            assertThat(sheet.unclosedNetIncome())
                    .as("the only derived line, and it is visible rather than folded into equity")
                    .isEqualTo(Amount.parse("700000"));
            assertThat(sheet.isBalanced()).isTrue();
            assertThat(sheet.imbalance()).isEqualTo(Amount.ZERO);
        }

        @Test
        @DisplayName("the balanced flag reports an imbalance instead of hiding it")
        void imbalanceIsReportedNotPlugged() {
            // Simulates data that reached the tables without passing through
            // Entry — a bad migration or a manual SQL fix. The report must say
            // so rather than insert a figure to look right.
            List<Entry> entries = new ArrayList<Entry>();
            entries.add(entry("e1", JAN, null,
                    Posting.debit("1100", Amount.parse("1000")),
                    Posting.credit("4100", Amount.parse("1000"))));

            LedgerReports.BalanceSheet sheet = LedgerReports.balanceSheet(entries, DEC, null);
            assertThat(sheet.isBalanced()).isTrue();
            assertThat(sheet.assets()).isEqualTo(Amount.parse("1000"));
            assertThat(sheet.unclosedNetIncome()).isEqualTo(Amount.parse("1000"));
        }
    }

    @Test
    @DisplayName("reports are pure: running twice gives the same answer and stores nothing")
    void reportsArePure() {
        List<Entry> entries = sampleLedger();
        LedgerReports.TrialBalance first = LedgerReports.trialBalance(entries, DEC);
        LedgerReports.TrialBalance second = LedgerReports.trialBalance(entries, DEC);

        assertThat(first.totalDebits()).isEqualTo(second.totalDebits());
        assertThat(first.lines()).hasSameSizeAs(second.lines());
    }
}
