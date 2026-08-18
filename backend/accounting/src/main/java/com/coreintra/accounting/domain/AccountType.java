package com.coreintra.accounting.domain;

/**
 * The five account types. <b>Immutable once an account is created</b>, and
 * encoded in the account id prefix so the type is visible wherever the id is.
 *
 * <p>Changing an account's type after it has postings would silently reclassify
 * history: last year's balance sheet would move a figure without any journal
 * entry explaining it. Retire the account and open a new one instead.
 */
public enum AccountType {

    ASSET("1", true),
    LIABILITY("2", false),
    EQUITY("3", false),
    INCOME("4", false),
    EXPENSE("5", true);

    private final String idPrefix;
    private final boolean debitNormal;

    AccountType(String idPrefix, boolean debitNormal) {
        this.idPrefix = idPrefix;
        this.debitNormal = debitNormal;
    }

    public String idPrefix() {
        return idPrefix;
    }

    /** True when a positive balance is naturally a debit. */
    public boolean isDebitNormal() {
        return debitNormal;
    }

    /** Assets and liabilities/equity sit on the balance sheet; income and expense do not. */
    public boolean isBalanceSheet() {
        return this == ASSET || this == LIABILITY || this == EQUITY;
    }

    public boolean isIncomeStatement() {
        return this == INCOME || this == EXPENSE;
    }

    /** Derives the type from an account id's prefix. */
    public static AccountType fromAccountId(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("account id is empty");
        }
        String prefix = accountId.substring(0, 1);
        for (AccountType type : values()) {
            if (type.idPrefix.equals(prefix)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "account id \"" + accountId + "\" does not begin with a known type prefix "
                        + "(1 asset, 2 liability, 3 equity, 4 income, 5 expense)");
    }
}
