import { displayAmount, parseDecimal, type DisplayedAmount } from '@coreintra/money';
import { useMemo, useState, type ReactNode } from 'react';
import { useFullDecimal } from './FullDecimal.js';

/**
 * The one component that renders a number in this product.
 *
 * §9 requires the complete unrounded stored value to be reachable from every
 * screen that shows a number, and requires the rounded and the exact value to
 * be distinguishable — never ambiguous. Both obligations are met here, once,
 * so that no screen can accidentally omit either.
 *
 * Three rules it enforces that are easy to lose one table at a time:
 *
 *  1. The value arrives as an **exact decimal string** and is never turned into
 *     a `Number`. A prop typed `number` would defeat the entire money design,
 *     so the prop is typed `string` and a numeric value is a type error.
 *  2. `displayDecimals` is presentation only. It never rescales or limits what
 *     is stored, and the exact value stays available regardless of it.
 *  3. When the display is an abbreviation, the cell says so — with a marker, a
 *     title, and a screen-reader sentence. A rounded figure that looks exact is
 *     the failure this component exists to prevent.
 */
export interface AmountProps {
  /** The stored value, exactly as the API sent it. Never a `number`. */
  readonly value: string;
  /** The currency's presentation precision. KRW is 0, USD is 2, a share count may be anything. */
  readonly displayDecimals: number;
  /** Rendered after the figure. Omitted in a column that already has a currency header. */
  readonly currencyCode?: string;
  /** Adds the sign for a positive number too, for a column of movements. */
  readonly signed?: boolean;
  readonly className?: string;
  /** Labels, injected so this package needs no i18n runtime of its own. */
  readonly labels?: AmountLabels;
}

export interface AmountLabels {
  /** e.g. "This value is rounded. The stored value is {exact}" */
  readonly roundedNotice?: (exact: string) => string;
}

const DEFAULT_LABELS: Required<AmountLabels> = {
  roundedNotice: (exact) => `표시된 값은 반올림된 값입니다. 저장된 값은 ${exact} 입니다`,
};

function useDisplayed(value: string, displayDecimals: number): DisplayedAmount | Error {
  return useMemo(() => {
    try {
      return displayAmount(parseDecimal(value), displayDecimals);
    } catch (error) {
      return error instanceof Error ? error : new Error(String(error));
    }
  }, [value, displayDecimals]);
}

export function Amount({
  value,
  displayDecimals,
  currencyCode,
  signed = false,
  className,
  labels,
}: AmountProps): ReactNode {
  const { showExact } = useFullDecimal();
  const [expanded, setExpanded] = useState(false);
  const displayed = useDisplayed(value, displayDecimals);
  const roundedNotice = labels?.roundedNotice ?? DEFAULT_LABELS.roundedNotice;

  if (displayed instanceof Error) {
    // A value the money parser refuses is a bug upstream — almost always a
    // JSON number that lost precision. Showing it raw and marked is far more
    // useful than rendering NaN or silently showing nothing.
    return (
      <span className={joined('ci-numeric', 'ci-amount', 'ci-amount--invalid', className)} title={displayed.message}>
        {value}
      </span>
    );
  }

  const showingExact = showExact || expanded;
  const prefix = signed && !displayed.rounded.startsWith('-') ? '+' : '';
  const figure = showingExact ? displayed.exact : displayed.rounded;

  return (
    <span
      className={joined('ci-numeric', 'ci-amount', displayed.abbreviated ? 'ci-amount--abbreviated' : '', className)}
      // The title is the cheapest way to make the exact value reachable with a
      // pointer; the button below makes it reachable from the keyboard.
      title={displayed.abbreviated ? roundedNotice(displayed.exact) : undefined}
    >
      <span className="ci-amount__figure">
        {prefix}
        {figure}
      </span>
      {currencyCode ? <span className="ci-amount__currency"> {currencyCode}</span> : null}
      {displayed.abbreviated && !showExact ? (
        <button
          type="button"
          className="ci-amount__expand"
          aria-expanded={expanded}
          aria-label={roundedNotice(displayed.exact)}
          onClick={() => setExpanded((open) => !open)}
        >
          {/* A marker, not decoration: it means "there are digits behind this". */}
          <span aria-hidden="true">…</span>
        </button>
      ) : null}
      {displayed.abbreviated ? (
        <span className="ci-visually-hidden">{roundedNotice(displayed.exact)}</span>
      ) : null}
    </span>
  );
}

function joined(...parts: Array<string | undefined>): string {
  return parts.filter((part): part is string => Boolean(part)).join(' ');
}
