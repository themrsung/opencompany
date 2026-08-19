package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.service.AccountingGate;
import com.coreintra.accounting.service.BatchService;
import com.coreintra.accounting.service.JournalService;
import com.coreintra.accounting.service.NewEntry;
import com.coreintra.accounting.service.NoSuchAccountingRecordException;
import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.paging.Cursors;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal: entries, their corrections, and the batches that group them.
 *
 * <h2>Every amount here is a string</h2>
 *
 * <p>In and out, at every level, including inside a posting inside an entry inside a batch. ADR
 * 0004: a JSON number becomes an IEEE-754 double in most parsers, and 333333.33333333333333 comes
 * back as 333333.3333333333 with nothing to say it happened. {@link Amounts} holds the rule.
 *
 * <h2>Signs, and why the wire does not carry them</h2>
 *
 * <p>Storage keeps one signed number per posting, because a magnitude plus a side flag can
 * disagree and then nothing downstream knows which to believe. The wire carries {@code side} plus
 * a positive magnitude instead, in both directions, so a client cannot post a credit by sending a
 * positive number to the wrong field. {@code signedBaseAmount} is there as well for a caller that
 * wants to add a column up without branching, and it is derived from the same figure.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Both POSTs here create money and both require {@code Idempotency-Key}. A retry with the same
 * key and the same body replays the first response; the same key with a different body is refused
 * with a 409 rather than answered with the first result, because a client that has done that is
 * confused about which operation it is retrying.
 */
@RestController
@RequestMapping("/api/v1/accounting")
@AccountingEnabled
@Tag(name = "Accounting: journal",
        description = "Entries, corrections and batches. Debits equal credits exactly or the "
                + "entry does not exist; corrections are numbered revisions and a wrong "
                + "transaction is voided, never deleted.")
public class JournalController {

    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 500;

    private final JournalService journal;
    private final BatchService batches;
    private final AccountingIdempotency idempotency;
    private final CurrentPrincipal current;
    private final AccountingGate gate;

    /**
     * @param gate held only to answer "which company does this book belong to", which the
     *     idempotency key has to be scoped by before the work runs and therefore before the work
     *     authorises itself. It is not an authorisation path and nothing is read through it: a
     *     caller who is about to be refused can, at worst, reserve an idempotency key against
     *     their own account, which expires in a day and inconveniences nobody else.
     */
    public JournalController(JournalService journal, BatchService batches,
            AccountingIdempotency idempotency, CurrentPrincipal current, AccountingGate gate) {
        this.journal = journal;
        this.batches = batches;
        this.idempotency = idempotency;
        this.current = current;
        this.gate = gate;
    }

    // ------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------

    @GetMapping("/books/{bookId}/entries")
    @Operation(summary = "The journal, in business order",
            description = "Requires accounting.entry:read. Drafts and voided entries are included "
                    + "— this is the journal, not a report — and each says which it is. Ordered "
                    + "by business date then by offset, never by the wire string.")
    public CursorPage<EntryResponse> entries(@PathVariable("bookId") String bookId,
            @Parameter(description = "Opaque; from the previous page's nextCursor.")
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        List<Entry> all = journal.journal(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate));
        return page(all, cursor, limit);
    }

    @GetMapping("/entries/{entryId}")
    @Operation(summary = "One entry, with its correction trail",
            description = "Requires accounting.entry:read, resolved as of the entry's own "
                    + "business date rather than today's.")
    public ResponseEntity<EntryResponse> entry(@PathVariable("entryId") String entryId) {
        PermissionPrincipal caller = current.require();
        Entry entry = journal.load(caller, entryId);
        List<Entry.Revision> trail = journal.revisionsOf(caller, entryId);
        return ResponseEntity.ok().eTag(etagOf(entry)).body(new EntryResponse(entry, trail));
    }

    @PostMapping("/books/{bookId}/entries")
    @Operation(summary = "Post an entry",
            description = "Requires accounting.entry:post, resolved as of the entry's own posted "
                    + "business date. Requires an Idempotency-Key header. Debits must equal "
                    + "credits exactly in the book's base currency; a rounding gap is a refusal, "
                    + "never absorbed.")
    @ApiResponse(responseCode = "201", description = "The entry as posted",
            content = @Content(schema = @Schema(implementation = EntryResponse.class)))
    public ResponseEntity<Object> post(@PathVariable("bookId") String bookId,
            @RequestHeader(value = "Idempotency-Key", required = false) final String key,
            @Valid @RequestBody final PostEntryRequest request) {
        final PermissionPrincipal caller = current.require();
        String companyId = gate.book(bookId).companyId();
        return idempotency.once(caller, companyId, key, "POST /api/v1/accounting/books/{bookId}"
                + "/entries", request, HttpStatus.CREATED, () -> {
                    Entry posted = request.isDraft()
                            ? journal.draft(caller, bookId, request.toNewEntry())
                            : journal.post(caller, bookId, request.toNewEntry());
                    return new EntryResponse(posted, journal.revisionsOf(caller, posted.id()));
                });
    }

    @PatchMapping("/entries/{entryId}")
    @Operation(summary = "Correct an entry that carries a wrong figure",
            description = "Requires accounting.entry:update and If-Match. The pre-state is kept "
                    + "as a numbered revision with a mandatory reason, recorded before the new "
                    + "lines are written. The new lines must balance like any others; if they do "
                    + "not, nothing has been touched. When the entry was the wrong transaction "
                    + "entirely, void it instead.")
    public ResponseEntity<EntryResponse> correct(@PathVariable("entryId") String entryId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CorrectEntryRequest request) {
        PermissionPrincipal caller = current.require();
        Entry before = journal.load(caller, entryId);
        ETags.require(ifMatch, etagOf(before), "entry");

        Entry corrected = journal.correct(caller, entryId, request.getDescription(),
                request.postings(), request.getReason(), request.at(before));
        List<Entry.Revision> trail = journal.revisionsOf(caller, entryId);
        return ResponseEntity.ok().eTag(etagOf(corrected))
                .body(new EntryResponse(corrected, trail));
    }

    @PostMapping("/entries/{entryId}/void")
    @Operation(summary = "Void an entry that was the wrong transaction",
            description = "Requires accounting.entry:void and If-Match. The entry disappears from "
                    + "every report and stays in the journal, because the journal is the record "
                    + "of what was done, including what was done wrongly. A reason is mandatory: "
                    + "an unexplained reversal is indistinguishable from a mistake.")
    public ResponseEntity<EntryResponse> voidEntry(@PathVariable("entryId") String entryId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody VoidRequest request) {
        PermissionPrincipal caller = current.require();
        Entry before = journal.load(caller, entryId);
        ETags.require(ifMatch, etagOf(before), "entry");

        Entry voided = journal.voidEntry(caller, entryId, request.getReason(),
                request.at(before));
        List<Entry.Revision> trail = journal.revisionsOf(caller, entryId);
        return ResponseEntity.ok().eTag(etagOf(voided)).body(new EntryResponse(voided, trail));
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    @GetMapping("/books/{bookId}/batches")
    @Operation(summary = "The batches of a book, newest first",
            description = "Requires accounting.batch:read.")
    public List<BatchResponse> batches(@PathVariable("bookId") String bookId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        List<BatchResponse> found = new ArrayList<BatchResponse>();
        for (Batch batch : batches.list(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate))) {
            found.add(new BatchResponse(batch, null));
        }
        return found;
    }

    @GetMapping("/batches/{batchId}")
    @Operation(summary = "One batch and its entries",
            description = "Requires accounting.batch:read. A batch stays first-class after it is "
                    + "written: listable, fetchable and voidable as a unit.")
    public BatchResponse batch(@PathVariable("batchId") String batchId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(businessDate);
        Batch batch = batches.find(caller, batchId, asOf)
                .orElseThrow(() -> new NoSuchAccountingRecordException("batch", batchId));
        List<EntryResponse> entries = new ArrayList<EntryResponse>();
        for (Entry entry : batches.entriesOf(caller, batchId, asOf)) {
            entries.add(new EntryResponse(entry, null));
        }
        return new BatchResponse(batch, entries);
    }

    @PostMapping("/books/{bookId}/batches")
    @Operation(summary = "Write many entries as one atomic batch",
            description = "Requires accounting.batch:create AND accounting.entry:post for every "
                    + "entry, each resolved as of that entry's own business date — batching is "
                    + "not a way to post an entry you could not post alone. Requires an "
                    + "Idempotency-Key header. All or nothing: one unbalanced entry and nothing "
                    + "is written. Kind CLOSING excludes the entries from the income statement, "
                    + "so a closed year still reports what it earned.")
    @ApiResponse(responseCode = "201", description = "The batch as written",
            content = @Content(schema = @Schema(implementation = BatchResponse.class)))
    public ResponseEntity<Object> writeBatch(@PathVariable("bookId") final String bookId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody final WriteBatchRequest request) {
        final PermissionPrincipal caller = current.require();
        String companyId = gate.book(bookId).companyId();
        return idempotency.once(caller, companyId, key, "POST /api/v1/accounting/books/{bookId}"
                + "/batches", request, HttpStatus.CREATED, () -> {
                    Batch written = batches.write(caller, bookId, request.kind(),
                            request.getLabel(), request.getNote(), request.getGeneratorParams(),
                            request.toNewEntries());
                    List<EntryResponse> entries = new ArrayList<EntryResponse>();
                    for (Entry entry : batches.entriesOf(caller, written.id(),
                            request.earliestBusinessDate())) {
                        entries.add(new EntryResponse(entry, null));
                    }
                    return new BatchResponse(written, entries);
                });
    }

    @PostMapping("/batches/{batchId}/void")
    @Operation(summary = "Void every entry of a batch, with one reason",
            description = "Requires accounting.batch:void AND accounting.entry:void per entry. "
                    + "Entries already voided individually are left alone: the caller asked for "
                    + "the batch to be off the books, and it is.")
    public VoidedBatchResponse voidBatch(@PathVariable("batchId") String batchId,
            @Valid @RequestBody VoidRequest request) {
        PermissionPrincipal caller = current.require();
        BusinessInstant at = request.getAt() == null
                ? BusinessInstant.startOfDay(LocalDate.now()) : request.getAt();
        int voided = batches.voidBatch(caller, batchId, request.getReason(), at);
        return new VoidedBatchResponse(batchId, voided);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * An entry's entity tag.
     *
     * <p>The revision count is the version, and it is the right one: every change to an entry —
     * a correction, a void — appends a numbered revision, so two clients holding the same count
     * are looking at the same entry. Hashing the response instead would make the tag depend on
     * which fields the DTO happens to carry.
     */
    private static String etagOf(Entry entry) {
        return ETags.of(entry.id(), entry.revisions().size());
    }

    /**
     * One page, cut in memory from the business-ordered journal.
     *
     * <p>The cursor is the business instant plus the entry id, exactly as {@link Cursors}
     * prescribes: the instant alone is not unique, because entries stamped at the same instant are
     * the ordinary case and a non-total order makes a page either skip a row or repeat one
     * forever.
     */
    private static CursorPage<EntryResponse> page(List<Entry> all, String cursor, Integer limit) {
        int pageSize = Cursors.pageSize(limit, DEFAULT_PAGE, MAX_PAGE);
        Cursors.Position from = Cursors.decode(cursor);
        boolean resuming = from != null;

        List<EntryResponse> page = new ArrayList<EntryResponse>();
        String nextCursor = null;
        for (Entry entry : all) {
            String key = Cursors.sortKey(entry.postedAt());
            if (resuming) {
                int byKey = key.compareTo(from.sortKey());
                if (byKey < 0 || (byKey == 0 && entry.id().compareTo(from.id()) <= 0)) {
                    continue;
                }
            }
            if (page.size() == pageSize) {
                // There is at least one more row, so the page ends here and the cursor names the
                // last row returned - not this one, which would start the next page a row late.
                EntryResponse last = page.get(page.size() - 1);
                nextCursor = Cursors.encode(last.sortKey, last.getId());
                break;
            }
            page.add(new EntryResponse(entry, null));
        }
        return new CursorPage<EntryResponse>(page, nextCursor);
    }

    // ------------------------------------------------------------------
    // Wire types
    // ------------------------------------------------------------------

    /** One line of an entry. Every amount is an exact decimal string. */
    public static class PostingResponse {
        private final String accountId;
        private final String side;
        private final String amount;
        private final String currencyCode;
        private final String baseAmount;
        private final String signedBaseAmount;
        private final String rate;
        private final String clientId;
        private final String memo;

        PostingResponse(Posting posting) {
            this.accountId = posting.accountId();
            this.side = posting.isDebit() ? "debit" : "credit";
            this.amount = Amounts.wire(magnitude(posting.amount()));
            this.currencyCode = posting.currencyCode();
            this.baseAmount = Amounts.wire(magnitude(posting.baseAmount()));
            this.signedBaseAmount = Amounts.wire(posting.baseAmount());
            this.rate = posting.rate() == null ? null : posting.rate().toPlainString();
            this.clientId = posting.clientId();
            this.memo = posting.memo();
        }

        private static Amount magnitude(Amount value) {
            return value.isNegative() ? value.negate() : value;
        }

        public String getAccountId() {
            return accountId;
        }

        /** {@code debit} or {@code credit}. The sign, said in words. */
        public String getSide() {
            return side;
        }

        /** In the account's own currency, as a positive magnitude. */
        public String getAmount() {
            return amount;
        }

        /** Null when the posting is already in the book's base currency. */
        public String getCurrencyCode() {
            return currencyCode;
        }

        /** In the book's base currency, as a positive magnitude. This is what balances. */
        public String getBaseAmount() {
            return baseAmount;
        }

        /** The same figure, signed: positive is a debit. For adding a column up without branching. */
        public String getSignedBaseAmount() {
            return signedBaseAmount;
        }

        /**
         * The rate the business used, as supplied. Never looked up by this system.
         *
         * <p>A string like every other decimal here, and for the same reason: a rate of
         * 1320.4567 is not something to hand to a double.
         */
        public String getRate() {
            return rate;
        }

        /** 거래처, if this line carries one. */
        public String getClientId() {
            return clientId;
        }

        public String getMemo() {
            return memo;
        }
    }

    /** One correction, kept for good. */
    public static class RevisionResponse {
        private final int number;
        private final String kind;
        private final String reason;
        private final String actorAccountId;
        private final BusinessInstant at;
        private final String preStateSnapshot;

        RevisionResponse(Entry.Revision revision) {
            this.number = revision.number();
            this.kind = revision.kind();
            this.reason = revision.reason();
            this.actorAccountId = revision.actorAccountId();
            this.at = revision.at();
            this.preStateSnapshot = revision.preStateSnapshot();
        }

        public int getNumber() {
            return number;
        }

        /** {@code UPDATE} or {@code VOID}. */
        public String getKind() {
            return kind;
        }

        public String getReason() {
            return reason;
        }

        public String getActorAccountId() {
            return actorAccountId;
        }

        public BusinessInstant getAt() {
            return at;
        }

        /** What the entry looked like before this revision. */
        public String getPreStateSnapshot() {
            return preStateSnapshot;
        }
    }

    /** An entry as it stands. */
    public static class EntryResponse {
        private final String id;
        private final String bookId;
        private final String batchId;
        private final BusinessInstant postedAt;
        private final String description;
        private final String status;
        private final boolean reportable;
        private final String totalDebits;
        private final List<PostingResponse> postings;
        private final List<RevisionResponse> revisions;
        private final String sortKey;

        public EntryResponse(Entry entry, List<Entry.Revision> trail) {
            this.id = entry.id();
            this.bookId = entry.bookId();
            this.batchId = entry.batchId();
            this.postedAt = entry.postedAt();
            this.description = entry.description();
            this.status = entry.status().name();
            this.reportable = entry.isReportable();
            this.totalDebits = Amounts.wire(entry.totalDebits());
            this.postings = new ArrayList<PostingResponse>();
            for (Posting posting : entry.postings()) {
                this.postings.add(new PostingResponse(posting));
            }
            this.revisions = new ArrayList<RevisionResponse>();
            List<Entry.Revision> source = trail == null ? entry.revisions() : trail;
            for (Entry.Revision revision : source) {
                this.revisions.add(new RevisionResponse(revision));
            }
            this.sortKey = Cursors.sortKey(entry.postedAt());
        }

        public String getId() {
            return id;
        }

        public String getBookId() {
            return bookId;
        }

        /** The batch this entry was written as part of, or null when it stands alone. */
        public String getBatchId() {
            return batchId;
        }

        public BusinessInstant getPostedAt() {
            return postedAt;
        }

        public String getDescription() {
            return description;
        }

        /** {@code DRAFT}, {@code POSTED} or {@code VOID}. */
        public String getStatus() {
            return status;
        }

        /** True when this entry contributes to reports. Drafts and voids do not. */
        public boolean isReportable() {
            return reportable;
        }

        /** The entry's size, which is also its credits. An exact decimal string. */
        public String getTotalDebits() {
            return totalDebits;
        }

        public List<PostingResponse> getPostings() {
            return postings;
        }

        public List<RevisionResponse> getRevisions() {
            return revisions;
        }
    }

    /** A batch, and its entries when they were asked for. */
    public static class BatchResponse {
        private final String id;
        private final String bookId;
        private final String kind;
        private final String label;
        private final String note;
        private final String generatorParams;
        private final String createdBy;
        private final List<EntryResponse> entries;

        BatchResponse(Batch batch, List<EntryResponse> entries) {
            this.id = batch.id();
            this.bookId = batch.bookId();
            this.kind = batch.kind().name();
            this.label = batch.label();
            this.note = batch.note();
            this.generatorParams = batch.generatorParams();
            this.createdBy = batch.createdBy();
            this.entries = entries;
        }

        public String getId() {
            return id;
        }

        public String getBookId() {
            return bookId;
        }

        /** MANUAL, IMPORT, CLOSING, RECURRING, AMORTIZATION or FX_REVALUATION. */
        public String getKind() {
            return kind;
        }

        public String getLabel() {
            return label;
        }

        public String getNote() {
            return note;
        }

        /**
         * What a generator was asked for, stored as lineage.
         *
         * <p>Never re-executed. Re-running a schedule against today's chart of accounts would
         * quietly produce a different past.
         */
        public String getGeneratorParams() {
            return generatorParams;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        /** Null on the list view, populated when one batch was asked for. */
        public List<EntryResponse> getEntries() {
            return entries;
        }
    }

    /** How many entries a batch void actually took off the books. */
    public static class VoidedBatchResponse {
        private final String batchId;
        private final int voidedEntries;

        VoidedBatchResponse(String batchId, int voidedEntries) {
            this.batchId = batchId;
            this.voidedEntries = voidedEntries;
        }

        public String getBatchId() {
            return batchId;
        }

        /** Entries already void before the call are not counted; they were already off. */
        public int getVoidedEntries() {
            return voidedEntries;
        }
    }

    /** One line of an entry being written. */
    public static class PostingRequest {
        @NotBlank
        private String accountId;
        @NotBlank
        private String side;
        @NotBlank
        private String amount;
        private String currencyCode;
        private String baseAmount;
        private String rate;
        private String clientId;
        private String memo;

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String value) {
            this.accountId = value;
        }

        /** {@code debit} or {@code credit}. The sign is applied here, never sent. */
        public String getSide() {
            return side;
        }

        public void setSide(String value) {
            this.side = value;
        }

        /**
         * A positive magnitude, as an exact decimal string.
         *
         * <p>Thousands separators are accepted; {@code 1e3}, {@code .5} and {@code 1.} are
         * refused, because each is a client that believes something about the format which is not
         * true and guessing is how a figure ends up a thousand times too small.
         */
        public String getAmount() {
            return amount;
        }

        public void setAmount(String value) {
            this.amount = value;
        }

        /** Set only for a foreign-currency posting. */
        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(String value) {
            this.currencyCode = value;
        }

        /** The base-currency equivalent, as a positive magnitude. Required with a currency. */
        public String getBaseAmount() {
            return baseAmount;
        }

        public void setBaseAmount(String value) {
            this.baseAmount = value;
        }

        /** The rate the business used. Supplied and recorded; this system never looks one up. */
        public String getRate() {
            return rate;
        }

        public void setRate(String value) {
            this.rate = value;
        }

        /** Overrides the entry-level 거래처 for this line. */
        public String getClientId() {
            return clientId;
        }

        public void setClientId(String value) {
            this.clientId = value;
        }

        public String getMemo() {
            return memo;
        }

        public void setMemo(String value) {
            this.memo = value;
        }

        Posting toDomain(int index) {
            boolean debit = isDebit(index);
            Amount magnitude = Amounts.parse(amount, "postings[" + index + "].amount");
            if (!magnitude.isPositive()) {
                throw new IllegalArgumentException("postings[" + index + "].amount must be a "
                        + "positive magnitude; the side field carries the sign");
            }
            Posting posting;
            if (Texts.isBlank(currencyCode)) {
                posting = debit
                        ? Posting.debit(accountId, magnitude)
                        : Posting.credit(accountId, magnitude);
            } else {
                Amount base = Amounts.parse(baseAmount, "postings[" + index + "].baseAmount");
                if (Texts.isBlank(rate)) {
                    throw new IllegalArgumentException("postings[" + index + "] is in "
                            + currencyCode + ", so it needs the rate the business used. This "
                            + "system never looks one up: a fetched rate is one nobody agreed to "
                            + "and cannot be reproduced later.");
                }
                posting = Posting.foreignCurrency(accountId,
                        debit ? magnitude : magnitude.negate(), currencyCode,
                        debit ? base : base.negate(), parseRate(index));
            }
            if (!Texts.isBlank(clientId)) {
                posting = posting.withClient(Texts.strip(clientId));
            }
            if (!Texts.isBlank(memo)) {
                posting = posting.withMemo(Texts.strip(memo));
            }
            return posting;
        }

        private boolean isDebit(int index) {
            if ("debit".equalsIgnoreCase(side)) {
                return true;
            }
            if ("credit".equalsIgnoreCase(side)) {
                return false;
            }
            throw new IllegalArgumentException(
                    "postings[" + index + "].side must be \"debit\" or \"credit\", got: " + side);
        }

        private BigDecimal parseRate(int index) {
            // Parsed with the same rule as an amount and then handed over as a BigDecimal: a rate
            // is a decimal that must not become a double either.
            return Amounts.parse(rate, "postings[" + index + "].rate").value();
        }
    }

    /** Posting one entry. */
    public static class PostEntryRequest {
        private BusinessInstant postedAt;
        @NotBlank
        private String description;
        private String clientId;
        private boolean draft;
        @NotEmpty
        private List<PostingRequest> postings;

        /** When in business time this happened. Decides which period reports it. */
        public BusinessInstant getPostedAt() {
            return postedAt;
        }

        public void setPostedAt(BusinessInstant value) {
            this.postedAt = value;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String value) {
            this.description = value;
        }

        /**
         * The entry-level 거래처, applied to every line that does not name its own.
         *
         * <p>A default, not a stored field: the postings are where the counterparty lives, because
         * a factored receivable has the customer on one leg and the bank on the other.
         */
        public String getClientId() {
            return clientId;
        }

        public void setClientId(String value) {
            this.clientId = value;
        }

        /** True to write it as a draft: excluded from every report until it is posted. */
        public boolean isDraft() {
            return draft;
        }

        public void setDraft(boolean value) {
            this.draft = value;
        }

        public List<PostingRequest> getPostings() {
            return postings;
        }

        public void setPostings(List<PostingRequest> value) {
            this.postings = value;
        }

        public NewEntry toNewEntry() {
            return NewEntry.withClient(postedAt, description, toPostings(postings), clientId);
        }
    }

    /** Correcting an entry. */
    public static class CorrectEntryRequest {
        private String description;
        @NotBlank
        private String reason;
        private BusinessInstant at;
        @NotEmpty
        private List<PostingRequest> postings;

        /** Null keeps the description it had. */
        public String getDescription() {
            return description;
        }

        public void setDescription(String value) {
            this.description = value;
        }

        /** Mandatory, and kept for good as part of the numbered revision. */
        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }

        /** When the correction is being made. Defaults to the entry's own instant. */
        public BusinessInstant getAt() {
            return at;
        }

        public void setAt(BusinessInstant value) {
            this.at = value;
        }

        public List<PostingRequest> getPostings() {
            return postings;
        }

        public void setPostings(List<PostingRequest> value) {
            this.postings = value;
        }

        List<Posting> postings() {
            return toPostings(postings);
        }

        BusinessInstant at(Entry entry) {
            return at == null ? entry.postedAt() : at;
        }
    }

    /** Voiding an entry or a batch. */
    public static class VoidRequest {
        @NotBlank
        private String reason;
        private BusinessInstant at;

        /**
         * Mandatory.
         *
         * <p>An unexplained reversal is indistinguishable from a mistake, and the person who has
         * to tell them apart is reading the journal a year later.
         */
        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }

        public BusinessInstant getAt() {
            return at;
        }

        public void setAt(BusinessInstant value) {
            this.at = value;
        }

        BusinessInstant at(Entry entry) {
            return at == null ? entry.postedAt() : at;
        }
    }

    /** Writing a batch. */
    public static class WriteBatchRequest {
        @NotBlank
        private String kind;
        @NotBlank
        private String label;
        private String note;
        private String generatorParams;
        @NotEmpty
        private List<PostEntryRequest> entries;

        /** MANUAL, IMPORT, CLOSING, RECURRING, AMORTIZATION or FX_REVALUATION. */
        public String getKind() {
            return kind;
        }

        public void setKind(String value) {
            this.kind = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String value) {
            this.label = value;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String value) {
            this.note = value;
        }

        /**
         * What a generator was asked for, stored as lineage and never re-executed.
         *
         * <p>This is where an amortisation preview's {@code generatorParams} goes when a person
         * accepts the schedule and posts it.
         */
        public String getGeneratorParams() {
            return generatorParams;
        }

        public void setGeneratorParams(String value) {
            this.generatorParams = value;
        }

        public List<PostEntryRequest> getEntries() {
            return entries;
        }

        public void setEntries(List<PostEntryRequest> value) {
            this.entries = value;
        }

        BatchKind kind() {
            return BatchKind.fromStored(kind);
        }

        List<NewEntry> toNewEntries() {
            List<NewEntry> built = new ArrayList<NewEntry>();
            for (PostEntryRequest entry : entries) {
                built.add(entry.toNewEntry());
            }
            return built;
        }

        /**
         * The earliest business date the batch touches — the date the batch permission is judged
         * on, and the date used to resolve the book before any of it is written.
         */
        LocalDate earliestBusinessDate() {
            LocalDate earliest = null;
            for (PostEntryRequest entry : entries) {
                if (entry.getPostedAt() == null) {
                    throw new IllegalArgumentException("every entry in a batch needs postedAt, in "
                            + "the form YYYY-MM-DDT[-]HH:MM:SS.mmm");
                }
                LocalDate on = entry.getPostedAt().businessDate();
                if (earliest == null || on.isBefore(earliest)) {
                    earliest = on;
                }
            }
            return earliest;
        }
    }

    private static List<Posting> toPostings(List<PostingRequest> requests) {
        List<Posting> built = new ArrayList<Posting>();
        for (int index = 0; index < requests.size(); index++) {
            built.add(requests.get(index).toDomain(index));
        }
        return built;
    }
}
