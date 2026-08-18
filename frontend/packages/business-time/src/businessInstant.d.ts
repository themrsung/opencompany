/**
 * BusinessInstant — the frontend counterpart of the Java type in
 * `backend/business-time`.
 *
 * Both implementations are driven by the same vectors in
 * `spec/business-time-vectors.json`. If you change behaviour here, that file is
 * where the change is agreed, and the Java suite will fail until it follows.
 *
 * A business day is a calendar date. A moment within it is that date plus an
 * offset in seconds, running from -86400 (`-24:00:00`) to +172800 (`+48:00:00`)
 * inclusive — a closed 72-hour window. A shift ending at 03:00 is `27:00` on
 * the business day it belongs to; a 22:00 briefing for tomorrow is `-02:00` on
 * that next day.
 */
export declare const MIN_OFFSET_SECONDS = -86400;
export declare const MAX_OFFSET_SECONDS = 172800;
/** Immutable. Construct through {@link businessInstant} or {@link parseBusinessInstant}. */
export interface BusinessInstant {
    /** ISO calendar date, `YYYY-MM-DD`. The business day, not a wall-clock date. */
    readonly businessDate: string;
    /** Seconds from that date's midnight, in `[-86400, 172800]`. */
    readonly offsetSeconds: number;
}
export declare class BusinessInstantParseError extends Error {
    readonly input: string;
    constructor(input: string, reason: string);
}
export declare function businessInstant(businessDate: string, offsetSeconds: number): BusinessInstant;
/**
 * Parses `YYYY-MM-DDT[-]HH:MM:SS.mmm`.
 *
 * Rejects rather than repairs. A trailing `Z` means the producer believes this
 * is a UTC instant, which is the actual bug; silently accepting it would let
 * that misunderstanding write rows.
 */
export declare function parseBusinessInstant(wire: string | null | undefined): BusinessInstant;
/** The canonical wire form. No sign on zero; milliseconds always `.000`. */
export declare function formatBusinessInstant(instant: BusinessInstant): string;
/**
 * The canonical ordering: business date first, then offset.
 *
 * Never sort wire strings. `-` sorts below every digit, so lexical comparison
 * produces a third order that is neither this one nor wall-clock order.
 */
export declare function compareBusinessInstants(left: BusinessInstant, right: BusinessInstant): number;
export declare const isBefore: (a: BusinessInstant, b: BusinessInstant) => boolean;
export declare const isAfter: (a: BusinessInstant, b: BusinessInstant) => boolean;
export declare const isSameInstant: (a: BusinessInstant, b: BusinessInstant) => boolean;
/**
 * The derived wall-clock moment, as `YYYY-MM-DDTHH:MM:SS`, for range display only.
 *
 * Not an ordering key: this reorders the `26:01` / `-03:22` pair, which is the
 * bug the business ordering exists to prevent.
 */
export declare function absoluteDateTime(instant: BusinessInstant): string;
/** True when the offset lands outside the ordinary 00:00–23:59:59 clock face. */
export declare const isOutsideCalendarDay: (instant: BusinessInstant) => boolean;
/** Splits an offset into the signed clock-face parts a picker edits. */
export interface ClockFace {
    readonly negative: boolean;
    readonly hours: number;
    readonly minutes: number;
    readonly seconds: number;
}
export declare function toClockFace(offsetSeconds: number): ClockFace;
export declare function fromClockFace(face: ClockFace): number;
