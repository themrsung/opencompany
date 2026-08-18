import {
  MAX_OFFSET_SECONDS,
  MIN_OFFSET_SECONDS,
  absoluteDateTime,
  businessInstant,
  fromClockFace,
  isOutsideCalendarDay,
  toClockFace,
  type BusinessInstant,
} from '@coreintra/business-time';
import { useId, useMemo, useState, type ReactNode } from 'react';

/**
 * Edits a {@link BusinessInstant} — a business day plus a clock face that runs
 * from `-24:00` to `+48:00`.
 *
 * A native `<input type="time">` cannot express `27:00` or `-02:00` at all, and
 * a pair of hour/minute spinners capped at 23 quietly makes the 72-hour window
 * unreachable from the UI while the backend still supports it. So the clock is
 * a text field with its own parser, and the component's job is to make the
 * unusual values feel ordinary rather than like a workaround: `27:00` is a
 * perfectly normal way to record a shift that ended at three in the morning.
 *
 * The derived wall-clock moment is shown underneath whenever the offset leaves
 * the calendar day, because "27:00 on the 30th" is the correct record but "03:00
 * on the 31st" is what the person actually remembers doing.
 */
export interface BusinessInstantFieldProps {
  readonly value: BusinessInstant | null;
  readonly onChange: (value: BusinessInstant | null) => void;
  readonly labels: BusinessInstantFieldLabels;
  readonly disabled?: boolean;
  readonly required?: boolean;
  readonly id?: string;
}

export interface BusinessInstantFieldLabels {
  readonly legend: string;
  readonly businessDate: string;
  readonly clock: string;
  readonly hint: string;
  /** Shown when the clock face leaves 00:00–23:59:59. Receives the resolved moment. */
  readonly outsideCalendarDay: (resolved: string) => string;
  readonly invalid: string;
}

/** `-02:00`, `09:30`, `27:00`, `27:00:30`. Seconds optional. */
const CLOCK = /^(-)?(\d{1,2}):([0-5]\d)(?::([0-5]\d))?$/;

export function parseClockFace(text: string): number | null {
  const match = CLOCK.exec(text.trim());
  if (match === null) {
    return null;
  }
  const seconds = fromClockFace({
    negative: match[1] === '-',
    hours: Number(match[2]),
    minutes: Number(match[3]),
    seconds: match[4] === undefined ? 0 : Number(match[4]),
  });
  if (seconds < MIN_OFFSET_SECONDS || seconds > MAX_OFFSET_SECONDS) {
    return null;
  }
  return seconds;
}

/** `27:00` rather than `27:00:00` unless the seconds carry information. */
export function formatClockFace(offsetSeconds: number): string {
  const face = toClockFace(offsetSeconds);
  const hh = String(face.hours).padStart(2, '0');
  const mm = String(face.minutes).padStart(2, '0');
  const base = `${face.negative ? '-' : ''}${hh}:${mm}`;
  return face.seconds === 0 ? base : `${base}:${String(face.seconds).padStart(2, '0')}`;
}

export function BusinessInstantField({
  value,
  onChange,
  labels,
  disabled = false,
  required = false,
  id,
}: BusinessInstantFieldProps): ReactNode {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const dateId = `${fieldId}-date`;
  const clockId = `${fieldId}-clock`;
  const hintId = `${fieldId}-hint`;

  /**
   * The clock input keeps its own draft while it is being typed.
   *
   * Deriving the text purely from the model looks tidier and is unusable: on
   * the way to `27:00` the field passes through `2`, `27` and `27:`, none of
   * which parse, so a controlled value would discard every keystroke and the
   * field could never be typed into at all. The draft holds what the person
   * wrote, the model updates the moment it becomes valid, and blur snaps the
   * text back to the canonical form.
   */
  const [draft, setDraft] = useState<string | null>(null);

  const canonicalClock = value === null ? '' : formatClockFace(value.offsetSeconds);
  const clockText = draft ?? canonicalClock;
  const outside = value !== null && isOutsideCalendarDay(value);
  const resolved = useMemo(() => (value === null ? '' : absoluteDateTime(value)), [value]);

  const handleDate = (businessDate: string): void => {
    if (businessDate === '') {
      onChange(null);
      return;
    }
    onChange(businessInstant(businessDate, value?.offsetSeconds ?? 0));
  };

  const handleClock = (text: string): void => {
    setDraft(text);
    if (value === null) {
      return;
    }
    const offsetSeconds = parseClockFace(text);
    if (offsetSeconds !== null) {
      onChange(businessInstant(value.businessDate, offsetSeconds));
    }
  };

  const handleClockBlur = (): void => {
    // A draft that never became valid stays on screen with its error, so the
    // person can see what they typed and fix it rather than watch it vanish.
    if (draft === null || parseClockFace(draft) !== null) {
      setDraft(null);
    }
  };

  const clockValid = clockText === '' || parseClockFace(clockText) !== null;

  return (
    <fieldset className="ci-bi-field" disabled={disabled}>
      <legend className="ci-bi-field__legend">{labels.legend}</legend>

      <div className="ci-bi-field__row">
        <label className="ci-bi-field__label" htmlFor={dateId}>
          {labels.businessDate}
        </label>
        <input
          id={dateId}
          type="date"
          className="ci-bi-field__date"
          required={required}
          value={value?.businessDate ?? ''}
          onChange={(event) => handleDate(event.target.value)}
          aria-describedby={hintId}
        />

        <label className="ci-bi-field__label" htmlFor={clockId}>
          {labels.clock}
        </label>
        <input
          id={clockId}
          type="text"
          inputMode="numeric"
          className="ci-bi-field__clock ci-numeric"
          placeholder="27:00"
          value={clockText}
          onChange={(event) => handleClock(event.target.value)}
          onBlur={handleClockBlur}
          aria-describedby={hintId}
          aria-invalid={!clockValid}
        />
      </div>

      <p className="ci-bi-field__hint" id={hintId}>
        {labels.hint}
      </p>

      {outside ? (
        <p className="ci-bi-field__resolved" role="note">
          {labels.outsideCalendarDay(resolved)}
        </p>
      ) : null}

      {!clockValid ? (
        <p className="ci-bi-field__error" role="alert">
          {labels.invalid}
        </p>
      ) : null}
    </fieldset>
  );
}

/** Read-only rendering of a business instant, for tables and trails. */
export function BusinessInstantText({
  value,
  className,
}: {
  readonly value: BusinessInstant;
  readonly className?: string;
}): ReactNode {
  const outside = isOutsideCalendarDay(value);
  return (
    <span
      className={['ci-numeric', 'ci-bi-text', outside ? 'ci-bi-text--outside' : '', className]
        .filter(Boolean)
        .join(' ')}
      title={outside ? absoluteDateTime(value) : undefined}
    >
      {value.businessDate} {formatClockFace(value.offsetSeconds)}
    </span>
  );
}
