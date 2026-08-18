package com.coreintra.app.seed;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.accounting.service.BookService;
import com.coreintra.accounting.service.ChartOfAccountsService;
import com.coreintra.accounting.service.JournalService;
import com.coreintra.accounting.service.LedgerReportService;
import com.coreintra.accounting.service.NewEntry;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A small set of books that balances, and two entries that exist to be looked at closely.
 *
 * <p>Most of what is posted here is the ordinary month of a small company. Two entries are
 * deliberate:
 *
 * <ul>
 *   <li>The overseas invoice is posted in USD with both the foreign amount and its base
 *       equivalent, at the rate the business actually used. The entry balances in the book's own
 *       currency, which is the only unit in which balance means anything.</li>
 *   <li>The interest accrual carries three decimal places in a book displayed to none, so the
 *       full-precision toggle has something to reveal. Nothing is rounded on write — the stored
 *       value is the value, and rounding is a display decision.</li>
 * </ul>
 *
 * <p>The whole step is optional. The accounting module can be switched off, and an installation
 * with it off must boot and demo fine, so the services are injected as {@link Optional} and their
 * absence is reported rather than being a failure.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class LedgerSeed {

    private final Optional<BookService> books;
    private final Optional<ChartOfAccountsService> chart;
    private final Optional<JournalService> journal;
    private final Optional<LedgerReportService> reports;

    LedgerSeed(Optional<BookService> books, Optional<ChartOfAccountsService> chart,
            Optional<JournalService> journal, Optional<LedgerReportService> reports) {
        this.books = books;
        this.chart = chart;
        this.journal = journal;
        this.reports = reports;
    }

    boolean isAvailable() {
        return books.isPresent() && chart.isPresent() && journal.isPresent() && reports.isPresent();
    }

    /**
     * Opens the book, lays the chart and posts the month, as 재무팀 부장 — whose 회계 직무 carries
     * the accounting permissions. The operator could do it, but then the demo would not prove
     * that the 직무 grants work.
     */
    void run(SeedWorld world) {
        PermissionPrincipal accountant = world.principal(DemoCompany.FINANCE_LEAD_EMPLOYEE_NUMBER);
        String companyId = world.companyId(DemoCompany.HQ_CODE);
        LocalDate on = DemoCompany.TODAY;

        BookRow book = books.get().openBook(accountant, companyId, DemoCompany.BOOK_NAME,
                Currency.krw(), on);
        world.setBookId(book.id());
        // The rate is recorded on the posting, but the currency has to be one the book knows.
        books.get().defineCurrency(accountant, book.id(), Currency.usd(), on);
        books.get().registerClient(accountant, book.id(), "대한전자 주식회사",
                "국내 용역 매출 거래처", on);

        openAccounts(accountant, book.id(), on);
        postEntries(accountant, book.id());
    }

    private void openAccounts(PermissionPrincipal accountant, String bookId, LocalDate on) {
        ChartOfAccountsService accounts = chart.get();
        // Parents first: an account that has children stops being postable, which is how the
        // chart keeps summary rows out of the journal.
        accounts.openAccount(accountant, bookId, "1000", null, "자산", "Assets", null, false, on);
        accounts.openAccount(accountant, bookId, "2000", null, "부채", "Liabilities", null, false, on);
        accounts.openAccount(accountant, bookId, "3000", null, "자본", "Equity", null, false, on);
        accounts.openAccount(accountant, bookId, "4000", null, "수익", "Income", null, false, on);
        accounts.openAccount(accountant, bookId, "5000", null, "비용", "Expenses", null, false, on);

        accounts.openAccount(accountant, bookId, "1100", "1000", "보통예금", "Bank", null, false, on);
        accounts.openAccount(accountant, bookId, "1150", "1000", "미수수익", "Accrued income",
                null, false, on);
        accounts.openAccount(accountant, bookId, "1200", "1000", "외상매출금", "Trade receivables",
                null, false, on);
        accounts.openAccount(accountant, bookId, "1210", "1000", "외화 외상매출금",
                "Trade receivables (USD)", "USD", false, on);
        accounts.openAccount(accountant, bookId, "2100", "2000", "미지급금", "Trade payables",
                null, false, on);
        accounts.openAccount(accountant, bookId, "2200", "2000", "예수금", "Withholdings",
                null, false, on);
        accounts.openAccount(accountant, bookId, "3100", "3000", "자본금", "Paid-in capital",
                null, false, on);
        accounts.openAccount(accountant, bookId, "4100", "4000", "용역매출", "Service revenue",
                null, false, on);
        accounts.openAccount(accountant, bookId, "4200", "4000", "이자수익", "Interest income",
                null, false, on);
        accounts.openAccount(accountant, bookId, "5100", "5000", "급여", "Salaries", null, false, on);
        accounts.openAccount(accountant, bookId, "5200", "5000", "지급임차료", "Rent", null, false, on);
        accounts.openAccount(accountant, bookId, "5300", "5000", "통신비", "Telecoms", null, false, on);
        accounts.openAccount(accountant, bookId, "5400", "5000", "소모품비", "Supplies", null, false, on);
        accounts.openAccount(accountant, bookId, "5500", "5000", "접대비", "Entertainment",
                null, false, on);

        // Enough classification for the cash-flow statement to have an opinion about the cash
        // line; the rest of the chart can be described by the client as they see fit.
        accounts.describeAccount(accountant, bookId, "1100", ChartOfAccounts.Classification.OPERATING,
                ChartOfAccounts.Category.CASH, on);
        accounts.describeAccount(accountant, bookId, "1200", ChartOfAccounts.Classification.OPERATING,
                ChartOfAccounts.Category.RECEIVABLE, on);
        accounts.describeAccount(accountant, bookId, "1210", ChartOfAccounts.Classification.OPERATING,
                ChartOfAccounts.Category.RECEIVABLE, on);
        accounts.describeAccount(accountant, bookId, "1150", ChartOfAccounts.Classification.OPERATING,
                ChartOfAccounts.Category.RECEIVABLE, on);
        accounts.describeAccount(accountant, bookId, "2100", ChartOfAccounts.Classification.OPERATING,
                ChartOfAccounts.Category.UNPAID_EXPENSE, on);
    }

    private void postEntries(PermissionPrincipal accountant, String bookId) {
        post(accountant, bookId, LocalDate.of(2026, 7, 1), 9, "설립 자본금 납입",
                debit("1100", "100000000"), credit("3100", "100000000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 5), 10, "7월 사무실 임차료",
                debit("5200", "3500000"), credit("1100", "3500000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 10), 11, "용역 매출 — 대한전자",
                debit("1200", "30000000"), credit("4100", "30000000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 12), 14, "7월 통신비",
                debit("5300", "480000"), credit("2100", "480000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 18), 15, "사무용 소모품 구입",
                debit("5400", "1250000"), credit("1100", "1250000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 22), 20, "거래처 접대비",
                debit("5500", "860000"), credit("1100", "860000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 25), 16, "7월 급여 지급",
                debit("5100", "24500000"), credit("1100", "22300000"), credit("2200", "2200000"));
        post(accountant, bookId, LocalDate.of(2026, 7, 28), 11, "용역 매출 대금 입금",
                debit("1100", "30000000"), credit("1200", "30000000"));

        // Three decimal places in a book displayed to none. Stored as calculated; rounded only
        // when something decides to show it.
        post(accountant, bookId, LocalDate.of(2026, 7, 31), 23, "정기예금 미수이자 계상",
                debit("1150", "12345.678"), credit("4200", "12345.678"));

        post(accountant, bookId, LocalDate.of(2026, 8, 3), 10, "7월 통신비 미지급금 결제",
                debit("2100", "480000"), credit("1100", "480000"));

        // USD 12,000 at the rate on the invoice. Both figures are on the posting: the foreign
        // amount is what the client owes, the base amount is what the book balances in.
        post(accountant, bookId, LocalDate.of(2026, 8, 5), 13,
                "해외 용역 매출 — USD 12,000 @ 1,318.50",
                Posting.foreignCurrency("1210", Amount.parse("12000"), "USD",
                        Amount.parse("15822000"), new BigDecimal("1318.50")),
                credit("4100", "15822000"));
    }

    private void post(PermissionPrincipal accountant, String bookId, LocalDate day, int hour,
            String description, Posting... postings) {
        List<Posting> lines = new ArrayList<Posting>(postings.length);
        for (Posting posting : postings) {
            lines.add(posting);
        }
        journal.get().post(accountant, bookId,
                new NewEntry(BusinessInstant.of(day, hour, 0, 0), description, lines));
    }

    private static Posting debit(String accountId, String amount) {
        return Posting.debit(accountId, Amount.parse(amount));
    }

    private static Posting credit(String accountId, String amount) {
        return Posting.credit(accountId, Amount.parse(amount));
    }

    /** Reads the trial balance back through the report service, as the demo's accountant would. */
    boolean balances(SeedWorld world) {
        if (world.bookId() == null || !isAvailable()) {
            return false;
        }
        PermissionPrincipal accountant = world.principal(DemoCompany.FINANCE_LEAD_EMPLOYEE_NUMBER);
        LedgerReports.TrialBalance trialBalance =
                reports.get().trialBalance(accountant, world.bookId(), DemoCompany.TODAY);
        return trialBalance.isBalanced();
    }

    /** The book the demo opened, if the company already has one. Used by the second run. */
    Optional<BookRow> existingBook(SeedWorld world) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        PermissionPrincipal accountant = world.principal(DemoCompany.FINANCE_LEAD_EMPLOYEE_NUMBER);
        List<BookRow> found = books.get().booksOf(accountant,
                world.companyId(DemoCompany.HQ_CODE), DemoCompany.TODAY);
        for (BookRow book : found) {
            if (DemoCompany.BOOK_NAME.equals(book.name())) {
                return Optional.of(book);
            }
        }
        return Optional.empty();
    }
}
