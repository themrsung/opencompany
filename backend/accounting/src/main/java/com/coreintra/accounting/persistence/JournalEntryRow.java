package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Entry;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;
import java.time.OffsetDateTime;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The header of a journal entry. Its postings are rows of their own.
 *
 * <h2>Why the balance invariant is not a column here</h2>
 *
 * <p>Debits equal credits across the entry's postings, which is a sum over rows and therefore not
 * expressible as a row constraint. {@code Entry.post} proves it on write, and
 * {@code Entry.rehydrate} proves it again on every read, so a row that stopped balancing - a bad
 * migration, a hand-written UPDATE during a support session - is found by the next reader rather
 * than trusted. Storing a "balanced" flag instead would mean storing the answer to a question the
 * data can be asked directly, and the flag would eventually be the one that was wrong.
 *
 * <h2>Business time, not a timestamp</h2>
 *
 * <p>{@code postedAt} is a {@link BusinessInstant}: which business day an entry falls in decides
 * which period reports it, and a 03:00 posting on a shift that began the previous evening belongs
 * to the day the organisation says it does (ADR 0002). {@code created_at} is the separate,
 * unconflated UTC record of when the machine wrote the row.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntryRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    /** Null for an entry written on its own rather than as part of a batch. */
    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /** DRAFT and VOID are excluded from every report and stay in the journal. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Entry.EntryStatus status;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "posted_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "posted_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "posted_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable postedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JournalEntryRow() {
    }

    public JournalEntryRow(Entry entry) {
        this.id = entry.id();
        this.bookId = entry.bookId();
        this.batchId = entry.batchId();
        this.description = entry.description();
        this.status = entry.status();
        this.postedAt = BusinessInstantEmbeddable.from(entry.postedAt());
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public String batchId() {
        return batchId;
    }

    public String description() {
        return description;
    }

    public Entry.EntryStatus status() {
        return status;
    }

    public BusinessInstant postedAt() {
        return postedAt == null ? null : postedAt.toBusinessInstant();
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /**
     * Records the new state after a domain operation.
     *
     * <p>Takes the whole entry rather than a status, so the row cannot be moved to VOID by a
     * caller who has not been through {@link Entry#voidEntry} and therefore has no reason and no
     * revision to show for it.
     */
    public void syncFrom(Entry entry) {
        this.description = entry.description();
        this.status = entry.status();
    }
}
