package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.persistence.AccountingClientRepository;
import com.coreintra.accounting.persistence.AccountingClientRow;
import com.coreintra.accounting.persistence.AccountingCurrencyRepository;
import com.coreintra.accounting.persistence.AccountingCurrencyRow;
import com.coreintra.accounting.persistence.BookRepository;
import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.compat.Texts;
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
 */
public class BookService {

    private final BookRepository books;
    private final AccountingCurrencyRepository currencies;
    private final AccountingClientRepository clients;

    public BookService(BookRepository books, AccountingCurrencyRepository currencies,
            AccountingClientRepository clients) {
        this.books = books;
        this.currencies = currencies;
        this.clients = clients;
    }

    /**
     * Opens a book with its base currency, plus the seeded KRW and USD.
     *
     * @param baseCurrency the unit this book balances in; written first, because a book whose base
     *     currency does not exist cannot report anything
     */
    @Transactional
    public BookRow openBook(String companyId, String name, Currency baseCurrency) {
        if (Texts.isBlank(companyId)) {
            throw new IllegalArgumentException("a book belongs to a company");
        }
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
        defineCurrency(book.id(), baseCurrency);
        seedIfAbsent(book.id(), Currency.krw());
        seedIfAbsent(book.id(), Currency.usd());
        return book;
    }

    public BookRow book(String bookId) {
        return books.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("there is no book " + bookId));
    }

    public List<BookRow> booksOf(String companyId) {
        return books.findByCompanyIdOrderByNameAsc(companyId);
    }

    @Transactional
    public void retireBook(String bookId) {
        BookRow book = book(bookId);
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
    public AccountingCurrencyRow defineCurrency(String bookId, Currency currency) {
        if (currency == null) {
            throw new IllegalArgumentException("nothing to define");
        }
        book(bookId);
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

    /** The units this book can still use. Retired ones stay readable through the row repository. */
    public List<Currency> currencies(String bookId) {
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
    public void retireCurrency(String bookId, String code) {
        AccountingCurrencyRow row = currencies.findByBookIdAndCode(bookId, code)
                .orElseThrow(() -> new IllegalArgumentException(
                        "book " + bookId + " has no currency " + code + " defined"));
        if (book(bookId).baseCurrencyCode().equals(code)) {
            throw new IllegalStateException("currency " + code + " is the base currency of book "
                    + bookId + ". Retiring it would leave every entry balancing in a unit the book "
                    + "no longer recognises.");
        }
        row.retire(OffsetDateTime.now());
        currencies.save(row);
    }

    /** Registers a 거래처, the dimension receivables and payables are aged by. */
    @Transactional
    public AccountingClientRow registerClient(String bookId, String name, String note) {
        book(bookId);
        if (Texts.isBlank(name)) {
            throw new IllegalArgumentException("a 거래처 needs a name");
        }
        return clients.save(
                new AccountingClientRow(UUID.randomUUID().toString(), bookId, name, note));
    }

    public List<AccountingClientRow> clients(String bookId) {
        return clients.findByBookIdOrderByNameAsc(bookId);
    }

    /** A counterparty you no longer trade with still owes what it owed, so this hides, not deletes. */
    @Transactional
    public void retireClient(String clientId) {
        AccountingClientRow row = clients.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("there is no 거래처 " + clientId));
        row.retire(OffsetDateTime.now());
        clients.save(row);
    }

    private void seedIfAbsent(String bookId, Currency currency) {
        if (!currencies.findByBookIdAndCode(bookId, currency.code()).isPresent()) {
            defineCurrency(bookId, currency);
        }
    }
}
