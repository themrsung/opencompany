package com.coreintra.accounting.persistence;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 거래처 - a counterparty, carried on postings rather than in a ledger of its own.
 *
 * <p>Receivables age and payables come due by counterparty. Keeping that dimension on the posting
 * means the ageing is derived from the same rows as the balance sheet, so the two cannot disagree;
 * a separate sub-ledger would need reconciling, and reconciliation is the thing double entry was
 * supposed to remove.
 */
@Entity
@Table(name = "accounting_client")
public class AccountingClientRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** A counterparty you no longer trade with still owes what it owed. */
    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    protected AccountingClientRow() {
    }

    public AccountingClientRow(String id, String bookId, String name, String note) {
        this.id = id;
        this.bookId = bookId;
        this.name = name;
        this.note = note;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public String name() {
        return name;
    }

    public String note() {
        return note;
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

    public void rename(String newName) {
        this.name = newName;
    }
}
