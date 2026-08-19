import type { components } from '@coreintra/api-client';
import {
  businessInstant,
  formatBusinessInstant,
  parseBusinessInstant,
  type BusinessInstant,
} from '@coreintra/business-time';
import { api } from '../../api/client.js';

/**
 * Every call the 결재 screens make.
 *
 * The types are the generated ones. They are reached through
 * `components['schemas'][…]` rather than through the `GetResponse` helper,
 * because the generated document declares these responses under the wildcard
 * media type and the helper only reads the JSON one, so it resolves to `never`
 * for every endpoint on this screen. That is a contract bug, reported rather
 * than papered over; when it is fixed these aliases become one-liners.
 */

export type Inbox = components['schemas']['ApprovalInbox'];
export type InboxCounts = components['schemas']['ApprovalInboxCounts'];
export type DocumentSummary = components['schemas']['ApprovalDocumentSummary'];
export type DocumentDetail = components['schemas']['ApprovalDocumentDetail'];
export type Step = components['schemas']['ApprovalStep'];
export type TrailAction = components['schemas']['ApprovalAction'];
export type Company = components['schemas']['CompanyView'];

/**
 * A summary that has an id.
 *
 * Every field on the generated summary is optional, including the id, so the
 * lists are narrowed once on the way in rather than defended against in every
 * cell. A row the server sent without an id cannot be opened or approved, and
 * dropping it here is the only place that decision needs to be made.
 */
export type InboxRow = DocumentSummary & { readonly id: string };
type Commented = components['schemas']['CommentedApprovalActionRequest'];
type Reasoned = components['schemas']['ReasonedApprovalActionRequest'];

/** The three lists the inbox renders, in the order it renders them. */
export const BUCKETS = ['awaitingMe', 'draftedByMe', 'copiedToMe'] as const;
export type Bucket = (typeof BUCKETS)[number];

export const approvalKeys = {
  companies: ['org', 'companies'] as const,
  inbox: (companyId: string) => ['approvals', 'inbox', companyId] as const,
  document: (documentId: string) => ['approvals', 'document', documentId] as const,
};

async function all<T>(
  path: string,
  query?: Readonly<Record<string, string | number | undefined>>,
): Promise<T[]> {
  const items: T[] = [];
  for await (const item of api.paginate<T>(path, query === undefined ? {} : { query })) {
    items.push(item);
  }
  return items;
}

export const approvals = {
  /**
   * The whole 결재함 in one request. Three lists and their counts arrive
   * together; splitting this into a query per bucket would turn one round trip
   * into three and undo the reason the endpoint exists.
   */
  inbox: (companyId: string): Promise<Inbox> =>
    api.get<Inbox>('/approvals/inbox', { query: { companyId } }),

  document: (documentId: string): Promise<DocumentDetail> =>
    api.get<DocumentDetail>(`/approvals/${documentId}`),

  companies: (): Promise<Company[]> => all<Company>('/org/companies'),

  approve: (
    documentId: string,
    stepId: string,
    body: Commented,
    idempotencyKey: string,
  ): Promise<DocumentDetail> =>
    api.post<DocumentDetail>(`/approvals/${documentId}/steps/${stepId}/approve`, body, {
      idempotencyKey,
    }),

  returnToDrafter: (
    documentId: string,
    stepId: string,
    body: Reasoned,
    idempotencyKey: string,
  ): Promise<DocumentDetail> =>
    api.post<DocumentDetail>(`/approvals/${documentId}/steps/${stepId}/return`, body, {
      idempotencyKey,
    }),

  hold: (
    documentId: string,
    stepId: string,
    body: Reasoned,
    idempotencyKey: string,
  ): Promise<DocumentDetail> =>
    api.post<DocumentDetail>(`/approvals/${documentId}/steps/${stepId}/hold`, body, {
      idempotencyKey,
    }),

  delegateFinal: (
    documentId: string,
    stepId: string,
    body: Reasoned,
    idempotencyKey: string,
  ): Promise<DocumentDetail> =>
    api.post<DocumentDetail>(`/approvals/${documentId}/steps/${stepId}/delegate-final`, body, {
      idempotencyKey,
    }),

  recall: (documentId: string, body: Commented, idempotencyKey: string): Promise<DocumentDetail> =>
    api.post<DocumentDetail>(`/approvals/${documentId}/recall`, body, { idempotencyKey }),
};

/* ------------------------------------------------------------- idempotency */

/**
 * One key per logical operation, held until that operation succeeds.
 *
 * The rule is that a *retry* carries the key the first attempt used and a new
 * decision carries a new one. Minting inside the mutation would give every
 * attempt its own key and turn a dropped response into a double approval,
 * which is precisely what the header exists to prevent. Keyed by document,
 * step and verb, because approving and returning the same step are two
 * different operations.
 */
const openOperations = new Map<string, string>();

export function operationId(verb: string, documentId: string, stepId: string | null): string {
  return `${verb}:${documentId}:${stepId ?? '-'}`;
}

export function idempotencyKeyFor(operation: string): string {
  const held = openOperations.get(operation);
  if (held !== undefined) {
    return held;
  }
  const minted = api.idempotencyKey();
  openOperations.set(operation, minted);
  return minted;
}

/** Call once the server has accepted the operation: the next one is genuinely new. */
export function releaseIdempotencyKey(operation: string): void {
  openOperations.delete(operation);
}

/* ------------------------------------------------------------ business time */

function pad2(value: number): string {
  return value.toString().padStart(2, '0');
}

/**
 * Now, as a business instant.
 *
 * The calendar date and the clock face taken from the local machine. A person
 * approving at 02:00 in the middle of a shift that began the previous evening
 * is, strictly, acting at 26:00 on the earlier business day — but only their
 * open attendance record knows that, and the inbox does not have it. The
 * honest place to decide this is the server, which has both; see the report.
 */
export function businessNow(clock: Date = new Date()): BusinessInstant {
  const date = `${clock.getFullYear()}-${pad2(clock.getMonth() + 1)}-${pad2(clock.getDate())}`;
  return businessInstant(
    date,
    clock.getHours() * 3600 + clock.getMinutes() * 60 + clock.getSeconds(),
  );
}

export function actedAtNow(clock: Date = new Date()): string {
  return formatBusinessInstant(businessNow(clock));
}

/**
 * A business instant off the wire, or null when the server sent something this
 * client cannot read. A dash in one cell beats an exception that takes the
 * whole inbox down.
 */
export function readInstant(value: string | undefined): BusinessInstant | null {
  if (value === undefined || value === '') {
    return null;
  }
  try {
    return parseBusinessInstant(value);
  } catch {
    return null;
  }
}

/**
 * Whole days a document has been waiting, counted in business days rather than
 * elapsed milliseconds. The two differ by a timezone the wire form deliberately
 * does not carry, and "3일 경과" is a statement about business days anyway.
 */
export function daysWaiting(submittedAt: string | undefined, today: Date = new Date()): number | null {
  const instant = readInstant(submittedAt);
  if (instant === null) {
    return null;
  }
  const [year, month, day] = instant.businessDate.split('-').map((part) => Number(part));
  if (year === undefined || month === undefined || day === undefined) {
    return null;
  }
  const from = Date.UTC(year, month - 1, day);
  const to = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.max(0, Math.round((to - from) / 86_400_000));
}

/* ------------------------------------------------------------------ buckets */

/**
 * The three lists, with any document that qualifies for two of them shown in
 * one.
 *
 * The server is explicit that a document you drafted and are also being asked
 * to approve appears in both lists, because it genuinely is both. On screen
 * that reads as a duplicate and makes the counts look wrong, so the inbox
 * picks the bucket that asks something of the reader: 결재 대기 first.
 */
export function bucketRows(inbox: Inbox | undefined): Readonly<Record<Bucket, InboxRow[]>> {
  const seen = new Set<string>();
  const rows: Record<Bucket, InboxRow[]> = {
    awaitingMe: [],
    draftedByMe: [],
    copiedToMe: [],
  };
  for (const bucket of BUCKETS) {
    for (const row of inbox?.[bucket] ?? []) {
      const id = row.id;
      if (id === undefined || seen.has(id)) {
        continue;
      }
      seen.add(id);
      rows[bucket].push({ ...row, id });
    }
  }
  return rows;
}

/**
 * Refused before it was sent.
 *
 * Distinct from an `ApiError` because it never reached the server: the reader
 * has no step to act on, and saying "unexpected error" — which is what the
 * generic presenter says about a plain `Error` — would send them looking for a
 * fault that is not there.
 */
export class NoStepForYou extends Error {
  constructor() {
    super('no pending step for this account');
    this.name = 'NoStepForYou';
  }
}

/**
 * The step this account is being asked to act on, or null.
 *
 * A document can be pending at a step whose approvers do not include you —
 * you may be reading somebody else's inbox, or a 합의 may be running beside
 * the 결재. Acting then is not a mistake the UI should let someone make.
 */
export function pendingStepFor(detail: DocumentDetail, accountId: string | undefined): Step | null {
  for (const step of detail.steps ?? []) {
    if (step.pending !== true || step.id === undefined) {
      continue;
    }
    const mine = (step.approvers ?? []).some(
      (approver) => approver.accountId === accountId && approver.acted !== true,
    );
    if (mine) {
      return step;
    }
  }
  return null;
}

/** The step a reader is looking at, whoever it belongs to — for the list's step column. */
export function currentStep(detail: DocumentDetail | undefined): Step | null {
  return (detail?.steps ?? []).find((step) => step.pending === true) ?? null;
}

/**
 * The name held at submission for an account, from whatever the detail carries.
 *
 * `ApprovalDocumentSummary` names the drafter by account id only and no
 * endpoint turns an account id into a person, so the only names available are
 * the ones snapshotted onto a step or a trail entry. This reads them out of a
 * document that has already been fetched rather than asking for anything.
 */
export function nameFromDocument(
  detail: DocumentDetail | undefined,
  accountId: string | undefined,
): string | null {
  if (detail === undefined || accountId === undefined) {
    return null;
  }
  for (const step of detail.steps ?? []) {
    for (const approver of step.approvers ?? []) {
      if (approver.accountId === accountId && approver.displayName !== undefined) {
        return approver.displayName;
      }
    }
  }
  for (const action of detail.trail ?? []) {
    if (action.actorAccountId === accountId && action.actorDisplayName !== undefined) {
      return action.actorDisplayName;
    }
  }
  return null;
}

/**
 * Presentation precision for a currency code.
 *
 * The authoritative source is the currency registry, which lives behind a book
 * id the approval endpoints do not have. Two decimals is right for most of the
 * world and wrong for KRW, which is the default locale's currency, so the
 * zero-decimal cases are named rather than guessed.
 */
const ZERO_DECIMAL_CURRENCIES = new Set(['KRW', 'JPY', 'VND', 'CLP', 'ISK']);

export function displayDecimalsFor(currencyCode: string | undefined): number {
  return currencyCode !== undefined && ZERO_DECIMAL_CURRENCIES.has(currencyCode) ? 0 : 2;
}
