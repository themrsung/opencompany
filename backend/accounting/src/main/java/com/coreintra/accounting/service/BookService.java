package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.persistence.AccountingClientRepository;
import com.coreintra.accounting.persistence.AccountingClientRow;
import com.coreintra.accounting.persistence.AccountingCurrencyRepository;
import com.coreintra.accounting.persistence.AccountingCurrencyRow;
import com.coreintra.accounting.persistence.BookRepository;
import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens books, and keeps the two things a book needs before anything can be posted into it: its
 * units of account and its counterparties.
 *
 * <h2>Why the seeded currencies are seeded here as well as in the migration</h2>
 *
 * <p>The migration backfills KRW and USD for books that already exist, which on a fresh
 * installation is none of them. A book opened afterwards would otherwise start with no currency at
 * all, including the one it says it balances in. Both paths seed the same two, both are editable,
 * and neither is privileged: nothing in the code looks up a currency by name.
 *
 * <h2>Every method here is authorised</h2>
 *
 * <p>The reads as well as the writes. Which counterparties a company trades with, and which sets of
 * books it keeps, are facts about the business, and an endpoint that returned them to anyone who
 * had signed in would be a hole regardless of what the write paths did. The permission is checked
 * against the book's own company, so a COMPANY-scoped grant on 본사 does not reach 자회사's ledger.
 */
public class BookService {

    private final BookRepository books;
    private final AccountingCurrencyRepository currencies;
    private final AccountingClientRepository clients;
    private final AccountingGate gate;

    public BookService(BookRepository books, AccountingCurrencyRepository currencies,
            AccountingClientRepository clients, AccountingGate gate) {
        this.books = books;
        this.currencies = currencies;
        this.clients = clients;
        this.gate = gate;
    }

    /**
     * Opens a book with its base currency, plus the seeded KRW and USD.
     *
     * @param businessDate the date this decision is being taken on; the org chart is resolved as of
     *     it, so back-dating the opening of a book resolves against the org chart of that day
     * @param baseCurrency the unit this book balances in; written first, because a book whose base
     *     currency does not exist cannot report anything
     */
    @Transactional
    public BookRow openBook(PermissionPrincipal caller, String companyId, String name,
            Currency baseCurrency, LocalDate businessDate) {
        if (Texts.isBlank(companyId)) {
            throw new IllegalArgumentException("a book belongs to a company");
        }
        gate.requireOnCompany(caller, AccountingPermissions.BOOK_MANAGE, companyId, businessDate,
                "open a set of books for company " + companyId);
        if (Texts.isBlank(name)) {
            throw new IllegalArgumentException("a book needs a name");
        }
        if (baseCurrency == null) {
            throw new IllegalArgumentException(
                    "a book needs a base currency: it is the unit every entry balances in");
        }
        BookRow book = new BookRow(UUID.randomUUID().toString(), companyId, name,
                baseCurrency.code());
        books.save(book);
        writeCurrency(book.id(), baseCurrency);
        seedIfAbsent(book.id(), Currency.krw());
        seedIfAbsent(book.id(), Currency.usd());
        return book;
    }

    public BookRow book(PermissionPrincipal caller, String bookId, LocalDate businessDate) {
        return gate.requireOnBook(caller, AccountingPermissions.BOOK_READ, bookId, businessDate,
                "read book " + bookId);
    }

    public List<BookRow> booksOf(PermissionPrincipal caller, String companyId,
            LocalDate businessDate) {
        gate.requireOnCompany(caller, AccountingPermissions.BOOK_READ, companyId, businessDate,
                "list the books of company " + companyId);
        return books.findByCompanyIdOrderByNameAsc(companyId);
    }

    @Transactional
    public void retireBook(PermissionPrincipal caller, String bookId, LocalDate businessDate) {
        BookRow book = gate.requireOnBook(caller, AccountingPermissions.BOOK_MANAGE, bookId,
                businessDate, "retire book " + bookId);
        if (book.isRetired()) {
            throw new IllegalStateException("book " + bookId + " is already retired");
        }
        book.retire(OffsetDateTime.now());
        books.save(book);
    }

    /**
     * Defines a unit of account for this book, or re-describes one that exists.
     *
     * <p>{@code displayDecimals} is presentation and nothing else. Defining KRW with 0 decimals
     * does not make KRW amounts integers, and no write path consults it. The code itself is not
     * editable: it is what every posting points at, and changing it would silently reassign
     * amounts to a different unit.
     */
    @Transactional
    public AccountingCurrencyRow defineCurrency(PermissionPrincipal caller, String bookId,
            Currency currency, LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.CURRENCY_MANAGE, bookId, businessDate,
                "define a unit of account in book " + bookId);
        if (currency == null) {
            throw new IllegalArgumentException("nothing to define");
        }
        return writeCurrency(bookId, currency);
    }

    /** The units this book can still use. Retired ones stay readable through the row repository. */
    public List<Currency> currencies(PermissionPrincipal caller, String bookId,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.CURRENCY_READ, bookId, businessDate,
                "list the units of account of book " + bookId);
        List<Currency> active = new ArrayList<Currency>();
        for (AccountingCurrencyRow row : currencies.findByBookIdOrderByCodeAsc(bookId)) {
            if (!row.isRetired()) {
                active.add(row.toDomain());
            }
        }
        return active;
    }

    /**
     * Stops a currency being used for new postings. The historical ones keep it: those amounts
     * were denominated in something, and forgetting what leaves the figures unlabelled.
     */
    @Transactional
    public void retireCurrency(PermissionPrincipal caller, String bookId, String code,
            LocalDate businessDate) {
        BookRow book = gate.requireOnBook(caller, AccountingPermissions.CURRENCY_MANAGE, bookId,
                businessDate, "retire unit of account " + code + " in book " + bookId);
        AccountingCurrencyRow row = currencies.findByBookIdAndCode(bookId, code)
                .orElseThrow(() -> new NoSuchAccountingRecordException("currency", code,
                        "book " + bookId + " has no currency " + code + " defined"));
        if (book.baseCurrencyCode().equals(code)) {
            throw new IllegalStateException("currency " + code + " is the base currency of book "
                    + bookId + ". Retiring it would leave every entry balancing in a unit the book "
                    + "no longer recognises.");
        }
        row.retire(OffsetDateTime.now());
        currencies.save(row);
    }

    /** Registers a 거래처, the dimension receivables and payables are aged by. */
    @Transactional
    public AccountingClientRow registerClient(PermissionPrincipal caller, String bookId,
            String name, String note, LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.CLIENT_MANAGE, bookId, businessDate,
                "register a 거래처 in book " + bookId);
        if (Texts.isBlank(name)) {
            throw new IllegalArgumentException("a 거래처 needs a name");
        }
        return clients.save(
                new AccountingClientRow(UUID.randomUUID().toString(), bookId, name, note));
    }

    public List<AccountingClientRow> clients(PermissionPrincipal caller, String bookId,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.CLIENT_READ, bookId, businessDate,
                "list the 거래처 of book " + bookId);
        return clients.findByBookIdOrderByNameAsc(bookId);
    }

    /** A counterparty you no longer trade with still owes what it owed, so this hides, not deletes. */
    @Transactional
    public void retireClient(PermissionPrincipal caller, String clientId, LocalDate businessDate) {
        AccountingClientRow row = clients.findById(AccountingGate.required(clientId, "clientId"))
                .orElseThrow(() -> new NoSuchAccountingRecordException("거래처", clientId));
        // The 거래처 names its book and the book names the company, so the check lands on the
        // legal entity that actually trades with this counterparty rather than on the installation.
        gate.requireOnBook(caller, AccountingPermissions.CLIENT_MANAGE, row.bookId(), businessDate,
                "retire 거래처 " + clientId);
        row.retire(OffsetDateTime.now());
        clients.save(row);
    }

    /**
     * Writes a currency row without asking again.
     *
     * <p>Private, and only reachable from a method that has already authorised the caller against
     * this book. Opening a book defines three currencies, and re-checking the same permission
     * three times would make the audit log read as though three separate decisions were taken.
     */
    private AccountingCurrencyRow writeCurrency(String bookId, Currency currency) {
        gate.book(bookId);
        AccountingCurrencyRow existing =
                currencies.findByBookIdAndCode(bookId, currency.code()).orElse(null);
        if (existing != null) {
            existing.describe(currency.nameKo(), currency.nameEn(), currency.symbol(),
                    currency.displayDecimals());
            return currencies.save(existing);
        }
        return currencies.save(new AccountingCurrencyRow(UUID.randomUUID().toString(), bookId,
                currency.code(), currency.nameKo(), currency.nameEn(), currency.symbol(),
                currency.displayDecimals()));
    }

    private void seedIfAbsent(String bookId, Currency currency) {
        if (!currencies.findByBookIdAndCode(bookId, currency.code()).isPresent()) {
            writeCurrency(bookId, currency);
        }
    }
}
