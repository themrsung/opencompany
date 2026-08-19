package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.persistence.AccountRepository;
import com.coreintra.accounting.persistence.AccountRow;
import com.coreintra.accounting.persistence.JournalPostingRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chart of accounts: opening accounts, retiring them, and answering what an account inherits.
 *
 * <h2>Three rules this service exists to keep readable</h2>
 *
 * <p>All three are enforced by the database as well, which is the point - a support session or an
 * import cannot get round them. What this class adds is a sentence a person can act on, in place
 * of a foreign-key violation:
 *
 * <ul>
 *   <li>An account's type is its id prefix, so it cannot be changed. Retire it and open another.
 *   <li>Only leaves are postable, and an account that already carries postings cannot be given a
 *       child - its subtotal would stop equalling the sum of its children, and no report would
 *       say so.
 *   <li>Accounts are retired, never deleted, so prior-period reports do not change behind anyone.
 * </ul>
 *
 * <h2>The chart has no business date of its own</h2>
 *
 * <p>An account is not an event: it does not happen on a day. So the permission checks here take
 * the business date from the caller rather than from the row, and the caller has to say which date
 * it means — usually today, but the date of the document being prepared when a back-dated
 * correction is being set up. {@link com.coreintra.core.permission.PermissionTarget} refuses to
 * default it, which is what stops that decision being made by accident.
 */
public class ChartOfAccountsService {

    private final AccountRepository accounts;
    private final JournalPostingRepository postings;
    private final AccountingGate gate;

    public ChartOfAccountsService(AccountRepository accounts, JournalPostingRepository postings,
            AccountingGate gate) {
        this.accounts = accounts;
        this.postings = postings;
        this.gate = gate;
    }

    /**
     * Opens an account.
     *
     * @param id the account code; its first character is the type ('1' asset … '5' expense) and
     *     that is the only place the type is decided
     * @param parentId null for a root account. Naming a parent makes the parent unpostable from
     *     here on.
     * @throws IllegalArgumentException if the code, the name or the parent is unusable
     * @throws IllegalStateException if the parent already carries postings
     */
    @Transactional
    public Account openAccount(PermissionPrincipal caller, String bookId, String id,
            String parentId, String nameKo, String nameEn, String currencyCode, boolean contra,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_CREATE, bookId, businessDate,
                "open account " + id + " in book " + bookId);
        // Built first: the domain constructor is what decides whether the code, the type and the
        // name make an account at all.
        Account account = new Account(id, parentId, nameKo, nameEn, currencyCode, contra);
        if (accounts.findById(new AccountRow.Key(bookId, id)).isPresent()) {
            throw new IllegalArgumentException("book " + bookId + " already has an account " + id);
        }
        if (contra && parentId == null) {
            throw new IllegalArgumentException("a contra account nets against a parent, so account "
                    + id + " needs one. Accumulated depreciation sits under the asset it reduces.");
        }
        if (parentId != null) {
            adoptChild(bookId, parentId, id);
        }
        accounts.save(new AccountRow(bookId, account));
        return account;
    }

    /**
     * Sets what this account contributes to inheritance. Null for either means "keep inheriting".
     *
     * <p>Checked as an update rather than a read even though nothing about the money moves:
     * classification is what puts a payment in the operating or the financing section of the
     * cash-flow statement, so relabelling one account restates a statement that has already been
     * published.
     */
    @Transactional
    public void describeAccount(PermissionPrincipal caller, String bookId, String id,
            ChartOfAccounts.Classification classification, ChartOfAccounts.Category category,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_UPDATE, bookId, businessDate,
                "describe account " + id + " in book " + bookId);
        AccountRow row = row(bookId, id);
        row.describeAs(classification, category);
        accounts.save(row);
    }

    /** Renaming is always safe: the id, the type and the tree position are untouched. */
    @Transactional
    public void renameAccount(PermissionPrincipal caller, String bookId, String id, String nameKo,
            String nameEn, LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_UPDATE, bookId, businessDate,
                "rename account " + id + " in book " + bookId);
        AccountRow row = row(bookId, id);
        // Round-trip through the domain so a blank Korean name is refused here too.
        new Account(id, row.parentId(), nameKo, nameEn, row.currencyCode(), row.isContra());
        row.rename(nameKo, nameEn);
        accounts.save(row);
    }

    /**
     * Hides an account from future use and leaves every historical figure alone.
     *
     * <p>There is no delete. A deleted account would silently rewrite prior-period reports, which
     * is the one thing a ledger must never do.
     */
    @Transactional
    public void retireAccount(PermissionPrincipal caller, String bookId, String id,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_RETIRE, bookId, businessDate,
                "retire account " + id + " in book " + bookId);
        AccountRow row = row(bookId, id);
        if (row.isRetired()) {
            throw new IllegalStateException("account " + id + " is already retired");
        }
        row.retire(OffsetDateTime.now());
        accounts.save(row);
    }

    /** Every account in the book, with inheritance resolvable over the whole tree. */
    public ChartOfAccounts chart(PermissionPrincipal caller, String bookId,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_READ, bookId, businessDate,
                "read the chart of accounts of book " + bookId);
        return chartOf(bookId);
    }

    /**
     * The same snapshot, for a caller this module has already authorised.
     *
     * <p>Package-private, and reached only from {@link LedgerReportService} once it has checked
     * {@code accounting.report:read} against the same book. A cash-flow statement needs the
     * classification of every account it touches, and requiring {@code accounting.account:read}
     * on top would mean the two permissions had to be granted together everywhere or the report
     * would fail for half the people entitled to it — which is how a grant of "everything" gets
     * made. This is not a bypass: no caller reaches it without a decision having been taken on
     * this book, on this date, by the one evaluator.
     */
    ChartOfAccounts chartOf(String bookId) {
        List<AccountRow> rows = accounts.findByBookIdOrderByIdAsc(bookId);
        List<Account> domain = new ArrayList<Account>();
        Map<String, ChartOfAccounts.Attributes> attributes =
                new LinkedHashMap<String, ChartOfAccounts.Attributes>();
        for (AccountRow row : rows) {
            domain.add(row.toDomain());
            attributes.put(row.id(), row.attributes());
        }
        return ChartOfAccounts.of(domain, attributes);
    }

    public Account account(PermissionPrincipal caller, String bookId, String id,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.ACCOUNT_READ, bookId, businessDate,
                "read account " + id + " in book " + bookId);
        return row(bookId, id).toDomain();
    }

    /**
     * Refuses a posting to an account that cannot take one, with the account's own explanation.
     *
     * <p>Called by {@link JournalService} before anything is written. The database refuses it too,
     * through the foreign key into the leaf half of the account table; this is the version a
     * person can read.
     */
    void requirePostable(String bookId, String accountId) {
        Account account = row(bookId, accountId).toDomain();
        if (!account.isPostable()) {
            throw new IllegalStateException(account.postingRefusalReason());
        }
    }

    private void adoptChild(String bookId, String parentId, String childId) {
        AccountRow parent = accounts.findById(new AccountRow.Key(bookId, parentId))
                .orElseThrow(() -> new IllegalArgumentException("account " + childId
                        + " names a parent " + parentId + " that this book does not have"));
        if (parent.isRetired()) {
            throw new IllegalStateException("account " + parentId + " is retired, so it cannot "
                    + "take a new child. Retirement is meant to stop a subtree growing.");
        }
        long existingPostings = postings.countByBookIdAndAccountId(bookId, parentId);
        if (existingPostings > 0) {
            throw new IllegalStateException("account " + parentId + " already carries "
                    + existingPostings + " posting(s), so it cannot be given a child. A parent "
                    + "that still holds postings makes its own subtotal wrong - the total of the "
                    + "children no longer equals the parent - and no report can tell you it "
                    + "happened. Move those postings to a child account first.");
        }
        if (!parent.hasChildren()) {
            parent.markAsParent();
            accounts.save(parent);
        }
    }

    private AccountRow row(String bookId, String id) {
        return accounts.findById(new AccountRow.Key(bookId, id))
                .orElseThrow(() -> new NoSuchAccountingRecordException("account", id,
                        "book " + bookId + " has no account " + id));
    }
}
