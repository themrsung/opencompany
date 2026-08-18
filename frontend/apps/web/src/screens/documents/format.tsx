import { parseBusinessInstant, type BusinessInstant } from '@coreintra/business-time';
import { BusinessInstantText, type AmountLabels, type BusinessInstantFieldLabels } from '@coreintra/ui';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';

/**
 * The small formatting decisions these screens would otherwise each make
 * differently.
 *
 * Two of them are rules rather than preferences. Money is only ever handed to
 * `<Amount>`, which keeps the exact stored value reachable; and a business
 * instant is only ever handed to `<BusinessInstantText>` or parsed with
 * `parseBusinessInstant`, never to `new Date`, which would turn a correct
 * `27:00` into the wrong day at three in the morning.
 */

/** Labels for `<Amount>`, so every figure on these screens explains its own rounding. */
export function useAmountLabels(): AmountLabels {
  const { t } = useTranslation();
  return { roundedNotice: (exact: string) => t('common.roundedNotice', { exact }) };
}

export function useInstantLabels(): BusinessInstantFieldLabels {
  const { t } = useTranslation();
  return {
    legend: t('businessTime.label'),
    businessDate: t('businessTime.businessDate'),
    clock: t('businessTime.clock'),
    hint: t('businessTime.hint'),
    outsideCalendarDay: (resolved: string) => `${t('businessTime.outsideCalendarDay')} — ${resolved}`,
    invalid: t('businessTime.invalid'),
  };
}

/** Korean or English, whichever the reader is in, falling back to the one that exists. */
export function useLocalName(): (nameKo: string | undefined, nameEn: string | undefined) => string {
  const { i18n } = useTranslation();
  const english = i18n.language.startsWith('en');
  return (nameKo, nameEn) => {
    const preferred = english ? nameEn : nameKo;
    return preferred ?? nameEn ?? nameKo ?? '';
  };
}

/**
 * A business instant from the wire, rendered — or the raw string when it does
 * not parse.
 *
 * Showing the unparsed value is deliberate: a wire form this component refuses
 * is a backend bug, and blanking the cell hides it in the one place someone
 * would have noticed.
 */
export function WireInstant({ wire }: { readonly wire: string | null | undefined }): ReactNode {
  if (wire === null || wire === undefined || wire === '') {
    return <span aria-hidden="true">—</span>;
  }
  const parsed = tryParseInstant(wire);
  return parsed === null ? <span>{wire}</span> : <BusinessInstantText value={parsed} />;
}

export function tryParseInstant(wire: string | null | undefined): BusinessInstant | null {
  if (wire === null || wire === undefined || wire === '') {
    return null;
  }
  try {
    return parseBusinessInstant(wire);
  } catch {
    return null;
  }
}

/** A content hash, shortened for a column but never truncated in a way that reads as the whole thing. */
export function shortHash(hash: string | undefined): string {
  if (hash === undefined || hash === '') {
    return '—';
  }
  return hash.length <= 12 ? hash : `${hash.slice(0, 12)}…`;
}
