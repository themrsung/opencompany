package com.coreintra.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.support.AccountingDatabaseTestSupport;
import com.coreintra.accounting.support.AccountingTestApplication;
import com.coreintra.accounting.support.AccountingTestPermissions;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The gate: no accounting service does anything without asking the evaluator first.
 *
 * <p>§3 says there is one gate with no bypass, and an ArchUnit rule stops a controller reaching a
 * repository. Neither of those makes the <em>service</em> check anything, so until this test
 * existed the module was exactly as safe as whoever called it. What is asserted here is threefold:
 *
 * <ul>
 *   <li>the permission that was checked is the one the operation is named after;</li>
 *   <li>the target is the book's own company, so a grant scoped to 본사 does not reach 자회사;</li>
 *   <li>the check is made <b>as of the entry's business date</b>, not today's — the one that
 *       regresses silently, because a system that uses today's date works perfectly right up
 *       until somebody changes job.</li>
 * </ul>
 *
 * <p>Runs against the real database because the services are the unit under test and they are
 * built around repositories; the evaluator is the real one, over an in-memory grant table.
 */
@SpringBootTest(classes = AccountingTestApplication.class, properties = {
    "spring.flyway.target=8",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
class AccountingPermissionGateTest {

    @BeforeAll
    static void requireDatabase() {
        AccountingDatabaseTestSupport.requireDatabase();
    }

    private static final String COMPANY = "gate-test-company";
    private static final String ACTOR = "gate-test-actor";

    /** Deliberately months before "today": the whole point is that the check uses this. */
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 15);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 20);
    private static final LocalDate SETUP_DAY = LocalDate.of(2026, 1, 2);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 12, 31);

    @Autowired private AccountingTestPermissions permissions;
    @Autowired private BookService books;
    @Autowired private ChartOfAccountsService chart;
    @Autowired private JournalService journal;
    @Autowired private BatchService batches;
    @Autowired private AmortizationService amortization;
    @Autowired private LedgerReportService reports;
    @Autowired private JdbcTemplate jdbc;

    private PermissionPrincipal caller;
    private String bookId;

    @BeforeEach
    void openABook() {
        jdbc.update("INSERT INTO company (id, code, name_ko, kind) VALUES (?, ?, ?, 'HEAD_OFFICE') "
                + "ON CONFLICT (id) DO NOTHING", COMPANY, "GATETEST", "게이트 테스트 회사");
        jdbc.update("INSERT INTO user_account (id, username, display_name, kind) "
                + "VALUES (?, ?, ?, 'SERVICE_ACCOUNT') ON CONFLICT (id) DO NOTHING",
                ACTOR, "gate-test", "게이트 테스트");

        caller = permissions.accountantWhoMayDoEverything(ACTOR);
        bookId = books.openBook(caller, COMPANY, "Gate book " + UUID.randomUUID(), Currency.krw(),
                SETUP_DAY).id();
        chart.openAccount(caller, bookId, "1100", null, "현금", "Cash", null, false, SETUP_DAY);
        chart.openAccount(caller, bookId, "1200", null, "매출채권", "Receivables", null, false,
                SETUP_DAY);
        chart.openAccount(caller, bookId, "4100", null, "매출", "Revenue", null, false, SETUP_DAY);
        chart.openAccount(caller, bookId, "5100", null, "급여", "Salaries", null, false, SETUP_DAY);
        permissions.forget();
    }

    private NewEntry entry(LocalDate on, String description, Posting... postings) {
        return new NewEntry(BusinessInstant.of(on, 9, 0, 0), description,
                Immutables.listOfArray(postings));
    }

    private NewEntry sale(LocalDate on) {
        return entry(on, "외상 매출",
                Posting.debit("1200", Amount.parse("1000000")),
                Posting.credit("4100", Amount.parse("1000000")));
    }

    private AccountingTestPermissions.Check only(PermissionKey key) {
        AccountingTestPermissions.Check check = permissions.lastCheckOf(key);
        assertThat(check).as("%s was never checked", key).isNotNull();
        return check;
    }

    @Nested
    @DisplayName("the check is made on the entry's own business date")
    class OnTheEntrysDate {

        @Test
        @DisplayName("posting a March entry in August is judged against March")
        void postingUsesTheEntryDate() {
            journal.post(caller, bookId, sale(MARCH));

            AccountingTestPermissions.Check check = only(AccountingPermissions.ENTRY_POST);
            assertThat(check.asOf())
                    .as("today's date here would re-answer every past-dated question the "
                            + "first time somebody is promoted")
                    .isEqualTo(MARCH);
            assertThat(check.companyId())
                    .as("the book's own company, so a COMPANY-scoped grant cannot cross entities")
                    .isEqualTo(COMPANY);
        }

        @Test
        @DisplayName("voiding and correcting are judged against the date of the entry, "
                + "not the date of the correction")
        void correctionsUseTheOriginalDate() {
            Entry posted = journal.post(caller, bookId, sale(MARCH));
            permissions.forget();

            journal.correct(caller, posted.id(), "매출 (정정)", Immutables.listOf(
                    Posting.debit("1200", Amount.parse("1200000")),
                    Posting.credit("4100", Amount.parse("1200000"))),
                    "세금계산서와 불일치", BusinessInstant.of(APRIL, 9, 0, 0));
            assertThat(only(AccountingPermissions.ENTRY_UPDATE).asOf())
                    .as("the correction happens in April; the entry is a March entry")
                    .isEqualTo(MARCH);

            permissions.forget();
            journal.voidEntry(caller, posted.id(), "취소", BusinessInstant.of(APRIL, 10, 0, 0));
            assertThat(only(AccountingPermissions.ENTRY_VOID).asOf()).isEqualTo(MARCH);
        }

        @Test
        @DisplayName("reading one entry is judged against that entry's date")
        void readingUsesTheEntryDate() {
            Entry posted = journal.post(caller, bookId, sale(MARCH));
            permissions.forget();

            journal.load(caller, posted.id());
            assertThat(only(AccountingPermissions.ENTRY_READ).asOf()).isEqualTo(MARCH);
        }

        @Test
        @DisplayName("a batch is judged on the earliest date it touches, and every entry again "
                + "on its own")
        void batchChecksEveryEntrySeparately() {
            batches.write(caller, bookId, BatchKind.IMPORT, "수입분", null, null, Immutables.listOf(
                    sale(MARCH), sale(APRIL)));

            assertThat(only(AccountingPermissions.BATCH_CREATE).asOf())
                    .as("the earliest date the batch affects the books")
                    .isEqualTo(MARCH);
            assertThat(permissions.checksOf(AccountingPermissions.ENTRY_POST))
                    .as("batching must not become a way to post an entry you could not post "
                            + "one at a time")
                    .hasSize(2);
            assertThat(permissions.checksOf(AccountingPermissions.ENTRY_POST).get(0).asOf())
                    .isEqualTo(MARCH);
            assertThat(permissions.checksOf(AccountingPermissions.ENTRY_POST).get(1).asOf())
                    .isEqualTo(APRIL);
        }

        @Test
        @DisplayName("a report is judged against the date the report is about")
        void reportsUseTheirOwnDate() {
            reports.balanceSheet(caller, bookId, MARCH);
            assertThat(only(AccountingPermissions.REPORT_READ).asOf()).isEqualTo(MARCH);

            permissions.forget();
            reports.incomeStatement(caller, bookId, LocalDate.of(2026, 1, 1), YEAR_END);
            assertThat(only(AccountingPermissions.REPORT_READ).asOf())
                    .as("a period report is judged at its close")
                    .isEqualTo(YEAR_END);
        }
    }

    @Nested
    @DisplayName("every mutating method and every sensitive read is gated")
    class NothingIsUngated {

        @Test
        @DisplayName("the journal, the batches and the chart each check their own permission")
        void eachOperationChecksItsOwnPermission() {
            journal.post(caller, bookId, sale(MARCH));
            assertThat(permissions.wasChecked(AccountingPermissions.ENTRY_POST)).isTrue();

            permissions.forget();
            journal.journal(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.ENTRY_READ)).isTrue();

            permissions.forget();
            chart.chart(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.ACCOUNT_READ)).isTrue();

            permissions.forget();
            chart.renameAccount(caller, bookId, "5100", "급여 및 상여", "Salaries and bonuses", MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.ACCOUNT_UPDATE)).isTrue();

            permissions.forget();
            chart.describeAccount(caller, bookId, "1100", ChartOfAccounts.Classification.OPERATING,
                    ChartOfAccounts.Category.CASH, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.ACCOUNT_UPDATE)).isTrue();

            permissions.forget();
            chart.retireAccount(caller, bookId, "5100", MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.ACCOUNT_RETIRE)).isTrue();

            permissions.forget();
            books.currencies(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.CURRENCY_READ)).isTrue();

            permissions.forget();
            books.defineCurrency(caller, bookId, new Currency("XAU", "금", "Gold", "oz", 6), MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.CURRENCY_MANAGE)).isTrue();

            permissions.forget();
            books.registerClient(caller, bookId, "㈜게이트", null, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.CLIENT_MANAGE)).isTrue();

            permissions.forget();
            books.clients(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.CLIENT_READ)).isTrue();

            permissions.forget();
            books.book(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.BOOK_READ)).isTrue();

            permissions.forget();
            batches.list(caller, bookId, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.BATCH_READ)).isTrue();
        }

        @Test
        @DisplayName("every report checks accounting.report:read, including the sub-ledgers")
        void everyReportIsGated() {
            reports.trialBalance(caller, bookId, MARCH);
            reports.balanceSheet(caller, bookId, MARCH);
            reports.incomeStatement(caller, bookId, SETUP_DAY, MARCH);
            reports.cashFlow(caller, bookId, SETUP_DAY, MARCH);
            reports.equityStatement(caller, bookId, SETUP_DAY, MARCH);
            reports.receivablesAgeing(caller, bookId, MARCH, null);
            reports.prepaidSchedules(caller, bookId, MARCH);

            assertThat(permissions.checksOf(AccountingPermissions.REPORT_READ))
                    .as("seven reports, seven decisions")
                    .hasSize(7);
        }

        @Test
        @DisplayName("previewing an amortisation needs the authority to post the result")
        void previewIsGatedLikeThePost() {
            AmortizationService.Request request = new AmortizationService.Request("5100", "1100",
                    Amount.parse("300000"), Amount.ZERO, java.time.YearMonth.of(2026, 1), 3, 2,
                    com.coreintra.accounting.domain.AmortizationSchedule.Remainder.END, 28, 32400,
                    "보험료 상각", null);

            amortization.preview(caller, bookId, request, MARCH);
            assertThat(permissions.wasChecked(AccountingPermissions.BATCH_CREATE))
                    .as("a preview handed to somebody who cannot post it is a control routed "
                            + "around socially")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a caller without the permission is refused, and told which one")
    class Refusals {

        @Test
        @DisplayName("ACCEPTANCE: an account without accounting.entry:post cannot post, "
                + "and the refusal names the permission")
        void postingIsRefusedByName() {
            PermissionPrincipal reader = permissions.accountantWithout("gate-test-reader",
                    AccountingPermissions.ENTRY_POST);

            assertThatThrownBy(() -> journal.post(reader, bookId, sale(MARCH)))
                    .isInstanceOf(PermissionDeniedException.class)
                    .satisfies(thrown -> assertThat(
                            ((PermissionDeniedException) thrown).requiredPermission().toString())
                            .as("a bare 403 leaves an administrator guessing which grant to make")
                            .isEqualTo("accounting.entry:post"));

            assertThat(journal.journal(caller, bookId, MARCH))
                    .as("and nothing was written")
                    .isEmpty();
        }

        @Test
        @DisplayName("the batch route cannot post an entry the caller could not post alone")
        void batchingIsNotAWayRound() {
            PermissionPrincipal reader = permissions.accountantWithout("gate-test-batcher",
                    AccountingPermissions.ENTRY_POST);

            assertThatThrownBy(() -> batches.write(reader, bookId, BatchKind.IMPORT, "수입분", null,
                    null, Immutables.listOf(sale(MARCH))))
                    .isInstanceOf(PermissionDeniedException.class)
                    .satisfies(thrown -> assertThat(
                            ((PermissionDeniedException) thrown).requiredPermission().toString())
                            .isEqualTo("accounting.entry:post"));

            assertThat(batches.list(caller, bookId, MARCH))
                    .as("the batch row must not survive its refused entries")
                    .isEmpty();
        }

        @Test
        @DisplayName("an account with no accounting grants at all can read nothing")
        void denyByDefault() {
            PermissionPrincipal stranger = permissions.strangerWithNoGrants("gate-test-stranger");

            assertThatThrownBy(() -> reports.trialBalance(stranger, bookId, MARCH))
                    .isInstanceOf(PermissionDeniedException.class);
            assertThatThrownBy(() -> chart.chart(stranger, bookId, MARCH))
                    .isInstanceOf(PermissionDeniedException.class);
            assertThatThrownBy(() -> books.clients(stranger, bookId, MARCH))
                    .isInstanceOf(PermissionDeniedException.class);
            assertThatThrownBy(() -> journal.journal(stranger, bookId, MARCH))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("reading a report does not let you read the journal behind it")
        void reportReadIsNotEntryRead() {
            PermissionPrincipal manager = permissions.accountantWithout("gate-test-manager",
                    AccountingPermissions.ENTRY_READ);
            journal.post(caller, bookId, sale(MARCH));

            assertThat(reports.incomeStatement(manager, bookId, SETUP_DAY, YEAR_END).income())
                    .as("the manager may see the result")
                    .isEqualTo(Amount.parse("1000000"));
            assertThatThrownBy(() -> journal.journal(manager, bookId, MARCH))
                    .as("and not the memo on every line behind it")
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a caller is refused before anything is written, not after")
        void refusalWritesNothing() {
            PermissionPrincipal reader = permissions.accountantWithout("gate-test-nowrite",
                    AccountingPermissions.ACCOUNT_CREATE);

            assertThatThrownBy(() -> chart.openAccount(reader, bookId, "5200", null, "복리후생비",
                    "Benefits", null, false, MARCH))
                    .isInstanceOf(PermissionDeniedException.class);

            assertThat(chart.chart(caller, bookId, MARCH).account("5200")).isNull();
        }
    }

    @Nested
    @DisplayName("the caller is the actor")
    class CallerIsTheActor {

        @Test
        @DisplayName("a correction records whoever the evaluator judged, not a name passed beside "
                + "the principal")
        void revisionsRecordTheAuthorisedCaller() {
            Entry posted = journal.post(caller, bookId, sale(MARCH));
            journal.voidEntry(caller, posted.id(), "취소", BusinessInstant.of(APRIL, 9, 0, 0));

            List<Entry.Revision> trail = journal.revisionsOf(caller, posted.id());
            assertThat(trail).hasSize(1);
            assertThat(trail.get(0).actorAccountId())
                    .as("the audit trail cannot name somebody other than the account that "
                            + "was authorised, because there is no second name to pass")
                    .isEqualTo(ACTOR);
        }

        @Test
        @DisplayName("a batch records the caller as its author")
        void batchRecordsTheCaller() {
            String batchId = batches.write(caller, bookId, BatchKind.MANUAL, "수기 입력", null, null,
                    Immutables.listOf(sale(MARCH))).id();

            assertThat(batches.find(caller, batchId, MARCH).get().createdBy()).isEqualTo(ACTOR);
        }
    }
}
