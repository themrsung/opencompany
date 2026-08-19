package com.coreintra.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.accounting.domain.AmortizationSchedule;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.accounting.support.AccountingDatabaseTestSupport;
import com.coreintra.accounting.support.AccountingTestApplication;
import com.coreintra.accounting.support.AccountingTestPermissions;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's invariants, once they have been through PostgreSQL.
 *
 * <p>Everything here is also true in memory and tested there. This is the second half of the
 * question: whether the schema, the mappings and the services keep it true once amounts have been
 * written to disk and read back. Several of the named acceptance tests from the brief are repeated
 * here for exactly that reason - a ledger that balances in a unit test and rounds on the way into
 * a NUMERIC column is not a ledger that balances.
 *
 * <p>Runs against real PostgreSQL 16 through the application's own migrations, with
 * {@code ddl-auto: validate}, so a mapping that disagrees with {@code V8__accounting.sql} fails
 * before any assertion does.
 */
@SpringBootTest(classes = AccountingTestApplication.class, properties = {
    "spring.flyway.target=8",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
class AccountingPersistenceTest {

    @BeforeAll
    static void requireDatabase() {
        AccountingDatabaseTestSupport.requireDatabase();
    }

    private static final String COMPANY = "accounting-test-company";
    private static final String ACTOR = "accounting-test-actor";
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 15);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 15);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 12, 31);

    /**
     * The date these tests hand to operations that have no business date of their own — opening a
     * book, opening an account. The entries carry their own, which is the point of
     * {@link AccountingPermissionGateTest}; here it only has to be a date.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    /**
     * A caller allowed to do everything in the module.
     *
     * <p>Whether the gate refuses the wrong caller is a separate question, asked in
     * {@link AccountingPermissionGateTest}. These tests are about the engine, so the caller is out
     * of the way — but it is a real principal through the real evaluator, not a bypass, because
     * there is no bypass to use.
     */
    private PermissionPrincipal caller;

    @Autowired private AccountingTestPermissions permissions;
    @Autowired private BookService books;
    @Autowired private ChartOfAccountsService chart;
    @Autowired private JournalService journal;
    @Autowired private BatchService batches;
    @Autowired private AmortizationService amortization;
    @Autowired private LedgerReportService reports;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private String bookId;

    @BeforeEach
    void openBookWithAChart() {
        jdbc.update("INSERT INTO company (id, code, name_ko, kind) VALUES (?, ?, ?, 'HEAD_OFFICE') "
                + "ON CONFLICT (id) DO NOTHING", COMPANY, "ACCTEST", "회계 테스트 회사");
        jdbc.update("INSERT INTO user_account (id, username, display_name, kind) "
                + "VALUES (?, ?, ?, 'SERVICE_ACCOUNT') ON CONFLICT (id) DO NOTHING",
                ACTOR, "accounting-test", "회계 테스트");
        caller = permissions.accountantWhoMayDoEverything(ACTOR);

        // A book per test: account codes are scoped to a book, so nothing leaks between them.
        bookId = books.openBook(caller, COMPANY, "Test book " + UUID.randomUUID(), Currency.krw(),
                TODAY).id();
        chart.openAccount(caller, bookId, "1100", null, "현금", "Cash", null, false, TODAY);
        chart.openAccount(caller, bookId, "1200", null, "매출채권", "Trade receivables", null,
                false, TODAY);
        chart.openAccount(caller, bookId, "3900", null, "이익잉여금", "Retained earnings", null,
                false, TODAY);
        chart.openAccount(caller, bookId, "4100", null, "매출", "Revenue", null, false, TODAY);
        chart.openAccount(caller, bookId, "5100", null, "급여", "Salaries", null, false, TODAY);
    }

    private NewEntry entry(LocalDate on, String description, Posting... postings) {
        return new NewEntry(BusinessInstant.of(on, 9, 0, 0), description,
                Immutables.listOfArray(postings));
    }

    private void postASaleAndACost() {
        journal.post(caller, bookId, entry(MARCH, "외상 매출",
                Posting.debit("1200", Amount.parse("1000000")),
                Posting.credit("4100", Amount.parse("1000000"))));
        journal.post(caller, bookId, entry(APRIL, "3월 급여",
                Posting.debit("5100", Amount.parse("300000")),
                Posting.credit("1100", Amount.parse("300000"))));
    }

    @Test
    @DisplayName("ACCEPTANCE: rounding never occurs on write, all the way down to the column")
    void storedAmountsKeepEveryDigit() {
        String third = "333333.33333333333333";
        Entry posted = journal.post(caller, bookId, entry(MARCH, "3분할 배부",
                Posting.debit("5100", Amount.parse(third)),
                Posting.credit("1100", Amount.parse(third))));

        assertThat(journal.load(caller, posted.id()).postings().get(0).amount().toExactString())
                .as("read back through JPA")
                .isEqualTo(third);
        assertThat(jdbc.queryForObject("SELECT amount::text FROM journal_posting "
                + "WHERE entry_id = ? AND position = 0", String.class, posted.id()))
                .as("and in the column itself, not merely in the object we happen to hold")
                .isEqualTo(third);
    }

    @Test
    @DisplayName("ACCEPTANCE: display_decimals never changes a stored value")
    void displayDecimalsAreDisplayOnly() {
        String third = "333333.33333333333333";
        Entry posted = journal.post(caller, bookId, entry(MARCH, "3분할 배부",
                Posting.debit("5100", Amount.parse(third)),
                Posting.credit("1100", Amount.parse(third))));

        Currency krw = books.currencies(caller, bookId, TODAY).get(0);
        assertThat(krw.code()).isEqualTo("KRW");
        assertThat(krw.displayDecimals()).isZero();

        Amount stored = journal.load(caller, posted.id()).postings().get(0).amount();
        assertThat(krw.formatForDisplay(stored)).as("what a screen shows").isEqualTo("333333");
        assertThat(krw.displayHidesPrecision(stored))
                .as("so the UI must offer the exact figure as well").isTrue();
        assertThat(stored.toExactString())
                .as("having been displayed changes nothing").isEqualTo(third);

        // And the setting itself is not a write-path input: change it, and the stored figure is
        // the same figure.
        books.defineCurrency(caller, bookId,
                new Currency("KRW", "대한민국 원", "South Korean won", "₩", 4), TODAY);
        assertThat(jdbc.queryForObject("SELECT amount::text FROM journal_posting "
                + "WHERE entry_id = ? AND position = 0", String.class, posted.id()))
                .isEqualTo(third);
    }

    @Test
    @DisplayName("ACCEPTANCE: an unbalanced entry does not exist, so nothing at all is written")
    void unbalancedEntryWritesNothing() {
        assertThatThrownBy(() -> journal.post(caller, bookId, entry(MARCH, "틀린 전표",
                Posting.debit("5100", Amount.parse("1000000")),
                Posting.credit("1100", Amount.parse("999999")))))
                .isInstanceOf(Entry.UnbalancedEntryException.class);

        assertThat(journal.journal(caller, bookId, TODAY)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_posting WHERE book_id = ?",
                Long.class, bookId)).isZero();
    }

    @Test
    @DisplayName("ACCEPTANCE: the database refuses an unbalanced entry from a writer "
            + "that never went through the engine")
    void theDatabaseRefusesAnUnbalancedEntryOnItsOwn() {
        // An import, a support session or a client module reaches these tables without touching
        // Entry.post. The deferred constraint trigger is what stands between them and a ledger
        // that no longer adds up.
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        String entryId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc.update("INSERT INTO journal_entry (id, book_id, description, status, "
                    + "posted_business_date, posted_offset_seconds) VALUES (?, ?, ?, 'POSTED', ?, "
                    + "32400)", entryId, bookId, "손으로 쓴 전표", java.sql.Date.valueOf(MARCH));
            jdbc.update("INSERT INTO journal_posting (id, entry_id, book_id, position, account_id, "
                    + "amount, base_amount) VALUES (?, ?, ?, 0, '5100', 1000000, 1000000)",
                    UUID.randomUUID().toString(), entryId, bookId);
            jdbc.update("INSERT INTO journal_posting (id, entry_id, book_id, position, account_id, "
                    + "amount, base_amount) VALUES (?, ?, ?, 1, '1100', -999999, -999999)",
                    UUID.randomUUID().toString(), entryId, bookId);
            return null;
        })).hasStackTraceContaining("out of balance");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entry WHERE id = ?",
                Long.class, entryId))
                .as("the entry does not exist, which is the whole of the rule")
                .isZero();
    }

    @Test
    @DisplayName("a batch writes every entry or none of them, and says which one failed")
    void batchIsAllOrNothing() {
        List<NewEntry> entries = Immutables.listOf(
                entry(MARCH, "좋은 전표",
                        Posting.debit("5100", Amount.parse("1000")),
                        Posting.credit("1100", Amount.parse("1000"))),
                entry(MARCH, "틀린 전표",
                        Posting.debit("5100", Amount.parse("1000")),
                        Posting.credit("1100", Amount.parse("999"))));

        assertThatThrownBy(() -> batches.write(caller, bookId, BatchKind.IMPORT, "3월 수입분",
                null, null, entries))
                .isInstanceOf(Entry.UnbalancedEntryException.class)
                .hasMessageContaining("entry 2 of 2");

        assertThat(journal.journal(caller, bookId, TODAY))
                .as("the good first entry must not survive the bad second one").isEmpty();
        assertThat(batches.list(caller, bookId, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("ACCEPTANCE: a closing batch is excluded from the income statement, "
            + "and the closed year still reports what it earned")
    void closingBatchExcludedFromIncomeStatement() {
        postASaleAndACost();

        LedgerReports.IncomeStatement before = reports.incomeStatement(caller, bookId,
                LocalDate.of(2026, 1, 1), YEAR_END);
        assertThat(before.netIncome()).isEqualTo(Amount.parse("700000"));

        Batch closing = batches.write(caller, bookId, BatchKind.CLOSING, "2026 결산", null, null,
                Immutables.listOf(entry(YEAR_END, "손익 대체",
                        Posting.debit("4100", Amount.parse("1000000")),
                        Posting.credit("5100", Amount.parse("300000")),
                        Posting.credit("3900", Amount.parse("700000")))));
        assertThat(closing.kind()).isEqualTo(BatchKind.CLOSING);

        LedgerReports.IncomeStatement after = reports.incomeStatement(caller, bookId,
                LocalDate.of(2026, 1, 1), YEAR_END);
        assertThat(after.income())
                .as("the year earned this, and closing it does not unearn it")
                .isEqualTo(Amount.parse("1000000"));
        assertThat(after.netIncome()).isEqualTo(Amount.parse("700000"));

        assertThat(reports.balanceOf(caller, bookId,
                chart.account(caller, bookId, "4100", TODAY), YEAR_END))
                .as("the closing entry did land: revenue is closed out to nil")
                .isEqualTo(Amount.ZERO);
        assertThat(reports.trialBalance(caller, bookId, YEAR_END).isBalanced()).isTrue();
    }

    @Test
    @DisplayName("ACCEPTANCE: a voided entry disappears from every report and stays in the journal")
    void voidedEntryLeavesTheReportsAndNotTheJournal() {
        postASaleAndACost();
        Entry mistake = journal.post(caller, bookId, entry(APRIL, "중복 입력",
                Posting.debit("5100", Amount.parse("50000")),
                Posting.credit("1100", Amount.parse("50000"))));

        journal.voidEntry(caller, mistake.id(), "중복 입력이라 취소",
                BusinessInstant.of(APRIL, 17, 30, 0));

        assertThat(reports.incomeStatement(caller, bookId, LocalDate.of(2026, 1, 1), YEAR_END)
                .expense())
                .as("the voided cost is not an expense of the year")
                .isEqualTo(Amount.parse("300000"));
        assertThat(reports.trialBalance(caller, bookId, YEAR_END).totalDebits())
                .isEqualTo(Amount.parse("1300000"));
        assertThat(reports.postedEntries(caller, bookId, YEAR_END)).extracting(Entry::id)
                .doesNotContain(mistake.id());

        Entry stillThere = journal.load(caller, mistake.id());
        assertThat(stillThere.status()).isEqualTo(Entry.EntryStatus.VOID);
        assertThat(journal.journal(caller, bookId, TODAY)).extracting(Entry::id)
                .contains(mistake.id());

        List<Entry.Revision> trail = journal.revisionsOf(caller, mistake.id());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).kind()).isEqualTo("VOID");
        assertThat(trail.get(0).reason()).isEqualTo("중복 입력이라 취소");
        assertThat(trail.get(0).actorAccountId()).isEqualTo(ACTOR);
        assertThat(trail.get(0).preStateSnapshot()).contains("50000");
    }

    @Test
    @DisplayName("voiding without a reason is refused, and the entry stays posted")
    void voidingNeedsAReason() {
        Entry posted = journal.post(caller, bookId, entry(MARCH, "매출",
                Posting.debit("1200", Amount.parse("1000")),
                Posting.credit("4100", Amount.parse("1000"))));

        assertThatThrownBy(() -> journal.voidEntry(caller, posted.id(), "   ",
                BusinessInstant.of(MARCH, 10, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(journal.load(caller, posted.id()).status()).isEqualTo(Entry.EntryStatus.POSTED);
        assertThat(journal.revisionsOf(caller, posted.id())).isEmpty();
    }

    @Test
    @DisplayName("a correction is a numbered revision with a reason and the state before it")
    void correctionKeepsThePreState() {
        Entry posted = journal.post(caller, bookId, entry(MARCH, "매출",
                Posting.debit("1200", Amount.parse("100000")),
                Posting.credit("4100", Amount.parse("100000"))));

        Entry corrected = journal.correct(caller, posted.id(), "매출 (금액 정정)", Immutables.listOf(
                Posting.debit("1200", Amount.parse("120000")),
                Posting.credit("4100", Amount.parse("120000"))),
                "세금계산서 금액과 불일치", BusinessInstant.of(APRIL, 9, 0, 0));

        assertThat(corrected.totalDebits()).isEqualTo(Amount.parse("120000"));
        assertThat(journal.load(caller, posted.id()).postings()).hasSize(2);
        assertThat(journal.load(caller, posted.id()).totalDebits())
                .isEqualTo(Amount.parse("120000"));

        List<Entry.Revision> trail = journal.revisionsOf(caller, posted.id());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).number()).isEqualTo(1);
        assertThat(trail.get(0).kind()).isEqualTo("UPDATE");
        assertThat(trail.get(0).reason()).isEqualTo("세금계산서 금액과 불일치");
        assertThat(trail.get(0).preStateSnapshot())
                .as("the figure that was wrong, kept where a reader can see it")
                .contains("100000");
        assertThat(reports.balanceOf(caller, bookId,
                chart.account(caller, bookId, "4100", TODAY), YEAR_END))
                .isEqualTo(Amount.parse("120000"));
    }

    @Test
    @DisplayName("only leaves are postable, and the database says so even without the service")
    void onlyLeavesArePostable() {
        chart.openAccount(caller, bookId, "1300", null, "재고자산", "Inventory", null, false, TODAY);
        chart.openAccount(caller, bookId, "1310", "1300", "원재료", "Raw materials", null,
                false, TODAY);

        assertThatThrownBy(() -> journal.post(caller, bookId, entry(MARCH, "부모 계정 전표",
                Posting.debit("1300", Amount.parse("1000")),
                Posting.credit("1100", Amount.parse("1000")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has child accounts");

        Entry good = journal.post(caller, bookId, entry(MARCH, "원재료 매입",
                Posting.debit("1310", Amount.parse("1000")),
                Posting.credit("1100", Amount.parse("1000"))));

        // Bypassing the service entirely: the foreign key into the leaf half of the account table
        // refuses it as well, which is what makes this structural rather than a policy in Java.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO journal_posting "
                + "(id, entry_id, book_id, position, account_id, amount, base_amount) "
                + "VALUES (?, ?, ?, 9, '1300', 1, 1)",
                UUID.randomUUID().toString(), good.id(), bookId))
                .hasMessageContaining("journal_posting_account_fk");
    }

    @Test
    @DisplayName("an account that already carries postings cannot be turned into a parent")
    void anAccountWithPostingsCannotAdoptAChild() {
        journal.post(caller, bookId, entry(MARCH, "급여",
                Posting.debit("5100", Amount.parse("1000")),
                Posting.credit("1100", Amount.parse("1000"))));

        assertThatThrownBy(() -> chart.openAccount(caller, bookId, "5110", "5100", "상여",
                "Bonus", null, false, TODAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subtotal");

        assertThat(chart.chart(caller, bookId, TODAY).account("5100").isPostable())
                .as("and the refusal left the account exactly as it was").isTrue();
    }

    @Test
    @DisplayName("a retired account keeps its history and takes no new postings")
    void retirementPreservesHistory() {
        journal.post(caller, bookId, entry(MARCH, "급여",
                Posting.debit("5100", Amount.parse("250000")),
                Posting.credit("1100", Amount.parse("250000"))));

        chart.retireAccount(caller, bookId, "5100", TODAY);

        assertThatThrownBy(() -> journal.post(caller, bookId, entry(APRIL, "4월 급여",
                Posting.debit("5100", Amount.parse("250000")),
                Posting.credit("1100", Amount.parse("250000")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");

        assertThat(reports.balanceOf(caller, bookId,
                chart.account(caller, bookId, "5100", TODAY), YEAR_END))
                .as("last month's figure is untouched by this month's decision")
                .isEqualTo(Amount.parse("250000"));
    }

    @Test
    @DisplayName("account attributes resolve by nearest ancestor over the stored chart")
    void attributesResolveByNearestAncestor() {
        chart.openAccount(caller, bookId, "1400", null, "투자자산", "Investments", null, false, TODAY);
        chart.openAccount(caller, bookId, "1410", "1400", "장기예금", "Long-term deposits", null,
                false, TODAY);
        chart.describeAccount(caller, bookId, "1400", ChartOfAccounts.Classification.INVESTING,
                ChartOfAccounts.Category.INVESTMENT, TODAY);

        ChartOfAccounts stored = chart.chart(caller, bookId, TODAY);
        assertThat(stored.classificationOf("1410"))
                .isEqualTo(ChartOfAccounts.Classification.INVESTING);
        assertThat(stored.categoryOf("1410")).isEqualTo(ChartOfAccounts.Category.INVESTMENT);
        assertThat(stored.ownAttributes("1410").classification())
                .as("inherited, not copied down onto the child")
                .isNull();
    }

    @Test
    @DisplayName("a foreign-currency posting balances in the base currency at the supplied rate")
    void foreignCurrencyBalancesInBase() {
        books.defineCurrency(caller, bookId, Currency.usd(), TODAY);
        chart.openAccount(caller, bookId, "1150", null, "외화예금", "FX deposits", "USD", false, TODAY);

        Entry posted = journal.post(caller, bookId, entry(MARCH, "달러 입금",
                Posting.foreignCurrency("1150",
                Amount.parse("1000"), "USD", Amount.parse("1320000"),
                new java.math.BigDecimal("1320")),
                Posting.credit("1100", Amount.parse("1320000"))));

        Posting stored = journal.load(caller, posted.id()).postings().get(0);
        assertThat(stored.amount()).isEqualTo(Amount.parse("1000"));
        assertThat(stored.baseAmount()).isEqualTo(Amount.parse("1320000"));
        assertThat(stored.rate()).isEqualByComparingTo(new java.math.BigDecimal("1320"));
        assertThat(stored.currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("a 거래처 travels with the posting it was recorded on")
    void clientDimensionSurvivesTheRoundTrip() {
        String clientId = books.registerClient(caller, bookId, "㈜테스트상사", "월말 결제", TODAY).id();

        Entry posted = journal.post(caller, bookId, entry(MARCH, "외상 매출",
                Posting.debit("1200", Amount.parse("500000")).withClient(clientId),
                Posting.credit("4100", Amount.parse("500000"))));

        assertThat(journal.load(caller, posted.id()).postings().get(0).clientId())
                .isEqualTo(clientId);
        assertThat(journal.load(caller, posted.id()).postings().get(1).clientId()).isNull();
    }

    @Test
    @DisplayName("amortisation previews without writing, and posts only when a person says so")
    void amortizationPreviewsThenPosts() {
        chart.openAccount(caller, bookId, "1500", null, "선급비용", "Prepaid expenses", null,
                false, TODAY);
        AmortizationService.Request request = new AmortizationService.Request("5100", "1500",
                Amount.parse("1000000"), Amount.ZERO, YearMonth.of(2026, 1), 3, 2,
                AmortizationSchedule.Remainder.END, 28, 32400, "보험료 상각", null);

        AmortizationService.Preview preview = amortization.preview(caller, bookId, request, TODAY);
        assertThat(preview.entries()).hasSize(3);
        assertThat(journal.journal(caller, bookId, TODAY))
                .as("a preview is a calculation, not a write").isEmpty();

        Batch batch = amortization.post(caller, bookId, "보험료 상각 2026", preview);

        List<Entry> written = batches.entriesOf(caller, batch.id(), TODAY);
        assertThat(written).hasSize(3);
        assertThat(batch.kind()).isEqualTo(BatchKind.AMORTIZATION);
        assertThat(batches.find(caller, batch.id(), TODAY).get().generatorParams())
                .as("lineage, stored as text and never re-executed")
                .contains("\"baseAmount\":\"1000000\"")
                .contains("\"remainderTo\":\"END\"")
                .contains("\"neverReExecuted\":true");

        assertThat(reports.balanceOf(caller, bookId, chart.account(caller, bookId, "5100", TODAY),
                LocalDate.of(2026, 3, 31)))
                .as("the schedule recognises the whole amount, to the last sub-unit")
                .isEqualTo(Amount.parse("1000000"));
        assertThat(reports.trialBalance(caller, bookId, YEAR_END).isBalanced()).isTrue();
    }

    @Test
    @DisplayName("a batch is voidable as a unit, and its entries stay in the journal")
    void batchVoidsAsAUnit() {
        Batch batch = batches.write(caller, bookId, BatchKind.RECURRING, "월 정기 지급", null, null,
                Immutables.listOf(
                        entry(MARCH, "임차료",
                                Posting.debit("5100", Amount.parse("1000")),
                                Posting.credit("1100", Amount.parse("1000"))),
                        entry(APRIL, "임차료",
                                Posting.debit("5100", Amount.parse("1000")),
                                Posting.credit("1100", Amount.parse("1000")))));

        int voided = batches.voidBatch(caller, batch.id(), "계약 해지로 전체 취소",
                BusinessInstant.of(APRIL, 20, 0, 0));

        assertThat(voided).isEqualTo(2);
        assertThat(reports.postedEntries(caller, bookId, YEAR_END)).isEmpty();
        assertThat(journal.journal(caller, bookId, TODAY)).hasSize(2);
        for (Entry entry : batches.entriesOf(caller, batch.id(), TODAY)) {
            assertThat(entry.status()).isEqualTo(Entry.EntryStatus.VOID);
            assertThat(journal.revisionsOf(caller, entry.id())).hasSize(1);
        }
    }

    @Test
    @DisplayName("a book is opened with the seeded currencies, both of them editable")
    void seededCurrencies() {
        assertThat(books.currencies(caller, bookId, TODAY)).extracting(Currency::code)
                .containsExactly("KRW", "USD");

        books.defineCurrency(caller, bookId, new Currency("XAU", "금", "Gold", "oz", 6), TODAY);
        assertThat(books.currencies(caller, bookId, TODAY)).extracting(Currency::code)
                .as("a unit of account need not be money")
                .containsExactly("KRW", "USD", "XAU");
    }
}
