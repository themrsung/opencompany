import { Badge, Banner, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, postMultipart, reads, writes } from './api.js';
import {
  DOCUMENT_FORMATS,
  FIELD_TYPES,
  type DocumentView,
  type TemplateField,
  type TemplateVersion,
} from './contract.js';
import { Checkbox, ConfirmPanel, FileField, KeyValues, Mono, Panel, Select } from './controls.js';
import { ErrorNote, violationsByField } from './errors.js';
import { WireInstant, useLocalName } from './format.js';

/**
 * One template: its versions, its field manifest, and the four things §6.7 and
 * §6.8 ask for — publish, fork, restore to factory state, retire.
 */
export function TemplateScreen({
  templateId,
  onOpenDocument,
}: {
  readonly templateId: string;
  readonly onOpenDocument: (documentId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<number | null>(null);
  const [retiring, setRetiring] = useState(false);

  const header = useQuery({
    queryKey: docKeys.template(templateId),
    queryFn: () => reads.template(templateId),
  });

  const versions = useQuery({
    queryKey: docKeys.templateVersions(templateId),
    queryFn: () => reads.templateVersions(templateId),
  });

  const rows = [...(versions.data ?? [])].sort((left, right) => (right.versionNo ?? 0) - (left.versionNo ?? 0));
  const latest = rows[0]?.versionNo ?? null;
  const versionNo = selected ?? latest;

  const manifest = useQuery({
    queryKey: docKeys.templateFields(templateId, versionNo ?? 0),
    queryFn: () => reads.templateFields(templateId, versionNo ?? 0),
    enabled: versionNo !== null,
  });

  const drafted = useQuery({
    queryKey: docKeys.templateDocuments(templateId, versionNo ?? 0),
    queryFn: () => reads.templateDocuments(templateId, versionNo ?? 0),
    enabled: versionNo !== null,
  });

  const restore = useMutation({
    mutationFn: () => writes.restoreTemplate(templateId, header.data?.etag ?? null),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: docKeys.templateVersions(templateId) });
      void queryClient.invalidateQueries({ queryKey: docKeys.template(templateId) });
    },
  });

  const retire = useMutation({
    mutationFn: () => writes.retireTemplate(templateId, header.data?.etag ?? null),
    onSuccess: () => {
      setRetiring(false);
      void queryClient.invalidateQueries({ queryKey: docKeys.template(templateId) });
    },
  });

  if (header.isPending) {
    return <p>{t('app.loading')}</p>;
  }
  if (header.error !== null) {
    return <ErrorNote error={header.error} title={t('docs.templateNotFound')} />;
  }

  const template = header.data.data;
  const builtIn = template.builtIn === true;

  const draftedColumns: readonly Column<DocumentView>[] = [
    { key: 'title', header: t('docs.columnTitle'), render: (row) => row.title ?? '—' },
    { key: 'type', header: t('docs.columnType'), render: (row) => row.documentType ?? '—' },
    {
      key: 'version',
      header: t('docs.columnVersion'),
      numeric: true,
      render: (row) => row.currentVersionNo ?? '—',
    },
  ];

  const versionColumns: readonly Column<TemplateVersion>[] = [
    {
      key: 'no',
      header: t('docs.templateVersionColumnNo'),
      numeric: true,
      render: (row) => row.versionNo ?? '—',
    },
    {
      key: 'published',
      header: t('docs.templateVersionColumnPublished'),
      render: (row) => <WireInstant wire={row.publishedAt} />,
    },
    {
      key: 'approvalBlock',
      header: t('docs.templateVersionColumnApprovalBlock'),
      render: (row) =>
        row.hasApprovalBlock === true
          ? t('docs.templateHasApprovalBlock')
          : t('docs.templateNoApprovalBlock'),
    },
    {
      key: 'by',
      header: t('docs.templateVersionColumnBy'),
      render: (row) => <Mono>{row.publishedByAccountId ?? '—'}</Mono>,
    },
    {
      key: 'body',
      header: t('docs.templateVersionColumnBody'),
      render: (row) =>
        row.versionNo === undefined ? (
          '—'
        ) : (
          <a
            href={`/api/v1/templates/${encodeURIComponent(templateId)}/versions/${row.versionNo}/body`}
            download
          >
            {t('action.download')}
          </a>
        ),
    },
  ];

  return (
    <>
      <Panel
        title={`${template.code ?? ''} · ${localName(template.nameKo, template.nameEn)}`}
        actions={
          <div style={{ display: 'flex', gap: 'var(--ci-space-2)' }}>
            <Badge tone={builtIn ? 'accent' : 'default'}>
              {builtIn ? t('docs.templateBuiltIn') : t('docs.templateForked')}
            </Badge>
            {template.active === false ? <Badge tone="warning">{t('docs.templateRetired')}</Badge> : null}
          </div>
        }
      >
        <KeyValues
          rows={[
            { key: 'type', label: t('docs.templateColumnType'), value: template.documentType ?? '—' },
            {
              key: 'version',
              label: t('docs.templateColumnVersion'),
              value:
                template.currentVersionNo === undefined || template.currentVersionNo === null
                  ? t('docs.templateNoVersionYet')
                  : t('documents.version', { no: template.currentVersionNo }),
            },
            { key: 'id', label: 'id', value: <Mono>{template.id ?? ''}</Mono> },
          ]}
        />
      </Panel>

      <Panel title={t('docs.templateVersionsTitle')}>
        <ErrorNote error={versions.error} />
        <DataTable
          caption={t('docs.templateVersionsTitle')}
          columns={versionColumns}
          rows={rows}
          rowKey={(row) => String(row.versionNo ?? '')}
          emptyMessage={versions.isPending ? t('app.loading') : t('docs.templateNoVersionYet')}
          onRowActivate={(row) => {
            setSelected(row.versionNo ?? null);
          }}
        />
      </Panel>

      {versionNo === null ? null : (
        <Panel
          title={`${t('docs.templateManifestTitle')} · ${t('documents.version', { no: versionNo })}`}
          description={t('docs.templateManifestHint')}
        >
          <ErrorNote error={manifest.error} />
          <ManifestTable fields={manifest.data ?? []} />
        </Panel>
      )}

      <PublishVersion
        templateId={templateId}
        etag={header.data.etag}
        startingFrom={manifest.data ?? []}
        onPublished={() => {
          void queryClient.invalidateQueries({ queryKey: docKeys.templateVersions(templateId) });
          void queryClient.invalidateQueries({ queryKey: docKeys.template(templateId) });
        }}
      />

      <ForkTemplate templateId={templateId} />

      <Panel title={t('docs.templateRestoreTitle')} description={t('docs.templateRestoreHint')}>
        {builtIn ? (
          <>
            <ErrorNote error={restore.error} />
            {restore.isSuccess ? (
              <Banner tone="positive">
                {t('docs.templateRestored', { no: restore.data.versionNo ?? '' })}
              </Banner>
            ) : null}
            <Button
              busy={restore.isPending}
              onClick={() => {
                restore.mutate();
              }}
            >
              {t('docs.templateRestoreRun')}
            </Button>
          </>
        ) : (
          <Banner tone="info">{t('docs.templateRestoreOnlyBuiltIn')}</Banner>
        )}
      </Panel>

      <Panel title={t('docs.templateRetireRun')} description={t('docs.templateRetireHint')}>
        <Button
          tone="danger"
          disabled={template.active === false}
          onClick={() => {
            setRetiring(true);
          }}
        >
          {t('docs.templateRetireRun')}
        </Button>
        {retiring ? (
          <ConfirmPanel
            title={t('docs.templateRetireRun')}
            onCancel={() => {
              setRetiring(false);
            }}
          >
            <p style={{ marginTop: 0 }}>{t('docs.templateRetireHint')}</p>
            <ErrorNote error={retire.error} />
            <div style={{ display: 'flex', gap: 'var(--ci-space-2)' }}>
              <Button
                tone="danger"
                busy={retire.isPending}
                onClick={() => {
                  retire.mutate();
                }}
              >
                {t('action.retire')}
              </Button>
              <Button
                onClick={() => {
                  setRetiring(false);
                }}
              >
                {t('action.cancel')}
              </Button>
            </div>
          </ConfirmPanel>
        ) : null}
      </Panel>

      {versionNo === null ? null : (
        <Panel title={t('docs.templateDocumentsTitle')}>
          <DataTable
            caption={t('docs.templateDocumentsTitle')}
            columns={draftedColumns}
            rows={drafted.data ?? []}
            rowKey={(row) => row.id ?? ''}
            emptyMessage={drafted.isPending ? t('app.loading') : t('docs.templateDocumentsEmpty')}
            onRowActivate={(row) => {
              if (row.id !== undefined) {
                onOpenDocument(row.id);
              }
            }}
          />
        </Panel>
      )}
    </>
  );
}

function ManifestTable({ fields }: { readonly fields: readonly TemplateField[] }): ReactNode {
  const { t } = useTranslation();
  const columns: readonly Column<TemplateField>[] = [
    { key: 'tag', header: t('docs.templateManifestTag'), render: (row) => <Mono>{row.tag ?? ''}</Mono> },
    { key: 'type', header: t('docs.templateManifestType'), render: (row) => row.type ?? '—' },
    { key: 'ko', header: t('docs.templateManifestLabelKo'), render: (row) => row.labelKo ?? '—' },
    { key: 'en', header: t('docs.templateManifestLabelEn'), render: (row) => row.labelEn ?? '—' },
    {
      key: 'required',
      header: t('docs.templateManifestRequired'),
      render: (row) => (row.required === true ? t('common.yes') : t('common.no')),
    },
  ];
  return (
    <DataTable
      caption={t('docs.templateManifestTitle')}
      columns={columns}
      rows={fields}
      rowKey={(row) => row.tag ?? ''}
      emptyMessage={t('docs.templateManifestEmpty')}
    />
  );
}

interface ManifestDraft {
  readonly key: string;
  tag: string;
  type: string;
  labelKo: string;
  labelEn: string;
  required: boolean;
}

interface BodyDraft {
  readonly key: string;
  file: File | null;
  locale: string;
  format: string;
}

let nextKey = 0;
function makeKey(): string {
  nextKey += 1;
  return `row-${nextKey}`;
}

/**
 * Publishing a version.
 *
 * The manifest and the docx are cross-validated server-side, and a mismatch
 * comes back as one violation per tag. §12's rule is that those attach to their
 * field, so they do: a tag the document does not carry gets its error on that
 * row, and a control the document *does* carry that the manifest never declared
 * gets a row of its own at the bottom, with a button that adds it — because
 * that is the fix, and making someone retype a tag they can see on screen is
 * how tags get mistyped.
 */
function PublishVersion({
  templateId,
  etag,
  startingFrom,
  onPublished,
}: {
  readonly templateId: string;
  readonly etag: string | null;
  readonly startingFrom: readonly TemplateField[];
  readonly onPublished: () => void;
}): ReactNode {
  const { t } = useTranslation();
  const [fields, setFields] = useState<readonly ManifestDraft[]>([]);
  const [bodies, setBodies] = useState<readonly BodyDraft[]>([
    { key: makeKey(), file: null, locale: 'ko', format: 'DOCX' },
  ]);
  const [seeded, setSeeded] = useState(false);

  // The published manifest is the starting point for the next one; a publish
  // form that begins empty invites someone to drop half the fields by accident.
  useEffect(() => {
    if (!seeded && startingFrom.length > 0) {
      setFields(
        startingFrom.map((field) => ({
          key: makeKey(),
          tag: field.tag ?? '',
          type: field.type ?? 'TEXT',
          labelKo: field.labelKo ?? '',
          labelEn: field.labelEn ?? '',
          required: field.required === true,
        })),
      );
      setSeeded(true);
    }
  }, [seeded, startingFrom]);

  const publish = useMutation({
    mutationFn: async () => {
      const usable = bodies.filter((body): body is BodyDraft & { file: File } => body.file !== null);
      const form = new FormData();
      for (const body of usable) {
        form.append('files', body.file, body.file.name);
      }
      const schema = JSON.stringify({
        fields: fields.map((field) => ({
          tag: field.tag,
          type: field.type,
          labelKo: field.labelKo,
          labelEn: field.labelEn,
          required: field.required,
        })),
      });
      return postMultipart<TemplateVersion>(
        `/templates/${encodeURIComponent(templateId)}/versions`,
        form,
        {
          query: {
            // Matched by position with the files, and sent comma-joined
            // because the client's query is one value per name. Spring binds a
            // comma-separated value to List<String>.
            locales: usable.map((body) => body.locale).join(','),
            formats: usable.map((body) => body.format).join(','),
            schema,
          },
          ifMatch: etag,
        },
      );
    },
    onSuccess: onPublished,
  });

  const byField = violationsByField(publish.error, t);
  const declaredTags = new Set(fields.map((field) => field.tag));
  const undeclared = [...byField.keys()].filter((tag) => !declaredTags.has(tag));
  const hasDocx = bodies.some((body) => body.file !== null && body.format === 'DOCX');

  return (
    <Panel title={t('docs.templatePublishTitle')} description={t('docs.templatePublishNeedsDocx')}>
      {publish.error === null ? null : (
        <ErrorNote error={publish.error} title={t('docs.templatePublishMismatch')} />
      )}
      {publish.isSuccess ? (
        <Banner tone="positive">{t('docs.templatePublished', { no: publish.data.versionNo ?? '' })}</Banner>
      ) : null}

      <h3 style={{ fontSize: 'var(--ci-text-sm)' }}>{t('docs.templateManifestTitle')}</h3>
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)' }}>
        {fields.map((field, index) => {
          const violation = byField.get(field.tag);
          return (
            <div
              key={field.key}
              style={{
                display: 'grid',
                gridTemplateColumns: '1fr 1fr 1fr 1fr auto auto',
                gap: 'var(--ci-space-2)',
                alignItems: 'start',
              }}
            >
              <TextField
                label={t('docs.templateManifestTag')}
                value={field.tag}
                {...(violation === undefined ? {} : { error: violation })}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  setFields((current) =>
                    current.map((row, at) => (at === index ? { ...row, tag: value } : row)),
                  );
                }}
              />
              <Select
                label={t('docs.templateManifestType')}
                value={field.type}
                onChange={(value) => {
                  setFields((current) =>
                    current.map((row, at) => (at === index ? { ...row, type: value } : row)),
                  );
                }}
                options={FIELD_TYPES.map((type) => ({ value: type, label: type }))}
              />
              <TextField
                label={t('docs.templateManifestLabelKo')}
                value={field.labelKo}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  setFields((current) =>
                    current.map((row, at) => (at === index ? { ...row, labelKo: value } : row)),
                  );
                }}
              />
              <TextField
                label={t('docs.templateManifestLabelEn')}
                value={field.labelEn}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  setFields((current) =>
                    current.map((row, at) => (at === index ? { ...row, labelEn: value } : row)),
                  );
                }}
              />
              <div style={{ paddingTop: 'var(--ci-space-4)' }}>
                <Checkbox
                  label={t('docs.templateManifestRequired')}
                  checked={field.required}
                  onChange={(checked) => {
                    setFields((current) =>
                      current.map((row, at) => (at === index ? { ...row, required: checked } : row)),
                    );
                  }}
                />
              </div>
              <div style={{ paddingTop: 'var(--ci-space-4)' }}>
                <Button
                  onClick={() => {
                    setFields((current) => current.filter((_row, at) => at !== index));
                  }}
                  aria-label={`${t('docs.templateManifestRemove')} ${field.tag}`}
                >
                  ×
                </Button>
              </div>
            </div>
          );
        })}
      </div>

      {undeclared.length === 0 ? null : (
        <div style={{ marginTop: 'var(--ci-space-3)' }}>
          {undeclared.map((tag) => (
            <div
              key={tag}
              style={{
                border: '1px solid var(--ci-danger)',
                borderRadius: 'var(--ci-radius)',
                padding: 'var(--ci-space-2)',
                marginBottom: 'var(--ci-space-2)',
              }}
            >
              <p style={{ margin: 0 }}>
                <Mono>{tag}</Mono> — {byField.get(tag)}
              </p>
              <Button
                onClick={() => {
                  setFields((current) => [
                    ...current,
                    { key: makeKey(), tag, type: 'TEXT', labelKo: tag, labelEn: tag, required: false },
                  ]);
                }}
              >
                {t('docs.templateManifestAdd')}
              </Button>
            </div>
          ))}
        </div>
      )}

      <div style={{ marginTop: 'var(--ci-space-3)' }}>
        <Button
          onClick={() => {
            setFields((current) => [
              ...current,
              { key: makeKey(), tag: '', type: 'TEXT', labelKo: '', labelEn: '', required: false },
            ]);
          }}
        >
          {t('docs.templateManifestAdd')}
        </Button>
      </div>

      <h3 style={{ fontSize: 'var(--ci-text-sm)', marginTop: 'var(--ci-space-4)' }}>
        {t('docs.templatePublishFiles')}
      </h3>
      {bodies.map((body, index) => (
        <div
          key={body.key}
          style={{
            display: 'grid',
            gridTemplateColumns: '2fr 1fr 1fr',
            gap: 'var(--ci-space-2)',
            marginBottom: 'var(--ci-space-2)',
          }}
        >
          <FileField
            label={t('docs.templatePublishFiles')}
            onChange={(file) => {
              setBodies((current) => current.map((row, at) => (at === index ? { ...row, file } : row)));
            }}
          />
          <Select
            label={t('docs.templatePublishLocale')}
            value={body.locale}
            onChange={(value) => {
              setBodies((current) =>
                current.map((row, at) => (at === index ? { ...row, locale: value } : row)),
              );
            }}
            options={[
              { value: 'ko', label: 'ko' },
              { value: 'en', label: 'en' },
            ]}
          />
          <Select
            label={t('docs.templatePublishFormat')}
            value={body.format}
            onChange={(value) => {
              setBodies((current) =>
                current.map((row, at) => (at === index ? { ...row, format: value } : row)),
              );
            }}
            options={DOCUMENT_FORMATS.map((format) => ({ value: format, label: format }))}
          />
        </div>
      ))}
      <Button
        onClick={() => {
          setBodies((current) => [...current, { key: makeKey(), file: null, locale: 'en', format: 'DOCX' }]);
        }}
      >
        {t('docs.templatePublishAddBody')}
      </Button>

      <div style={{ marginTop: 'var(--ci-space-4)' }}>
        <Button
          tone="primary"
          disabled={!hasDocx}
          busy={publish.isPending}
          onClick={() => {
            publish.mutate();
          }}
        >
          {t('docs.templatePublishRun')}
        </Button>
      </div>
    </Panel>
  );
}

function ForkTemplate({ templateId }: { readonly templateId: string }): ReactNode {
  const { t } = useTranslation();
  const [newCode, setNewCode] = useState('');
  const [newNameKo, setNewNameKo] = useState('');

  const fork = useMutation({
    mutationFn: () => writes.forkTemplate(templateId, { newCode, newNameKo }),
  });

  return (
    <Panel title={t('docs.templateForkTitle')} description={t('docs.templateForkHint')}>
      <ErrorNote error={fork.error} />
      {fork.isSuccess ? (
        <Banner tone="positive">{t('docs.templateForked2', { code: fork.data.code ?? '' })}</Banner>
      ) : null}
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '34rem' }}>
        <TextField
          label={t('docs.templateForkCode')}
          value={newCode}
          onChange={(event) => {
            setNewCode(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('docs.templateForkNameKo')}
          value={newNameKo}
          onChange={(event) => {
            setNewNameKo(event.currentTarget.value);
          }}
        />
        <div>
          <Button
            busy={fork.isPending}
            disabled={newCode.trim() === '' || newNameKo.trim() === ''}
            onClick={() => {
              fork.mutate();
            }}
          >
            {t('docs.templateForkRun')}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
