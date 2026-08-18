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

import { inboxEn, inboxKo } from './inbox.js';

export const screensKo = {
  inbox: inboxKo,
} as const;

export type ScreenResources = typeof screensKo;

export const screensEn: Translated<ScreenResources> = {
  inbox: inboxEn,
};
