/**
 * The `fetch` boundary, and nothing above it.
 *
 * This module imports nothing, on purpose. `ApiClient` binds `globalThis.fetch`
 * when the singleton in `src/api/client.ts` is constructed — at module
 * evaluation, before any test body runs — so a mock installed in `beforeEach`
 * would be installed into a variable nobody reads. Evaluating this file first
 * puts a stable dispatcher in place before the client can bind, and the route
 * table behind it stays swappable per test.
 *
 * Which means: **import this (or the harness that re-exports it) before you
 * import anything from `src/`.**
 */

export interface Call {
  readonly url: string;
  /** Pathname with the `/api/v1` prefix removed, as the screens write it. */
  readonly path: string;
  readonly method: string;
  readonly query: Readonly<Record<string, string>>;
  readonly body: unknown;
  readonly headers: Readonly<Record<string, string>>;
}

export interface Reply {
  readonly status?: number;
  readonly body?: unknown;
  readonly headers?: Readonly<Record<string, string>>;
}

export type Route = Reply | ((call: Call) => Reply);

let table: Record<string, Route> = {};
const calls: Call[] = [];

/** Replaces the route table and forgets what was called. One call per test. */
export function setRoutes(next: Record<string, Route>): void {
  table = next;
  calls.length = 0;
}

/** Every request the screen made, in order, including the ones nothing answered. */
export function recordedCalls(): readonly Call[] {
  return calls;
}

export function callsTo(key: string): readonly Call[] {
  return calls.filter((call) => `${call.method} ${call.path}` === key);
}

function readHeaders(init: RequestInit | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  const headers = init?.headers;
  if (headers instanceof Headers) {
    headers.forEach((value, key) => {
      out[key.toLowerCase()] = value;
    });
  } else if (Array.isArray(headers)) {
    for (const [key, value] of headers) {
      out[String(key).toLowerCase()] = String(value);
    }
  } else if (headers !== undefined) {
    for (const [key, value] of Object.entries(headers)) {
      out[key.toLowerCase()] = String(value);
    }
  }
  return out;
}

async function dispatch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const url =
    typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url;
  const parsed = new URL(url, 'http://intranet.test');
  const path = parsed.pathname.replace(/^\/api\/v1/, '');
  const method = (init?.method ?? 'GET').toUpperCase();
  const query: Record<string, string> = {};
  parsed.searchParams.forEach((value, key) => {
    query[key] = value;
  });

  const rawBody = init?.body;
  const call: Call = {
    url,
    path,
    method,
    query,
    body: typeof rawBody === 'string' ? (JSON.parse(rawBody) as unknown) : null,
    headers: readHeaders(init),
  };
  calls.push(call);

  const route = table[`${method} ${path}`];
  if (route === undefined) {
    // Loud rather than empty: a screen quietly rendering nothing because a
    // route was not stubbed is the test failure that takes an hour to read.
    return new Response(
      JSON.stringify({
        type: 'about:blank',
        title: 'No stub',
        status: 404,
        code: 'not_found',
        detail: `No stub for ${method} ${path}`,
      }),
      { status: 404, headers: { 'content-type': 'application/problem+json' } },
    );
  }

  const reply = typeof route === 'function' ? route(call) : route;
  const status = reply.status ?? 200;
  return new Response(status === 204 ? null : JSON.stringify(reply.body ?? null), {
    status,
    headers: { 'content-type': 'application/json', ...reply.headers },
  });
}

globalThis.fetch = dispatch as typeof globalThis.fetch;

/*
 * Two holes in the environment, patched rather than designed around.
 *
 * `localStorage` is missing under this Node/jsdom pairing — Node's own
 * experimental one shadows jsdom's and then refuses to work without a flag —
 * and the session provider legitimately uses it to remember a display name.
 * `crypto.randomUUID` is what the client mints idempotency keys with. Both
 * exist in every browser this ships to; neither is worth bending the source
 * out of shape for.
 */
function hasWorkingStorage(): boolean {
  try {
    return typeof globalThis.localStorage?.getItem === 'function';
  } catch {
    return false;
  }
}

if (!hasWorkingStorage()) {
  const cells = new Map<string, string>();
  const storage = {
    get length(): number {
      return cells.size;
    },
    clear: (): void => {
      cells.clear();
    },
    getItem: (key: string): string | null => cells.get(key) ?? null,
    key: (index: number): string | null => [...cells.keys()][index] ?? null,
    removeItem: (key: string): void => {
      cells.delete(key);
    },
    setItem: (key: string, value: string): void => {
      cells.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });
}

if (typeof globalThis.crypto?.randomUUID !== 'function') {
  let counter = 0;
  const randomUUID = (): string => {
    counter += 1;
    return `00000000-0000-4000-8000-${String(counter).padStart(12, '0')}`;
  };
  if (globalThis.crypto === undefined) {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { randomUUID } });
  } else {
    Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID });
  }
}
