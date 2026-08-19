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
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One numbered correction to an entry, with its reason and the state before it.
 *
 * <p>Append-only. Nothing updates or deletes a revision, because this is the record of what was
 * corrected and by whom - a trail that can be edited is not a trail. The correcting entry is
 * posted alongside rather than replacing the original, so the journal shows both what was done
 * and what was done about it.
 *
 * <p>{@link #preStateSnapshot()} is text and deliberately not a set of foreign keys: it has to
 * still read correctly after the accounts it names have been renamed or retired.
 */
@Entity
@Table(name = "journal_entry_revision")
public class JournalEntryRevisionRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "entry_id", nullable = false, length = 36)
    private String entryId;

    /** 1-based and unique per entry: revision 3 is the third thing that happened to it. */
    @Column(name = "number", nullable = false)
    private int number;

    /** UPDATE (the figures were wrong) or VOID (the transaction was wrong). */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    /** Mandatory. An unexplained reversal is indistinguishable from a mistake. */
    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "actor_account_id", nullable = false, length = 36)
    private String actorAccountId;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "recorded_business_date")),
        @AttributeOverride(name = "offsetSeconds",
                column = @Column(name = "recorded_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "recorded_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable recordedAt;

    @Column(name = "pre_state_snapshot", nullable = false)
    private String preStateSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JournalEntryRevisionRow() {
    }

    public JournalEntryRevisionRow(String id, String entryId, Entry.Revision revision) {
        this.id = id;
        this.entryId = entryId;
        this.number = revision.number();
        this.kind = revision.kind();
        this.reason = revision.reason();
        this.actorAccountId = revision.actorAccountId();
        this.recordedAt = BusinessInstantEmbeddable.from(revision.at());
        this.preStateSnapshot = revision.preStateSnapshot();
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String entryId() {
        return entryId;
    }

    public int number() {
        return number;
    }

    public String kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }

    public String actorAccountId() {
        return actorAccountId;
    }

    public BusinessInstant recordedAt() {
        return recordedAt == null ? null : recordedAt.toBusinessInstant();
    }

    public String preStateSnapshot() {
        return preStateSnapshot;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /** The stored revision, verbatim - not a recomputation of what the snapshot would be now. */
    public Entry.Revision toDomain() {
        return Entry.Revision.of(number, kind, reason, actorAccountId, recordedAt(),
                preStateSnapshot);
    }
}
