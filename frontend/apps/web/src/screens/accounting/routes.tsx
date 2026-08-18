import { createRoute, useParams, type AnyRoute } from '@tanstack/react-router';
import type { ReactNode } from 'react';

import { AccountingArea } from './Area.js';
import { AmortisationScreen } from './Amortisation.js';
import { BatchDetailScreen, BatchesScreen } from './Batches.js';
import { BooksScreen } from './Books.js';
import { ChartOfAccountsScreen } from './ChartOfAccounts.js';
import { EntryDetailScreen } from './EntryDetail.js';
import { JournalScreen, NewEntryScreen } from './Journal.js';
import { REPORTS, ReportsScreen, type ReportName } from './Reports.js';

/**
 * The ledger and its reports. Absent entirely when the module is switched off.
 *
 * A pathless layout route carries the chrome — which book is open, the tabs,
 * the exact-value switch — so that moving between the chart and the journal
 * cannot quietly change which ledger is being read.
 *
 * Entries and batches are addressable on their own, without the book in the
 * path, because their endpoints are too: an entry id in a support ticket has
 * to be a link somebody can follow.
 */
export function accountingRoutes(parent: AnyRoute): AnyRoute[] {
  const area = createRoute({
    getParentRoute: () => parent,
    id: 'accounting',
    component: AccountingArea,
  });

  const child = (path: string, component: () => ReactNode): AnyRoute =>
    createRoute({ getParentRoute: () => area, path, component });

  return [
    area.addChildren([
      child('/accounting', BooksScreen),
      child('/accounting/accounts', ChartOfAccountsScreen),
      child('/accounting/entries', JournalScreen),
      child('/accounting/entries/new', NewEntryScreen),
      child('/accounting/entries/$entryId', EntryRoute),
      child('/accounting/batches', BatchesScreen),
      child('/accounting/batches/$batchId', BatchRoute),
      child('/accounting/reports', ReportsIndexRoute),
      child('/accounting/reports/$report', ReportRoute),
      child('/accounting/amortisation', AmortisationScreen),
    ]),
  ];
}

function EntryRoute(): ReactNode {
  const params = useParams({ strict: false });
  const entryId = typeof params.entryId === 'string' ? params.entryId : '';
  return <EntryDetailScreen entryId={entryId} />;
}

function BatchRoute(): ReactNode {
  const params = useParams({ strict: false });
  const batchId = typeof params.batchId === 'string' ? params.batchId : '';
  return <BatchDetailScreen batchId={batchId} />;
}

function ReportsIndexRoute(): ReactNode {
  return <ReportsScreen report="trial-balance" />;
}

/**
 * An unrecognised report name falls back to the trial balance rather than
 * 404ing: the tab strip is right there, and a blank page for a mistyped URL
 * helps nobody.
 */
function ReportRoute(): ReactNode {
  const params = useParams({ strict: false });
  const candidate = typeof params.report === 'string' ? params.report : '';
  const report: ReportName = (REPORTS as readonly string[]).includes(candidate)
    ? (candidate as ReportName)
    : 'trial-balance';
  return <ReportsScreen report={report} />;
}
