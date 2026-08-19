import { ApiError } from '@coreintra/api-client';
import { Badge, Banner, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, postMultipart, reads, writes } from './api.js';
import { EMBEDDING_PERMISSIONS, type InstalledFontView } from './contract.js';
import { Checkbox, ConfirmPanel, FileField, KeyValues, Mono, Panel, Select } from './controls.js';
import { ErrorNote } from './errors.js';
import { FontTools } from './FontTools.js';
import { shortHash } from './format.js';

/**
 * The font manager (§6.9).
 *
 * Client fonts are accepted at the client's own risk, and this screen is where
 * that sentence has to be true rather than decorative:
 *
 *  - installing requires an explicit acknowledgement, in the server's words,
 *    echoed back verbatim, never pre-ticked, and with the button dead until it
 *    is ticked;
 *  - the font's own embedding metadata is shown read-only beside it, so the
 *    client can see what they are agreeing about;
 *  - removing a font names how many archived renders used it **before** it is
 *    removed, and the removal states that number back to the server, which
 *    refuses if it moved while the person was reading.
 */
export function FontsScreen({ companyId }: { readonly companyId: string }): ReactNode {
  const { t } = useTranslation();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const fonts = useQuery({
    queryKey: docKeys.fonts(companyId),
    queryFn: () => reads.fonts(companyId),
  });

  const rows = fonts.data ?? [];
  const selected = rows.find((font) => font.id === selectedId) ?? null;

  const columns: readonly Column<InstalledFontView>[] = [
    { key: 'family', header: t('fontManager.columnFamily'), render: (row) => row.family ?? '—' },
    { key: 'style', header: t('fontManager.columnStyle'), render: (row) => row.style ?? '—' },
    {
      key: 'coverage',
      header: t('fontManager.columnCoverage'),
      render: (row) => (row.scriptCoverage ?? []).join(', ') || '—',
    },
    { key: 'source', header: t('fontManager.columnSource'), render: (row) => sourceLabel(row.source, t) },
    {
      key: 'uploader',
      header: t('fontManager.columnUploader'),
      render: (row) => <Mono>{row.uploadedByAccountId ?? '—'}</Mono>,
    },
    {
      key: 'embedding',
      header: t('fontManager.columnEmbedding'),
      render: (row) => embeddingLabel(row.embeddingPermission, t),
    },
    {
      key: 'status',
      header: t('fontManager.columnStatus'),
      render: (row) =>
        row.enabled === false ? (
          <Badge tone="warning">{t('fontManager.statusDisabled')}</Badge>
        ) : (
          <Badge tone="positive">{t('fontManager.statusEnabled')}</Badge>
        ),
    },
  ];

  return (
    <>
      <InstallFont companyId={companyId} />

      <Panel title={t('fontManager.installedTitle')}>
        <ErrorNote
          error={fonts.error}
          onRetry={() => {
            void fonts.refetch();
          }}
        />
        <DataTable
          caption={t('fontManager.installedCaption')}
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id ?? ''}
          emptyMessage={fonts.isPending ? t('app.loading') : t('fontManager.installedEmpty')}
          onRowActivate={(row) => {
            setSelectedId(row.id ?? null);
          }}
        />
        <p style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
          {t('fontManager.embeddingNote')}
        </p>
      </Panel>

      {selected === null ? null : (
        <FontDetail
          companyId={companyId}
          font={selected}
          onRemoved={() => {
            setSelectedId(null);
          }}
        />
      )}

      <FontTools companyId={companyId} />
    </>
  );
}

function sourceLabel(source: string | undefined, t: (key: string) => string): string {
  switch (source) {
    case 'BUNDLED':
    case 'CLIENT_UPLOADED':
    case 'HOST_PROVIDED':
      return t(`fontManager.source${source}`);
    default:
      return source ?? '—';
  }
}

function embeddingLabel(permission: string | undefined, t: (key: string) => string): string {
  if (permission !== undefined && (EMBEDDING_PERMISSIONS as readonly string[]).includes(permission)) {
    return t(`fontManager.embedding${permission}`);
  }
  return permission ?? '—';
}

/**
 * Installing a font.
 *
 * The acknowledgement is fetched, shown in full, and echoed back exactly — the
 * server refuses an install whose text does not match, which is what makes the
 * recorded agreement provably the words that were on the screen. If the wording
 * cannot be read, the form does not open: an agreement cannot be recorded
 * against words nobody was shown.
 */
function InstallFont({ companyId }: { readonly companyId: string }): ReactNode {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();

  const wording = useQuery({ queryKey: docKeys.fontWording, queryFn: reads.fontWording });

  const [file, setFile] = useState<File | null>(null);
  const [family, setFamily] = useState('');
  const [style, setStyle] = useState('Regular');
  const [fileFormat, setFileFormat] = useState('TTF');
  const [scripts, setScripts] = useState('');
  const [embedding, setEmbedding] = useState('UNKNOWN');
  const [fsTypeRaw, setFsTypeRaw] = useState('');
  // Never pre-ticked. A default of `true` here is the same thing as not asking.
  const [acknowledged, setAcknowledged] = useState(false);

  const echoed = (i18n.language.startsWith('en') ? wording.data?.textEn : wording.data?.textKo) ?? '';

  const install = useMutation({
    mutationFn: async () => {
      if (file === null || wording.data === null || wording.data === undefined) {
        throw new Error('not ready');
      }
      const form = new FormData();
      form.append('file', file, file.name);
      return postMultipart<InstalledFontView>('/fonts', form, {
        query: {
          companyId,
          family,
          style,
          fileFormat,
          ...(scripts.trim() === ''
            ? {}
            : {
                scriptCoverage: scripts
                  .split(',')
                  .map((code) => code.trim())
                  .filter((code) => code !== '')
                  .join(','),
              }),
          licenceAcknowledged: true,
          licenceAcknowledgementVersion: wording.data.version ?? '',
          licenceAcknowledgementText: echoed,
          embeddingPermission: embedding,
          ...(fsTypeRaw.trim() === '' ? {} : { fsTypeRaw: fsTypeRaw.trim() }),
        },
      });
    },
    onSuccess: () => {
      setFile(null);
      setAcknowledged(false);
      void queryClient.invalidateQueries({ queryKey: docKeys.fonts(companyId) });
    },
    onError: (error: unknown) => {
      // The wording moved under us. Re-read it, untick, and make the person
      // read the new statement — an agreement to superseded words is not one.
      if (error instanceof ApiError && error.code === 'licence_wording_stale') {
        setAcknowledged(false);
        void queryClient.invalidateQueries({ queryKey: docKeys.fontWording });
      }
    },
  });

  const stale = install.error instanceof ApiError && install.error.code === 'licence_wording_stale';
  const wordingMissing = wording.isSuccess && wording.data === null;

  return (
    <Panel title={t('fontManager.installTitle')} id="install-font">
      <ErrorNote error={wording.error} />
      {wordingMissing ? <Banner tone="danger">{t('fontManager.licenceUnavailable')}</Banner> : null}
      {stale ? <Banner tone="warning">{t('fontManager.licenceStale')}</Banner> : null}
      {install.isSuccess ? (
        <Banner tone="positive">{t('fontManager.installed', { family: install.data.family ?? family })}</Banner>
      ) : null}
      {stale ? null : <ErrorNote error={install.error} />}

      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '38rem' }}>
        <FileField
          label={t('fontManager.installFile')}
          accept=".ttf,.otf,.ttc,.otc,.woff,.woff2"
          onChange={setFile}
          {...(file === null ? { hint: t('fontManager.installNeedsFile') } : {})}
        />
        <TextField
          label={t('fontManager.installFamily')}
          value={family}
          required
          {...(family.trim() === '' ? { hint: t('fontManager.installNeedsFamily') } : {})}
          onChange={(event) => {
            setFamily(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('fontManager.installStyle')}
          value={style}
          onChange={(event) => {
            setStyle(event.currentTarget.value);
          }}
        />
        <Select
          label={t('fontManager.installFormat')}
          value={fileFormat}
          onChange={setFileFormat}
          options={['TTF', 'OTF', 'TTC', 'OTC', 'WOFF', 'WOFF2'].map((format) => ({
            value: format,
            label: format,
          }))}
        />
        <TextField
          label={t('fontManager.installScripts')}
          value={scripts}
          placeholder="Hang, Latn, Hani"
          onChange={(event) => {
            setScripts(event.currentTarget.value);
          }}
        />
        <Select
          label={t('fontManager.installEmbedding')}
          value={embedding}
          onChange={setEmbedding}
          hint={t('fontManager.embeddingNote')}
          options={EMBEDDING_PERMISSIONS.map((permission) => ({
            value: permission,
            label: embeddingLabel(permission, t),
          }))}
        />
        <TextField
          label={t('fontManager.installFsType')}
          hint={t('fontManager.installFsTypeHint')}
          value={fsTypeRaw}
          inputMode="numeric"
          onChange={(event) => {
            setFsTypeRaw(event.currentTarget.value);
          }}
        />
      </div>

      <section
        aria-labelledby="licence-heading"
        style={{
          marginTop: 'var(--ci-space-4)',
          border: '1px solid var(--ci-border-strong)',
          borderRadius: 'var(--ci-radius)',
          padding: 'var(--ci-space-3)',
        }}
      >
        <h3 id="licence-heading" style={{ margin: 0, fontSize: 'var(--ci-text-sm)' }}>
          {t('fontManager.licenceHeading')}
        </h3>
        <p style={{ margin: 'var(--ci-space-1) 0', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
          {t('fontManager.licenceIntro')}
        </p>

        {/* When the wording is missing the banner above says so, once: the same
            sentence twice on one screen teaches people to skim both. */}
        {wording.isPending ? (
          <p>{t('app.loading')}</p>
        ) : wording.data === null || wording.data === undefined ? null : (
          <>
            {/* Both languages, in full, at body size. The record is of the
                statement, not of whichever translation was on screen. */}
            <p id="licence-text-ko" style={{ margin: '0 0 var(--ci-space-2)', lineHeight: 1.6 }}>
              {wording.data.textKo}
            </p>
            <p id="licence-text-en" style={{ margin: '0 0 var(--ci-space-3)', lineHeight: 1.6 }}>
              {wording.data.textEn}
            </p>
            <p style={{ margin: '0 0 var(--ci-space-2)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
              {t('fontManager.licenceVersion', { version: wording.data.version ?? '' })} ·{' '}
              {t('fontManager.licenceRecorded')}
            </p>
            <Checkbox
              id="licence-acknowledged"
              checked={acknowledged}
              onChange={setAcknowledged}
              describedBy="licence-text-ko licence-text-en"
              label={t('fontManager.licenceAgree')}
            />
          </>
        )}
      </section>

      <div style={{ marginTop: 'var(--ci-space-3)' }}>
        <Button
          tone="primary"
          busy={install.isPending}
          disabled={!acknowledged || file === null || family.trim() === '' || wording.data == null}
          aria-describedby={acknowledged ? undefined : 'licence-blocked'}
          onClick={() => {
            install.mutate();
          }}
        >
          {t('fontManager.installRun')}
        </Button>
        {acknowledged ? null : (
          <p id="licence-blocked" style={{ margin: 'var(--ci-space-2) 0 0', color: 'var(--ci-fg-muted)' }}>
            {t('fontManager.licenceBlocked')}
          </p>
        )}
      </div>
    </Panel>
  );
}

/**
 * One font, its three consumer views, and what can be done to it.
 *
 * Removal asks `removal-impact` first and puts the count at the top of the
 * confirmation, before the consequence and before the button — the number is
 * the decision, so it goes where the eye lands, not in a clause at the end.
 */
function FontDetail({
  companyId,
  font,
  onRemoved,
}: {
  readonly companyId: string;
  readonly font: InstalledFontView;
  readonly onRemoved: () => void;
}): ReactNode {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const fontId = font.id ?? '';
  const [removing, setRemoving] = useState(false);

  const impact = useQuery({
    queryKey: docKeys.fontImpact(fontId),
    queryFn: () => reads.removalImpact(fontId),
    enabled: removing,
  });

  const toggle = useMutation({
    mutationFn: () => (font.enabled === false ? writes.enableFont(fontId) : writes.disableFont(fontId)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: docKeys.fonts(companyId) });
    },
  });

  const remove = useMutation({
    mutationFn: (acknowledgedAffectedCount: number) => writes.removeFont(fontId, acknowledgedAffectedCount),
    onSuccess: () => {
      setRemoving(false);
      void queryClient.invalidateQueries({ queryKey: docKeys.fonts(companyId) });
      onRemoved();
    },
    onError: (error: unknown) => {
      // The count moved. Re-read it rather than letting a second press send the
      // same stale number.
      if (error instanceof ApiError && error.status === 409) {
        void impact.refetch();
      }
    },
  });

  const affected = impact.data?.affectedRenderCount ?? null;
  const countChanged = remove.error instanceof ApiError && remove.error.status === 409;

  return (
    <Panel
      title={`${font.family ?? ''} ${font.style ?? ''}`.trim()}
      actions={
        <div style={{ display: 'flex', gap: 'var(--ci-space-2)' }}>
          <Button
            busy={toggle.isPending}
            onClick={() => {
              toggle.mutate();
            }}
          >
            {font.enabled === false ? t('fontManager.enableRun') : t('fontManager.disableRun')}
          </Button>
          <Button
            tone="danger"
            onClick={() => {
              setRemoving(true);
            }}
          >
            {t('fontManager.removeRun')}
          </Button>
        </div>
      }
    >
      <p style={{ marginTop: 0, color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
        {t('fontManager.disableHint')}
      </p>
      <ErrorNote error={toggle.error} />

      <KeyValues
        rows={[
          { key: 'format', label: t('fontManager.columnFormat'), value: font.fileFormat ?? '—' },
          {
            key: 'coverage',
            label: t('fontManager.columnCoverage'),
            value: (font.scriptCoverage ?? []).join(', ') || '—',
          },
          { key: 'source', label: t('fontManager.columnSource'), value: sourceLabel(font.source, t) },
          {
            key: 'embedding',
            label: t('fontManager.columnEmbedding'),
            value: `${embeddingLabel(font.embeddingPermission, t)} — ${t('fontManager.embeddingNote')}`,
          },
          {
            key: 'uploader',
            label: t('fontManager.columnUploader'),
            value: <Mono>{font.uploadedByAccountId ?? '—'}</Mono>,
          },
          { key: 'uploadedAt', label: t('fontManager.columnUploadedAt'), value: font.uploadedAt ?? '—' },
          { key: 'hash', label: t('fontManager.blobHash'), value: <Mono>{shortHash(font.blobSha256)}</Mono> },
          {
            key: 'licence',
            label: t('fontManager.licenceHeading'),
            value: font.licenceAcknowledgementText ?? '—',
          },
        ]}
      />

      <h3 style={{ fontSize: 'var(--ci-text-sm)', marginTop: 'var(--ci-space-4)' }}>
        {t('fontManager.consumerViews')}
      </h3>
      <p style={{ margin: '0 0 var(--ci-space-2)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
        {t('fontManager.consumerReadOnly')}
      </p>
      <KeyValues
        rows={[
          {
            key: 'fontconfig',
            label: t('fontManager.consumerFontconfig'),
            value: <Mono>{font.fontconfigFileName ?? '—'}</Mono>,
          },
          {
            key: 'webfont',
            label: t('fontManager.consumerWebfont'),
            value: <Mono>{font.webfontFaceCss ?? '—'}</Mono>,
          },
          { key: 'mdv', label: t('fontManager.consumerMdv'), value: <Mono>{font.mdvFontConfigEntry ?? '—'}</Mono> },
        ]}
      />

      {removing ? (
        <ConfirmPanel
          title={t('fontManager.removeTitle', { family: font.family ?? '' })}
          onCancel={() => {
            setRemoving(false);
          }}
        >
          <ErrorNote error={impact.error} />

          {impact.isPending ? (
            <p style={{ marginTop: 0 }}>{t('fontManager.removeCheckingImpact')}</p>
          ) : (
            <>
              {/* The count first: it is the decision. */}
              <p style={{ marginTop: 0, fontWeight: 600 }}>
                {affected === null || affected === 0
                  ? t('fontManager.removeAffectedNone')
                  : t('fontManager.removeAffected', { count: affected })}
              </p>
              <p style={{ marginTop: 0 }}>
                {(i18n.language.startsWith('en') ? impact.data?.warningEn : impact.data?.warningKo) ??
                  t('fontManager.removeConsequence')}
              </p>

              {countChanged ? (
                <Banner tone="warning">
                  {t('fontManager.removeCountChanged', { count: affected ?? 0 })}
                </Banner>
              ) : (
                <ErrorNote error={remove.error} />
              )}

              <div style={{ display: 'flex', gap: 'var(--ci-space-2)', marginTop: 'var(--ci-space-2)' }}>
                <Button
                  tone="danger"
                  busy={remove.isPending}
                  disabled={affected === null}
                  onClick={() => {
                    remove.mutate(affected ?? 0);
                  }}
                >
                  {t('fontManager.removeConfirm', { count: affected ?? 0 })}
                </Button>
                <Button
                  onClick={() => {
                    setRemoving(false);
                  }}
                >
                  {t('fontManager.removeCancel')}
                </Button>
              </div>
            </>
          )}
        </ConfirmPanel>
      ) : null}
    </Panel>
  );
}
