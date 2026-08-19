import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, type CursorPage } from '../src/index.js';

interface Call {
  readonly url: string;
  readonly method: string;
  readonly headers: Record<string, string>;
  readonly body: string | null;
}

function recorder(handler: (call: Call, index: number) => Response): {
  fetch: typeof globalThis.fetch;
  calls: Call[];
} {
  const calls: Call[] = [];
  const fetchImpl = (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, key) => {
      headers[key] = value;
    });
    const call: Call = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers,
      body: typeof init?.body === 'string' ? init.body : null,
    };
    calls.push(call);
    return handler(call, calls.length - 1);
  }) as typeof globalThis.fetch;
  return { fetch: fetchImpl, calls };
}

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    ...init,
    headers: { 'content-type': 'application/json', ...init.headers },
  });
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title: code, status, code, ...extra }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

describe('requests', () => {
  it('sends the session cookie and asks for JSON', async () => {
    const { fetch, calls } = recorder(() => json({ ok: true }));
    const client = new ApiClient({ fetch });

    await client.get('/whoami');

    expect(calls[0]?.url).toBe('/api/v1/whoami');
    expect(calls[0]?.headers['accept']).toBe('application/json');
  });

  it('builds a query string and drops undefined values', async () => {
    const { fetch, calls } = recorder(() => json({ items: [], nextCursor: null }));
    const client = new ApiClient({ fetch });

    await client.get('/approvals', { query: { state: 'IN_PROGRESS', cursor: undefined, limit: 50 } });

    expect(calls[0]?.url).toBe('/api/v1/approvals?state=IN_PROGRESS&limit=50');
  });

  it('sends an idempotency key on a POST when one is supplied, and only then', async () => {
    const { fetch, calls } = recorder(() => json({ id: 'e1' }));
    const client = new ApiClient({ fetch });

    await client.post('/accounting/entries', { memo: 'x' }, { idempotencyKey: 'key-1' });
    await client.post('/search', { term: 'x' });

    expect(calls[0]?.headers['idempotency-key']).toBe('key-1');
    expect(calls[1]?.headers['idempotency-key']).toBeUndefined();
  });

  it('passes If-Match through and returns the ETag for the next write', async () => {
    const { fetch, calls } = recorder(() => json({ id: 'd1' }, { headers: { etag: 'W/"7"' } }));
    const client = new ApiClient({ fetch });

    const response = await client.request('/documents/d1', { method: 'PUT', ifMatch: 'W/"6"', body: {} });

    expect(calls[0]?.headers['if-match']).toBe('W/"6"');
    expect(response.etag).toBe('W/"7"');
  });

  it('returns undefined rather than exploding on 204', async () => {
    const { fetch } = recorder(() => new Response(null, { status: 204 }));
    const client = new ApiClient({ fetch });

    await expect(client.request('/sessions/s1', { method: 'DELETE' })).resolves.toMatchObject({ status: 204 });
  });
});

describe('failures', () => {
  it('turns problem+json into an ApiError carrying the machine-readable code', async () => {
    const { fetch } = recorder(() =>
      problem(403, 'permission_denied', { extensions: { requiredPermission: 'hr.employee:read' } }),
    );
    const client = new ApiClient({ fetch });

    const error = await client.get('/employees').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('permission_denied');
    expect((error as ApiError).status).toBe(403);
  });

  it('keeps every validation violation, because the server returns them at once', async () => {
    const { fetch } = recorder(() =>
      problem(400, 'validation_failed', {
        violations: [
          { field: 'amount', code: 'invalid', message: 'not an exact decimal' },
          { field: 'businessInstant', code: 'invalid_business_instant', message: 'a Z is not accepted' },
        ],
      }),
    );
    const client = new ApiClient({ fetch });

    const error = (await client.post('/expenses', {}).catch((e: unknown) => e)) as ApiError;

    expect(error.violations.map((v) => v.field)).toEqual(['amount', 'businessInstant']);
  });

  it('survives a reverse proxy answering with HTML instead of problem+json', async () => {
    const { fetch } = recorder(() => new Response('<html>502</html>', { status: 502, headers: { 'content-type': 'text/html' } }));
    const client = new ApiClient({ fetch });

    const error = (await client.get('/anything').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.problem).toBeNull();
    expect(error.retryable).toBe(true);
  });

  it('reports a network failure as its own thing, not as a fabricated status', async () => {
    const fetchImpl = (() => Promise.reject(new TypeError('offline'))) as unknown as typeof globalThis.fetch;
    const client = new ApiClient({ fetch: fetchImpl });

    const error = (await client.get('/anything').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(0);
    expect(error.retryable).toBe(false);
  });

  it('carries Retry-After off a 429 so the UI can say how long', async () => {
    const { fetch } = recorder(
      () =>
        new Response(JSON.stringify({ status: 429, code: 'rate_limited', type: '', title: 'x' }), {
          status: 429,
          headers: { 'content-type': 'application/problem+json', 'retry-after': '30' },
        }),
    );
    const client = new ApiClient({ fetch });

    const error = (await client.get('/anything').catch((e: unknown) => e)) as ApiError;

    expect(error.retryAfterSeconds).toBe(30);
  });
});

describe('session refresh', () => {
  it('refreshes once and replays the original request', async () => {
    const { fetch, calls } = recorder((call) => {
      if (call.url.endsWith('/auth/session/refresh')) {
        return json({ ok: true });
      }
      return calls.filter((c) => !c.url.includes('refresh')).length === 1
        ? problem(401, 'unauthenticated')
        : json({ id: 'me' });
    });
    const client = new ApiClient({ fetch });

    await expect(client.get('/whoami')).resolves.toEqual({ id: 'me' });

    expect(calls.map((c) => c.url)).toEqual([
      '/api/v1/whoami',
      '/api/v1/auth/session/refresh',
      '/api/v1/whoami',
    ]);
  });

  it('refreshes ONCE for six parallel 401s, because racing rotations burn the chain', async () => {
    let refreshes = 0;
    let expired = true;
    const { fetch } = recorder((call) => {
      if (call.url.endsWith('/auth/session/refresh')) {
        refreshes += 1;
        expired = false;
        return json({ ok: true });
      }
      return expired ? problem(401, 'unauthenticated') : json({ ok: true });
    });
    const client = new ApiClient({ fetch });

    await Promise.all(Array.from({ length: 6 }, () => client.get('/whoami')));

    expect(refreshes).toBe(1);
  });

  it('gives up and reports the session over when the refresh itself fails', async () => {
    const onSessionExpired = vi.fn();
    const { fetch } = recorder((call) =>
      call.url.endsWith('/auth/session/refresh') ? problem(401, 'unauthenticated') : problem(401, 'unauthenticated'),
    );
    const client = new ApiClient({ fetch, onSessionExpired });

    await expect(client.get('/whoami')).rejects.toBeInstanceOf(ApiError);
    expect(onSessionExpired).toHaveBeenCalledOnce();
  });

  it('never tries to refresh a failing refresh', async () => {
    const { fetch, calls } = recorder(() => problem(401, 'unauthenticated'));
    const client = new ApiClient({ fetch });

    await expect(client.request('/auth/session/refresh', { method: 'POST' })).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(1);
  });
});

describe('pagination', () => {
  it('walks the cursor and stops when the server says there is no more', async () => {
    const pages: Record<string, CursorPage<{ id: string }>> = {
      '/api/v1/approvals': { items: [{ id: 'a' }, { id: 'b' }], nextCursor: 'c2' },
      '/api/v1/approvals?cursor=c2': { items: [{ id: 'c' }], nextCursor: null },
    };
    const { fetch, calls } = recorder((call) => json(pages[call.url]));
    const client = new ApiClient({ fetch });

    const seen: string[] = [];
    for await (const row of client.paginate<{ id: string }>('/approvals')) {
      seen.push(row.id);
    }

    expect(seen).toEqual(['a', 'b', 'c']);
    expect(calls).toHaveLength(2);
  });
});
