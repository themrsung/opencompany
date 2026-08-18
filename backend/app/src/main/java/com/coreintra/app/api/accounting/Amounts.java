package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Amount;
import com.coreintra.compat.Texts;
import java.time.LocalDate;

/**
 * How money crosses this API's wire, in both directions.
 *
 * <h2>A string, at every level of every response</h2>
 *
 * <p>ADR 0004 is unambiguous: amounts are exact decimal strings, never JSON numbers. The reason is
 * the receiving end rather than ours — {@code JSON.parse} in a browser turns any JSON number into
 * an IEEE-754 double, so {@code 333333.33333333333333} arrives as {@code 333333.3333333333} and
 * the client cannot tell that it happened. The server's storage was exact and the screen is wrong.
 *
 * <p>So no response DTO in this package declares a {@link java.math.BigDecimal} field. They declare
 * {@link String}, produced by {@link #wire}. A {@code @JsonSerialize} annotation on a field would
 * do the same job for that field and quietly not do it for the next one somebody adds, or for a
 * nested type reached through a list — and the failure is invisible in a code review because the
 * annotation is present on the field being reviewed. Making the field a {@code String} means the
 * mistake does not compile.
 *
 * <h2>What is accepted coming in</h2>
 *
 * <p>{@link Amount#parse} takes optional thousands separators and refuses {@code 1e3}, {@code .5}
 * and {@code 1.}. Those three are refused rather than interpreted because each is a client that
 * believes something about the format which is not true, and guessing what they meant is how a
 * figure ends up a thousand times too small.
 */
public final class Amounts {

    private Amounts() {
    }

    /** An amount as it goes out: exact, complete, and textual. Null stays null. */
    public static String wire(Amount amount) {
        return amount == null ? null : amount.toExactString();
    }

    /**
     * An amount as it comes in.
     *
     * @param field the field name, so a validation failure names the field rather than the value
     * @throws IllegalArgumentException which the exception handler turns into a 400 listing every
     *     failure at once
     */
    public static Amount parse(String text, String field) {
        if (Texts.isBlank(text)) {
            throw new IllegalArgumentException(field + " is required, as an exact decimal string");
        }
        try {
            return Amount.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage(), e);
        }
    }

    /** A business date from a query parameter, or today when the caller did not say. */
    public static LocalDate businessDateOrToday(LocalDate supplied) {
        // Defaulting is safe here and only here: an operation with no business date of its own -
        // opening an account, listing the chart - genuinely concerns today unless the caller says
        // otherwise. Anything stamped with a BusinessInstant takes its date from the stamp, and
        // PermissionTarget refuses to default that one.
        return supplied == null ? LocalDate.now() : supplied;
    }
}
