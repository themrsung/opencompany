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
export class DecimalParseError extends Error {
    input;
    constructor(input, reason) {
        super(`${reason}: ${JSON.stringify(input)}`);
        this.name = 'DecimalParseError';
        this.input = input;
    }
}
/** The wire form: an optional sign, digits, optionally a point and more digits. Nothing else. */
const WIRE = /^-?\d+(?:\.\d+)?$/;
/**
 * Parses a value as it arrives from the API.
 *
 * Strict on purpose. `1e3`, `.5` and `1.` are rejected rather than coerced:
 * every one of them is a sign that something upstream turned an amount into a
 * `Number` and back, and the whole point of the string wire format is that this
 * never happens silently.
 */
export function parseDecimal(text) {
    if (typeof text !== 'string' || text.length === 0) {
        throw new DecimalParseError(String(text), 'an amount must be a non-empty string');
    }
    if (!WIRE.test(text)) {
        throw new DecimalParseError(text, 'an amount must be an exact decimal string — no exponent, no bare leading or trailing point');
    }
    return fromParts(text);
}
/**
 * Parses what a person typed.
 *
 * Grouping separators and surrounding space are accepted because people paste
 * `1,234,567` out of spreadsheets all day. Exponents and half-written numbers
 * are still refused, so the leniency does not extend to anything ambiguous.
 */
export function parseAmountInput(text) {
    const trimmed = text.trim().replace(/,/g, '');
    const unsigned = trimmed.startsWith('+') ? trimmed.slice(1) : trimmed;
    return parseDecimal(unsigned);
}
/** True when {@link parseAmountInput} would accept the text. For live field validation. */
export function isValidAmountInput(text) {
    try {
        parseAmountInput(text);
        return true;
    }
    catch {
        return false;
    }
}
function fromParts(text) {
    const negative = text.startsWith('-');
    const body = negative ? text.slice(1) : text;
    const point = body.indexOf('.');
    const digits = point < 0 ? body : body.slice(0, point) + body.slice(point + 1);
    const scale = point < 0 ? 0 : body.length - point - 1;
    const magnitude = BigInt(digits);
    return { unscaled: negative ? -magnitude : magnitude, scale };
}
export function decimalOf(unscaled, scale) {
    if (!Number.isInteger(scale) || scale < 0) {
        throw new RangeError(`scale must be a non-negative integer, got ${scale}`);
    }
    return { unscaled, scale };
}
export const ZERO = { unscaled: 0n, scale: 0 };
export function isZero(value) {
    return value.unscaled === 0n;
}
export function isNegative(value) {
    return value.unscaled < 0n;
}
export function negate(value) {
    return { unscaled: -value.unscaled, scale: value.scale };
}
function pow10(exponent) {
    return 10n ** BigInt(exponent);
}
/** Restates both values at the same scale so they can be compared or added exactly. */
function align(a, b) {
    const scale = Math.max(a.scale, b.scale);
    return {
        a: a.unscaled * pow10(scale - a.scale),
        b: b.unscaled * pow10(scale - b.scale),
        scale,
    };
}
/** Compares by quantity, not by representation: `1.50` equals `1.5`. */
export function compareDecimals(a, b) {
    const aligned = align(a, b);
    if (aligned.a < aligned.b)
        return -1;
    if (aligned.a > aligned.b)
        return 1;
    return 0;
}
export function addDecimals(a, b) {
    const aligned = align(a, b);
    return { unscaled: aligned.a + aligned.b, scale: aligned.scale };
}
export function subtractDecimals(a, b) {
    return addDecimals(a, negate(b));
}
/**
 * Rounds for display only, half away from zero.
 *
 * Half-up away from zero is what a Korean finance team reads on paper and what
 * the seeded templates print, so it is the default here. It is **never** used
 * on a value being sent back: the stored quantity is whatever the server holds,
 * and this function exists to decide what a cell shows, not what it means.
 */
export function roundForDisplay(value, decimals) {
    if (!Number.isInteger(decimals) || decimals < 0) {
        throw new RangeError(`display decimals must be a non-negative integer, got ${decimals}`);
    }
    if (decimals >= value.scale) {
        return value;
    }
    const drop = value.scale - decimals;
    const divisor = pow10(drop);
    const negative = value.unscaled < 0n;
    const magnitude = negative ? -value.unscaled : value.unscaled;
    const quotient = magnitude / divisor;
    const remainder = magnitude % divisor;
    // Half away from zero: exactly half rounds up in magnitude.
    const rounded = remainder * 2n >= divisor ? quotient + 1n : quotient;
    return { unscaled: negative ? -rounded : rounded, scale: decimals };
}
/**
 * True when showing the value at `decimals` would hide something.
 *
 * This is the flag the `<Amount>` component uses to mark a cell as abbreviated.
 * Trailing zeros do not count as hidden precision — `1.500` shown as `1.50` has
 * lost nothing a reader cares about — so the comparison is on quantity.
 */
export function hasHiddenPrecision(value, decimals) {
    return compareDecimals(roundForDisplay(value, decimals), value) !== 0;
}
/** The canonical string form: the digits as stored, sign and point, no grouping. */
export function toPlainString(value) {
    const negative = value.unscaled < 0n;
    const digits = (negative ? -value.unscaled : value.unscaled).toString();
    if (value.scale === 0) {
        return (negative ? '-' : '') + digits;
    }
    const padded = digits.padStart(value.scale + 1, '0');
    const cut = padded.length - value.scale;
    return `${negative ? '-' : ''}${padded.slice(0, cut)}.${padded.slice(cut)}`;
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
export function formatGrouped(value, options = {}) {
    const groupSeparator = options.groupSeparator ?? ',';
    const decimalSeparator = options.decimalSeparator ?? '.';
    const plain = toPlainString(value);
    const negative = plain.startsWith('-');
    const body = negative ? plain.slice(1) : plain;
    const point = body.indexOf('.');
    const whole = point < 0 ? body : body.slice(0, point);
    const fraction = point < 0 ? '' : body.slice(point + 1);
    const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, groupSeparator);
    return (negative ? '-' : '') + grouped + (fraction ? decimalSeparator + fraction : '');
}
/** Pads or trims the fraction to exactly `decimals` places, for column alignment. */
export function withExactScale(value, decimals) {
    const rounded = roundForDisplay(value, decimals);
    if (rounded.scale === decimals) {
        return rounded;
    }
    return { unscaled: rounded.unscaled * pow10(decimals - rounded.scale), scale: decimals };
}
/**
 * Prepares a value for display at a currency's precision.
 *
 * Both forms are always returned. §9 requires that the complete unrounded value
 * be reachable from every screen that shows a number, and the reliable way to
 * get that is to make the abbreviation and the truth travel together rather
 * than leaving each call site to remember.
 */
export function displayAmount(value, displayDecimals, options = {}) {
    return {
        rounded: formatGrouped(withExactScale(value, displayDecimals), options),
        exact: formatGrouped(value, options),
        abbreviated: hasHiddenPrecision(value, displayDecimals),
    };
}
