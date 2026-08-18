import { ApiError, isProblem, type Problem } from './problem.js';

/**
 * The HTTP transport every request goes through.
 *
 * Deliberately small and hand-written, unlike the types: this is behaviour, not
 * schema. It owns four things the generated layer cannot:
 *
 *  1. **Single-flight refresh.** Fifteen-minute access tokens mean a screen with
 *     six parallel queries will hit 401 six times at once. Refreshing once and
 *     letting the others wait is the difference between one rotation and six
 *     racing rotations, and six racing rotations trip the reuse detection and
 *     sign the user out — the exact bug the rotation exists to prevent.
 *  2. **Idempotency keys** on the writes that create money or approvals, so a
 *     retry after a dropped response cannot double-post.
 *  3. **ETag / If-Match** plumbing, so a stale editor loses to a clear 412
 *     rather than silently overwriting someone.
 *  4. **RFC 7807 parsing**, so every failure reaches the UI as one shape.
 */

export interface ClientOptions {
  /** Same-origin in every deployment: the reverse proxy serves the SPA and the API together. */
  readonly baseUrl?: string;
  readonly fetch?: typeof globalThis.fetch;
  /** Called when a refresh fails and the session is genuinely over. */
  readonly onSessionExpired?: () => void;
  /** Overridable so tests are deterministic. */
  readonly newIdempotencyKey?: () => string;
}

export interface RequestOptions {
  readonly method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  readonly query?: Readonly<Record<string, string | number | boolean | undefined>>;
  readonly body?: unknown;
  /** Sent as `If-Match`. Required by the server on mutations of versioned resources. */
  readonly ifMatch?: string;
  /**
   * Supply for POSTs that create money or approvals. Pass the *same* key when
   * retrying the same logical operation; a new key is a new operation.
   */
  readonly idempotencyKey?: string;
  readonly signal?: AbortSignal;
  readonly accept?: string;
}

export interface ApiResponse<T> {
  readonly data: T;
  /** Present on resources that support optimistic concurrency. Feed it back as `ifMatch`. */
  readonly etag: string | null;
  readonly status: number;
}

/** One page of a cursor-paginated collection. Offsets are not offered: they skip and duplicate under writes. */
export interface CursorPage<T> {
  readonly items: readonly T[];
  /** Pass as `cursor` to fetch the next page. `null` means this was the last one. */
  readonly nextCursor: string | null;
}

const REFRESH_PATH = '/auth/session/refresh';

export class ApiClient {
  private readonly baseUrl: string;
  private readonly doFetch: typeof globalThis.fetch;
  private readonly onSessionExpired: (() => void) | undefined;
  private readonly newIdempotencyKey: () => string;
  /** The in-flight refresh, shared by every request that hit a 401 at once. */
  private refreshing: Promise<boolean> | null = null;

  constructor(options: ClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? '/api/v1';
    this.doFetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.onSessionExpired = options.onSessionExpired;
    this.newIdempotencyKey = options.newIdempotencyKey ?? defaultIdempotencyKey;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const first = await this.send(path, options);
    if (first.status !== 401 || path === REFRESH_PATH) {
      return this.interpret<T>(first);
    }

    // 401 on an ordinary call means the access token aged out. Refresh once,
    // for everybody, then replay.
    const refreshed = await this.refreshOnce();
    if (!refreshed) {
      this.onSessionExpired?.();
      return this.interpret<T>(first);
    }
    return this.interpret<T>(await this.send(path, options));
  }

  async get<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return (await this.request<T>(path, { ...options, method: 'GET' })).data;
  }

  async post<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return (await this.request<T>(path, { ...options, method: 'POST', body })).data;
  }

  /**
   * Walks a cursor-paginated collection.
   *
   * An async generator rather than a "fetch all" helper, so a caller that only
   * needs the first screenful cannot accidentally pull a hundred thousand rows.
   */
  async *paginate<T>(path: string, options: RequestOptions = {}): AsyncGenerator<T, void, undefined> {
    let cursor: string | null = null;
    do {
      // Sequential by definition: page N+1's cursor comes out of page N. There
      // is nothing here to parallelise.
      // oxlint-disable-next-line no-await-in-loop
      const page: CursorPage<T> = await this.get<CursorPage<T>>(path, {
        ...options,
        query: { ...options.query, ...(cursor === null ? {} : { cursor }) },
      });
      yield* page.items;
      cursor = page.nextCursor;
    } while (cursor !== null);
  }

  private async refreshOnce(): Promise<boolean> {
    this.refreshing ??= this.send(REFRESH_PATH, { method: 'POST' })
      .then((response) => response.ok)
      .catch(() => false)
      .finally(() => {
        this.refreshing = null;
      });
    return this.refreshing;
  }

  private async send(path: string, options: RequestOptions): Promise<Response> {
    const method = options.method ?? 'GET';
    const headers = new Headers({ accept: options.accept ?? 'application/json' });

    if (options.body !== undefined) {
      headers.set('content-type', 'application/json');
    }
    if (options.ifMatch !== undefined) {
      headers.set('if-match', options.ifMatch);
    }
    if (method === 'POST' && options.idempotencyKey !== undefined) {
      headers.set('idempotency-key', options.idempotencyKey);
    }

    const request: RequestInit = {
      method,
      headers,
      // The session cookie is HttpOnly and first-party. Nothing here reads it,
      // which is the point: script cannot exfiltrate what script cannot see.
      credentials: 'same-origin',
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    };

    try {
      return await this.doFetch(this.baseUrl + path + buildQuery(options.query), request);
    } catch (cause) {
      // A network failure is not an HTTP status, and turning it into a fake
      // response would let it be handled as though the server had answered.
      // The underlying error is kept as the cause: on a self-hosted box the
      // difference between DNS, TLS and a refused connection is the whole
      // support ticket.
      const failure = new ApiError(0, null, 'Could not reach the server', null);
      failure.cause = cause;
      throw failure;
    }
  }

  private async interpret<T>(response: Response): Promise<ApiResponse<T>> {
    if (response.ok) {
      const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T);
      return { data, etag: response.headers.get('etag'), status: response.status };
    }
    throw new ApiError(
      response.status,
      await readProblem(response),
      `Request failed with ${response.status}`,
      readRetryAfter(response),
    );
  }

  /** A fresh key for a new logical operation. Reuse the same one when retrying. */
  idempotencyKey(): string {
    return this.newIdempotencyKey();
  }
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

function buildQuery(query: RequestOptions['query']): string {
  if (query === undefined) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.append(key, String(value));
    }
  }
  const rendered = params.toString();
  return rendered === '' ? '' : `?${rendered}`;
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}
