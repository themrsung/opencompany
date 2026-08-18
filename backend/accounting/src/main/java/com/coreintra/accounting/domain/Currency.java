package com.coreintra.accounting.domain;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * A unit of account. Client-definable, and not necessarily money.
 *
 * <p>KRW (0 display decimals) and USD (2) are seeded. A client may define
 * anything else — a commodity, a share count, carbon credits — so nothing here
 * assumes money semantics.
 *
 * <h2>displayDecimals is presentation only</h2>
 *
 * <p>It never limits or rescales what is stored. KRW showing 0 decimals does not
 * mean KRW amounts are integers: an allocation or an FX conversion legitimately
 * produces fractions, they are stored in full, and the UI's expand affordance
 * is how a user sees them. A display setting that truncated storage would be
 * rounding on write wearing a different hat.
 */
public final class Currency implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String nameKo;
    private final String nameEn;
    private final String symbol;
    private final int displayDecimals;
    private final boolean active;

    public Currency(String code, String nameKo, String nameEn, String symbol, int displayDecimals) {
        if (Texts.isBlank(code)) {
            throw new IllegalArgumentException("a currency needs a code");
        }
        if (displayDecimals < 0 || displayDecimals > 12) {
            throw new IllegalArgumentException(
                    "displayDecimals must be 0-12, got " + displayDecimals);
        }
        this.code = code;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.symbol = symbol;
        this.displayDecimals = displayDecimals;
        this.active = true;
    }

    public static Currency krw() {
        return new Currency("KRW", "대한민국 원", "South Korean won", "₩", 0);
    }

    public static Currency usd() {
        return new Currency("USD", "미국 달러", "US dollar", "$", 2);
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

    /** Presentation only. Never limits or rescales storage. */
    public int displayDecimals() {
        return displayDecimals;
    }

    public boolean isActive() {
        return active;
    }

    /** The rounded figure to show. The exact value is always available alongside. */
    public String formatForDisplay(Amount amount) {
        return amount.round(displayDecimals);
    }

    /** True when the displayed figure hides digits, so the UI must offer the exact one. */
    public boolean displayHidesPrecision(Amount amount) {
        return amount.hasHiddenPrecision(displayDecimals);
    }
}
