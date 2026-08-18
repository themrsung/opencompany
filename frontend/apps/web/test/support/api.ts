/**
 * The `fetch` boundary, stubbed.
 *
 * The screens are tested through the real `ApiClient`, because its refresh,
 * its problem+json parsing and its idempotency header are part of what they
 * depend on — a stub of the client would test a fiction. So the seam is one
 * level lower: `globalThis.fetch`.
 *
 * **This module has no imports on purpose.** `ApiClient` binds `globalThis.fetch`
 * once, when `src/api/client.ts` is first evaluated, so the stub has to be in
 * place before that module loads. Having nothing to import means this file's
 * body runs the moment anything imports it, and `support/render.tsx` imports it
 * first, before it reaches for any screen.
 */

export interface RecordedRequest {
  readonly method: string;
  /** Path with the `/api/v1` prefix removed, e.g. `/approvals/inbox`. */
  readonly path: string;
  readonly query: URLSearchParams;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: unknown;
}

export interface Reply {
  readonly status?: number;
  readonly body?: unknown;
  readonly etag?: string;
}

export type Responder = Reply | ((request: RecordedRequest) => Reply);

const responders = new Map<string, Responder[]>();

/** Every request the screens made, in order, for assertions. */
export const requests: RecordedRequest[] = [];

/**
 * Answers for `METHOD /path`.
 *
 * Several replies are consumed in order and the last one repeats, which is how
 * "it failed, then it worked" is expressed — the shape of a retry.
 */
export function route(key: string, ...replies: Responder[]): void {
  responders.set(key, [...replies]);
}

export function resetApi(): void {
  responders.clear();
  requests.length = 0;
}

/** The requests made to one endpoint, oldest first. */
export function requestsTo(key: string): RecordedRequest[] {
  return requests.filter((request) => `${request.method} ${request.path}` === key);
}

function nextReply(request: RecordedRequest): Reply {
  const key = `${request.method} ${request.path}`;
  const queue = responders.get(key);
  const responder = queue === undefined ? undefined : (queue.length > 1 ? queue.shift() : queue[0]);

  if (responder === undefined) {
    // A test that forgot a stub should say so, not quietly render an empty
    // screen and pass.
    return {
      status: 501,
      body: {
        type: 'about:blank',
        title: 'No stub',
        status: 501,
        code: 'not_stubbed',
        detail: `No stub for ${key}`,
      },
    };
  }

  return typeof responder === 'function' ? responder(request) : responder;
}

function record(input: RequestInfo | URL, init: RequestInit | undefined): RecordedRequest {
  const url = new URL(String(input), 'http://intranet.test');
  const headers: Record<string, string> = {};
  new Headers(init?.headers).forEach((value, key) => {
    headers[key] = value;
  });
  const raw = typeof init?.body === 'string' ? init.body : null;

  return {
    method: init?.method ?? 'GET',
    path: url.pathname.replace(/^\/api\/v1/, ''),
    query: url.searchParams,
    headers,
    body: raw === null ? undefined : (JSON.parse(raw) as unknown),
  };
}

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = record(input, init);
  requests.push(request);

  const reply = nextReply(request);
  const status = reply.status ?? 200;
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (reply.etag !== undefined) {
    headers['etag'] = reply.etag;
  }

  return new Response(status === 204 ? null : JSON.stringify(reply.body ?? {}), {
    status,
    headers,
  });
}) as typeof globalThis.fetch;

/** One page of a cursor-paginated collection, as the server sends it. */
export function page<T>(items: readonly T[]): { items: readonly T[]; nextCursor: null } {
  return { items, nextCursor: null };
}

/** An RFC 7807 body, as `ApiExceptionHandler` writes one. */
export function problem(status: number, code: string, detail: string): Reply {
  return { status, body: { type: 'about:blank', title: code, status, code, detail } };
}
