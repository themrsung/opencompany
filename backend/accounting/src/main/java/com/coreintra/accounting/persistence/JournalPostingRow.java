package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Posting;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One side of an entry, stored.
 *
 * <h2>Amounts are NUMERIC without a scale, and nothing here rounds</h2>
 *
 * <p>The columns are unbounded {@code NUMERIC} and the mapping imposes no precision on purpose:
 * an allocation or a conversion legitimately produces more digits than anyone displays, and every
 * one of them is kept. Rounding belongs to the read path. A figure rounded on the way in cannot be
 * recovered by any later report (ADR 0004).
 *
 * <h2>account_has_children is a foreign key, not data</h2>
 *
 * <p>It exists so that the composite key {@code (book_id, account_id, account_has_children)} can
 * point into the leaf half of {@code account}, with a CHECK pinning this copy to FALSE. The
 * database therefore refuses a posting to a parent account, and refuses to make an account a
 * parent while postings reference it. The column is written by its DEFAULT - never by this class,
 * which is why it is neither insertable nor updatable here.
 */
@Entity
@Table(name = "journal_posting")
public class JournalPostingRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "entry_id", nullable = false, length = 36)
    private String entryId;

    /** Denormalised from the entry so the account foreign key can be held to the same book. */
    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    /** The order the accountant wrote the lines in. A journal that reorders itself reads wrong. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "account_has_children", nullable = false, insertable = false, updatable = false)
    private boolean accountHasChildren;

    /** Positive is a debit, negative a credit. In the account's own currency. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** Null when the posting is already in the book's base currency. */
    @Column(name = "currency_code", length = 12)
    private String currencyCode;

    /** What the entry balances on, always in the book's base currency. */
    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    /** Supplied by the caller and recorded. The system never looks a rate up. */
    @Column(name = "rate")
    private BigDecimal rate;

    /** 거래처, overriding whatever the entry as a whole was about. */
    @Column(name = "client_id", length = 36)
    private String clientId;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JournalPostingRow() {
    }

    public JournalPostingRow(String id, String entryId, String bookId, int position,
            Posting posting) {
        this.id = id;
        this.entryId = entryId;
        this.bookId = bookId;
        this.position = position;
        this.accountId = posting.accountId();
        this.amount = posting.amount().value();
        this.currencyCode = posting.currencyCode();
        this.baseAmount = posting.baseAmount().value();
        this.rate = posting.rate();
        this.clientId = posting.clientId();
        this.memo = posting.memo();
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String entryId() {
        return entryId;
    }

    public String bookId() {
        return bookId;
    }

    public int position() {
        return position;
    }

    public String accountId() {
        return accountId;
    }

    public boolean accountHasChildren() {
        return accountHasChildren;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public BigDecimal baseAmount() {
        return baseAmount;
    }

    public BigDecimal rate() {
        return rate;
    }

    public String clientId() {
        return clientId;
    }

    public String memo() {
        return memo;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /**
     * The domain value, rebuilt through the same factories the write path uses - so a stored
     * foreign-currency line with no rate, or a stored zero, is refused on the way out too.
     */
    public Posting toDomain() {
        Posting posting = currencyCode == null
                ? Posting.of(accountId, Amount.of(amount))
                : Posting.foreignCurrency(accountId, Amount.of(amount), currencyCode,
                        Amount.of(baseAmount), rate);
        if (clientId != null) {
            posting = posting.withClient(clientId);
        }
        if (memo != null) {
            posting = posting.withMemo(memo);
        }
        return posting;
    }
}
