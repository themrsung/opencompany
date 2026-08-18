/**
 * Everything on these screens that is a number goes through here, so that the
 * currency's display precision and the exact-value labels are decided once.
 *
 * The rounding lives in `<Amount>`; this file only supplies it with the two
 * things it cannot know: how many decimals the book's currency prints at, and
 * what the notice about a rounded figure says in the reader's language.
 */
import { Amount, useFullDecimal, type AmountLabels } from '@coreintra/ui';
import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { useBook } from './book.js';
import { Checkbox } from './fields.js';

export function useAmountLabels(): AmountLabels {
  const { t } = useTranslation();
  return useMemo(
    () => ({ roundedNotice: (exact: string) => t('common.roundedNotice', { exact }) }),
    [t],
  );
}

export interface MoneyProps {
  /** The value exactly as the server sent it. Never a `number`. */
  readonly value: string | undefined;
  /** Defaults to the book's base currency. */
  readonly currencyCode?: string | null | undefined;
  /** Shows the currency beside the figure. Off in columns with a header. */
  readonly withCurrency?: boolean;
  readonly signed?: boolean;
  readonly className?: string;
}

/**
 * A missing amount is a dash rather than a zero: the two mean different things
 * to anyone reading a ledger, and inventing a zero is the more expensive lie.
 */
export function Money({ value, currencyCode, withCurrency = false, signed = false, className }: MoneyProps): ReactNode {
  const { decimalsFor, baseCurrencyCode } = useBook();
  const labels = useAmountLabels();

  if (value === undefined || value === '') {
    return <span className="ci-numeric">—</span>;
  }

  const code = currencyCode === null || currencyCode === undefined || currencyCode === '' ? baseCurrencyCode : currencyCode;

  return (
    <Amount
      value={value}
      displayDecimals={decimalsFor(code)}
      labels={labels}
      signed={signed}
      {...(withCurrency ? { currencyCode: code } : {})}
      {...(className === undefined ? {} : { className })}
    />
  );
}

/**
 * §9 requires the complete unrounded stored value to be reachable from every
 * screen that shows a number. The provider is app-wide and the preference is
 * sticky; this is the control that flips it, sitting in the accounting header
 * because that is where the rounding is worth arguing about.
 */
export function ExactValuesToggle(): ReactNode {
  const { t } = useTranslation();
  const { showExact, setShowExact } = useFullDecimal();

  return (
    <Checkbox
      label={t('common.showExactValues')}
      hint={t('ledger.exact.hint')}
      checked={showExact}
      onChange={setShowExact}
    />
  );
}
