package com.coreintra.accounting.domain;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * One account in the chart of accounts.
 *
 * <h2>Only leaves are postable</h2>
 *
 * <p>Giving an account a child stops it accepting postings — automatically, not
 * by a separate flag someone must remember to set. A parent that still holds
 * postings makes its own subtotal wrong: the total of the children no longer
 * equals the parent, and every report built on the tree quietly disagrees with
 * itself.
 *
 * <h2>Retirement, never deletion</h2>
 *
 * <p>A retired account is hidden from future use and keeps every historical
 * figure. Deleting it would silently rewrite prior-period reports, which is the
 * one thing a ledger must never do.
 */
public final class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String parentId;
    private final AccountType type;
    private final String nameKo;
    private final String nameEn;
    private final String currencyCode;
    private final boolean contra;
    private boolean hasChildren;
    private boolean retired;

    public Account(String id, String parentId, String nameKo, String nameEn, String currencyCode,
            boolean contra) {
        if (Texts.isBlank(id)) {
            throw new IllegalArgumentException("an account needs an id");
        }
        if (Texts.isBlank(nameKo)) {
            throw new IllegalArgumentException("account " + id + " needs a Korean name");
        }
        this.id = id;
        this.parentId = parentId;
        // Derived from the id prefix rather than passed separately, so the two
        // cannot disagree.
        this.type = AccountType.fromAccountId(id);
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.currencyCode = currencyCode;
        this.contra = contra;
    }

    public String id() {
        return id;
    }

    public String parentId() {
        return parentId;
    }

    /** Immutable. There is no setter, deliberately. */
    public AccountType type() {
        return type;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    /** Null means the book's base currency. */
    public String currencyCode() {
        return currencyCode;
    }

    /**
     * A contra account — accumulated depreciation under its asset.
     *
     * <p>Sits under its parent's type but carries the opposite normal balance,
     * so it nets against the parent instead of inflating the section.
     */
    public boolean isContra() {
        return contra;
    }

    /** True when a positive balance is a debit, accounting for contra. */
    public boolean isDebitNormal() {
        return contra != type.isDebitNormal();
    }

    public boolean hasChildren() {
        return hasChildren;
    }

    /** Called when a child is added. From then on this account is not postable. */
    public void markAsParent() {
        this.hasChildren = true;
    }

    public boolean isRetired() {
        return retired;
    }

    public void retire() {
        this.retired = true;
    }

    /** Only leaves that are not retired accept postings. */
    public boolean isPostable() {
        return !hasChildren && !retired;
    }

    /** Why this account cannot be posted to, for the error message. */
    public String postingRefusalReason() {
        if (hasChildren) {
            return "account " + id + " (" + nameKo + ") has child accounts, so it is a subtotal "
                    + "rather than a postable account. Post to one of its children instead — "
                    + "otherwise its subtotal would no longer equal the sum of them.";
        }
        if (retired) {
            return "account " + id + " (" + nameKo + ") has been retired. Its historical figures "
                    + "are unchanged and still reported; it accepts no new postings.";
        }
        return null;
    }
}
