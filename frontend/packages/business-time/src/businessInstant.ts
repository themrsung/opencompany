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

export const MIN_OFFSET_SECONDS = -86_400;
export const MAX_OFFSET_SECONDS = 172_800;

const SECONDS_PER_MINUTE = 60;
const SECONDS_PER_HOUR = 3_600;
const SECONDS_PER_DAY = 86_400;

/** Immutable. Construct through {@link businessInstant} or {@link parseBusinessInstant}. */
export interface BusinessInstant {
  /** ISO calendar date, `YYYY-MM-DD`. The business day, not a wall-clock date. */
  readonly businessDate: string;
  /** Seconds from that date's midnight, in `[-86400, 172800]`. */
  readonly offsetSeconds: number;
}

export class BusinessInstantParseError extends Error {
  readonly input: string;

  constructor(input: string, reason: string) {
    super(`Cannot parse business instant "${input}": ${reason}`);
    this.name = 'BusinessInstantParseError';
    this.input = input;
  }
}

const WIRE = /^(\d{4})-(\d{2})-(\d{2})T(-)?(\d{2}):(\d{2}):(\d{2})\.(\d{3})$/;
const LOOKS_ZONED = /([Zz]|\+\d{2}:?\d{2}|GMT|UTC)\s*$/;

function isRealCalendarDate(year: number, month: number, day: number): boolean {
  if (month < 1 || month > 12 || day < 1) return false;
  // Date.UTC normalises out-of-range days (Feb 30 -> Mar 2), so round-trip and
  // compare rather than trusting it not to have rolled over.
  const probe = new Date(Date.UTC(year, month - 1, day));
  return (
    probe.getUTCFullYear() === year &&
    probe.getUTCMonth() === month - 1 &&
    probe.getUTCDate() === day
  );
}

export function businessInstant(businessDate: string, offsetSeconds: number): BusinessInstant {
  if (!Number.isInteger(offsetSeconds)) {
    throw new RangeError(`offsetSeconds must be a whole number of seconds, got ${offsetSeconds}`);
  }
  if (offsetSeconds < MIN_OFFSET_SECONDS) {
    throw new RangeError(
      `offsetSeconds ${offsetSeconds} is before the -24:00:00 bound (${MIN_OFFSET_SECONDS})`,
    );
  }
  if (offsetSeconds > MAX_OFFSET_SECONDS) {
    throw new RangeError(
      `offsetSeconds ${offsetSeconds} is after the +48:00:00 bound (${MAX_OFFSET_SECONDS})`,
    );
  }
  return Object.freeze({ businessDate, offsetSeconds });
}

/**
 * Parses `YYYY-MM-DDT[-]HH:MM:SS.mmm`.
 *
 * Rejects rather than repairs. A trailing `Z` means the producer believes this
 * is a UTC instant, which is the actual bug; silently accepting it would let
 * that misunderstanding write rows.
 */
export function parseBusinessInstant(wire: string | null | undefined): BusinessInstant {
  if (wire === null || wire === undefined) {
    throw new BusinessInstantParseError(String(wire), 'input is null');
  }
  const trimmed = wire.trim();
  if (trimmed.length === 0) {
    throw new BusinessInstantParseError(wire, 'input is empty');
  }
  if (LOOKS_ZONED.test(trimmed)) {
    throw new BusinessInstantParseError(
      wire,
      "business instants carry no timezone. A trailing 'Z' or UTC offset means the producer is " +
        'sending an absolute instant; business time is not UTC and the two must not be conflated',
    );
  }

  const match = WIRE.exec(trimmed);
  if (match === null) {
    throw new BusinessInstantParseError(
      wire,
      'expected YYYY-MM-DDT[-]HH:MM:SS.mmm (hour 00-48, optional leading \'-\', ' +
        'exactly three millisecond digits)',
    );
  }

  // noUncheckedIndexedAccess is on, so each group is narrowed explicitly rather
  // than asserted with `!`.
  const [, yearText, monthText, dayText, sign, hourText, minuteText, secondText, milliText] = match;
  if (
    yearText === undefined ||
    monthText === undefined ||
    dayText === undefined ||
    hourText === undefined ||
    minuteText === undefined ||
    secondText === undefined ||
    milliText === undefined
  ) {
    throw new BusinessInstantParseError(wire, 'expected YYYY-MM-DDT[-]HH:MM:SS.mmm');
  }

  const year = Number.parseInt(yearText, 10);
  const month = Number.parseInt(monthText, 10);
  const day = Number.parseInt(dayText, 10);
  if (!isRealCalendarDate(year, month, day)) {
    throw new BusinessInstantParseError(wire, `not a real calendar date: ${yearText}-${monthText}-${dayText}`);
  }

  const hours = Number.parseInt(hourText, 10);
  const minutes = Number.parseInt(minuteText, 10);
  const seconds = Number.parseInt(secondText, 10);
  const millis = Number.parseInt(milliText, 10);

  if (minutes > 59) {
    throw new BusinessInstantParseError(wire, `minute field is ${minutes}, must be 00-59`);
  }
  if (seconds > 59) {
    throw new BusinessInstantParseError(wire, `second field is ${seconds}, must be 00-59`);
  }
  if (millis !== 0) {
    throw new BusinessInstantParseError(
      wire,
      `millisecond field is .${milliText} but business instants are second-granularity; ` +
        'accepting it would silently discard precision',
    );
  }

  const magnitude = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds;
  const offset = sign === '-' ? -magnitude : magnitude;

  if (offset < MIN_OFFSET_SECONDS || offset > MAX_OFFSET_SECONDS) {
    throw new BusinessInstantParseError(
      wire,
      `offset ${offset}s is outside the 72-hour window [-24:00:00, +48:00:00]`,
    );
  }

  return businessInstant(`${yearText}-${monthText}-${dayText}`, offset);
}

const pad2 = (value: number): string => (value < 10 ? `0${value}` : String(value));

/** The canonical wire form. No sign on zero; milliseconds always `.000`. */
export function formatBusinessInstant(instant: BusinessInstant): string {
  const negative = instant.offsetSeconds < 0;
  const magnitude = Math.abs(instant.offsetSeconds);
  const hours = Math.floor(magnitude / SECONDS_PER_HOUR);
  const minutes = Math.floor((magnitude % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
  const seconds = magnitude % SECONDS_PER_MINUTE;
  return `${instant.businessDate}T${negative ? '-' : ''}${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}.000`;
}

/**
 * The canonical ordering: business date first, then offset.
 *
 * Never sort wire strings. `-` sorts below every digit, so lexical comparison
 * produces a third order that is neither this one nor wall-clock order.
 */
export function compareBusinessInstants(left: BusinessInstant, right: BusinessInstant): number {
  if (left.businessDate < right.businessDate) return -1;
  if (left.businessDate > right.businessDate) return 1;
  return Math.sign(left.offsetSeconds - right.offsetSeconds);
}

export const isBefore = (a: BusinessInstant, b: BusinessInstant): boolean =>
  compareBusinessInstants(a, b) < 0;
export const isAfter = (a: BusinessInstant, b: BusinessInstant): boolean =>
  compareBusinessInstants(a, b) > 0;
export const isSameInstant = (a: BusinessInstant, b: BusinessInstant): boolean =>
  compareBusinessInstants(a, b) === 0;

/**
 * The derived wall-clock moment, as `YYYY-MM-DDTHH:MM:SS`, for range display only.
 *
 * Not an ordering key: this reorders the `26:01` / `-03:22` pair, which is the
 * bug the business ordering exists to prevent.
 */
export function absoluteDateTime(instant: BusinessInstant): string {
  const [y, m, d] = instant.businessDate.split('-');
  const base = Date.UTC(Number(y), Number(m) - 1, Number(d));
  const moment = new Date(base + instant.offsetSeconds * 1000);
  const iso = moment.toISOString();
  return iso.slice(0, 19);
}

/** True when the offset lands outside the ordinary 00:00–23:59:59 clock face. */
export const isOutsideCalendarDay = (instant: BusinessInstant): boolean =>
  instant.offsetSeconds < 0 || instant.offsetSeconds >= SECONDS_PER_DAY;

/** Splits an offset into the signed clock-face parts a picker edits. */
export interface ClockFace {
  readonly negative: boolean;
  readonly hours: number;
  readonly minutes: number;
  readonly seconds: number;
}

export function toClockFace(offsetSeconds: number): ClockFace {
  const negative = offsetSeconds < 0;
  const magnitude = Math.abs(offsetSeconds);
  return {
    negative,
    hours: Math.floor(magnitude / SECONDS_PER_HOUR),
    minutes: Math.floor((magnitude % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE),
    seconds: magnitude % SECONDS_PER_MINUTE,
  };
}

export function fromClockFace(face: ClockFace): number {
  const magnitude =
    face.hours * SECONDS_PER_HOUR + face.minutes * SECONDS_PER_MINUTE + face.seconds;
  return face.negative ? -magnitude : magnitude;
}
