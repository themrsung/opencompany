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
import com.coreintra.compat.Texts;
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
 */
public class JournalService {

    private final JournalEntryRepository entries;
    private final JournalPostingRepository postings;
    private final JournalEntryRevisionRepository revisions;
    private final ChartOfAccountsService chart;

    public JournalService(JournalEntryRepository entries, JournalPostingRepository postings,
            JournalEntryRevisionRepository revisions, ChartOfAccountsService chart) {
        this.entries = entries;
        this.postings = postings;
        this.revisions = revisions;
        this.chart = chart;
    }

    /** Posts one entry. Reportable immediately. */
    @Transactional
    public Entry post(String bookId, NewEntry entry) {
        return writeAll(bookId, null, Immutables.listOf(entry)).get(0);
    }

    /**
     * Writes an entry that is not yet posted. Excluded from every report until it is, and it still
     * has to balance: a draft that could never post is not worth keeping.
     */
    @Transactional
    public Entry draft(String bookId, NewEntry entry) {
        requirePostableAccounts(bookId, entry);
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
    public List<Entry> writeAll(String bookId, String batchId, List<NewEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            throw new IllegalArgumentException("there are no entries to write");
        }
        List<Entry> built = new ArrayList<Entry>();
        int index = 0;
        for (NewEntry candidate : newEntries) {
            index++;
            requirePostableAccounts(bookId, candidate);
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

    public Entry load(String entryId) {
        JournalEntryRow row = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("there is no entry " + entryId));
        return hydrate(Immutables.listOf(row)).get(0);
    }

    /** Everything, in business order: drafts, posted entries and voids alike. */
    public List<Entry> journal(String bookId) {
        return hydrate(entries.findJournal(bookId));
    }

    /**
     * The entries reports are built from. Drafts and voids are excluded here, once, rather than in
     * each report - a report added later cannot forget a filter it never had to write.
     */
    public List<Entry> postedEntries(String bookId) {
        return hydrate(entries.findByStatus(bookId, Entry.EntryStatus.POSTED));
    }

    public List<Entry> entriesOfBatch(String batchId) {
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
    public Entry voidEntry(String entryId, String reason, String actorAccountId,
            BusinessInstant at) {
        requireActor(actorAccountId);
        Entry entry = load(entryId);
        int before = entry.revisions().size();
        entry.voidEntry(reason, actorAccountId, at);
        JournalEntryRow row = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("there is no entry " + entryId));
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
    public Entry correct(String entryId, String description, List<Posting> newPostings,
            String reason, String actorAccountId, BusinessInstant at) {
        requireActor(actorAccountId);
        Entry current = load(entryId);
        int before = current.revisions().size();
        current.recordRevision(reason, actorAccountId, at);

        for (Posting posting : newPostings) {
            chart.requirePostable(current.bookId(), posting.accountId());
        }
        Entry corrected = Entry.rehydrate(current.id(), current.bookId(), current.postedAt(),
                description == null ? current.description() : description, newPostings,
                current.batchId(), current.status(), current.revisions());

        postings.deleteByEntryId(entryId);
        writePostingRows(corrected);
        JournalEntryRow row = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("there is no entry " + entryId));
        row.syncFrom(corrected);
        entries.save(row);
        saveRevisionsFrom(corrected, before);
        return corrected;
    }

    /** The correction trail as stored, not a recomputation of it. */
    public List<Entry.Revision> revisionsOf(String entryId) {
        List<Entry.Revision> trail = new ArrayList<Entry.Revision>();
        for (JournalEntryRevisionRow row : revisions.findByEntryIdOrderByNumberAsc(entryId)) {
            trail.add(row.toDomain());
        }
        return trail;
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

    private static void requireActor(String actorAccountId) {
        if (Texts.isBlank(actorAccountId)) {
            // Stored NOT NULL as well. A correction trail that does not say who is a list of
            // changes, not an audit trail.
            throw new IllegalArgumentException("a correction has to say who made it");
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
        Map<String, List<Entry.Revision>> trails = revisionsOf(ids);
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

    private Map<String, List<Entry.Revision>> revisionsOf(Collection<String> entryIds) {
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
