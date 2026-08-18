package com.coreintra.accounting.domain;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A journal entry: two or more postings whose debits and credits are exactly equal.
 *
 * <h2>The invariant</h2>
 *
 * <p><b>Debits equal credits, exactly, or the entry does not exist.</b> Not
 * "is flagged", not "is rejected on save" — an unbalanced {@code Entry} cannot
 * be constructed, so no code path anywhere can hold one, log one, or write half
 * of one.
 *
 * <p>Equality is numeric ({@code compareTo}), so a posting of {@code 1000.00}
 * balances one of {@code 1000}. Differing scale is not an imbalance; a rounding
 * gap is.
 *
 * <p>Nothing here rounds. If two postings differ by 0.0000001, that is an
 * imbalance and the entry is refused with the difference stated. Silently
 * absorbing it would be a plug, and a plug is how a ledger stops meaning
 * anything.
 *
 * <h2>All or nothing</h2>
 *
 * <p>Because the constructor validates, "all-or-nothing write" is structural
 * rather than transactional discipline: there is no partially-built entry to
 * write.
 */
public final class Entry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Refused entry. Carries the actual difference, which is what a user needs. */
    public static class UnbalancedEntryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final Amount difference;

        public UnbalancedEntryException(String message, Amount difference) {
            super(message);
            this.difference = difference;
        }

        /** Debits minus credits. Positive means debits exceed credits. */
        public Amount difference() {
            return difference;
        }
    }

    /** Draft entries and voided entries are excluded from every report. */
    public enum EntryStatus {
        /** Not yet posted. Excluded from all reports. */
        DRAFT,
        /** Posted and included in reporting. */
        POSTED,
        /**
         * Retired. Hidden from every report, still visible in the journal.
         *
         * <p>The journal is the record of what was done, including what was done
         * wrongly. Removing it there would hide the mistake as well as its effect.
         */
        VOID
    }

    private final String id;
    private final String bookId;
    private final BusinessInstant postedAt;
    private final String description;
    private final List<Posting> postings;
    private final String batchId;
    private EntryStatus status;
    private final List<Revision> revisions = new ArrayList<Revision>();

    private Entry(String id, String bookId, BusinessInstant postedAt, String description,
            List<Posting> postings, String batchId, EntryStatus status) {
        this.id = id;
        this.bookId = bookId;
        this.postedAt = postedAt;
        this.description = description;
        this.postings = Immutables.copyOf(postings);
        this.batchId = batchId;
        this.status = status;
    }

    /**
     * Builds a balanced entry, or refuses.
     *
     * @throws UnbalancedEntryException if debits and credits differ at all
     * @throws IllegalArgumentException if there are fewer than two postings
     */
    public static Entry post(String id, String bookId, BusinessInstant postedAt,
            String description, List<Posting> postings, String batchId) {
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException(
                    "an entry needs at least two postings; a single-sided entry cannot balance");
        }
        if (postedAt == null) {
            throw new IllegalArgumentException(
                    "an entry needs a business instant. Which business day it falls in decides "
                            + "which period reports it (ADR 0002).");
        }

        Amount debits = Amount.ZERO;
        Amount credits = Amount.ZERO;
        for (Posting posting : postings) {
            // Balance is checked in BASE currency: a foreign-currency entry
            // balances in the book's own unit, not in the account's.
            Amount base = posting.baseAmount();
            if (base.isPositive()) {
                debits = debits.add(base);
            } else {
                credits = credits.add(base.negate());
            }
        }

        if (!debits.isEqualTo(credits)) {
            Amount difference = debits.subtract(credits);
            throw new UnbalancedEntryException(
                    "차변과 대변이 일치하지 않습니다. (Debits " + debits.toExactString()
                            + " do not equal credits " + credits.toExactString()
                            + "; the difference is " + difference.toExactString()
                            + ". The entry has not been created. Nothing is rounded to make an "
                            + "entry balance — a plug would make the ledger stop meaning anything.)",
                    difference);
        }
        return new Entry(id, bookId, postedAt, description, postings, batchId, EntryStatus.POSTED);
    }

    /**
     * Rebuilds an entry that is already in the books, with the status and the correction
     * trail it was stored with.
     *
     * <p>The invariant is re-proved on the way in. A row that no longer balances — because a
     * migration touched it, or someone wrote SQL by hand during a support session — does not
     * quietly become an {@code Entry}. It throws here, in front of whoever is reading, rather
     * than surfacing later as a balance sheet that is off by an amount nobody can source.
     *
     * <p>Persistence is the only caller. Everything else goes through {@link #post} or
     * {@link #draft}, which is where the decision whether an entry may exist at all is made.
     *
     * @throws UnbalancedEntryException if the stored postings no longer balance
     */
    public static Entry rehydrate(String id, String bookId, BusinessInstant postedAt,
            String description, List<Posting> postings, String batchId,
            EntryStatus status, List<Revision> revisions) {
        if (status == null) {
            throw new IllegalArgumentException("a stored entry needs a status");
        }
        Entry entry = post(id, bookId, postedAt, description, postings, batchId);
        entry.status = status;
        if (revisions != null) {
            entry.revisions.addAll(revisions);
        }
        return entry;
    }

    /** A draft. Still must balance: a draft that cannot post is not worth keeping. */
    public static Entry draft(String id, String bookId, BusinessInstant postedAt,
            String description, List<Posting> postings) {
        Entry entry = post(id, bookId, postedAt, description, postings, null);
        entry.status = EntryStatus.DRAFT;
        return entry;
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public BusinessInstant postedAt() {
        return postedAt;
    }

    public String description() {
        return description;
    }

    public List<Posting> postings() {
        return postings;
    }

    public String batchId() {
        return batchId;
    }

    public EntryStatus status() {
        return status;
    }

    /** True when this entry contributes to reports. */
    public boolean isReportable() {
        return status == EntryStatus.POSTED;
    }

    public Amount totalDebits() {
        Amount total = Amount.ZERO;
        for (Posting posting : postings) {
            if (posting.baseAmount().isPositive()) {
                total = total.add(posting.baseAmount());
            }
        }
        return total;
    }

    /**
     * Retires this entry with a reason.
     *
     * <p>The correcting entry is posted alongside rather than replacing it, so
     * the journal shows both what was done and what was done about it.
     *
     * @throws IllegalArgumentException if no reason is given
     */
    public void voidEntry(String reason, String actorAccountId, BusinessInstant at) {
        if (Texts.isBlank(reason)) {
            throw new IllegalArgumentException(
                    "voiding an entry requires a reason. An unexplained reversal is "
                            + "indistinguishable from a mistake.");
        }
        if (status == EntryStatus.VOID) {
            throw new IllegalStateException("entry " + id + " is already void");
        }
        revisions.add(new Revision(revisions.size() + 1, "VOID", reason, actorAccountId, at,
                snapshot()));
        this.status = EntryStatus.VOID;
    }

    /**
     * Records a correction as a numbered revision, keeping the pre-state.
     *
     * <p>Used when an entry carries a wrong figure but was the right
     * transaction. When it was the wrong transaction entirely, void it instead.
     */
    public void recordRevision(String reason, String actorAccountId, BusinessInstant at) {
        if (Texts.isBlank(reason)) {
            throw new IllegalArgumentException(
                    "amending an entry requires a reason, kept as a numbered revision");
        }
        revisions.add(new Revision(revisions.size() + 1, "UPDATE", reason, actorAccountId, at,
                snapshot()));
    }

    public List<Revision> revisions() {
        return Immutables.copyOf(revisions);
    }

    private String snapshot() {
        StringBuilder text = new StringBuilder();
        text.append(description).append(" | ");
        for (Posting posting : postings) {
            text.append(posting.accountId()).append('=')
                .append(posting.baseAmount().toExactString()).append(' ');
        }
        return text.toString();
    }

    /** A numbered correction with its reason and the state before it. */
    public static final class Revision implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int number;
        private final String kind;
        private final String reason;
        private final String actorAccountId;
        private final BusinessInstant at;
        private final String preStateSnapshot;

        /**
         * Rebuilds a revision that was already recorded. Persistence is the only caller: a new
         * correction goes through {@link Entry#recordRevision} or {@link Entry#voidEntry}, which
         * is where the number and the pre-state come from. Handing those out would let a caller
         * write a history that never happened.
         */
        public static Revision of(int number, String kind, String reason, String actorAccountId,
                BusinessInstant at, String preStateSnapshot) {
            return new Revision(number, kind, reason, actorAccountId, at, preStateSnapshot);
        }

        Revision(int number, String kind, String reason, String actorAccountId, BusinessInstant at,
                String preStateSnapshot) {
            this.number = number;
            this.kind = kind;
            this.reason = reason;
            this.actorAccountId = actorAccountId;
            this.at = at;
            this.preStateSnapshot = preStateSnapshot;
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

        public BusinessInstant at() {
            return at;
        }

        /** What the entry looked like before this revision. */
        public String preStateSnapshot() {
            return preStateSnapshot;
        }
    }
}
