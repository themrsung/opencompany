package com.coreintra.accounting.domain;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * Many entries written in one transaction, and one thing afterwards.
 *
 * <h2>Why a batch survives its write</h2>
 *
 * <p>A batch is not a transaction that ended when the commit did. It is the answer to "where did
 * these four hundred entries come from", asked a year later by someone who was not there. So it
 * is listable, fetchable and voidable as a unit, and it carries the parameters it was generated
 * from.
 *
 * <h2>The generator is lineage, not a recipe</h2>
 *
 * <p>{@link #generatorParams()} records what a human approved. It is never re-executed. Re-running
 * a schedule against today's chart of accounts, today's currencies and today's idea of the
 * calendar would quietly produce a different past, and the difference would look like an
 * accounting error rather than a replay.
 */
public final class Batch implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String bookId;
    private final BatchKind kind;
    private final String label;
    private final String note;
    private final String generatorParams;
    private final String createdBy;

    public Batch(String id, String bookId, BatchKind kind, String label, String note,
            String generatorParams, String createdBy) {
        if (Texts.isBlank(id)) {
            throw new IllegalArgumentException("a batch needs an id");
        }
        if (Texts.isBlank(bookId)) {
            throw new IllegalArgumentException("a batch belongs to a book");
        }
        if (kind == null) {
            throw new IllegalArgumentException("a batch needs a kind");
        }
        if (Texts.isBlank(label)) {
            // An unlabelled batch is a hundred entries with no explanation attached, which is
            // exactly the situation batches exist to prevent.
            throw new IllegalArgumentException("a batch needs a label saying what it is");
        }
        this.id = id;
        this.bookId = bookId;
        this.kind = kind;
        this.label = label;
        this.note = note;
        this.generatorParams = generatorParams;
        this.createdBy = createdBy;
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public BatchKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    /** Free text from whoever wrote the batch. Null when nobody added any. */
    public String note() {
        return note;
    }

    /** What the generator was asked for, kept verbatim. Null for a hand-written batch. */
    public String generatorParams() {
        return generatorParams;
    }

    /** The account that wrote it. Null only for batches created before there was a session. */
    public String createdBy() {
        return createdBy;
    }

    @Override
    public String toString() {
        return kind + " batch " + id + " (" + label + ")";
    }
}
