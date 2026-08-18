/**
 * The `fetch` boundary.
 *
 * This module must be imported **before** anything that reaches the shared
 * `ApiClient`, because the client binds `globalThis.fetch` when it is
 * constructed at module scope. It has no imports of its own apart from vitest,
 * so putting it first in a test file's import list is enough.
 *
 * Mocking here rather than stubbing the client is deliberate: the client's
 * refresh, `If-Match`, idempotency-key and problem+json handling are part of
 * what these screens depend on, and a stub would test the stub.
 */
import { vi } from 'vitest';

export interface RecordedCall {
  readonly method: string;
  readonly path: string;
  readonly search: string;
  readonly headers: Record<string, string>;
  readonly body: unknown;
}

export type Reply = Response | Promise<Response>;
export type Handler = (call: RecordedCall) => Reply;

const handlers = new Map<string, Handler>();
export const calls: RecordedCall[] = [];

export function resetApi(): void {
  handlers.clear();
  calls.length = 0;
}

/** `route('GET /accounting/books', body)` or a handler for anything conditional. */
export function route(spec: string, reply: Handler | unknown): void {
  handlers.set(spec, typeof reply === 'function' ? (reply as Handler) : () => json(reply));
}

export function json(body: unknown, init: { status?: number; etag?: string } = {}): Response {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (init.etag !== undefined) {
    headers['etag'] = init.etag;
  }
  return new Response(JSON.stringify(body), { status: init.status ?? 200, headers });
}

export function problem(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify({ type: 'about:blank', status, ...body }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

/** Every call made so far, for asserting that something was — or was not — sent. */
export function callsTo(method: string, path: string): RecordedCall[] {
  return calls.filter((call) => call.method === method && call.path === path);
}

const mock = vi.fn(async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
  const raw = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  const url = new URL(raw, 'http://localhost');
  const method = (init?.method ?? 'GET').toUpperCase();
  const path = url.pathname.replace(/^\/api\/v1/, '');

  const headers: Record<string, string> = {};
  new Headers(init?.headers ?? {}).forEach((value, key) => {
    headers[key] = value;
  });

  const call: RecordedCall = {
    method,
    path,
    search: url.search,
    headers,
    body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : null,
  };
  calls.push(call);

  const handler = handlers.get(`${method} ${path}`);
  if (handler === undefined) {
    // Louder than a 404: an unrouted call is a test that does not know what its
    // screen asks for, and silently answering it would hide that.
    return Promise.resolve(
      problem(500, { title: `No stub for ${method} ${path}`, detail: `No stub for ${method} ${path}` }),
    );
  }
  return handler(call);
});

globalThis.fetch = mock as unknown as typeof globalThis.fetch;
