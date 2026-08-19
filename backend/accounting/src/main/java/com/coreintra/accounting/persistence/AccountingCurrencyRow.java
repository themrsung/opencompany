package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Currency;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The persistent form of a {@link Currency}: a client-definable unit of account.
 *
 * <h2>display_decimals is presentation, and this row is where that gets forgotten</h2>
 *
 * <p>It says how many decimals a screen shows. It does not limit, round or rescale anything
 * stored, and nothing on the write path may read it. An allocation of 1,000,000 across three ways
 * stores 333333.33333333333333 in a KRW book that displays no decimals at all, and the exact
 * value is still there when someone asks a year later.
 *
 * <p>A "currency" may be a commodity, a share count or carbon credits, so there is nothing
 * money-specific here: no minor units, no rate feed, no ISO membership test.
 */
@Entity
@Table(name = "accounting_currency")
public class AccountingCurrencyRow {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", nullable = false, length = 200)
    private String nameEn;

    @Column(name = "symbol", length = 16)
    private String symbol;

    /** Presentation only. Never consulted while writing an amount. */
    @Column(name = "display_decimals", nullable = false)
    private int displayDecimals;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Retirement rather than deletion. The domain {@link Currency} has no inactive state on
     * purpose - a currency you can still be handed is one you can still use - so a retired row is
     * simply not handed out.
     */
    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    protected AccountingCurrencyRow() {
    }

    public AccountingCurrencyRow(String id, String bookId, String code, String nameKo,
            String nameEn, String symbol, int displayDecimals) {
        this.id = id;
        this.bookId = bookId;
        this.code = code;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.symbol = symbol;
        this.displayDecimals = displayDecimals;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String bookId() {
        return bookId;
    }

    public String code() {
        return code;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public String symbol() {
        return symbol;
    }

    public int displayDecimals() {
        return displayDecimals;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
    }

    /**
     * Renames and re-presents. The code is not editable here: it is what every posting points at,
     * and a rename that changed it would silently reassign amounts to a different unit.
     */
    public void describe(String newNameKo, String newNameEn, String newSymbol,
            int newDisplayDecimals) {
        this.nameKo = newNameKo;
        this.nameEn = newNameEn;
        this.symbol = newSymbol;
        this.displayDecimals = newDisplayDecimals;
    }

    /** The domain value. Rebuilt rather than stored, so the domain's own validation runs. */
    public Currency toDomain() {
        return new Currency(code, nameKo, nameEn, symbol, displayDecimals);
    }
}
