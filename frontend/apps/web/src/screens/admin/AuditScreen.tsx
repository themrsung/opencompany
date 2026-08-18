import { Banner, Button, TextField } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { usePrimaryShortcut } from '../../session/keyboard.js';
import {
  CompanySelect,
  todayBusinessDate,
  useCompanyChoice,
} from '../../session/viewingContext.js';
import { AuditTable, type AuditEntry } from './AuditTable.js';

interface Filters {
  readonly companyId: string;
  readonly resource: string;
  readonly resourceId: string;
}

/**
 * The audit log.
 *
 * The server answers "everything that happened to one thing", so the resource
 * and its id are required rather than optional: this screen asks about a
 * subject, not about a time window. Narrowing by actor, outcome or capability
 * happens here on what came back — see the report, because that is a
 * limitation of the endpoint rather than a choice.
 */
export function AuditScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const choice = useCompanyChoice(todayBusinessDate());
  const companyId = choice.companyId;

  const [resource, setResource] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [applied, setApplied] = useState<Filters | null>(null);

  const [actor, setActor] = useState('');
  const [capability, setCapability] = useState('');
  const [outcome, setOutcome] = useState<'all' | 'allowed' | 'denied'>('all');

  const [newRetention, setNewRetention] = useState('');
  const [raisedTo, setRaisedTo] = useState<number | null>(null);

  const canApply = companyId !== null && resource.trim() !== '' && resourceId.trim() !== '';

  const apply = useCallback(() => {
    if (companyId === null || !canApply) {
      return;
    }
    setApplied({ companyId, resource: resource.trim(), resourceId: resourceId.trim() });
  }, [canApply, companyId, resource, resourceId]);

  usePrimaryShortcut(canApply, apply);

  const trail = useQuery({
    queryKey: ['audit', 'trail', applied],
    enabled: applied !== null,
    queryFn: async () => {
      const filters = applied as Filters;
      return api.get<AuditEntry[]>('/audit/trail', {
        query: {
          companyId: filters.companyId,
          resource: filters.resource,
          resourceId: filters.resourceId,
        },
      });
    },
  });

  const retention = useQuery({
    queryKey: ['audit', 'retention', companyId],
    enabled: companyId !== null,
    queryFn: async () =>
      api.get<number>('/audit/retention', { query: { companyId: companyId ?? '' } }),
  });

  const raise = useMutation({
    mutationFn: (days: number) =>
      api.post<void>('/audit/retention', undefined, {
        query: { companyId: companyId ?? '', days },
      }),
    onSuccess: (_result, days) => {
      setRaisedTo(days);
      setNewRetention('');
      void queryClient.invalidateQueries({ queryKey: ['audit', 'retention'] });
    },
  });

  const rows = useMemo(() => {
    const all = trail.data ?? [];
    const actorNeedle = actor.trim().toLowerCase();
    const capabilityNeedle = capability.trim().toLowerCase();
    return all.filter((entry) => {
      if (
        actorNeedle !== '' &&
        !`${entry.actorDisplayName ?? ''} ${entry.actorAccountId ?? ''}`
          .toLowerCase()
          .includes(actorNeedle)
      ) {
        return false;
      }
      if (capabilityNeedle !== '' && !(entry.capability ?? '').toLowerCase().includes(capabilityNeedle)) {
        return false;
      }
      const denied = (entry.outcome ?? '').toUpperCase().includes('DEN');
      if (outcome === 'denied' && !denied) {
        return false;
      }
      if (outcome === 'allowed' && denied) {
        return false;
      }
      return true;
    });
  }, [trail.data, actor, capability, outcome]);

  const currentRetention = retention.data;
  const proposed = Number.parseInt(newRetention, 10);
  const retentionValid =
    newRetention.trim() !== '' &&
    Number.isInteger(proposed) &&
    (currentRetention === undefined || proposed > currentRetention);

  const failure = trail.error ?? raise.error ?? choice.error;

  const onSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    apply();
  };

  return (
    <Page title={t('audit.title')}>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '48rem' }}>{t('audit.intro')}</p>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '48rem' }}>{t('audit.twoClocks')}</p>

      <form className="page__surface" style={{ padding: 'var(--ci-space-4)' }} onSubmit={onSubmit} noValidate>
        <h2>{t('audit.filters')}</h2>
        <div style={{ display: 'flex', gap: 'var(--ci-space-4)', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <CompanySelect choice={choice} language={i18n.language} />
          <TextField
            label={t('audit.filterResource')}
            name="resource"
            value={resource}
            onChange={(event) => {
              setResource(event.target.value);
            }}
          />
          <TextField
            label={t('audit.filterResourceId')}
            name="resourceId"
            value={resourceId}
            onChange={(event) => {
              setResourceId(event.target.value);
            }}
          />
          <TextField
            label={t('audit.filterActor')}
            name="actor"
            value={actor}
            onChange={(event) => {
              setActor(event.target.value);
            }}
          />
          <TextField
            label={t('audit.filterCapability')}
            name="capability"
            value={capability}
            onChange={(event) => {
              setCapability(event.target.value);
            }}
          />
          <div className="ci-field">
            <label className="ci-field__label" htmlFor="audit-outcome">
              {t('audit.filterOutcome')}
            </label>
            <select
              id="audit-outcome"
              className="ci-field__input"
              value={outcome}
              onChange={(event) => {
                const value = event.target.value;
                setOutcome(value === 'allowed' || value === 'denied' ? value : 'all');
              }}
            >
              <option value="all">{t('audit.anyValue')}</option>
              <option value="allowed">{t('audit.outcomeAllowed')}</option>
              <option value="denied">{t('audit.outcomeDenied')}</option>
            </select>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 'var(--ci-space-3)', marginTop: 'var(--ci-space-4)' }}>
          <Button tone="primary" type="submit" disabled={!canApply} busy={trail.isFetching}>
            {t('audit.apply')}
          </Button>
          <Button
            onClick={() => {
              setResource('');
              setResourceId('');
              setActor('');
              setCapability('');
              setOutcome('all');
              setApplied(null);
            }}
          >
            {t('audit.clear')}
          </Button>
        </div>
      </form>

      {failure === null || failure === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(failure, t).message}
        </Banner>
      )}

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <p style={{ color: 'var(--ci-fg-muted)' }}>
          {t('audit.count', { count: rows.length })} · {t('audit.truncated')}
        </p>
        <AuditTable
          rows={rows}
          emptyMessage={applied === null ? t('audit.emptyNoCompany') : t('audit.empty')}
        />
      </section>

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('audit.retention')}</h2>
        <p>
          {currentRetention === undefined
            ? '-'
            : t('audit.retentionDays', { days: currentRetention })}
        </p>
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('audit.retentionOneWay')}</p>
        <div style={{ display: 'flex', gap: 'var(--ci-space-3)', alignItems: 'flex-end' }}>
          <TextField
            label={t('audit.retentionNewValue')}
            inputMode="numeric"
            value={newRetention}
            onChange={(event) => {
              setNewRetention(event.target.value);
            }}
            {...(newRetention.trim() !== '' && !retentionValid
              ? { error: t('audit.retentionTooLow') }
              : {})}
          />
          <Button
            busy={raise.isPending}
            disabled={!retentionValid}
            onClick={() => {
              if (retentionValid) {
                raise.mutate(proposed);
              }
            }}
          >
            {t('audit.retentionRaise')}
          </Button>
        </div>
        {raisedTo === null ? null : (
          <Banner tone="positive">{t('audit.retentionRaised', { days: raisedTo })}</Banner>
        )}
      </section>
    </Page>
  );
}
