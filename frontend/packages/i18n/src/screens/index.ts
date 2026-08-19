/**
 * Screen-specific copy, one module per area.
 *
 * The shared catalogue in `ko.ts` holds the vocabulary every screen uses —
 * actions, statuses, domain nouns. Copy that belongs to exactly one screen
 * lives here instead, split by area, for a boring reason that matters in
 * practice: several people build screens at once, and a single 700-line
 * catalogue is a merge conflict on every branch. Splitting it means two screens
 * can be written in parallel without touching the same file.
 *
 * The same typing rule applies as in the main catalogue: Korean is written
 * first and the English is typed against it, so adding a key without its
 * translation fails the typecheck rather than shipping Korean into an English
 * screen.
 */
import type { Translated } from '../en.js';

import { accountingEn, accountingKo } from './accounting.js';
import { approvalEn, approvalKo } from './approval.js';
import { auditEn, auditKo } from './audit.js';
import { documentsEn, documentsKo } from './documents.js';
import { fontsEn, fontsKo } from './fonts.js';
import { inboxEn, inboxKo } from './inbox.js';
import { leaveEn, leaveKo } from './leave.js';
import { orgEn, orgKo } from './org.js';
import { permissionsEn, permissionsKo } from './permissions.js';
import { signinEn, signinKo } from './signin.js';
import { supportEn, supportKo } from './support.js';
import { whosinEn, whosinKo } from './whosin.js';

/**
 * The keys below are merged over the shared catalogue, one level deep. A key
 * that already exists there replaces it wholesale rather than extending it,
 * which is why the permission explainer registers as `explainer`: the shared
 * catalogue owns `permissions`, and this screen needs both. The accounting
 * screens register as `ledger` for the same reason — `accounting` in the shared
 * catalogue is the vocabulary (debit, credit, trial balance) that every screen
 * borrows, and replacing it would take it away from all of them. The document
 * and font screens register as `docs` and `fontManager` on the same grounds.
 */
export const screensKo = {
  approvalDoc: approvalKo,
  audit: auditKo,
  docs: documentsKo,
  explainer: permissionsKo,
  fontManager: fontsKo,
  ledger: accountingKo,
  inbox: inboxKo,
  leave: leaveKo,
  org: orgKo,
  signin: signinKo,
  support: supportKo,
  whosin: whosinKo,
} as const;

export type ScreenResources = typeof screensKo;

export const screensEn: Translated<ScreenResources> = {
  approvalDoc: approvalEn,
  audit: auditEn,
  docs: documentsEn,
  explainer: permissionsEn,
  fontManager: fontsEn,
  ledger: accountingEn,
  inbox: inboxEn,
  leave: leaveEn,
  org: orgEn,
  signin: signinEn,
  support: supportEn,
  whosin: whosinEn,
};
