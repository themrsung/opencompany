package com.coreintra.accounting.service;

import com.coreintra.core.permission.PermissionKey;

/**
 * The permission vocabulary the accounting services check against.
 *
 * <p>Written down once, in one place, for the reason
 * {@code com.coreintra.core.service.OrgPermissions} gives: a permission key is a
 * public contract. An administrator types it into the grant table, the
 * effective-permissions explainer prints it, and a support call quotes it. A key
 * invented inline at a call site is a key nobody can grant, because nothing tells
 * the administrator it exists.
 *
 * <p>The resource tree is {@code accounting.*}, so a client whose 회계팀 job
 * function should reach the whole module can be granted {@code accounting.*:read}
 * and have it mean what it looks like. Splitting the module across two trees
 * would make that grant reach some of it and silently miss the rest.
 *
 * <h2>Why the actions are split the way they are</h2>
 *
 * <p>{@code post}, {@code update} and {@code void} are three permissions rather
 * than one {@code write}, because they are three different amounts of authority
 * over the same row. Posting adds to the record; updating rewrites a figure that
 * has already been reported; voiding removes a transaction from every report.
 * A junior bookkeeper posts all day and should not be able to do either of the
 * other two, and an install that could not express that would end up granting
 * everybody everything.
 *
 * <p>{@code accounting.report:read} is separate from {@code accounting.entry:read}
 * on the same reasoning in reverse: a manager who may see this quarter's income
 * statement is not thereby entitled to read every line of the journal, including
 * the memo field on a payroll posting.
 */
public final class AccountingPermissions {

    private AccountingPermissions() {
    }

    /**
     * Seeing that a set of books exists, and what it balances in.
     *
     * <p>Books are named separately from the accounts inside them because a
     * multi-entity installation keeps several — statutory, management, a
     * disposal ledger — and "which books exist" is a far weaker fact than
     * "what is in them".
     */
    public static final PermissionKey BOOK_READ = PermissionKey.of("accounting.book", "read");

    /** Opening a set of books, or retiring one. */
    public static final PermissionKey BOOK_MANAGE = PermissionKey.of("accounting.book", "manage");

    public static final PermissionKey ACCOUNT_READ = PermissionKey.of("accounting.account", "read");
    public static final PermissionKey ACCOUNT_CREATE =
            PermissionKey.of("accounting.account", "create");

    /**
     * Renaming an account or changing its classification and category.
     *
     * <p>Not folded into {@link #ACCOUNT_CREATE}: the type is immutable and the
     * tree position is fixed at creation, so everything this permits is a
     * relabelling — but a relabelling that moves cash between sections of the
     * cash-flow statement, which is why it is not free either.
     */
    public static final PermissionKey ACCOUNT_UPDATE =
            PermissionKey.of("accounting.account", "update");

    /** Hiding an account from future use. Historical figures are untouched. */
    public static final PermissionKey ACCOUNT_RETIRE =
            PermissionKey.of("accounting.account", "retire");

    public static final PermissionKey ENTRY_READ = PermissionKey.of("accounting.entry", "read");
    public static final PermissionKey ENTRY_POST = PermissionKey.of("accounting.entry", "post");

    /** Correcting an entry that carries a wrong figure, keeping a numbered revision. */
    public static final PermissionKey ENTRY_UPDATE = PermissionKey.of("accounting.entry", "update");

    /** Retiring an entry that was the wrong transaction. */
    public static final PermissionKey ENTRY_VOID = PermissionKey.of("accounting.entry", "void");

    public static final PermissionKey BATCH_READ = PermissionKey.of("accounting.batch", "read");

    /**
     * Writing many entries as one atomic group.
     *
     * <p>Holding this does not let anyone post an entry they could not post one
     * at a time: {@link JournalService} checks {@link #ENTRY_POST} for every
     * entry in the batch, on that entry's own business date. The batch
     * permission is about the grouping — an import, a closing run — not about
     * the entries.
     */
    public static final PermissionKey BATCH_CREATE = PermissionKey.of("accounting.batch", "create");

    public static final PermissionKey BATCH_VOID = PermissionKey.of("accounting.batch", "void");

    /** Every report: trial balance, balance sheet, income statement, cash flow, equity, sub-ledgers. */
    public static final PermissionKey REPORT_READ = PermissionKey.of("accounting.report", "read");

    public static final PermissionKey CURRENCY_READ =
            PermissionKey.of("accounting.currency", "read");

    /**
     * Defining or retiring a unit of account.
     *
     * <p>One key rather than create/update/retire because the three are the same
     * risk: every one of them changes what a figure on a screen means.
     */
    public static final PermissionKey CURRENCY_MANAGE =
            PermissionKey.of("accounting.currency", "manage");

    /** Reading the 거래처 list — which is also the counterparty list of the business. */
    public static final PermissionKey CLIENT_READ = PermissionKey.of("accounting.client", "read");

    public static final PermissionKey CLIENT_MANAGE =
            PermissionKey.of("accounting.client", "manage");
}
