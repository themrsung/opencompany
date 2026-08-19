package com.coreintra.accounting.service;

import com.coreintra.accounting.persistence.BookRepository;
import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;

/**
 * Where every accounting check is made, and where the target is built.
 *
 * <p>This is not a second permission system. It holds the one
 * {@link PermissionEvaluator} and calls it; what it adds is the mapping from "a
 * row in a set of books" to "the thing a grant reaches", written once. The
 * equivalent in the org services is {@code OrgTargets}, and the reasoning is the
 * same: a target that forgets its company puts every COMPANY-scoped grant out of
 * reach, and that failure reads like a permission bug rather than a missing
 * field.
 *
 * <h2>The business date is the entry's, never today's</h2>
 *
 * <p>{@link PermissionTarget#asOfBusinessDate()} decides which org chart the
 * scope is resolved against. Passing today's date for a past-dated entry would
 * mean that promoting somebody silently re-answers every question about what
 * they were allowed to do last quarter — and last quarter's ledger would become
 * un-auditable. So every method here takes the date from the object being acted
 * on: the entry's {@code postedAt}, the report's as-of, the period's end. Only
 * operations that genuinely have no business date of their own — opening a book,
 * renaming an account — take one from the caller, and they take it explicitly
 * rather than defaulting.
 *
 * <h2>Books, not accounts, carry the company</h2>
 *
 * <p>The chart of accounts, the journal, the batches and the 거래처 list all hang
 * off a book, and the book is the only one of them that knows which legal entity
 * it belongs to. Resolving the book on every check costs one primary-key lookup
 * and is what makes a COMPANY-scoped grant on 본사 mean 본사's books rather than
 * every book in the installation.
 */
public class AccountingGate {

    private final BookRepository books;
    private final PermissionEvaluator evaluator;

    public AccountingGate(BookRepository books, PermissionEvaluator evaluator) {
        if (books == null) {
            throw new NullPointerException("books");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.books = books;
        this.evaluator = evaluator;
    }

    /**
     * Authorises an operation on a book, or throws naming the permission.
     *
     * @param businessDate the date the acted-on object falls on — an entry's
     *     {@code postedAt} business date, a report's as-of. Never {@code now()}
     *     unless the operation genuinely concerns today.
     * @return the book, already loaded, so the caller does not read it twice
     */
    public BookRow requireOnBook(PermissionPrincipal caller, PermissionKey key, String bookId,
            LocalDate businessDate, String description) {
        requireCaller(caller, businessDate);
        BookRow book = book(bookId);
        evaluator.check(caller, key, PermissionTarget.builder()
                .companyId(book.companyId())
                .asOfBusinessDate(businessDate)
                .description(description)
                .build()).orThrow();
        return book;
    }

    /**
     * Authorises an operation that names a company rather than a book — opening
     * the first set of books, or listing which sets exist.
     */
    public void requireOnCompany(PermissionPrincipal caller, PermissionKey key, String companyId,
            LocalDate businessDate, String description) {
        requireCaller(caller, businessDate);
        evaluator.check(caller, key, PermissionTarget.builder()
                .companyId(required(companyId, "companyId"))
                .asOfBusinessDate(businessDate)
                .description(description)
                .build()).orThrow();
    }

    /** The book, or a bad request naming it. No authorisation happens here. */
    public BookRow book(String bookId) {
        return books.findById(required(bookId, "bookId"))
                .orElseThrow(() -> new NoSuchAccountingRecordException("book", bookId));
    }

    /**
     * Who is asking, and about which business date. Both mandatory.
     *
     * <p>A null principal or a null date reaching the evaluator produces a
     * {@code NullPointerException} from inside the permission engine, which reads
     * in the log like a bug in authorisation rather than a caller that forgot to
     * say who is asking and when.
     */
    static void requireCaller(PermissionPrincipal caller, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
    }

    /** A mandatory string field, trimmed. Blank is a bad request, not an empty value. */
    static String required(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return Texts.strip(value);
    }
}
