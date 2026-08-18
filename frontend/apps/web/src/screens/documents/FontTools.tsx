import { Badge, Banner, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads, writes } from './api.js';
import type { FontWarningView, SubstitutionScope } from './contract.js';
import { Mono, Panel, Select } from './controls.js';
import { ErrorNote } from './errors.js';

/**
 * The substitution map, and the question it exists to answer.
 *
 * Two panels that belong together: what the client has decided should stand in
 * for what, and what a given family would *actually* resolve to today. The
 * second reports every family asked about, not only the failures — a client who
 * only ever sees warnings cannot tell "checked and fine" from "not checked",
 * which is the distinction §6.9 asks for in as many words.
 */
export function FontTools({ companyId }: { readonly companyId: string }): ReactNode {
  return (
    <>
      <SubstitutionMap companyId={companyId} />
      <ResolveCheck companyId={companyId} />
    </>
  );
}

function SubstitutionMap({ companyId }: { readonly companyId: string }): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [scope, setScope] = useState<SubstitutionScope>('FAMILY');
  const [scopeKey, setScopeKey] = useState('');
  const [fallbackFamily, setFallbackFamily] = useState('');
  const [position, setPosition] = useState('1');

  const map = useQuery({
    queryKey: docKeys.substitutions(companyId),
    queryFn: () => reads.substitutions(companyId),
  });

  const add = useMutation({
    mutationFn: () =>
      writes.addSubstitution({
        companyId,
        scope,
        scopeKey,
        fallbackFamily,
        ...(position.trim() === '' ? {} : { position: Number.parseInt(position, 10) }),
      }),
    onSuccess: () => {
      setFallbackFamily('');
      void queryClient.invalidateQueries({ queryKey: docKeys.substitutions(companyId) });
    },
  });

  const familyChains = Object.entries(map.data?.familyChains ?? {});
  const scriptChains = Object.entries(map.data?.scriptChains ?? {});

  return (
    <Panel title={t('fontManager.substitutionsTitle')} description={t('fontManager.substitutionsHint')}>
      <ErrorNote
        error={map.error}
        onRetry={() => {
          void map.refetch();
        }}
      />

      {familyChains.length === 0 && scriptChains.length === 0 ? (
        <Banner tone="info">{t('fontManager.substitutionsEmpty')}</Banner>
      ) : (
        <>
          <ChainTable title={t('fontManager.substitutionsFamily')} chains={familyChains} />
          <ChainTable title={t('fontManager.substitutionsScript')} chains={scriptChains} />
        </>
      )}

      <h3 style={{ fontSize: 'var(--ci-text-sm)', marginTop: 'var(--ci-space-4)' }}>
        {t('fontManager.substitutionAddTitle')}
      </h3>
      <ErrorNote error={add.error} />
      {add.isSuccess ? (
        <Banner tone="positive">
          {t('fontManager.substitutionAdded', { key: scopeKey, fallback: fallbackFamily })}
        </Banner>
      ) : null}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr 1fr 6rem auto',
          gap: 'var(--ci-space-2)',
          alignItems: 'end',
          maxWidth: '52rem',
        }}
      >
        <Select
          label={t('fontManager.substitutionScope')}
          value={scope}
          onChange={(value) => {
            setScope(value === 'SCRIPT' ? 'SCRIPT' : 'FAMILY');
          }}
          options={[
            { value: 'FAMILY', label: t('fontManager.substitutionScopeFAMILY') },
            { value: 'SCRIPT', label: t('fontManager.substitutionScopeSCRIPT') },
          ]}
        />
        <TextField
          label={t('fontManager.substitutionScopeKey')}
          value={scopeKey}
          onChange={(event) => {
            setScopeKey(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('fontManager.substitutionFallback')}
          value={fallbackFamily}
          onChange={(event) => {
            setFallbackFamily(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('fontManager.substitutionPosition')}
          value={position}
          inputMode="numeric"
          onChange={(event) => {
            setPosition(event.currentTarget.value);
          }}
        />
        <Button
          busy={add.isPending}
          disabled={scopeKey.trim() === '' || fallbackFamily.trim() === ''}
          onClick={() => {
            add.mutate();
          }}
        >
          {t('fontManager.substitutionAdd')}
        </Button>
      </div>
    </Panel>
  );
}

function ChainTable({
  title,
  chains,
}: {
  readonly title: string;
  readonly chains: readonly (readonly [string, readonly string[]])[];
}): ReactNode {
  const { t } = useTranslation();
  if (chains.length === 0) {
    return null;
  }
  const columns: readonly Column<readonly [string, readonly string[]]>[] = [
    {
      key: 'key',
      header: t('fontManager.substitutionScopeKey'),
      render: (row) => <Mono>{row[0]}</Mono>,
    },
    {
      key: 'chain',
      header: t('fontManager.substitutionFallback'),
      render: (row) => row[1].join(' → '),
    },
  ];

  return (
    <>
      <h3 style={{ fontSize: 'var(--ci-text-sm)' }}>{title}</h3>
      <DataTable
        caption={title}
        columns={columns}
        rows={chains}
        rowKey={(row) => row[0]}
        emptyMessage={t('common.empty')}
      />
    </>
  );
}

function ResolveCheck({ companyId }: { readonly companyId: string }): ReactNode {
  const { t } = useTranslation();
  const [input, setInput] = useState('');
  const [script, setScript] = useState('');
  const [asked, setAsked] = useState<readonly string[]>([]);

  const families = input
    .split(',')
    .map((family) => family.trim())
    .filter((family) => family !== '');

  const resolution = useQuery({
    queryKey: docKeys.resolve(companyId, asked.join(','), script),
    queryFn: () => reads.resolve(companyId, asked, script),
    enabled: asked.length > 0,
  });

  const resolveColumns: readonly Column<FontWarningView>[] = [
    {
      key: 'requested',
      header: t('fontManager.resolveColumnRequested'),
      render: (row) => row.requestedFamily ?? '—',
    },
    {
      key: 'resolved',
      header: t('fontManager.resolveColumnResolved'),
      render: (row) => row.resolvedFamily ?? '—',
    },
    {
      key: 'outcome',
      header: t('fontManager.resolveColumnOutcome'),
      render: (row) => <Outcome warning={row} />,
    },
  ];

  return (
    <Panel title={t('fontManager.resolveTitle')} description={t('fontManager.resolveHint')}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '3fr 1fr auto',
          gap: 'var(--ci-space-2)',
          alignItems: 'end',
          maxWidth: '46rem',
          marginBottom: 'var(--ci-space-3)',
        }}
      >
        <TextField
          label={t('fontManager.resolveFamilies')}
          value={input}
          placeholder="함초롬바탕, Pretendard"
          onChange={(event) => {
            setInput(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('fontManager.resolveScript')}
          value={script}
          placeholder="Hang"
          onChange={(event) => {
            setScript(event.currentTarget.value);
          }}
        />
        <Button
          disabled={families.length === 0}
          busy={resolution.isFetching}
          onClick={() => {
            setAsked(families);
          }}
        >
          {t('fontManager.resolveRun')}
        </Button>
      </div>

      {families.length === 0 && asked.length === 0 ? (
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('fontManager.resolveEmpty')}</p>
      ) : null}

      <ErrorNote error={resolution.error} />

      {asked.length === 0 ? null : (
        <DataTable
          caption={t('fontManager.resolveTitle')}
          columns={resolveColumns}
          rows={resolution.data ?? []}
          rowKey={(row) => row.requestedFamily ?? ''}
          emptyMessage={resolution.isPending ? t('app.loading') : t('common.empty')}
        />
      )}
    </Panel>
  );
}

/**
 * The outcome, with both families named in the sentence.
 *
 * "Substituted" on its own is not a warning anyone can act on. Which family was
 * missing and which one was used in its place is the whole content of it.
 */
function Outcome({ warning }: { readonly warning: FontWarningView }): ReactNode {
  const { t } = useTranslation();

  if (warning.unresolved === true) {
    return (
      <span>
        <Badge tone="danger">{t('fontManager.resolveUnresolved')}</Badge>{' '}
        {t('fontManager.resolveNoneNamed', { missing: warning.requestedFamily ?? '' })}
      </span>
    );
  }
  if (warning.substituted === true) {
    return (
      <span>
        <Badge tone="warning">{t('fontManager.resolveSubstituted')}</Badge>{' '}
        {t('fonts.substituted', {
          missing: warning.requestedFamily ?? '',
          used: warning.resolvedFamily ?? '',
        })}
      </span>
    );
  }
  return (
    <span>
      <Badge tone="positive">{t('fontManager.resolveOk')}</Badge>
    </span>
  );
}
