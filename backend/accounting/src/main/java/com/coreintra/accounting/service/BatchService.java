package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.persistence.AccountingBatchRepository;
import com.coreintra.accounting.persistence.AccountingBatchRow;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.businesstime.BusinessInstant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches: many entries written as one thing, and still one thing afterwards.
 *
 * <h2>Atomic before it is transactional</h2>
 *
 * <p>{@link JournalService#writeAll} constructs every entry before writing any of them, so an
 * import with one bad line fails having written nothing at all. The transaction is the second
 * guarantee, not the first.
 *
 * <h2>Closing batches are the one kind that changes a report</h2>
 *
 * <p>{@link #closingBatchesOf} is handed to the income statement so that closing entries are left
 * out and a closed year still reports what it earned. It is a query against the batch's kind, so
 * the rule cannot be forgotten by a report and cannot drift from a flag copied onto ten thousand
 * entries.
 */
public class BatchService {

    private final AccountingBatchRepository batches;
    private final JournalService journal;

    public BatchService(AccountingBatchRepository batches, JournalService journal) {
        this.batches = batches;
        this.journal = journal;
    }

    /**
     * Writes a batch and its entries in one transaction.
     *
     * @param generatorParams what a generator was asked for, or null for a hand-written batch.
     *     Stored as lineage and never re-executed.
     */
    @Transactional
    public Batch write(String bookId, BatchKind kind, String label, String note,
            String generatorParams, List<NewEntry> newEntries, String actorAccountId) {
        if (newEntries == null || newEntries.isEmpty()) {
            throw new IllegalArgumentException(
                    "a batch with no entries records nothing and explains nothing");
        }
        Batch batch = new Batch(UUID.randomUUID().toString(), bookId, kind, label, note,
                generatorParams, actorAccountId);
        batches.save(new AccountingBatchRow(batch));
        journal.writeAll(bookId, batch.id(), newEntries);
        return batch;
    }

    public Optional<Batch> find(String batchId) {
        return batches.findById(batchId).map(AccountingBatchRow::toDomain);
    }

    public List<Batch> list(String bookId) {
        List<Batch> found = new ArrayList<Batch>();
        for (AccountingBatchRow row : batches.findByBookIdOrderByCreatedAtDesc(bookId)) {
            found.add(row.toDomain());
        }
        return found;
    }

    public List<Entry> entriesOf(String batchId) {
        return journal.entriesOfBatch(batchId);
    }

    /**
     * Voids every entry of a batch, with one reason covering all of them.
     *
     * <p>Entries already voided individually are left alone rather than making the whole call
     * fail: the caller asked for the batch to be off the books, and it is.
     *
     * @return how many entries this call voided
     */
    @Transactional
    public int voidBatch(String batchId, String reason, String actorAccountId, BusinessInstant at) {
        find(batchId).orElseThrow(
                () -> new IllegalArgumentException("there is no batch " + batchId));
        List<Entry> entries = journal.entriesOfBatch(batchId);
        int voided = 0;
        for (Entry entry : entries) {
            if (entry.status() == Entry.EntryStatus.VOID) {
                continue;
            }
            journal.voidEntry(entry.id(), reason, actorAccountId, at);
            voided++;
        }
        if (voided == 0) {
            throw new IllegalStateException("every entry in batch " + batchId
                    + " is already void");
        }
        return voided;
    }

    /**
     * The closing-batch filter for a book's income statement.
     *
     * <p>Read once per report rather than per entry: a year's journal asks the same question ten
     * thousand times and the answer is a handful of ids.
     */
    public LedgerReports.BatchKindLookup closingBatchesOf(String bookId) {
        Set<String> closing = new HashSet<String>();
        for (AccountingBatchRow row
                : batches.findByBookIdAndKind(bookId, BatchKind.CLOSING.name())) {
            closing.add(row.id());
        }
        return new ClosingBatches(closing);
    }

    /** A snapshot of which batches were closing batches when the report was run. */
    private static final class ClosingBatches implements LedgerReports.BatchKindLookup {

        private final Set<String> ids;

        ClosingBatches(Set<String> ids) {
            this.ids = ids;
        }

        @Override
        public boolean isClosingBatch(String batchId) {
            return batchId != null && ids.contains(batchId);
        }
    }
}
