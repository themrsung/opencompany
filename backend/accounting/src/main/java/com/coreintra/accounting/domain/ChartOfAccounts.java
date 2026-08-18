package com.coreintra.accounting.domain;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A book's accounts as a tree, with the attributes that resolve by nearest ancestor.
 *
 * <h2>Why attributes resolve upwards</h2>
 *
 * <p>A chart has hundreds of accounts and a handful of decisions. "Everything under 1100 is cash
 * for cash-flow purposes" is one decision; repeating it on forty child accounts is forty chances
 * to disagree, and the disagreement shows up as a cash-flow statement that is wrong in a way
 * nobody can see. So an attribute set on a parent covers its whole subtree, and a child overrides
 * it only when it genuinely differs. An unset attribute means "whatever my nearest ancestor says",
 * never "none".
 *
 * <p>The tree is a value: it is built from what was loaded and answers questions about that
 * snapshot. It is not a live view of the database, and it deliberately cannot write.
 */
public final class ChartOfAccounts implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Where an account's cash movements belong in a cash-flow statement. Client-visible, and set
     * on as few accounts as possible.
     */
    public enum Classification {
        OPERATING, INVESTING, FINANCING, TAX, FX, OTHER;

        public static Classification fromStored(String value) {
            return value == null ? null : valueOf(value);
        }
    }

    /**
     * What kind of thing the account holds. Drives the sub-ledger views - receivables ageing reads
     * RECEIVABLE, prepaid schedules read PREPAID_EXPENSE - rather than guessing from the name.
     */
    public enum Category {
        CASH, CASH_EQUIVALENT, RECEIVABLE, PREPAID_EXPENSE, OPERATING_ASSET, INVESTMENT,
        CREDIT_CARD, UNPAID_EXPENSE, LINE_OF_CREDIT, AMORTIZING_LOAN, PROVISION, GUARANTEE, OTHER;

        public static Category fromStored(String value) {
            return value == null ? null : valueOf(value);
        }
    }

    /** The attributes of one account. Either may be unset, meaning "inherit". */
    public static final class Attributes implements Serializable {

        private static final long serialVersionUID = 1L;

        public static final Attributes NONE = new Attributes(null, null);

        private final Classification classification;
        private final Category category;

        public Attributes(Classification classification, Category category) {
            this.classification = classification;
            this.category = category;
        }

        /** Null means inherit from the nearest ancestor that sets one. */
        public Classification classification() {
            return classification;
        }

        /** Null means inherit from the nearest ancestor that sets one. */
        public Category category() {
            return category;
        }
    }

    private final Map<String, Account> accounts;
    private final Map<String, Attributes> attributes;

    private ChartOfAccounts(Map<String, Account> accounts, Map<String, Attributes> attributes) {
        this.accounts = accounts;
        this.attributes = attributes;
    }

    /**
     * @param accounts every account in the book, parents included
     * @param attributes what each account sets for itself; an account may be absent
     */
    public static ChartOfAccounts of(List<Account> accounts, Map<String, Attributes> attributes) {
        Map<String, Account> byId = new LinkedHashMap<String, Account>();
        for (Account account : accounts) {
            byId.put(account.id(), account);
        }
        Map<String, Attributes> own = new LinkedHashMap<String, Attributes>();
        if (attributes != null) {
            own.putAll(attributes);
        }
        return new ChartOfAccounts(byId, own);
    }

    public List<Account> accounts() {
        return Immutables.copyOf(accounts.values());
    }

    /** Null when the book has no such account. */
    public Account account(String accountId) {
        return accounts.get(accountId);
    }

    public List<Account> children(String accountId) {
        List<Account> children = new ArrayList<Account>();
        for (Account account : accounts.values()) {
            if (accountId.equals(account.parentId())) {
                children.add(account);
            }
        }
        return children;
    }

    /** The accounts that can actually be posted to: leaves that have not been retired. */
    public List<Account> postableAccounts() {
        List<Account> postable = new ArrayList<Account>();
        for (Account account : accounts.values()) {
            if (account.isPostable()) {
                postable.add(account);
            }
        }
        return postable;
    }

    /** What this account sets for itself, ignoring inheritance. Never null. */
    public Attributes ownAttributes(String accountId) {
        Attributes own = attributes.get(accountId);
        return own == null ? Attributes.NONE : own;
    }

    /**
     * The classification in force for this account: its own, or the nearest ancestor's.
     *
     * @return null when neither it nor any ancestor sets one
     */
    public Classification classificationOf(String accountId) {
        Set<String> seen = new HashSet<String>();
        String current = accountId;
        while (current != null && seen.add(current)) {
            Attributes own = attributes.get(current);
            if (own != null && own.classification() != null) {
                return own.classification();
            }
            Account account = accounts.get(current);
            current = account == null ? null : account.parentId();
        }
        return null;
    }

    /**
     * The category in force for this account: its own, or the nearest ancestor's.
     *
     * @return null when neither it nor any ancestor sets one
     */
    public Category categoryOf(String accountId) {
        Set<String> seen = new HashSet<String>();
        String current = accountId;
        while (current != null && seen.add(current)) {
            Attributes own = attributes.get(current);
            if (own != null && own.category() != null) {
                return own.category();
            }
            Account account = accounts.get(current);
            current = account == null ? null : account.parentId();
        }
        return null;
    }

    /**
     * The chain from this account up to the root, itself first.
     *
     * <p>Stops on a repeat rather than looping. The database cannot express "no cycles in a tree",
     * so a cycle written by hand would otherwise hang a report instead of showing up as a chain
     * that ends early.
     */
    public List<Account> ancestryOf(String accountId) {
        List<Account> chain = new ArrayList<Account>();
        Set<String> seen = new HashSet<String>();
        String current = accountId;
        while (current != null && seen.add(current)) {
            Account account = accounts.get(current);
            if (account == null) {
                break;
            }
            chain.add(account);
            current = account.parentId();
        }
        return chain;
    }
}
