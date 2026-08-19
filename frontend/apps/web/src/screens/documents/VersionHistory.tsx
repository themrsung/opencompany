import { Amount, Badge, Banner, Button, DataTable, type Column } from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads } from './api.js';
import type { DocumentVersionView, FieldChange, FieldValueView, VersionDiffView } from './contract.js';
import { KeyValues, Mono, Panel, Select } from './controls.js';
import { ErrorNote } from './errors.js';
import { WireInstant, shortHash, useAmountLabels, useLocalName } from './format.js';

/**
 * Version history, and the compare that goes with it.
 *
 * <h2>A diff that is a diff, and a sentence where it is not</h2>
 *
 * §6.10 is the reason mdv exists: it is the only format here whose stored form
 * is text, so it is the only one that can produce a line-by-line comparison
 * that means anything. The API says so per response — `body.available` is false
 * for every other format, with the server's own reason attached.
 *
 * Where there is no body diff this screen says what "compare" *can* tell you
 * for that format — whether the stored bytes are identical, and which typed
 * field values moved — rather than rendering an empty two-column panel that
 * reads as "nothing changed". That misreading is precisely what the flag exists
 * to prevent, and reproducing it in the UI would undo the work.
 */
export function VersionHistory({
  documentId,
  currentVersionNo,
}: {
  readonly documentId: string;
  readonly currentVersionNo: number;
}): ReactNode {
  const { t } = useTranslation();

  const versions = useQuery({
    queryKey: docKeys.versions(documentId),
    queryFn: () => reads.versions(documentId),
  });

  const rows = [...(versions.data ?? [])].sort((left, right) => (right.versionNo ?? 0) - (left.versionNo ?? 0));

  const [toVersion, setToVersion] = useState<number>(currentVersionNo);
  const [against, setAgainst] = useState<number | null>(null);
  const [comparing, setComparing] = useState(false);

  const diff = useQuery({
    queryKey: docKeys.diff(documentId, toVersion, against),
    queryFn: () => reads.diff(documentId, toVersion, against),
    enabled: comparing,
  });

  const columns: readonly Column<DocumentVersionView>[] = [
    { key: 'no', header: t('docs.versionsColumnNo'), numeric: true, render: (row) => row.versionNo ?? '—' },
    {
      key: 'authored',
      header: t('docs.versionsColumnAuthored'),
      render: (row) => <WireInstant wire={row.authoredAt} />,
    },
    { key: 'format', header: t('docs.versionsColumnFormat'), render: (row) => row.format ?? '—' },
    {
      key: 'supersedes',
      header: t('docs.versionsColumnSupersedes'),
      numeric: true,
      render: (row) => row.supersedesVersionNo ?? '—',
    },
    {
      key: 'hash',
      header: t('docs.versionsColumnHash'),
      render: (row) => <Mono>{shortHash(row.blobSha256)}</Mono>,
    },
    {
      key: 'author',
      header: t('docs.versionsColumnAuthor'),
      render: (row) => <Mono>{row.authorAccountId ?? '—'}</Mono>,
    },
  ];

  const versionOptions = rows.map((row) => ({
    value: String(row.versionNo ?? ''),
    label: t('documents.version', { no: row.versionNo ?? '' }),
  }));

  return (
    <>
      <Panel title={t('docs.versionsCaption')} description={t('docs.versionsAppendOnly')}>
        <ErrorNote
          error={versions.error}
          onRetry={() => {
            void versions.refetch();
          }}
        />
        <DataTable
          caption={t('docs.versionsCaption')}
          columns={columns}
          rows={rows}
          rowKey={(row) => String(row.versionNo ?? '')}
          emptyMessage={versions.isPending ? t('app.loading') : t('common.empty')}
          onRowActivate={(row) => {
            setToVersion(row.versionNo ?? currentVersionNo);
            setAgainst(null);
            setComparing(true);
          }}
        />
      </Panel>

      <Panel title={t('docs.compareTitle')}>
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
              label={t('docs.compareTo')}
              value={String(toVersion)}
              onChange={(value) => {
                setToVersion(Number.parseInt(value, 10));
              }}
              options={versionOptions}
            />
          </div>
          <div style={{ minWidth: '12rem' }}>
            <Select
              label={t('docs.compareFrom')}
              value={against === null ? '' : String(against)}
              onChange={(value) => {
                setAgainst(value === '' ? null : Number.parseInt(value, 10));
              }}
              options={[{ value: '', label: t('docs.versionsColumnSupersedes') }, ...versionOptions]}
            />
          </div>
          <Button
            onClick={() => {
              setComparing(true);
              void diff.refetch();
            }}
          >
            {t('docs.compareRun')}
          </Button>
        </div>

        <ErrorNote error={diff.error} />
        {comparing && diff.data !== undefined ? <DiffView diff={diff.data} /> : null}
      </Panel>
    </>
  );
}

function DiffView({ diff }: { readonly diff: VersionDiffView }): ReactNode {
  const { t } = useTranslation();
  const body = diff.body;
  const available = body?.available === true;
  const lines = body?.lines ?? [];

  return (
    <>
      <KeyValues
        rows={[
          {
            key: 'pair',
            label: t('docs.compareTitle'),
            value: `${t('documents.version', { no: diff.fromVersion ?? '?' })} → ${t('documents.version', {
              no: diff.toVersion ?? '?',
            })}`,
          },
          {
            key: 'bytes',
            label: t('docs.versionsColumnHash'),
            value:
              diff.identicalBytes === true ? t('docs.diffIdenticalBytes') : t('docs.diffDifferentBytes'),
          },
        ]}
      />

      <h3 style={{ fontSize: 'var(--ci-text-sm)', marginTop: 'var(--ci-space-4)' }}>
        {t('docs.diffBodyTitle')}
      </h3>

      {available ? (
        <>
          <p style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)', margin: 0 }}>
            {t('docs.diffBodyAvailable', {
              added: body?.addedLines ?? 0,
              removed: body?.removedLines ?? 0,
            })}
          </p>
          {body?.truncated === true ? <Banner tone="warning">{t('docs.diffTruncated')}</Banner> : null}
          {body?.summarised === true ? <Banner tone="info">{t('docs.diffSummarised')}</Banner> : null}
          <pre
            style={{
              fontFamily: 'var(--ci-font-mono)',
              fontSize: 'var(--ci-text-xs)',
              background: 'var(--ci-bg-subtle)',
              border: '1px solid var(--ci-border)',
              borderRadius: 'var(--ci-radius)',
              padding: 'var(--ci-space-3)',
              overflowX: 'auto',
              margin: 'var(--ci-space-2) 0 0',
            }}
          >
            {lines.map((line) => {
              const op = line.op ?? ' ';
              const marker = op === 'ADDED' ? '+' : op === 'REMOVED' ? '-' : ' ';
              return (
                <div
                  // The text is not an identity — the same line can appear
                  // twice — but its position in the two files is: every line
                  // carries a before number, an after number, or both.
                  key={`${op}:${line.beforeLine ?? ''}:${line.afterLine ?? ''}`}
                  style={{
                    color:
                      marker === '+'
                        ? 'var(--ci-positive)'
                        : marker === '-'
                          ? 'var(--ci-danger)'
                          : 'var(--ci-fg)',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {marker} {line.text ?? ''}
                </div>
              );
            })}
          </pre>
        </>
      ) : (
        <Banner tone="info" title={t('docs.diffBodyUnavailableTitle')}>
          <p style={{ margin: 0 }}>
            {t('docs.diffBodyMeaning', { format: (diff.toFormat ?? diff.fromFormat ?? '').toUpperCase() })}
          </p>
          {body?.reason === undefined || body.reason === null ? null : (
            <p style={{ margin: 'var(--ci-space-2) 0 0', color: 'var(--ci-fg-muted)' }}>{body.reason}</p>
          )}
        </Banner>
      )}

      <h3 style={{ fontSize: 'var(--ci-text-sm)', marginTop: 'var(--ci-space-4)' }}>
        {t('docs.diffFieldsTitle')}
      </h3>
      <FieldChanges diff={diff} />
    </>
  );
}

function FieldChanges({ diff }: { readonly diff: VersionDiffView }): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();
  const changes = diff.fields ?? [];

  if (changes.length === 0) {
    return <p style={{ color: 'var(--ci-fg-muted)', margin: 0 }}>{t('docs.diffFieldsNone')}</p>;
  }

  const columns: readonly Column<FieldChange>[] = [
    {
      key: 'field',
      header: t('docs.fieldColumnLabel'),
      render: (row) =>
        localName(row.after?.labelKo ?? row.before?.labelKo, row.after?.labelEn ?? row.before?.labelEn) ||
        (row.fieldId ?? ''),
    },
    {
      key: 'change',
      header: t('common.status'),
      render: (row) => <Badge tone={toneOf(row.change)}>{changeLabel(row.change, t)}</Badge>,
    },
    { key: 'before', header: t('docs.diffBefore'), render: (row) => <ValueCell value={row.before} /> },
    { key: 'after', header: t('docs.diffAfter'), render: (row) => <ValueCell value={row.after} /> },
  ];

  return (
    <DataTable
      caption={t('docs.diffFieldsTitle')}
      columns={columns}
      rows={[...changes]}
      rowKey={(row) => row.fieldId ?? ''}
      emptyMessage={t('docs.diffFieldsNone')}
    />
  );
}

function toneOf(change: string | undefined): 'positive' | 'danger' | 'warning' | 'default' {
  switch (change) {
    case 'ADDED':
      return 'positive';
    case 'REMOVED':
      return 'danger';
    case 'CHANGED':
      return 'warning';
    default:
      return 'default';
  }
}

function changeLabel(change: string | undefined, t: (key: string) => string): string {
  switch (change) {
    case 'ADDED':
      return t('docs.diffChangeADDED');
    case 'REMOVED':
      return t('docs.diffChangeREMOVED');
    case 'CHANGED':
      return t('docs.diffChangeCHANGED');
    default:
      return change ?? '—';
  }
}

/** One field value, rendered by its own type — money through `<Amount>`, always. */
function ValueCell({ value }: { readonly value: FieldValueView | undefined }): ReactNode {
  const amountLabels = useAmountLabels();
  if (value === undefined) {
    return <span aria-hidden="true">—</span>;
  }
  if (value.amount !== undefined && value.amount !== null) {
    return (
      <Amount
        value={value.amount}
        displayDecimals={value.currencyCode === 'KRW' ? 0 : 2}
        {...(value.currencyCode === undefined ? {} : { currencyCode: value.currencyCode })}
        labels={amountLabels}
      />
    );
  }
  if (value.instant !== undefined && value.instant !== null) {
    return <WireInstant wire={value.instant} />;
  }
  return <span>{value.number ?? value.date ?? value.refId ?? value.text ?? '—'}</span>;
}
