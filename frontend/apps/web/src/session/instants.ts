import { parseBusinessInstant, type BusinessInstant } from '@coreintra/business-time';
import { formatClockFace } from '@coreintra/ui';

/**
 * Wire strings arrive as `unknown`-ish text from the generated types, and
 * `parseBusinessInstant` rejects rather than repairs. A screen must not blow
 * up because one row carries a value the parser refuses — but it must not
 * quietly show it as a normal date either, so the raw text is surfaced.
 */
export function tryParseBusinessInstant(wire: string | null | undefined): BusinessInstant | null {
  try {
    return parseBusinessInstant(wire);
  } catch {
    return null;
  }
}

/**
 * A business instant as one line of text, for the places that need a string
 * rather than a node — the support banner's sentence, an aria-label, a title.
 * Everywhere a node will do, use `<BusinessInstantText>` instead: it marks the
 * ones that ran past midnight, and this cannot.
 */
export function businessInstantLabel(wire: string | null | undefined): string {
  const parsed = tryParseBusinessInstant(wire);
  if (parsed === null) {
    return wire ?? '—';
  }
  return `${parsed.businessDate} ${formatClockFace(parsed.offsetSeconds)}`;
}
