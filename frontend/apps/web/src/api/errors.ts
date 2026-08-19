import { ApiError, type Violation } from '@coreintra/api-client';
import type { TFunction } from 'i18next';

/**
 * Turns a failure into a sentence a person can act on.
 *
 * The rule the brief sets for error copy is "say what happened and what to
 * do", and the honest way to meet it is to branch on the server's stable
 * machine-readable `code` rather than on the status alone. A 403 that names
 * the missing permission is a support ticket someone can resolve in a minute;
 * "Forbidden" is one that takes a day.
 */
export interface PresentedError {
  /** One sentence, already localised. */
  readonly message: string;
  /** Field-level failures, when the server returned them — all of them, at once. */
  readonly violations: readonly Violation[];
  /** True when pressing the same button again could plausibly work. */
  readonly retryable: boolean;
  /** Seconds to wait, on a rate limit. */
  readonly retryAfterSeconds: number | null;
}

export function presentError(error: unknown, t: TFunction): PresentedError {
  if (!(error instanceof ApiError)) {
    // Something threw that was not a request failure — a render bug, most
    // likely. Saying "could not reach the server" here would send someone to
    // check the network for a problem that is in the page.
    return {
      message: t('error.unexpected'),
      violations: [],
      retryable: false,
      retryAfterSeconds: null,
    };
  }

  return {
    message: messageFor(error, t),
    violations: error.violations,
    retryable: error.retryable,
    retryAfterSeconds: error.retryAfterSeconds,
  };
}

function messageFor(error: ApiError, t: TFunction): string {
  if (error.status === 0) {
    return t('error.network');
  }

  switch (error.code) {
    case 'permission_denied':
      return t('error.permissionDenied', {
        permission: readRequiredPermission(error) ?? '—',
      });
    case 'not_found':
      return t('error.notFound');
    case 'precondition_failed':
    case 'conflict':
      return t('error.conflict');
    case 'validation_failed':
    case 'invalid_business_instant':
      return t('error.validation', { count: Math.max(1, error.violations.length) });
    case 'rate_limited':
      return t('error.rateLimited', { seconds: error.retryAfterSeconds ?? 60 });
    case 'conversion_worker_unavailable':
      return t('error.conversionWorkerDown');
    default:
      // The server's own detail is usually better than anything generic, and
      // it is written by the same people who wrote these strings. Fall back
      // only when there is nothing.
      return error.problem?.detail ?? error.problem?.title ?? t('error.unexpected');
  }
}

function readRequiredPermission(error: ApiError): string | null {
  const value = error.problem?.extensions?.['requiredPermission'];
  return typeof value === 'string' ? value : null;
}

/** The current ETag to send back as `If-Match`, or undefined on a first write. */
export function ifMatch(etag: string | null | undefined): string | undefined {
  return etag ?? undefined;
}
