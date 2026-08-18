import { Banner, Button } from '@coreintra/ui';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import { presentError } from '../../api/errors.js';

/**
 * Failures, shown the way the brief asks for them: what happened, what to do,
 * and every field-level violation at once because the server returns them at
 * once.
 *
 * The banner deliberately carries only the violations that have **no** field —
 * the ones with a field belong on that field, and printing them twice trains
 * people to read neither.
 */

export function ErrorNote({
  error,
  onRetry,
  title,
}: {
  readonly error: unknown;
  readonly onRetry?: () => void;
  readonly title?: string;
}): ReactNode {
  const { t } = useTranslation();
  if (error === null || error === undefined) {
    return null;
  }
  const presented = presentError(error, t);
  const unattached = presented.violations.filter((violation) => violation.field === '');

  return (
    <Banner
      tone="danger"
      title={title ?? t('error.title')}
      {...(onRetry === undefined || !presented.retryable
        ? {}
        : {
            actions: (
              <Button tone="default" onClick={onRetry}>
                {t('action.retry')}
              </Button>
            ),
          })}
    >
      <p style={{ margin: 0 }}>{presented.message}</p>
      {unattached.length === 0 ? null : (
        <ul style={{ margin: 'var(--ci-space-2) 0 0', paddingLeft: 'var(--ci-space-4)' }}>
          {unattached.map((violation) => (
            <li key={`${violation.field}:${violation.code}`}>{violation.message}</li>
          ))}
        </ul>
      )}
    </Banner>
  );
}

/**
 * The violations, keyed by the field the server named.
 *
 * The template publish is the case this exists for: a manifest that disagrees
 * with the body comes back as one violation per tag, and §12's rule is that a
 * field-level failure attaches to its field rather than being summarised in a
 * banner the person then has to map back onto a form by hand.
 */
export function violationsByField(error: unknown, t: ReturnType<typeof useTranslation>['t']): ReadonlyMap<string, string> {
  const byField = new Map<string, string>();
  if (error === null || error === undefined) {
    return byField;
  }
  for (const violation of presentError(error, t).violations) {
    if (violation.field === '') {
      continue;
    }
    const existing = byField.get(violation.field);
    byField.set(violation.field, existing === undefined ? violation.message : `${existing} ${violation.message}`);
  }
  return byField;
}
