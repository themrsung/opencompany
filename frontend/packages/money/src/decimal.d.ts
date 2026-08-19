/**
 * Exact decimal arithmetic for amounts, in the browser.
 *
 * The backend keeps money in `BigDecimal` and an ArchUnit rule fails the build
 * if a float gets near it. That guarantee is worth nothing if the browser then
 * does `Number("0.1") + Number("0.2")` on the way to a screen, so amounts
 * arrive as exact decimal strings and stay strings the whole way through this
 * module. Nothing here ever calls `Number()` or `parseFloat` on a value.
 *
 * The internal form is a `bigint` of unscaled digits plus a scale, which is the
 * same shape as `BigDecimal` and for the same reason: `1.50` and `1.5` are the
 * same quantity recorded at different precisions, and the difference is
 * meaningful when deciding whether a display is hiding anything.
 */
/** An exact decimal. `value` is the unscaled integer; the point sits `scale` digits from the right. */
export interface Decimal {
    readonly unscaled: bigint;
    readonly scale: number;
}
export declare class DecimalParseError extends Error {
    readonly input: string;
    constructor(input: string, reason: string);
}
/**
 * Parses a value as it arrives from the API.
 *
 * Strict on purpose. `1e3`, `.5` and `1.` are rejected rather than coerced:
 * every one of them is a sign that something upstream turned an amount into a
 * `Number` and back, and the whole point of the string wire format is that this
 * never happens silently.
 */
export declare function parseDecimal(text: string): Decimal;
/**
 * Parses what a person typed.
 *
 * Grouping separators and surrounding space are accepted because people paste
 * `1,234,567` out of spreadsheets all day. Exponents and half-written numbers
 * are still refused, so the leniency does not extend to anything ambiguous.
 */
export declare function parseAmountInput(text: string): Decimal;
/** True when {@link parseAmountInput} would accept the text. For live field validation. */
export declare function isValidAmountInput(text: string): boolean;
export declare function decimalOf(unscaled: bigint, scale: number): Decimal;
export declare const ZERO: Decimal;
export declare function isZero(value: Decimal): boolean;
export declare function isNegative(value: Decimal): boolean;
export declare function negate(value: Decimal): Decimal;
/** Compares by quantity, not by representation: `1.50` equals `1.5`. */
export declare function compareDecimals(a: Decimal, b: Decimal): number;
export declare function addDecimals(a: Decimal, b: Decimal): Decimal;
export declare function subtractDecimals(a: Decimal, b: Decimal): Decimal;
/**
 * Rounds for display only, half away from zero.
 *
 * Half-up away from zero is what a Korean finance team reads on paper and what
 * the seeded templates print, so it is the default here. It is **never** used
 * on a value being sent back: the stored quantity is whatever the server holds,
 * and this function exists to decide what a cell shows, not what it means.
 */
export declare function roundForDisplay(value: Decimal, decimals: number): Decimal;
/**
 * True when showing the value at `decimals` would hide something.
 *
 * This is the flag the `<Amount>` component uses to mark a cell as abbreviated.
 * Trailing zeros do not count as hidden precision — `1.500` shown as `1.50` has
 * lost nothing a reader cares about — so the comparison is on quantity.
 */
export declare function hasHiddenPrecision(value: Decimal, decimals: number): boolean;
/** The canonical string form: the digits as stored, sign and point, no grouping. */
export declare function toPlainString(value: Decimal): string;
export interface GroupingOptions {
    /** Thousands separator. Both supported locales use a comma. */
    readonly groupSeparator?: string;
    /** Decimal separator. Both supported locales use a full stop. */
    readonly decimalSeparator?: string;
}
/**
 * Groups the integer part for reading.
 *
 * Deliberately hand-rolled rather than handed to `Intl.NumberFormat`: that API
 * takes a `number` in its common form, and a ledger will eventually hold a
 * value with more than fifteen significant digits. Korean and English agree on
 * `,` and `.`, so there is nothing locale-dependent left to get wrong; if a
 * third locale is ever added, pass the separators in.
 */
export declare function formatGrouped(value: Decimal, options?: GroupingOptions): string;
/** Pads or trims the fraction to exactly `decimals` places, for column alignment. */
export declare function withExactScale(value: Decimal, decimals: number): Decimal;
/** What a screen needs in order to show a number honestly. */
export interface DisplayedAmount {
    /** The value rounded to the currency's display decimals, grouped. */
    readonly rounded: string;
    /** The complete stored value, grouped. Equal to `rounded` when nothing is hidden. */
    readonly exact: string;
    /** True when `rounded` is an abbreviation of `exact`. */
    readonly abbreviated: boolean;
}
/**
 * Prepares a value for display at a currency's precision.
 *
 * Both forms are always returned. §9 requires that the complete unrounded value
 * be reachable from every screen that shows a number, and the reliable way to
 * get that is to make the abbreviation and the truth travel together rather
 * than leaving each call site to remember.
 */
export declare function displayAmount(value: Decimal, displayDecimals: number, options?: GroupingOptions): DisplayedAmount;
