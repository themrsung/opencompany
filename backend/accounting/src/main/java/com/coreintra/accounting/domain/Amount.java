package com.coreintra.accounting.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * An exact decimal amount.
 *
 * <h2>Stored raw, rounded only at display</h2>
 *
 * <p>Nothing here rounds. {@link #round} exists and returns a <em>string</em>
 * for display, precisely so a rounded value can never be mistaken for a stored
 * one and written back. Rounding on write destroys information permanently and
 * no later report can recover it.
 *
 * <h2>Decimal strings on the wire, never JSON numbers</h2>
 *
 * <p>A JSON number is a {@code double} in most parsers, which reintroduces
 * binary floating point at the API boundary and undoes every other precaution.
 * {@link #parse} accepts optional thousands separators, because people paste
 * figures out of spreadsheets, and rejects {@code 1e3}, {@code .5} and
 * {@code 1.} — each of which is ambiguous enough that guessing is worse than
 * refusing.
 *
 * <h2>Equality is numeric</h2>
 *
 * <p>{@code 1.00} and {@code 1.0} are the same amount. {@link BigDecimal#equals}
 * says otherwise because it compares scale, which is why every comparison here
 * goes through {@code compareTo}. An entry rejected for differing scale would be
 * a bug that looks like a balance error.
 */
public final class Amount implements Comparable<Amount>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Deliberately strict. Optional sign, digits with optional comma grouping,
     * optional fractional part with at least one digit.
     */
    private static final Pattern WIRE = Pattern.compile("^[+-]?(\\d{1,3}(,\\d{3})*|\\d+)(\\.\\d+)?$");

    public static final Amount ZERO = new Amount(BigDecimal.ZERO);

    private final BigDecimal value;

    private Amount(BigDecimal value) {
        this.value = value;
    }

    public static Amount of(BigDecimal value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return new Amount(value);
    }

    /**
     * Parses an exact decimal string.
     *
     * @throws IllegalArgumentException naming what was wrong, for anything else
     */
    public static Amount parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("amount is null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("amount is empty");
        }
        if (trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
            throw new IllegalArgumentException(
                    "\"" + text + "\" is in exponent form. Amounts must be written in full: "
                            + "1e3 is ambiguous about precision, and 1000 is not.");
        }
        if (!WIRE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "\"" + text + "\" is not an exact decimal amount. Expected digits with an "
                            + "optional sign, optional thousands separators, and an optional "
                            + "fractional part with at least one digit (\".5\" and \"1.\" are "
                            + "rejected as ambiguous).");
        }
        return new Amount(new BigDecimal(trimmed.replace(",", "")));
    }

    // There is deliberately no factory taking a double.
    //
    // An earlier version had one that always threw, as a tripwire. It was
    // removed for two reasons: it did not catch the mistake it claimed to
    // (Amount.of(BigDecimal.valueOf(0.1)) never goes near it), and it tripped
    // the ArchUnit rule banning floating point in money signatures — a guard
    // that violates its own rule is worse than no guard. The real protection is
    // that rule plus the BigDecimal.valueOf(double) ban beside it, both of
    // which fail the BUILD rather than a runtime call. See ADR 0004.

    public BigDecimal value() {
        return value;
    }

    public Amount add(Amount other) {
        return new Amount(value.add(other.value));
    }

    public Amount subtract(Amount other) {
        return new Amount(value.subtract(other.value));
    }

    public Amount negate() {
        return new Amount(value.negate());
    }

    public Amount multiply(BigDecimal factor) {
        // No scale is imposed: an FX conversion or an allocation keeps every
        // digit it produces, and rounding is a display decision made later.
        return new Amount(value.multiply(factor));
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public int signum() {
        return value.signum();
    }

    /** Numeric comparison. {@code 1.00} equals {@code 1.0}. */
    @Override
    public int compareTo(Amount other) {
        return value.compareTo(other.value);
    }

    public boolean isEqualTo(Amount other) {
        return compareTo(other) == 0;
    }

    /**
     * Numeric equality, matching {@link #compareTo}.
     *
     * <p>Deliberately not {@link BigDecimal#equals}, which distinguishes
     * {@code 1.00} from {@code 1.0}. Two amounts that compare equal must be
     * equal, or a {@code Set} of amounts behaves differently from a sort of them.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Amount)) {
            return false;
        }
        return value.compareTo(((Amount) obj).value) == 0;
    }

    @Override
    public int hashCode() {
        // Must agree with the numeric equals above: stripTrailingZeros makes
        // 1.00 and 1.0 hash alike.
        return value.stripTrailingZeros().hashCode();
    }

    /** The exact stored value, for the wire and for the full-decimal display. */
    public String toExactString() {
        return value.toPlainString();
    }

    /**
     * A rounded value <b>for display only</b>.
     *
     * <p>Returns a String rather than an Amount on purpose: a rounded figure
     * must not be able to flow back into a posting. HALF_UP matches what an
     * accountant expects to see; the stored value is untouched.
     */
    public String round(int displayDecimals) {
        if (displayDecimals < 0) {
            throw new IllegalArgumentException("displayDecimals cannot be negative");
        }
        return value.setScale(displayDecimals, RoundingMode.HALF_UP).toPlainString();
    }

    /** True when rounding to this many decimals would lose information. */
    public boolean hasHiddenPrecision(int displayDecimals) {
        return value.stripTrailingZeros().scale() > displayDecimals;
    }

    @Override
    public String toString() {
        return toExactString();
    }
}
