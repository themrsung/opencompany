package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.persistence.AccountingBatchRepository;
import com.coreintra.accounting.persistence.AccountingBatchRow;
import com.coreintra.accounting.report.LedgerReports;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
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
 *
 * <h2>The batch permission does not widen the entry permission</h2>
 *
 * <p>{@code accounting.batch:create} authorises the grouping - an import, a closing run, a
 * recurring schedule. It does not authorise the entries: {@link JournalService#writeAll} checks
 * {@code accounting.entry:post} for every entry in the batch, on that entry's own business date.
 * Without that, batching would be a way to post entries one at a time you were not allowed to
 * post, which is the shape every authorisation hole in a ledger has.
 */
public class BatchService {

    private final AccountingBatchRepository batches;
    private final JournalService journal;
    private final AccountingGate gate;

    public BatchService(AccountingBatchRepository batches, JournalService journal,
            AccountingGate gate) {
        this.batches = batches;
        this.journal = journal;
        this.gate = gate;
    }

    /**
     * Writes a batch and its entries in one transaction.
     *
     * <p>The batch itself is checked on the earliest business date it touches. A batch spanning a
     * year-end is judged against the org chart of the day it starts affecting the books, and each
     * entry is judged again on its own date, so the later dates are not authorised by the earlier
     * one.
     *
     * @param generatorParams what a generator was asked for, or null for a hand-written batch.
     *     Stored as lineage and never re-executed.
     */
    @Transactional
    public Batch write(PermissionPrincipal caller, String bookId, BatchKind kind, String label,
            String note, String generatorParams, List<NewEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            throw new IllegalArgumentException(
                    "a batch with no entries records nothing and explains nothing");
        }
        gate.requireOnBook(caller, AccountingPermissions.BATCH_CREATE, bookId,
                earliestBusinessDate(newEntries),
                "write a " + kind + " batch of " + newEntries.size() + " entries into book "
                        + bookId);
        Batch batch = new Batch(UUID.randomUUID().toString(), bookId, kind, label, note,
                generatorParams, caller.accountId());
        batches.save(new AccountingBatchRow(batch));
        journal.writeAll(caller, bookId, batch.id(), newEntries);
        return batch;
    }

    public Optional<Batch> find(PermissionPrincipal caller, String batchId,
            LocalDate businessDate) {
        AccountingBatchRow row = batches.findById(AccountingGate.required(batchId, "batchId"))
                .orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        gate.requireOnBook(caller, AccountingPermissions.BATCH_READ, row.bookId(), businessDate,
                "read batch " + batchId);
        return Optional.of(row.toDomain());
    }

    public List<Batch> list(PermissionPrincipal caller, String bookId, LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.BATCH_READ, bookId, businessDate,
                "list the batches of book " + bookId);
        List<Batch> found = new ArrayList<Batch>();
        for (AccountingBatchRow row : batches.findByBookIdOrderByCreatedAtDesc(bookId)) {
            found.add(row.toDomain());
        }
        return found;
    }

    public List<Entry> entriesOf(PermissionPrincipal caller, String batchId,
            LocalDate businessDate) {
        AccountingBatchRow row = batches.findById(AccountingGate.required(batchId, "batchId"))
                .orElseThrow(() -> new NoSuchAccountingRecordException("batch", batchId));
        gate.requireOnBook(caller, AccountingPermissions.BATCH_READ, row.bookId(), businessDate,
                "read the entries of batch " + batchId);
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
    public int voidBatch(PermissionPrincipal caller, String batchId, String reason,
            BusinessInstant at) {
        AccountingBatchRow row = batches.findById(AccountingGate.required(batchId, "batchId"))
                .orElseThrow(() -> new NoSuchAccountingRecordException("batch", batchId));
        List<Entry> entries = journal.entriesOfBatch(batchId);
        // Checked on the earliest date the batch touches, and then again per entry inside
        // voidEntry, on that entry's own date. Voiding a batch cannot reach an entry the caller
        // could not have voided by itself.
        gate.requireOnBook(caller, AccountingPermissions.BATCH_VOID, row.bookId(),
                earliestPostedDate(entries, at.businessDate()),
                "void batch " + batchId + " and its " + entries.size() + " entries");
        int voided = 0;
        for (Entry entry : entries) {
            if (entry.status() == Entry.EntryStatus.VOID) {
                continue;
            }
            journal.voidEntry(caller, entry.id(), reason, at);
            voided++;
        }
        if (voided == 0) {
            throw new IllegalStateException("every entry in batch " + batchId
                    + " is already void");
        }
        return voided;
    }

    /** The earliest business date a set of proposed entries touches. */
    private static LocalDate earliestBusinessDate(List<NewEntry> newEntries) {
        LocalDate earliest = null;
        for (NewEntry entry : newEntries) {
            if (entry == null || entry.postedAt() == null) {
                throw new IllegalArgumentException("every entry in a batch needs a business "
                        + "instant. Which business day it falls in decides which period reports "
                        + "it (ADR 0002).");
            }
            LocalDate on = entry.postedAt().businessDate();
            if (earliest == null || on.isBefore(earliest)) {
                earliest = on;
            }
        }
        return earliest;
    }

    /**
     * @param fallback used when the batch has no entries left to read - the date of the void
     *     itself, which is the only business date the operation still has
     */
    private static LocalDate earliestPostedDate(List<Entry> entries, LocalDate fallback) {
        LocalDate earliest = null;
        for (Entry entry : entries) {
            LocalDate on = entry.postedAt().businessDate();
            if (earliest == null || on.isBefore(earliest)) {
                earliest = on;
            }
        }
        return earliest == null ? fallback : earliest;
    }

    /**
     * The closing-batch filter for a book's income statement.
     *
     * <p>Read once per report rather than per entry: a year's journal asks the same question ten
     * thousand times and the answer is a handful of ids.
     *
     * <p>Package-private: it is a detail of how a report is computed, reached only from
     * {@link LedgerReportService} once the caller has been authorised for
     * {@code accounting.report:read} on the same book.
     */
    LedgerReports.BatchKindLookup closingBatchesOf(String bookId) {
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
