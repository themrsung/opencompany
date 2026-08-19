import { ApiError, isProblem, type CursorPage, type Problem } from '@coreintra/api-client';
import { api } from '../../api/client.js';
import {
  readFidelityRow,
  readList,
  readPage,
  readWording,
  type AcknowledgementWording,
  type CompanyView,
  type CreateFromTemplateRequest,
  type CreateTemplateRequest,
  type DocumentVersionView,
  type DocumentView,
  type EmployeeView,
  type ExportRequest,
  type ExportView,
  type FidelityRowView,
  type FieldValueView,
  type FontWarningView,
  type ForkRequest,
  type InstalledFontView,
  type OrgUnitView,
  type RemovalImpactView,
  type SubstitutionMapView,
  type SubstitutionRequest,
  type TemplateField,
  type TemplateVersion,
  type TemplateView,
  type VersionDiffView,
} from './contract.js';

/**
 * Every request these screens make, in one place.
 *
 * Reads go through the shared {@link api} client so that they inherit its
 * single-flight refresh, its problem+json parsing and its ETag plumbing. The
 * uploads cannot, and {@link postMultipart} below says why.
 */

/** Query keys. Grouped so that a mutation can invalidate exactly what it changed. */
export const docKeys = {
  companies: ['docs', 'companies'] as const,
  employees: (companyId: string) => ['docs', 'employees', companyId] as const,
  units: (companyId: string) => ['docs', 'units', companyId] as const,

  documents: (companyId: string, documentType: string | null) =>
    ['docs', 'documents', companyId, documentType] as const,
  document: (documentId: string) => ['docs', 'document', documentId] as const,
  versions: (documentId: string) => ['docs', 'versions', documentId] as const,
  fields: (documentId: string, versionNo: number) => ['docs', 'fields', documentId, versionNo] as const,
  missingRequired: (documentId: string, versionNo: number) =>
    ['docs', 'missing-required', documentId, versionNo] as const,
  content: (documentId: string, versionNo: number) => ['docs', 'content', documentId, versionNo] as const,
  diff: (documentId: string, versionNo: number, against: number | null) =>
    ['docs', 'diff', documentId, versionNo, against] as const,
  fidelity: (from: string, to: string) => ['docs', 'fidelity', from, to] as const,
  exportJob: (documentId: string, jobId: string) => ['docs', 'export-job', documentId, jobId] as const,

  templates: (companyId: string) => ['docs', 'templates', companyId] as const,
  template: (templateId: string) => ['docs', 'template', templateId] as const,
  templateVersions: (templateId: string) => ['docs', 'template-versions', templateId] as const,
  templateFields: (templateId: string, versionNo: number) =>
    ['docs', 'template-fields', templateId, versionNo] as const,
  templateDocuments: (templateId: string, versionNo: number) =>
    ['docs', 'template-documents', templateId, versionNo] as const,

  fonts: (companyId: string) => ['docs', 'fonts', companyId] as const,
  fontWording: ['docs', 'font-wording'] as const,
  fontImpact: (fontId: string) => ['docs', 'font-impact', fontId] as const,
  substitutions: (companyId: string) => ['docs', 'substitutions', companyId] as const,
  resolve: (companyId: string, families: string, script: string) =>
    ['docs', 'resolve', companyId, families, script] as const,
} as const;

/** A read that also needs the ETag, because the next write has to send it back. */
export interface Tagged<T> {
  readonly data: T;
  readonly etag: string | null;
}

export const reads = {
  companies: async (): Promise<readonly CompanyView[]> =>
    readList<CompanyView>(await api.get<unknown>('/org/companies', { query: { limit: 200 } })),

  employees: async (companyId: string): Promise<readonly EmployeeView[]> =>
    readList<EmployeeView>(
      await api.get<unknown>('/org/employees', { query: { companyId, limit: 500 } }),
    ),

  units: async (companyId: string): Promise<readonly OrgUnitView[]> =>
    readList<OrgUnitView>(await api.get<unknown>('/org/units', { query: { companyId, limit: 500 } })),

  documents: async (
    companyId: string,
    documentType: string | null,
    cursor: string | null,
  ): Promise<CursorPage<DocumentView>> =>
    readPage<DocumentView>(
      await api.get<unknown>('/documents', {
        query: {
          companyId,
          ...(documentType === null ? {} : { documentType }),
          ...(cursor === null ? {} : { cursor }),
          limit: 50,
        },
      }),
    ),

  /** The header, with the ETag every mutation of this document must carry. */
  document: async (documentId: string): Promise<Tagged<DocumentView>> => {
    const response = await api.request<DocumentView>(`/documents/${encodeURIComponent(documentId)}`);
    return { data: response.data, etag: response.etag };
  },

  versions: async (documentId: string): Promise<readonly DocumentVersionView[]> =>
    readList<DocumentVersionView>(
      await api.get<unknown>(`/documents/${encodeURIComponent(documentId)}/versions`, {
        query: { limit: 100 },
      }),
    ),

  fields: async (documentId: string, versionNo: number): Promise<readonly FieldValueView[]> =>
    readList<FieldValueView>(
      await api.get<unknown>(
        `/documents/${encodeURIComponent(documentId)}/versions/${versionNo}/fields`,
      ),
    ),

  missingRequired: async (documentId: string, versionNo: number): Promise<readonly string[]> =>
    readList<string>(
      await api.get<unknown>(
        `/documents/${encodeURIComponent(documentId)}/versions/${versionNo}/missing-required`,
      ),
    ),

  diff: async (
    documentId: string,
    versionNo: number,
    against: number | null,
  ): Promise<VersionDiffView> =>
    api.get<VersionDiffView>(
      `/documents/${encodeURIComponent(documentId)}/versions/${versionNo}/diff`,
      { query: against === null ? {} : { against } },
    ),

  /**
   * The fidelity row for one pair, fetched before an export runs.
   *
   * §6.5 puts this at export time rather than in a manual nobody opens, so it
   * is a separate read the export panel makes as soon as a target format is
   * chosen — not something bundled into the export response after the fact.
   */
  fidelity: async (from: string, to: string): Promise<FidelityRowView | null> =>
    readFidelityRow(
      await api.get<unknown>('/documents/fidelity', {
        query: { from: from.toLowerCase(), to: to.toLowerCase() },
      }),
    ),

  exportJob: async (documentId: string, jobId: string): Promise<ExportView> =>
    api.get<ExportView>(
      `/documents/${encodeURIComponent(documentId)}/export/jobs/${encodeURIComponent(jobId)}`,
    ),

  templates: async (companyId: string): Promise<readonly TemplateView[]> =>
    readList<TemplateView>(await api.get<unknown>('/templates', { query: { companyId, limit: 200 } })),

  template: async (templateId: string): Promise<Tagged<TemplateView>> => {
    const response = await api.request<TemplateView>(`/templates/${encodeURIComponent(templateId)}`);
    return { data: response.data, etag: response.etag };
  },

  templateVersions: async (templateId: string): Promise<readonly TemplateVersion[]> =>
    readList<TemplateVersion>(
      await api.get<unknown>(`/templates/${encodeURIComponent(templateId)}/versions`),
    ),

  templateFields: async (templateId: string, versionNo: number): Promise<readonly TemplateField[]> =>
    readList<TemplateField>(
      await api.get<unknown>(
        `/templates/${encodeURIComponent(templateId)}/versions/${versionNo}/fields`,
      ),
    ),

  templateDocuments: async (templateId: string, versionNo: number): Promise<readonly DocumentView[]> =>
    readList<DocumentView>(
      await api.get<unknown>(
        `/templates/${encodeURIComponent(templateId)}/versions/${versionNo}/documents`,
        { query: { limit: 50 } },
      ),
    ),

  fonts: async (companyId: string): Promise<readonly InstalledFontView[]> =>
    readList<InstalledFontView>(await api.get<unknown>('/fonts', { query: { companyId, limit: 200 } })),

  fontWording: async (): Promise<AcknowledgementWording | null> =>
    readWording(await api.get<unknown>('/fonts/licence-acknowledgement')),

  removalImpact: async (fontId: string): Promise<RemovalImpactView> =>
    api.get<RemovalImpactView>(`/fonts/${encodeURIComponent(fontId)}/removal-impact`),

  substitutions: async (companyId: string): Promise<SubstitutionMapView> =>
    api.get<SubstitutionMapView>('/fonts/substitutions', { query: { companyId } }),

  resolve: async (
    companyId: string,
    families: readonly string[],
    script: string,
  ): Promise<readonly FontWarningView[]> =>
    readList<FontWarningView>(
      await api.get<unknown>('/fonts/resolve', {
        query: {
          companyId,
          // Repeated query parameters are not expressible through the client's
          // record-shaped `query`, and Spring binds a comma-separated list to a
          // List<String> just as happily.
          families: families.join(','),
          ...(script === '' ? {} : { script }),
        },
      }),
    ),
} as const;

export const writes = {
  createFromTemplate: (body: CreateFromTemplateRequest): Promise<DocumentView> =>
    api.post<DocumentView>('/documents', body, { idempotencyKey: api.idempotencyKey() }),

  retireDocument: (documentId: string, etag: string | null): Promise<DocumentView> =>
    api.post<DocumentView>(
      `/documents/${encodeURIComponent(documentId)}/retirement`,
      {},
      etag === null ? {} : { ifMatch: etag },
    ),

  runExport: async (
    documentId: string,
    body: ExportRequest,
  ): Promise<{ readonly view: ExportView; readonly status: number }> => {
    const response = await api.request<ExportView>(
      `/documents/${encodeURIComponent(documentId)}/export`,
      { method: 'POST', body },
    );
    return { view: response.data, status: response.status };
  },

  createTemplate: (body: CreateTemplateRequest): Promise<TemplateView> =>
    api.post<TemplateView>('/templates', body, { idempotencyKey: api.idempotencyKey() }),

  forkTemplate: (templateId: string, body: ForkRequest): Promise<TemplateView> =>
    api.post<TemplateView>(`/templates/${encodeURIComponent(templateId)}/fork`, body, {
      idempotencyKey: api.idempotencyKey(),
    }),

  restoreTemplate: (templateId: string, etag: string | null): Promise<TemplateVersion> =>
    api.post<TemplateVersion>(
      `/templates/${encodeURIComponent(templateId)}/restore-to-factory`,
      {},
      etag === null ? {} : { ifMatch: etag },
    ),

  retireTemplate: (templateId: string, etag: string | null): Promise<unknown> =>
    api.post<unknown>(
      `/templates/${encodeURIComponent(templateId)}/retirement`,
      {},
      etag === null ? {} : { ifMatch: etag },
    ),

  addSubstitution: (body: SubstitutionRequest): Promise<unknown> =>
    api.post<unknown>('/fonts/substitutions', body),

  enableFont: (fontId: string): Promise<unknown> =>
    api.post<unknown>(`/fonts/${encodeURIComponent(fontId)}/enable`, {}),

  disableFont: (fontId: string): Promise<unknown> =>
    api.post<unknown>(`/fonts/${encodeURIComponent(fontId)}/disable`, {}),

  /**
   * Removal states the count back, which is the point: the server refuses with
   * a 409 if the number moved while the person was reading it.
   */
  removeFont: (fontId: string, acknowledgedAffectedCount: number): Promise<unknown> =>
    api.request<unknown>(`/fonts/${encodeURIComponent(fontId)}`, {
      method: 'DELETE',
      query: { acknowledgedAffectedCount },
    }),
} as const;

/**
 * A multipart POST.
 *
 * **This should not be here.** `ApiClient.send` JSON-encodes every body it is
 * given, so the shared client cannot express `multipart/form-data` at all — and
 * four of the writes in this area are multipart: installing a font, publishing
 * a template version, uploading a document and saving a new document version.
 * The right fix is a `postForm` on `@coreintra/api-client`, which is not this
 * agent's file to write; until it exists these screens would otherwise be
 * read-only.
 *
 * What it keeps from the client: same-origin credentials so the HttpOnly
 * session cookie rides along, `If-Match`, the idempotency key, and problem+json
 * parsing into the same {@link ApiError} every other failure arrives as, so
 * `presentError` and the field-violation plumbing work unchanged.
 *
 * What it cannot keep is the single-flight refresh, because that lives inside
 * the client. A 401 is therefore handled by asking the shared client for a
 * cheap authenticated read — which triggers *its* refresh, once, for everybody
 * — and then replaying the upload with the same idempotency key, so a retry
 * after a rotation cannot double-post.
 */
export async function postMultipart<T>(
  path: string,
  form: FormData,
  options: {
    readonly query?: Readonly<Record<string, string | number | boolean | undefined>>;
    readonly ifMatch?: string | null;
    readonly idempotencyKey?: string;
    readonly method?: 'POST';
  } = {},
): Promise<T> {
  const key = options.idempotencyKey ?? api.idempotencyKey();
  const url = '/api/v1' + path + buildQuery(options.query);

  const send = async (): Promise<Response> => {
    const headers = new Headers({ accept: 'application/json', 'idempotency-key': key });
    if (options.ifMatch !== undefined && options.ifMatch !== null) {
      headers.set('if-match', options.ifMatch);
    }
    // Deliberately no content-type: the browser has to set the multipart
    // boundary itself, and setting it by hand produces a body the server
    // cannot parse.
    try {
      return await globalThis.fetch(url, {
        method: options.method ?? 'POST',
        headers,
        credentials: 'same-origin',
        body: form,
      });
    } catch (cause) {
      const failure = new ApiError(0, null, 'Could not reach the server', null);
      failure.cause = cause;
      throw failure;
    }
  };

  let response = await send();
  if (response.status === 401) {
    // Borrow the shared client's refresh rather than starting a second one.
    await api.get<unknown>('/auth/session/active').catch(() => undefined);
    response = await send();
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      await readProblem(response),
      `Request failed with ${response.status}`,
      readRetryAfter(response),
    );
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

/**
 * Downloads the stored bytes of a version as text.
 *
 * Only mdv gets here: its stored form *is* text, which is what makes it the one
 * format this screen can edit and save. Everything else is a binary body served
 * as a download link, never decoded into a textarea.
 */
export async function readVersionText(documentId: string, versionNo: number): Promise<string> {
  const url = `/api/v1/documents/${encodeURIComponent(documentId)}/versions/${versionNo}/content`;
  const response = await globalThis.fetch(url, {
    credentials: 'same-origin',
    headers: new Headers({ accept: 'text/plain, */*' }),
  });
  if (!response.ok) {
    throw new ApiError(
      response.status,
      await readProblem(response),
      `Request failed with ${response.status}`,
      null,
    );
  }
  return response.text();
}

async function readProblem(response: Response): Promise<Problem | null> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    return null;
  }
  try {
    const parsed: unknown = await response.json();
    return isProblem(parsed) ? parsed : null;
  } catch {
    // A reverse proxy's own 502 page is not JSON, and pretending it was would
    // lose the status that is the whole diagnosis.
    return null;
  }
}

function readRetryAfter(response: Response): number | null {
  const header = response.headers.get('retry-after');
  if (header === null) {
    return null;
  }
  const seconds = Number.parseInt(header, 10);
  return Number.isFinite(seconds) ? seconds : null;
}

function buildQuery(query: Readonly<Record<string, string | number | boolean | undefined>> | undefined): string {
  if (query === undefined) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.append(name, String(value));
    }
  }
  const rendered = params.toString();
  return rendered === '' ? '' : `?${rendered}`;
}
