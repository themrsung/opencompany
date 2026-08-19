package com.coreintra.accounting.domain;

import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * One side of an entry: an account, an amount, and — for foreign currency —
 * both the account's amount and its base-currency equivalent.
 *
 * <h2>Sign convention</h2>
 *
 * <p>Positive is a debit, negative is a credit. One signed number rather than a
 * separate side flag, because a flag and a sign can disagree and then nothing
 * downstream knows which to believe.
 *
 * <h2>The system never looks up an FX rate</h2>
 *
 * <p>{@link #rate()} is supplied by the caller and recorded with the posting.
 * That is deliberate and is not a gap waiting to be filled: a rate fetched at
 * posting time is a rate nobody agreed to, it changes what the books say
 * depending on when the job ran, and it cannot be reproduced when the entry is
 * re-examined years later. The rate a business used is a fact about that
 * transaction, so it travels with it.
 */
public final class Posting implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String accountId;
    private final Amount amount;
    private final String currencyCode;
    private final Amount baseAmount;
    private final BigDecimal rate;
    private final String clientId;
    private final String memo;

    private Posting(String accountId, Amount amount, String currencyCode, Amount baseAmount,
            BigDecimal rate, String clientId, String memo) {
        this.accountId = accountId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.baseAmount = baseAmount;
        this.rate = rate;
        this.clientId = clientId;
        this.memo = memo;
    }

    /** A posting in the book's base currency. */
    public static Posting of(String accountId, Amount amount) {
        if (Texts.isBlank(accountId)) {
            throw new IllegalArgumentException("a posting needs an account");
        }
        if (amount == null || amount.isZero()) {
            // A zero posting contributes nothing and hides intent: whoever wrote
            // it meant something, and the entry should say what.
            throw new IllegalArgumentException(
                    "a posting cannot be zero. If a line is genuinely nil, leave it out.");
        }
        return new Posting(accountId, amount, null, amount, null, null, null);
    }

    public static Posting debit(String accountId, Amount amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a debit must be positive, got " + amount);
        }
        return of(accountId, amount);
    }

    public static Posting credit(String accountId, Amount amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "pass a credit as a positive magnitude; the sign is applied here");
        }
        return of(accountId, amount.negate());
    }

    /**
     * A foreign-currency posting.
     *
     * @param rate the rate the business actually used, supplied and recorded.
     *             Never looked up.
     * @throws IllegalArgumentException if the base amount does not follow from
     *             the amount and the rate
     */
    public static Posting foreignCurrency(String accountId, Amount amount, String currencyCode,
            Amount baseAmount, BigDecimal rate) {
        if (Texts.isBlank(currencyCode)) {
            throw new IllegalArgumentException("a foreign-currency posting needs a currency");
        }
        if (rate == null) {
            throw new IllegalArgumentException(
                    "a foreign-currency posting needs the rate the business used. This system "
                            + "never looks one up: a fetched rate is one nobody agreed to and "
                            + "cannot be reproduced later.");
        }
        if (baseAmount == null || baseAmount.isZero()) {
            throw new IllegalArgumentException("a foreign-currency posting needs a base amount");
        }
        if (amount.signum() != baseAmount.signum()) {
            throw new IllegalArgumentException(
                    "the amount and its base equivalent must be on the same side; got "
                            + amount + " and " + baseAmount);
        }
        return new Posting(accountId, amount, currencyCode, baseAmount, rate, null, null);
    }

    /** A copy carrying a 거래처, which drives receivables aging and prepaid schedules. */
    public Posting withClient(String clientIdValue) {
        return new Posting(accountId, amount, currencyCode, baseAmount, rate, clientIdValue, memo);
    }

    public Posting withMemo(String memoText) {
        return new Posting(accountId, amount, currencyCode, baseAmount, rate, clientId, memoText);
    }

    public String accountId() {
        return accountId;
    }

    /** In the account's own currency. Equal to {@link #baseAmount()} when domestic. */
    public Amount amount() {
        return amount;
    }

    /** Null for a base-currency posting. */
    public String currencyCode() {
        return currencyCode;
    }

    /** In the book's base currency. This is what the entry balances on. */
    public Amount baseAmount() {
        return baseAmount;
    }

    /** Supplied by the caller and recorded. Null for a base-currency posting. */
    public BigDecimal rate() {
        return rate;
    }

    /** 거래처. Set on the entry and overridable per posting. */
    public String clientId() {
        return clientId;
    }

    public String memo() {
        return memo;
    }

    public boolean isDebit() {
        return baseAmount.isPositive();
    }

    public boolean isCredit() {
        return baseAmount.isNegative();
    }

    public boolean isForeignCurrency() {
        return currencyCode != null;
    }

    @Override
    public String toString() {
        return accountId + " " + (isDebit() ? "Dr " : "Cr ") + baseAmount.toExactString();
    }
}
