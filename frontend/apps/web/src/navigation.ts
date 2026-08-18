/**
 * The sidebar, in order.
 *
 * One list, owned centrally, so that the order is a product decision rather
 * than an accident of which area module was imported first. The approval inbox
 * is the home route because it is the screen people open the product for; §12
 * names it and the who's-in view as the two that decide whether people like
 * this product, so they lead.
 */
export interface NavItem {
  readonly to: string;
  /** A key in the i18n catalogue, never a literal — both languages are first class. */
  readonly labelKey: string;
}

export const NAVIGATION: readonly NavItem[] = [
  { to: '/', labelKey: 'nav.inbox' },
  { to: '/whos-in', labelKey: 'nav.whosIn' },
  { to: '/documents', labelKey: 'nav.documents' },
  { to: '/templates', labelKey: 'nav.templates' },
  { to: '/leave', labelKey: 'nav.leave' },
  { to: '/org', labelKey: 'nav.org' },
  { to: '/accounting', labelKey: 'nav.accounting' },
  { to: '/fonts', labelKey: 'nav.fonts' },
  { to: '/audit', labelKey: 'nav.audit' },
  { to: '/support', labelKey: 'nav.support' },
];
