/**
 * RFC 7807 problem+json, as this API returns it.
 *
 * Mirrors `com.coreintra.app.api.error.ProblemDetail` on the server. This is
 * the one hand-written shape in this package: it is the *error* contract rather
 * than a resource schema, it is identical for every endpoint, and the client
 * has to be able to parse a failure even when the response never matched the
 * generated types in the first place.
 */
export interface Problem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  /** Machine-readable, stable, and what the UI switches on. Never the title. */
  readonly code: string;
  /** Every failure at once — §10 forbids returning them one at a time. */
  readonly violations?: readonly Violation[];
  readonly extensions?: Readonly<Record<string, unknown>>;
}

export interface Violation {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

/**
 * A failed request.
 *
 * Carries the parsed problem when the server sent one, and enough of the raw
 * response when it did not — a reverse proxy returning its own 502 HTML page is
 * a real failure mode on a self-hosted box, and swallowing it into "unexpected
 * error" is how that becomes unsupportable.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem | null;
  readonly violations: readonly Violation[];
  /** Present on 429, in seconds, when the server said so. */
  readonly retryAfterSeconds: number | null;

  constructor(status: number, problem: Problem | null, fallbackMessage: string, retryAfterSeconds: number | null) {
    super(problem?.detail ?? problem?.title ?? fallbackMessage);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.code = problem?.code ?? codeForStatus(status);
    this.violations = problem?.violations ?? [];
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** True when retrying the identical request could plausibly succeed. */
  get retryable(): boolean {
    return this.status === 429 || this.status >= 500;
  }
}

function codeForStatus(status: number): string {
  switch (status) {
    case 401:
      return 'unauthenticated';
    case 403:
      return 'permission_denied';
    case 404:
      return 'not_found';
    case 409:
      return 'conflict';
    case 412:
      return 'precondition_failed';
    case 429:
      return 'rate_limited';
    default:
      return status >= 500 ? 'server_error' : 'request_failed';
  }
}

export function isProblem(value: unknown): value is Problem {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<Problem>;
  return typeof candidate.status === 'number' && typeof candidate.code === 'string';
}
