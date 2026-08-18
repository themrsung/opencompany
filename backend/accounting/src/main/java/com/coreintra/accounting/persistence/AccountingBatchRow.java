package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The persistent form of a {@link Batch}.
 *
 * <p>Two things make this a table rather than a column on an entry. A batch is fetchable and
 * voidable as a unit, and it carries the parameters it was generated from - the lineage a reader
 * needs a year later when the question is "who decided this and on what basis".
 *
 * <p>{@link #kind} is the only field here that changes what a report says: closing batches are
 * excluded from income statements, so that a closed year still reports what it earned.
 */
@Entity
@Table(name = "accounting_batch")
public class AccountingBatchRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "note")
    private String note;

    /**
     * What the generator was asked for, kept as text and never re-executed. Replaying it against
     * today's chart of accounts would produce a different past and call it a correction.
     */
    @Column(name = "generator_params")
    private String generatorParams;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AccountingBatchRow() {
    }

    public AccountingBatchRow(Batch batch) {
        this.id = batch.id();
        this.bookId = batch.bookId();
        this.kind = batch.kind().name();
        this.label = batch.label();
        this.note = batch.note();
        this.generatorParams = batch.generatorParams();
        this.createdBy = batch.createdBy();
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public String kind() {
        return kind;
    }

    public boolean isClosing() {
        return BatchKind.CLOSING.name().equals(kind);
    }

    public String label() {
        return label;
    }

    public String note() {
        return note;
    }

    public String generatorParams() {
        return generatorParams;
    }

    public String createdBy() {
        return createdBy;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public Batch toDomain() {
        return new Batch(id, bookId, BatchKind.fromStored(kind), label, note, generatorParams,
                createdBy);
    }
}
