import type { components, CursorPage } from '@coreintra/api-client';

/**
 * The document, template and font API as this screen has to consume it.
 *
 * Everything here that can be a generated type **is** a generated type: the
 * aliases below are re-exports of `components['schemas']`, so a backend change
 * lands as a type error at the call site rather than as `unknown` three layers
 * away.
 *
 * <h2>What is hand-written, and why that is a bug report rather than a style</h2>
 *
 * Fifteen of the thirty-nine endpoints in this area declare their response as
 * `ResponseEntity<Object>` with no `@ApiResponse(schema=...)`, so the committed
 * OpenAPI document gives them a wildcard content type with an empty schema and
 * `openapi-typescript` renders them `unknown`. The element types usually
 * exist — `InstalledFontView`,
 * `FidelityRowView`, `FieldValueView`, `DocumentVersionView`, `TemplateView` —
 * they are simply not attached to the operation, so the generated client cannot
 * know that `GET /fonts` is a page of them.
 *
 * The affected operations, so the list can be worked through:
 *
 * ```
 * GET  /documents/fidelity                              -> FidelityRowView | FidelityRowView[]
 * GET  /documents/field-values                          -> CursorPage<FieldValueHit>
 * GET  /documents/{id}/versions                         -> CursorPage<DocumentVersionView>
 * GET  /documents/{id}/versions/{no}/fields             -> FieldValueView[]
 * GET  /documents/{id}/versions/{no}/missing-required   -> string[]
 * GET  /fonts                                           -> CursorPage<InstalledFontView>
 * GET  /fonts/licence-acknowledgement                   -> AcknowledgementWording
 * GET  /fonts/resolve                                   -> FontWarningView[]
 * POST /fonts/substitutions                             -> SubstitutionMapView
 * POST /fonts/{id}/disable | /enable | DELETE /fonts/{id}
 * GET  /templates                                       -> CursorPage<TemplateView>
 * GET  /templates/{id}/versions                         -> TemplateVersion[]
 * POST /templates/{id}/versions                         -> TemplateVersion   (201, not the 200 the document claims)
 * POST /templates/{id}/restore-to-factory               -> TemplateVersion
 * GET  /templates/{id}/versions/{no}/fields             -> TemplateField[]
 * GET  /templates/{id}/versions/{no}/documents          -> CursorPage<DocumentView>
 * ```
 *
 * Three of those element types have no schema at all, because they are nested
 * classes returned only from untyped operations: `TemplateView.Version`,
 * `TemplateView.Field` and `FontController.AcknowledgementWording`. Those three
 * are written out below, marked, and are the only shapes in this area that this
 * screen describes for itself. Everything else narrows a container — array or
 * cursor page — around a generated element type.
 */

export type DocumentView = components['schemas']['DocumentView'];
export type DocumentVersionView = components['schemas']['DocumentVersionView'];
export type FieldValueView = components['schemas']['FieldValueView'];
export type VersionDiffView = components['schemas']['VersionDiffView'];
export type FieldChange = components['schemas']['FieldChange'];
export type ExportRequest = components['schemas']['ExportRequest'];
export type ExportView = components['schemas']['ExportView'];
export type FidelityRowView = components['schemas']['FidelityRowView'];
export type FeatureView = components['schemas']['FeatureView'];
export type FontWarningView = components['schemas']['FontWarningView'];
export type InstalledFontView = components['schemas']['InstalledFontView'];
export type RemovalImpactView = components['schemas']['RemovalImpactView'];
export type SubstitutionMapView = components['schemas']['SubstitutionMapView'];
export type SubstitutionRequest = components['schemas']['SubstitutionRequest'];
export type TemplateView = components['schemas']['TemplateView'];
export type CreateTemplateRequest = components['schemas']['CreateTemplateRequest'];
export type CreateFromTemplateRequest = components['schemas']['CreateFromTemplateRequest'];
export type ForkRequest = components['schemas']['ForkRequest'];
export type EmployeeView = components['schemas']['EmployeeView'];
export type OrgUnitView = components['schemas']['OrgUnitView'];
export type CompanyView = components['schemas']['CompanyView'];

/**
 * `TemplateView.Version`, which the OpenAPI document does not carry.
 *
 * Written from `app/api/documents/TemplateView.java`. Every field optional for
 * the same reason the generated views are: the server declares no `required`
 * anywhere in this area, so a screen that assumes presence is guessing.
 */
export interface TemplateVersion {
  readonly templateId?: string;
  readonly versionNo?: number;
  readonly hasApprovalBlock?: boolean;
  readonly supersedesVersionNo?: number | null;
  readonly publishedByAccountId?: string;
  /** Business-time wire form, `YYYY-MM-DDT[-]HH:MM:SS.mmm`. Never a timestamp. */
  readonly publishedAt?: string | null;
  /** UTC, recorded separately from the business instant above. */
  readonly createdAt?: string;
}

/** `TemplateView.Field` — one entry of the manifest. Also absent from the contract. */
export interface TemplateField {
  readonly tag?: string;
  readonly type?: string;
  readonly labelKo?: string;
  readonly labelEn?: string;
  readonly required?: boolean;
  readonly helpKo?: string;
  readonly helpEn?: string;
}

/**
 * `FontController.AcknowledgementWording` — the words an uploader must agree to.
 *
 * Absent from the contract, and the one shape here whose absence is dangerous
 * rather than merely inconvenient: the install refuses unless the text is
 * echoed back exactly, so a screen that guesses the field names cannot install
 * anything, and a screen that falls back to its own wording would record an
 * agreement to words nobody was shown.
 */
export interface AcknowledgementWording {
  readonly version?: string;
  readonly textKo?: string;
  readonly textEn?: string;
}

/** The four formats a document *body* can be stored in. `DocumentFormat` on the server. */
export const DOCUMENT_FORMATS = ['DOCX', 'HWPX', 'HWP', 'MDV'] as const;
export type DocumentFormat = (typeof DOCUMENT_FORMATS)[number];

/** Every export target. `RenderFormat` on the server; wider than the above because a render is one-way. */
export const RENDER_FORMATS = ['PDF', 'DOCX', 'DOC', 'HWP', 'HWPX', 'HTML', 'MDV'] as const;
export type RenderFormat = (typeof RENDER_FORMATS)[number];

/** `.doc` is the legacy binary target, and the only one the UI is required to label lossy. */
export const LEGACY_RENDER_FORMATS: readonly RenderFormat[] = ['DOC'];

/** `FieldType` on the server. The editor renders one input per entry. */
export const FIELD_TYPES = [
  'TEXT',
  'MULTILINE_TEXT',
  'NUMBER',
  'MONEY',
  'DATE',
  'BUSINESS_INSTANT',
  'EMPLOYEE_REF',
  'ORG_REF',
  'FILE',
  'TABLE',
] as const;
export type FieldType = (typeof FIELD_TYPES)[number];

export function isFieldType(value: string | undefined): value is FieldType {
  return value !== undefined && (FIELD_TYPES as readonly string[]).includes(value);
}

/**
 * `ExportView.status` is `ready` or `queued` — 200 carries the first, 202 the
 * second. `ExportView.mode` is `immediate`, `synchronous` or `asynchronous`,
 * and `jobState` is one of QUEUED, RUNNING, SUCCEEDED, FAILED or ABANDONED.
 * None of the five is an enum in the contract, so they are matched as strings
 * where they are read and anything unrecognised is shown verbatim rather than
 * mapped to a friendly label that might be wrong.
 */

export type SubstitutionScope = 'FAMILY' | 'SCRIPT';

export const EMBEDDING_PERMISSIONS = [
  'INSTALLABLE',
  'RESTRICTED',
  'PRINT_AND_PREVIEW',
  'EDITABLE',
  'UNKNOWN',
] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Narrows an untyped response to a list.
 *
 * Accepts the two containers this API actually returns — a bare JSON array and
 * a `CursorPage` — and nothing else. An unrecognised shape becomes an empty
 * list rather than a thrown render, and the screen's own empty message says
 * which read came back empty.
 */
export function readList<T>(value: unknown): readonly T[] {
  if (Array.isArray(value)) {
    return value as readonly T[];
  }
  if (isRecord(value) && Array.isArray(value['items'])) {
    return value['items'] as readonly T[];
  }
  return [];
}

export function readNextCursor(value: unknown): string | null {
  if (isRecord(value) && typeof value['nextCursor'] === 'string') {
    return value['nextCursor'];
  }
  return null;
}

export function readPage<T>(value: unknown): CursorPage<T> {
  return { items: readList<T>(value), nextCursor: readNextCursor(value) };
}

/**
 * The fidelity endpoint answers a single row when both `from` and `to` are
 * given and an array when neither is — one operation, two shapes, which is why
 * this exists rather than a cast at the call site.
 */
export function readFidelityRow(value: unknown): FidelityRowView | null {
  if (Array.isArray(value)) {
    const first: unknown = value[0];
    return isRecord(first) ? (first as FidelityRowView) : null;
  }
  if (isRecord(value) && ('fromFormat' in value || 'toFormat' in value || 'available' in value)) {
    return value as FidelityRowView;
  }
  return null;
}

/**
 * The acknowledgement wording, or null when the server sent something this
 * build cannot recognise.
 *
 * Fails closed on purpose. The install is refused unless the exact displayed
 * text is echoed back, so a half-understood response must close the install
 * form rather than let someone tick a box next to nothing.
 */
export function readWording(value: unknown): AcknowledgementWording | null {
  if (!isRecord(value)) {
    return null;
  }
  const version = value['version'];
  const textKo = value['textKo'];
  const textEn = value['textEn'];
  if (typeof version !== 'string' || typeof textKo !== 'string' || typeof textEn !== 'string') {
    return null;
  }
  return { version, textKo, textEn };
}

/**
 * True for a URL this screen is willing to turn into a download link.
 *
 * `contentUrl` and `pollUrl` arrive in the response body, and a link is a place
 * a value gets to act. Restricting them to same-origin API paths means a
 * mis-rendered or tampered field cannot become an off-site link on a page
 * people click without reading.
 */
export function isApiPath(url: string | undefined): url is string {
  return url !== undefined && url.startsWith('/api/v1/') && !url.startsWith('/api/v1//');
}
