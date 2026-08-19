/**
 * Every call the accounting screens make, in one file.
 *
 * The types are the generated ones (`components['schemas'][…]`) rather than
 * hand-written mirrors: if a shape here is wrong, the contract is wrong and the
 * fix belongs in the backend, not in a local interface that papers over it.
 *
 * There is one exception, and it is deliberate and narrow — see `readInstant`.
 */
import type { components, CursorPage } from '@coreintra/api-client';
import { type BusinessInstant, formatBusinessInstant, parseBusinessInstant } from '@coreintra/business-time';
import { api } from '../../api/client.js';

export type Book = components['schemas']['BookResponse'];
export type Company = components['schemas']['CompanyView'];
export type Account = components['schemas']['AccountResponse'];
export type Currency = components['schemas']['CurrencyResponse'];
export type Client = components['schemas']['ClientResponse'];
export type Entry = components['schemas']['EntryResponse'];
export type Posting = components['schemas']['PostingResponse'];
export type Revision = components['schemas']['RevisionResponse'];
export type Batch = components['schemas']['BatchResponse'];
export type PostEntryRequest = components['schemas']['PostEntryRequest'];
export type PostingRequest = components['schemas']['PostingRequest'];
export type OpenAccountRequest = components['schemas']['OpenAccountRequest'];
export type UpdateAccountRequest = components['schemas']['UpdateAccountRequest'];
export type WriteBatchRequest = components['schemas']['WriteBatchRequest'];
export type PreviewRequest = components['schemas']['PreviewRequest'];
export type PreviewResponse = components['schemas']['PreviewResponse'];
export type TrialBalance = components['schemas']['TrialBalanceResponse'];
export type BalanceSheet = components['schemas']['BalanceSheetResponse'];
export type IncomeStatement = components['schemas']['IncomeStatementResponse'];
export type CashFlow = components['schemas']['CashFlowResponse'];
export type EquityStatement = components['schemas']['EquityStatementResponse'];
export type PrepaidSchedules = components['schemas']['PrepaidSchedulesResponse'];
export type ReceivablesAgeing = components['schemas']['ReceivablesAgeingResponse'];

/**
 * A business instant arrives as a string — `2026-08-30T27:00:00.000` — because
 * that is what `BusinessTimeJacksonModule` writes and what the brief specifies.
 * The generated `BusinessInstant` schema is an object carrying a derived
 * `outsideCalendarDay` flag, which is not what any endpoint sends or accepts.
 *
 * That is a contract bug and it is reported rather than absorbed: this pair of
 * functions is the only place the screens deal with it, so when the generator
 * catches up the fix is two lines here and nothing else.
 */
export function readInstant(value: unknown): BusinessInstant | null {
  if (typeof value !== 'string') {
    return null;
  }
  try {
    return parseBusinessInstant(value);
  } catch {
    // A moment the client cannot parse is a server that broke the wire form.
    // Showing a dash beats crashing a whole journal over one row.
    return null;
  }
}

/**
 * The counterpart of {@link readInstant}, for request bodies.
 *
 * A plain string now. The contract used to type a business instant as an object
 * carrying a stray `outsideCalendarDay` flag, because springdoc introspected the
 * Java class instead of the wire form; a converter fixes that at the source, so
 * there is no schema type to cast through any more.
 */
export function wireInstant(instant: BusinessInstant): string {
  return formatBusinessInstant(instant);
}

export const keys = {
  companies: ['accounting', 'companies'] as const,
  books: (companyId: string) => ['accounting', 'books', companyId] as const,
  book: (bookId: string) => ['accounting', 'book', bookId] as const,
  accounts: (bookId: string) => ['accounting', 'accounts', bookId] as const,
  currencies: (bookId: string) => ['accounting', 'currencies', bookId] as const,
  clients: (bookId: string) => ['accounting', 'clients', bookId] as const,
  entries: (bookId: string) => ['accounting', 'entries', bookId] as const,
  entry: (entryId: string) => ['accounting', 'entry', entryId] as const,
  batches: (bookId: string) => ['accounting', 'batches', bookId] as const,
  batch: (batchId: string) => ['accounting', 'batch', batchId] as const,
  report: (bookId: string, name: string, range: Readonly<Record<string, unknown>>) =>
    ['accounting', 'report', bookId, name, range] as const,
};

/**
 * Walks a cursor-paginated collection to the end.
 *
 * Only for the collections the contract actually paginates — the chart of
 * accounts, which has to be complete before a tree can be drawn, and the
 * company list. Currencies, clients and batches come back as plain arrays and
 * are fetched as such; running them through here would read `items` off an
 * array and yield nothing at all.
 */
async function all<T>(path: string, query?: Readonly<Record<string, string | number | undefined>>): Promise<T[]> {
  const items: T[] = [];
  for await (const item of api.paginate<T>(path, query === undefined ? {} : { query })) {
    items.push(item);
  }
  return items;
}

export const accounting = {
  companies: (): Promise<Company[]> => all<Company>('/org/companies'),

  books: (companyId: string): Promise<Book[]> =>
    api.get<Book[]>('/accounting/books', { query: { companyId } }),

  book: (bookId: string): Promise<Book> => api.get<Book>(`/accounting/books/${bookId}`),

  openBook: (body: components['schemas']['OpenBookRequest'], idempotencyKey?: string): Promise<Book> =>
    api.post<Book>('/accounting/books', body, idempotencyKey === undefined ? {} : { idempotencyKey }),

  accounts: (bookId: string): Promise<Account[]> =>
    all<Account>(`/accounting/books/${bookId}/accounts`, { limit: 200 }),

  account: (bookId: string, accountId: string) =>
    api.request<Account>(`/accounting/books/${bookId}/accounts/${accountId}`),

  openAccount: (bookId: string, body: OpenAccountRequest): Promise<Account> =>
    api.post<Account>(`/accounting/books/${bookId}/accounts`, body),

  updateAccount: (bookId: string, accountId: string, body: UpdateAccountRequest, ifMatch?: string) =>
    api.request<Account>(`/accounting/books/${bookId}/accounts/${accountId}`, {
      method: 'PATCH',
      body,
      ...(ifMatch === undefined ? {} : { ifMatch }),
    }),

  retireAccount: (bookId: string, accountId: string, ifMatch?: string) =>
    api.request<void>(`/accounting/books/${bookId}/accounts/${accountId}`, {
      method: 'DELETE',
      ...(ifMatch === undefined ? {} : { ifMatch }),
    }),

  currencies: (bookId: string): Promise<Currency[]> =>
    api.get<Currency[]>(`/accounting/books/${bookId}/currencies`),

  defineCurrency: (bookId: string, code: string, body: components['schemas']['CurrencyRequest']) =>
    api.request<Currency>(`/accounting/books/${bookId}/currencies/${code}`, { method: 'PUT', body }),

  clients: (bookId: string): Promise<Client[]> =>
    api.get<Client[]>(`/accounting/books/${bookId}/clients`),

  registerClient: (bookId: string, body: components['schemas']['ClientRequest']): Promise<Client> =>
    api.post<Client>(`/accounting/books/${bookId}/clients`, body),

  entries: (bookId: string, cursor: string | null): Promise<CursorPage<Entry>> =>
    api.get<CursorPage<Entry>>(`/accounting/books/${bookId}/entries`, {
      query: { limit: 50, ...(cursor === null ? {} : { cursor }) },
    }),

  postEntry: (bookId: string, body: PostEntryRequest, idempotencyKey: string): Promise<Entry> =>
    api.post<Entry>(`/accounting/books/${bookId}/entries`, body, { idempotencyKey }),

  entry: (entryId: string) => api.request<Entry>(`/accounting/entries/${entryId}`),

  correctEntry: (entryId: string, body: components['schemas']['CorrectEntryRequest'], ifMatch?: string) =>
    api.request<Entry>(`/accounting/entries/${entryId}`, {
      method: 'PATCH',
      body,
      ...(ifMatch === undefined ? {} : { ifMatch }),
    }),

  voidEntry: (entryId: string, body: components['schemas']['VoidRequest'], ifMatch?: string) =>
    api.request<Entry>(`/accounting/entries/${entryId}/void`, {
      method: 'POST',
      body,
      ...(ifMatch === undefined ? {} : { ifMatch }),
    }),

  batches: (bookId: string): Promise<Batch[]> =>
    api.get<Batch[]>(`/accounting/books/${bookId}/batches`),

  /**
   * The only way many entries reach the ledger at once, and the only way an
   * amortisation schedule is ever posted — by a person, deliberately, after
   * reading the preview.
   */
  writeBatch: (bookId: string, body: WriteBatchRequest, idempotencyKey: string): Promise<Batch> =>
    api.post<Batch>(`/accounting/books/${bookId}/batches`, body, { idempotencyKey }),

  batch: (batchId: string): Promise<Batch> => api.get<Batch>(`/accounting/batches/${batchId}`),

  voidBatch: (batchId: string, body: components['schemas']['VoidRequest']) =>
    api.post<components['schemas']['VoidedBatchResponse']>(`/accounting/batches/${batchId}/void`, body),

  preview: (bookId: string, body: PreviewRequest): Promise<PreviewResponse> =>
    api.post<PreviewResponse>(`/accounting/books/${bookId}/amortisation/preview`, body),

  report: <T>(bookId: string, name: string, query: Readonly<Record<string, string | undefined>>): Promise<T> =>
    api.get<T>(`/accounting/books/${bookId}/reports/${name}`, { query }),
};
