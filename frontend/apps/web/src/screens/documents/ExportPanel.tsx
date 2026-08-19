import { Badge, Banner, Button } from '@coreintra/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useCallback, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads, writes } from './api.js';
import {
  isApiPath,
  LEGACY_RENDER_FORMATS,
  RENDER_FORMATS,
  type ExportView,
  type FeatureView,
  type FidelityRowView,
  type FontWarningView,
  type RenderFormat,
} from './contract.js';
import { KeyValues, Mono, Panel, Select, useShortcut } from './controls.js';
import { ErrorNote } from './errors.js';
import { shortHash } from './format.js';

/**
 * Export, with the fidelity row in front of it.
 *
 * <h2>The order matters</h2>
 *
 * §6.5 puts the fidelity table "at export time, not in a manual", so the row
 * for the chosen pair is fetched and shown the moment a target format is
 * picked — before the export button does anything at all. The row can say three
 * different things and each is rendered as itself: this pair is supported and
 * here is what happens to each feature; this pair is not supported; or there is
 * no pair to describe, because the target is an export-only format nothing
 * reads back. The last one is the case an empty table would silently turn into
 * "no losses".
 *
 * <h2>Both answers, and the difference visible</h2>
 *
 * The API is synchronous with a bounded wait for small documents and
 * asynchronous with a job id for everything else, and a person needs to know
 * which they got: a file that is ready now, a worker that finished while they
 * waited, or a job that is still running. `mode` says which, and it is on
 * screen in words.
 *
 * <h2>A failed conversion offers nothing</h2>
 *
 * §13 requires that a conversion worker being down produce a clear, actionable
 * error and never a partial file. The download link is rendered only from a
 * `ready` response with an API-path `contentUrl`; a failure renders the
 * server's own message, which names the job, the attempts and the error code,
 * and a sentence saying there is no file — never a link that would 409.
 */
export function ExportPanel({
  documentId,
  storedFormat,
  currentVersionNo,
}: {
  readonly documentId: string;
  readonly storedFormat: string;
  readonly currentVersionNo: number;
}): ReactNode {
  const { t } = useTranslation();
  const [target, setTarget] = useState<RenderFormat>('PDF');
  const [versionNo, setVersionNo] = useState(currentVersionNo);
  const [jobId, setJobId] = useState<string | null>(null);
  const [answered, setAnswered] = useState<ExportView | null>(null);

  const fidelity = useQuery({
    queryKey: docKeys.fidelity(storedFormat, target),
    queryFn: () => reads.fidelity(storedFormat, target),
  });

  const run = useMutation({
    mutationFn: () =>
      writes.runExport(documentId, {
        format: target,
        versionNo,
        // The server caps this; asking to wait is how a small document comes
        // back as a file instead of a job. The conversion still happens in the
        // worker either way.
        waitMillis: 5_000,
      }),
    onSuccess: ({ view, status }) => {
      setAnswered(view);
      setJobId(status === 202 && view.jobId !== undefined ? view.jobId : null);
    },
  });

  const poll = useQuery({
    queryKey: docKeys.exportJob(documentId, jobId ?? ''),
    queryFn: () => reads.exportJob(documentId, jobId ?? ''),
    enabled: jobId !== null,
    refetchInterval: (query) => (query.state.data?.status === 'ready' ? false : 1_500),
  });

  useShortcut(
    useCallback((event: KeyboardEvent) => event.key === 'Enter' && (event.metaKey || event.ctrlKey), []),
    useCallback(() => {
      run.mutate();
    }, [run]),
  );

  const view: ExportView | null = poll.data ?? answered;
  const failure: unknown = run.error ?? poll.error;
  const ready = view?.status === 'ready' && failure === null;

  return (
    <Panel title={t('docs.exportCaption')}>
      <div
        style={{
          display: 'flex',
          gap: 'var(--ci-space-3)',
          alignItems: 'flex-end',
          flexWrap: 'wrap',
          marginBottom: 'var(--ci-space-3)',
        }}
      >
        <div style={{ minWidth: '12rem' }}>
          <Select
            label={t('docs.exportTarget')}
            value={target}
            onChange={(value) => {
              setTarget(value as RenderFormat);
              // A new pair is a new question: the previous answer and its job
              // must not stay on screen next to a row that no longer describes
              // them.
              setAnswered(null);
              setJobId(null);
              run.reset();
            }}
            options={RENDER_FORMATS.map((format) => ({ value: format, label: format }))}
          />
        </div>
        <div style={{ minWidth: '10rem' }}>
          <Select
            label={t('docs.exportVersion')}
            value={String(versionNo)}
            onChange={(value) => {
              setVersionNo(Number.parseInt(value, 10));
            }}
            options={Array.from({ length: Math.max(currentVersionNo, 1) }, (_unused, index) => {
              const no = currentVersionNo - index;
              return { value: String(no), label: t('documents.version', { no }) };
            })}
          />
        </div>
      </div>

      {LEGACY_RENDER_FORMATS.includes(target) ? (
        <Banner tone="warning" title={t('docs.exportLegacyTitle')}>
          <p style={{ margin: 0 }}>{t('documents.legacyLossy')}</p>
          <p style={{ margin: 'var(--ci-space-2) 0 0' }}>{t('docs.exportLegacyWhy')}</p>
        </Banner>
      ) : null}

      <FidelitySection row={fidelity.data ?? null} pending={fidelity.isPending} error={fidelity.error} />

      <div style={{ marginTop: 'var(--ci-space-4)' }}>
        <Button
          tone="primary"
          busy={run.isPending}
          onClick={() => {
            run.mutate();
          }}
        >
          {t('docs.exportRun')}
        </Button>
        <span style={{ marginLeft: 'var(--ci-space-3)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
          {t('docs.shortcutExport')}
        </span>
      </div>

      {failure === null || failure === undefined ? null : (
        <div style={{ marginTop: 'var(--ci-space-3)' }}>
          <ErrorNote error={failure} title={t('docs.exportFailedTitle')} />
          {/* Said plainly, next to the failure, because the alternative people
              assume is that a half-written file is sitting somewhere. */}
          <p style={{ margin: 'var(--ci-space-2) 0', fontWeight: 600 }}>{t('docs.exportNoFile')}</p>
          {/* Offered whatever the status: asking again is a new export, not a
              blind retry of a request the server already refused. */}
          <Button
            onClick={() => {
              setJobId(null);
              setAnswered(null);
              run.mutate();
            }}
          >
            {t('docs.exportRetry')}
          </Button>
        </div>
      )}

      {view === null ? null : (
        <div style={{ marginTop: 'var(--ci-space-4)' }}>
          <ExportOutcome view={view} ready={ready} />
        </div>
      )}
    </Panel>
  );
}

function FidelitySection({
  row,
  pending,
  error,
}: {
  readonly row: FidelityRowView | null;
  readonly pending: boolean;
  readonly error: unknown;
}): ReactNode {
  const { t, i18n } = useTranslation();
  const english = i18n.language.startsWith('en');
  const describe = (feature: FeatureView): string =>
    (english ? feature.describeEn : feature.describeKo) ?? feature.feature ?? '';

  if (error !== null && error !== undefined) {
    return <ErrorNote error={error} />;
  }
  if (pending) {
    return <p style={{ color: 'var(--ci-fg-muted)' }}>{t('app.loading')}</p>;
  }
  if (row === null) {
    return <Banner tone="warning">{t('docs.unknownShape')}</Banner>;
  }

  const unavailable = row.available === false;
  const unsupported = row.supported === false;

  return (
    <section
      aria-labelledby="fidelity-heading"
      style={{
        border: '1px solid var(--ci-border)',
        borderRadius: 'var(--ci-radius)',
        padding: 'var(--ci-space-3)',
      }}
    >
      <h3 id="fidelity-heading" style={{ margin: 0, fontSize: 'var(--ci-text-sm)' }}>
        {t('docs.exportFidelityHeading')}
      </h3>
      <p style={{ margin: 'var(--ci-space-1) 0 var(--ci-space-2)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
        {t('docs.exportFidelityBefore')} ·{' '}
        {t('docs.exportFidelityPair', {
          from: row.fromDisplayName ?? row.fromFormat ?? '',
          to: row.toDisplayName ?? row.toFormat ?? '',
        })}
      </p>

      {unavailable ? (
        <Banner tone="warning" title={t('docs.exportFidelityUnavailable')}>
          {row.unavailableReason ?? row.note ?? ''}
        </Banner>
      ) : null}

      {unsupported ? (
        <Banner tone="danger" title={t('docs.exportFidelityUnsupported')}>
          {row.unsupportedReason ?? row.note ?? ''}
        </Banner>
      ) : null}

      {row.note === undefined || row.note === null || unavailable ? null : (
        <p style={{ margin: '0 0 var(--ci-space-2)' }}>{row.note}</p>
      )}

      {(row.concerns ?? []).length === 0 ? null : (
        <>
          <h4 style={{ margin: 'var(--ci-space-2) 0 var(--ci-space-1)', fontSize: 'var(--ci-text-sm)' }}>
            {t('docs.exportFidelityConcerns')}
          </h4>
          <ul style={{ margin: 0, paddingLeft: 'var(--ci-space-4)' }}>
            {(row.concerns ?? []).map((concern) => (
              <li key={concern.feature ?? describe(concern)}>
                <SupportBadge support={concern.support} /> {describe(concern)}
              </li>
            ))}
          </ul>
        </>
      )}

      {(row.features ?? []).length === 0 ? null : (
        <details style={{ marginTop: 'var(--ci-space-2)' }}>
          <summary style={{ cursor: 'pointer', fontSize: 'var(--ci-text-sm)' }}>
            {t('docs.exportFidelityFeature')} · {t('docs.exportFidelitySupport')}
          </summary>
          <ul style={{ margin: 'var(--ci-space-2) 0 0', paddingLeft: 'var(--ci-space-4)' }}>
            {(row.features ?? []).map((feature) => (
              <li key={feature.feature ?? describe(feature)}>
                <SupportBadge support={feature.support} /> {describe(feature)}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function SupportBadge({ support }: { readonly support: string | undefined }): ReactNode {
  switch (support) {
    case 'FULL':
      return <Badge tone="positive">{support}</Badge>;
    case 'DEGRADED':
      return <Badge tone="warning">{support}</Badge>;
    case 'DROPPED':
      return <Badge tone="danger">{support}</Badge>;
    default:
      return <Badge>{support ?? '—'}</Badge>;
  }
}

function ExportOutcome({ view, ready }: { readonly view: ExportView; readonly ready: boolean }): ReactNode {
  const { t } = useTranslation();
  const mode = view.mode ?? '';
  const modeKey =
    mode === 'immediate' || mode === 'synchronous' || mode === 'asynchronous'
      ? `docs.exportMode${mode}`
      : null;

  return (
    <>
      <h3 style={{ margin: 0, fontSize: 'var(--ci-text-sm)' }}>{t('docs.exportModeTitle')}</h3>
      <p style={{ margin: 'var(--ci-space-1) 0 var(--ci-space-3)' }}>
        {modeKey === null ? mode : t(modeKey)}
      </p>

      {view.status === 'queued' ? (
        <Banner tone="info" title={t('docs.exportJobTitle')}>
          <KeyValues
            rows={[
              { key: 'job', label: t('docs.exportJobId'), value: <Mono>{view.jobId ?? '—'}</Mono> },
              { key: 'state', label: t('common.status'), value: jobStateLabel(view.jobState, t) },
              {
                key: 'attempt',
                label: t('docs.exportJobAttempt', {
                  attempt: view.attempt ?? 0,
                  max: view.maxAttempts ?? 0,
                }),
                value: view.lastErrorCode ?? '',
              },
            ]}
          />
          <p style={{ margin: 'var(--ci-space-2) 0 0' }} role="status">
            {t('docs.exportJobPolling')}
          </p>
        </Banner>
      ) : null}

      <FontWarnings warnings={view.fontWarnings ?? []} recorded={view.recordedSubstitutions ?? []} />

      {view.legacy === true ? (
        <Banner tone="warning" title={t('docs.exportLegacyTitle')}>
          {view.legacyNoteKo ?? view.legacyNoteEn ?? t('documents.legacyLossy')}
        </Banner>
      ) : null}

      {ready ? (
        <div style={{ marginTop: 'var(--ci-space-3)' }}>
          <KeyValues
            rows={[
              { key: 'ready', label: t('common.status'), value: t('docs.exportReady') },
              { key: 'renderedAt', label: t('docs.exportRenderedAt'), value: view.renderedAt ?? '—' },
              { key: 'renderer', label: t('docs.exportRenderer'), value: view.rendererVersion ?? '—' },
              {
                key: 'hash',
                label: t('docs.exportOutputHash'),
                value: <Mono>{shortHash(view.outputSha256)}</Mono>,
              },
              {
                key: 'archived',
                label: t('docs.exportArchived', { count: view.archivedRenderCount ?? 0 }),
                value: view.archiveNote ?? '',
              },
            ]}
          />
          {isApiPath(view.contentUrl) ? (
            <p style={{ marginTop: 'var(--ci-space-3)' }}>
              <a className="ci-button ci-button--primary" href={view.contentUrl} download>
                {t('docs.exportDownload')}
              </a>
            </p>
          ) : (
            <p style={{ marginTop: 'var(--ci-space-3)' }}>{t('docs.exportNoFile')}</p>
          )}
        </div>
      ) : null}
    </>
  );
}

function jobStateLabel(state: string | undefined, t: (key: string) => string): string {
  switch (state) {
    case 'QUEUED':
    case 'RUNNING':
    case 'SUCCEEDED':
    case 'FAILED':
    case 'ABANDONED':
      return t(`docs.exportJobState${state}`);
    default:
      return state ?? '—';
  }
}

/**
 * A missing font is named, both ends of it.
 *
 * §6.9: a missing font produces a named warning and a recorded substitution,
 * never a silent fallback. "A font was substituted" is not that warning — the
 * family that was asked for and the family that was used both have to be on
 * screen, because the second is the only way anyone can tell whether the result
 * is acceptable.
 */
function FontWarnings({
  warnings,
  recorded,
}: {
  readonly warnings: readonly FontWarningView[];
  readonly recorded: readonly string[];
}): ReactNode {
  const { t } = useTranslation();
  const notable = warnings.filter((warning) => warning.substituted === true || warning.unresolved === true);

  if (notable.length === 0 && recorded.length === 0) {
    return null;
  }

  return (
    <Banner tone="warning" title={t('docs.exportFontWarnings')}>
      <ul style={{ margin: 0, paddingLeft: 'var(--ci-space-4)' }}>
        {notable.map((warning) => (
          <li key={`${warning.requestedFamily ?? ''}->${warning.resolvedFamily ?? ''}`}>
            {warning.unresolved === true
              ? t('fontManager.resolveNoneNamed', { missing: warning.requestedFamily ?? '' })
              : t('fonts.substituted', {
                  missing: warning.requestedFamily ?? '',
                  used: warning.resolvedFamily ?? '',
                })}
            {warning.reason === undefined || warning.reason === null ? null : (
              <span style={{ color: 'var(--ci-fg-muted)' }}> — {warning.reason}</span>
            )}
          </li>
        ))}
      </ul>
      {recorded.length === 0 ? null : (
        <p style={{ margin: 'var(--ci-space-2) 0 0', fontSize: 'var(--ci-text-sm)' }}>
          {t('docs.exportSubstitutionsRecorded')}: {recorded.join(', ')}
        </p>
      )}
    </Banner>
  );
}
