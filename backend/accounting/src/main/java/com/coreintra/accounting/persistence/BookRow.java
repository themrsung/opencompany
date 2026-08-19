package com.coreintra.accounting.persistence;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The persistent form of a set of books.
 *
 * <p>Everything below - accounts, entries, postings, currencies, counterparties - hangs off one
 * book, and the balance invariant is per book. A company that keeps a statutory ledger and a
 * management ledger keeps two books, and no entry can straddle them: the foreign key from a
 * posting carries the book id alongside the entry id precisely so the database can say so.
 *
 * <p>Persistence rows live apart from the domain types (as {@code PermissionGrantRow} does for
 * {@code PermissionGrant}), so that {@code Account}, {@code Entry} and {@code Posting} stay free
 * of annotations and remain testable without a database.
 */
@Entity
@Table(name = "book")
public class BookRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * The unit everything balances in. Not a foreign key: currencies are scoped to a book, so the
     * book has to exist before its own base currency row can. {@code BookService} writes both in
     * one transaction.
     */
    @Column(name = "base_currency_code", nullable = false, length = 12)
    private String baseCurrencyCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Set when the book stops being used. Its history stays readable. */
    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    protected BookRow() {
    }

    public BookRow(String id, String companyId, String name, String baseCurrencyCode) {
        this.id = id;
        this.companyId = companyId;
        this.name = name;
        this.baseCurrencyCode = baseCurrencyCode;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String name() {
        return name;
    }

    public String baseCurrencyCode() {
        return baseCurrencyCode;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
    }
}
