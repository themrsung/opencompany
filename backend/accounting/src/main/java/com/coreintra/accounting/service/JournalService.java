package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.persistence.JournalEntryRepository;
import com.coreintra.accounting.persistence.JournalEntryRevisionRepository;
import com.coreintra.accounting.persistence.JournalEntryRevisionRow;
import com.coreintra.accounting.persistence.JournalEntryRow;
import com.coreintra.accounting.persistence.JournalPostingRepository;
import com.coreintra.accounting.persistence.JournalPostingRow;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing to and reading from the journal.
 *
 * <h2>All or nothing, twice over</h2>
 *
 * <p>{@link #writeAll} builds every {@link Entry} before it writes a single row. An unbalanced
 * entry cannot be constructed, so a batch containing one fails before anything has been written,
 * not halfway through - and the transaction is the second line of defence rather than the first.
 * That matters because "atomic" in a ledger has to mean "the third entry of forty never existed",
 * not "the third entry was rolled back after the first two were visible to a reader".
 *
 * <h2>Corrections leave both versions readable</h2>
 *
 * <p>{@link #correct} records a numbered revision holding the pre-state before it writes the new
 * lines, and {@link #voidEntry} hides an entry from every report while leaving it in the journal.
 * Neither deletes anything a reader might later need to explain a figure.
 *
 * <h2>Authorised on the entry's own business date</h2>
 *
 * <p>Every method here checks the caller against the book's company <em>as of the business date of
 * the entry being acted on</em> - not today's. A 3월 entry corrected in 8월 is judged against the
 * org chart of 3월, so a permission check on a past-dated document keeps answering the same way
 * after somebody is promoted or moves team. Today's date would make last quarter's ledger
 * un-auditable the first time anyone changed jobs, which is the failure ADR 0003 exists to prevent.
 *
 * <p>{@link #writeAll} checks <em>each</em> entry on its own date rather than the batch on one
 * date. A forty-entry import spanning a year-end is forty decisions, and collapsing them into one
 * would let an authority that only existed in December reach into March.
 *
 * <h2>The caller is the actor</h2>
 *
 * <p>{@code voidEntry} and {@code correct} used to take an {@code actorAccountId} beside the
 * principal. They no longer do: the correction trail records whoever the evaluator judged, so the
 * name in the audit trail cannot differ from the name that was authorised.
 */
public class JournalService {

    private final JournalEntryRepository entries;
    private final JournalPostingRepository postings;
    private final JournalEntryRevisionRepository revisions;
    private final ChartOfAccountsService chart;
    private final AccountingGate gate;

    public JournalService(JournalEntryRepository entries, JournalPostingRepository postings,
            JournalEntryRevisionRepository revisions, ChartOfAccountsService chart,
            AccountingGate gate) {
        this.entries = entries;
        this.postings = postings;
        this.revisions = revisions;
        this.chart = chart;
        this.gate = gate;
    }

    /** Posts one entry. Reportable immediately. */
    @Transactional
    public Entry post(PermissionPrincipal caller, String bookId, NewEntry entry) {
        return writeAll(caller, bookId, null, Immutables.listOf(entry)).get(0);
    }

    /**
     * Writes an entry that is not yet posted. Excluded from every report until it is, and it still
     * has to balance: a draft that could never post is not worth keeping.
     *
     * <p>Checked as a post rather than as something weaker. A draft is a proposal that a later
     * click turns into a figure in the accounts, and an account that may not post should not be
     * able to leave one waiting for somebody who can.
     */
    @Transactional
    public Entry draft(PermissionPrincipal caller, String bookId, NewEntry entry) {
        requirePostableAccounts(bookId, entry);
        requireMayPost(caller, bookId, entry, 1, 1);
        Entry built = Entry.draft(UUID.randomUUID().toString(), bookId, entry.postedAt(),
                entry.description(), entry.postings());
        writeRows(built);
        return built;
    }

    /**
     * Writes several entries as one unit, optionally as part of a batch.
     *
     * @param batchId null for entries that stand on their own
     * @throws Entry.UnbalancedEntryException naming which entry of how many failed, since a
     *     four-hundred-entry import that says only "does not balance" is unactionable
     */
    @Transactional
    public List<Entry> writeAll(PermissionPrincipal caller, String bookId, String batchId,
            List<NewEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            throw new IllegalArgumentException("there are no entries to write");
        }
        List<Entry> built = new ArrayList<Entry>();
        int index = 0;
        for (NewEntry candidate : newEntries) {
            index++;
            requirePostableAccounts(bookId, candidate);
            requireMayPost(caller, bookId, candidate, index, newEntries.size());
            try {
                built.add(Entry.post(UUID.randomUUID().toString(), bookId, candidate.postedAt(),
                        candidate.description(), candidate.postings(), batchId));
            } catch (Entry.UnbalancedEntryException failure) {
                throw new Entry.UnbalancedEntryException("entry " + index + " of "
                        + newEntries.size() + ": " + failure.getMessage(), failure.difference());
            }
        }
        for (Entry entry : built) {
            writeRows(entry);
        }
        return built;
    }

    /** One entry, if the caller may read the book it belongs to as it stood on the entry's date. */
    public Entry load(PermissionPrincipal caller, String entryId) {
        Entry entry = entryOf(entryId);
        requireOnEntry(caller, AccountingPermissions.ENTRY_READ, entry, "read entry " + entryId);
        return entry;
    }

    /** Everything, in business order: drafts, posted entries and voids alike. */
    public List<Entry> journal(PermissionPrincipal caller, String bookId, LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ENTRY_READ, bookId, businessDate,
                "read the journal of book " + bookId);
        return hydrate(entries.findJournal(bookId));
    }

    /**
     * The entries reports are built from. Drafts and voids are excluded here, once, rather than in
     * each report - a report added later cannot forget a filter it never had to write.
     */
    public List<Entry> postedEntries(PermissionPrincipal caller, String bookId,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ENTRY_READ, bookId, businessDate,
                "read the posted entries of book " + bookId);
        return postedEntriesOf(bookId);
    }

    /**
     * The same set, for a caller this module has already authorised on this book.
     *
     * <p>Package-private, and reached only from {@link LedgerReportService} once it has checked
     * {@code accounting.report:read}. Reports and the journal are deliberately separate
     * permissions - a manager entitled to the income statement is not thereby entitled to every
     * memo line behind it - so a report that also demanded {@code accounting.entry:read} would
     * make the finer grant unusable.
     */
    List<Entry> postedEntriesOf(String bookId) {
        return hydrate(entries.findByStatus(bookId, Entry.EntryStatus.POSTED));
    }

    /** The entries of a batch, for a caller already authorised on the batch's book. */
    List<Entry> entriesOfBatch(String batchId) {
        return hydrate(entries.findByBatchId(batchId));
    }

    /**
     * Retires an entry that was the wrong transaction.
     *
     * <p>It disappears from every report and stays in the journal, because the journal is the
     * record of what was done, including what was done wrongly. The correcting entry is posted
     * alongside.
     */
    @Transactional
    public Entry voidEntry(PermissionPrincipal caller, String entryId, String reason,
            BusinessInstant at) {
        Entry entry = entryOf(entryId);
        requireOnEntry(caller, AccountingPermissions.ENTRY_VOID, entry, "void entry " + entryId);
        int before = entry.revisions().size();
        entry.voidEntry(reason, caller.accountId(), at);
        JournalEntryRow row = entries.findById(entryId)
                .orElseThrow(() -> new NoSuchAccountingRecordException("entry", entryId));
        row.syncFrom(entry);
        entries.save(row);
        saveRevisionsFrom(entry, before);
        return entry;
    }

    /**
     * Corrects an entry that was the right transaction with the wrong figures.
     *
     * <p>The revision is recorded <em>before</em> the new lines are written, so its snapshot is
     * genuinely the pre-state. The new lines must balance like any others; if they do not, nothing
     * has been touched.
     */
    @Transactional
    public Entry correct(PermissionPrincipal caller, String entryId, String description,
            List<Posting> newPostings, String reason, BusinessInstant at) {
        Entry current = entryOf(entryId);
        requireOnEntry(caller, AccountingPermissions.ENTRY_UPDATE, current,
                "correct entry " + entryId);
        int before = current.revisions().size();
        current.recordRevision(reason, caller.accountId(), at);

        for (Posting posting : newPostings) {
            chart.requirePostable(current.bookId(), posting.accountId());
        }
        Entry corrected = Entry.rehydrate(current.id(), current.bookId(), current.postedAt(),
                description == null ? current.description() : description, newPostings,
                current.batchId(), current.status(), current.revisions());

        postings.deleteByEntryId(entryId);
        writePostingRows(corrected);
        JournalEntryRow row = entries.findById(entryId)
                .orElseThrow(() -> new NoSuchAccountingRecordException("entry", entryId));
        row.syncFrom(corrected);
        entries.save(row);
        saveRevisionsFrom(corrected, before);
        return corrected;
    }

    /** The correction trail as stored, not a recomputation of it. */
    public List<Entry.Revision> revisionsOf(PermissionPrincipal caller, String entryId) {
        Entry entry = entryOf(entryId);
        requireOnEntry(caller, AccountingPermissions.ENTRY_READ, entry,
                "read the correction trail of entry " + entryId);
        List<Entry.Revision> trail = new ArrayList<Entry.Revision>();
        for (JournalEntryRevisionRow row : revisions.findByEntryIdOrderByNumberAsc(entryId)) {
            trail.add(row.toDomain());
        }
        return trail;
    }

    /** Loads without deciding. Every public caller authorises the entry it gets back. */
    private Entry entryOf(String entryId) {
        JournalEntryRow row = entries.findById(AccountingGate.required(entryId, "entryId"))
                .orElseThrow(() -> new NoSuchAccountingRecordException("entry", entryId));
        return hydrate(Immutables.listOf(row)).get(0);
    }

    /** The check that matters: the entry's own business date, never today's. */
    private void requireOnEntry(PermissionPrincipal caller, PermissionKey key, Entry entry,
            String description) {
        gate.requireOnBook(caller, key, entry.bookId(), entry.postedAt().businessDate(),
                description);
    }

    /**
     * @param index which entry of the batch this is, so a refusal in the middle of an import says
     *     where rather than leaving the caller to bisect four hundred rows
     */
    private void requireMayPost(PermissionPrincipal caller, String bookId, NewEntry entry,
            int index, int total) {
        if (entry == null || entry.postedAt() == null) {
            throw new IllegalArgumentException("an entry needs a business instant. Which business "
                    + "day it falls in decides which period reports it (ADR 0002).");
        }
        String where = total == 1 ? "post an entry" : "post entry " + index + " of " + total;
        gate.requireOnBook(caller, AccountingPermissions.ENTRY_POST, bookId,
                entry.postedAt().businessDate(),
                where + " dated " + entry.postedAt().businessDate() + " into book " + bookId);
    }

    private void requirePostableAccounts(String bookId, NewEntry entry) {
        if (entry == null || entry.postings().isEmpty()) {
            throw new IllegalArgumentException("an entry needs postings");
        }
        for (Posting posting : entry.postings()) {
            chart.requirePostable(bookId, posting.accountId());
        }
    }

    private void writeRows(Entry entry) {
        entries.save(new JournalEntryRow(entry));
        writePostingRows(entry);
    }

    private void writePostingRows(Entry entry) {
        int position = 0;
        for (Posting posting : entry.postings()) {
            postings.save(new JournalPostingRow(UUID.randomUUID().toString(), entry.id(),
                    entry.bookId(), position, posting));
            position++;
        }
    }

    private void saveRevisionsFrom(Entry entry, int alreadyStored) {
        List<Entry.Revision> all = entry.revisions();
        for (int index = alreadyStored; index < all.size(); index++) {
            revisions.save(new JournalEntryRevisionRow(UUID.randomUUID().toString(), entry.id(),
                    all.get(index)));
        }
    }

    private List<Entry> hydrate(List<JournalEntryRow> rows) {
        List<Entry> hydrated = new ArrayList<Entry>();
        if (rows.isEmpty()) {
            return hydrated;
        }
        List<String> ids = new ArrayList<String>();
        for (JournalEntryRow row : rows) {
            ids.add(row.id());
        }
        Map<String, List<Posting>> lines = postingsOf(ids);
        Map<String, List<Entry.Revision>> trails = revisionTrailsOf(ids);
        for (JournalEntryRow row : rows) {
            List<Posting> entryPostings = lines.get(row.id());
            if (entryPostings == null) {
                throw new IllegalStateException("entry " + row.id() + " has no postings. An entry "
                        + "without lines cannot balance and should not exist; the row reached the "
                        + "table without going through Entry.post.");
            }
            // Rehydration re-proves that debits equal credits, so a row that stopped balancing
            // fails in front of whoever is reading rather than skewing a report quietly.
            hydrated.add(Entry.rehydrate(row.id(), row.bookId(), row.postedAt(), row.description(),
                    entryPostings, row.batchId(), row.status(), trails.get(row.id())));
        }
        return hydrated;
    }

    private Map<String, List<Posting>> postingsOf(Collection<String> entryIds) {
        Map<String, List<Posting>> byEntry = new LinkedHashMap<String, List<Posting>>();
        for (JournalPostingRow row : postings.findByEntryIdInOrderByEntryIdAscPositionAsc(entryIds)) {
            List<Posting> lines = byEntry.get(row.entryId());
            if (lines == null) {
                lines = new ArrayList<Posting>();
                byEntry.put(row.entryId(), lines);
            }
            lines.add(row.toDomain());
        }
        return byEntry;
    }

    private Map<String, List<Entry.Revision>> revisionTrailsOf(Collection<String> entryIds) {
        Map<String, List<Entry.Revision>> byEntry =
                new LinkedHashMap<String, List<Entry.Revision>>();
        for (JournalEntryRevisionRow row
                : revisions.findByEntryIdInOrderByEntryIdAscNumberAsc(entryIds)) {
            List<Entry.Revision> trail = byEntry.get(row.entryId());
            if (trail == null) {
                trail = new ArrayList<Entry.Revision>();
                byEntry.put(row.entryId(), trail);
            }
            trail.add(row.toDomain());
        }
        return byEntry;
    }
}
