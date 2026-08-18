import { Badge, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads, writes } from './api.js';
import type { TemplateView } from './contract.js';
import { Panel } from './controls.js';
import { ErrorNote } from './errors.js';
import { useLocalName } from './format.js';

/** The template list, and the row that creates one. */
export function TemplatesScreen({
  companyId,
  onOpenTemplate,
}: {
  readonly companyId: string;
  readonly onOpenTemplate: (templateId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();
  const [creating, setCreating] = useState(false);

  const templates = useQuery({
    queryKey: docKeys.templates(companyId),
    queryFn: () => reads.templates(companyId),
  });

  const columns: readonly Column<TemplateView>[] = [
    { key: 'code', header: t('docs.templateColumnCode'), render: (row) => row.code ?? '—' },
    {
      key: 'name',
      header: t('docs.templateColumnName'),
      render: (row) => localName(row.nameKo, row.nameEn),
    },
    { key: 'type', header: t('docs.templateColumnType'), render: (row) => row.documentType ?? '—' },
    {
      key: 'version',
      header: t('docs.templateColumnVersion'),
      numeric: true,
      render: (row) => row.currentVersionNo ?? '—',
    },
    {
      key: 'origin',
      header: t('docs.templateColumnOrigin'),
      render: (row) => (
        <>
          <Badge tone={row.builtIn === true ? 'accent' : 'default'}>
            {row.builtIn === true ? t('docs.templateBuiltIn') : t('docs.templateForked')}
          </Badge>{' '}
          {row.active === false ? <Badge tone="warning">{t('docs.templateRetired')}</Badge> : null}
        </>
      ),
    },
  ];

  return (
    <>
      <Panel
        title={t('docs.templatesTitle')}
        actions={
          <Button
            tone="primary"
            aria-expanded={creating}
            onClick={() => {
              setCreating((open) => !open);
            }}
          >
            {t('docs.templateCreate')}
          </Button>
        }
      >
        {creating ? (
          <CreateTemplate
            companyId={companyId}
            onCreated={(templateId) => {
              setCreating(false);
              onOpenTemplate(templateId);
            }}
          />
        ) : null}
        <ErrorNote
          error={templates.error}
          onRetry={() => {
            void templates.refetch();
          }}
        />
      </Panel>

      <div className="page__surface">
        <DataTable
          caption={t('docs.templatesCaption')}
          columns={columns}
          rows={templates.data ?? []}
          rowKey={(row) => row.id ?? ''}
          emptyMessage={templates.isPending ? t('app.loading') : t('docs.templateEmpty')}
          onRowActivate={(row) => {
            if (row.id !== undefined) {
              onOpenTemplate(row.id);
            }
          }}
        />
      </div>
    </>
  );
}

function CreateTemplate({
  companyId,
  onCreated,
}: {
  readonly companyId: string;
  readonly onCreated: (templateId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [code, setCode] = useState('');
  const [nameKo, setNameKo] = useState('');
  const [nameEn, setNameEn] = useState('');
  const [documentType, setDocumentType] = useState('');

  const create = useMutation({
    mutationFn: () =>
      writes.createTemplate({
        companyId,
        code,
        nameKo,
        documentType,
        ...(nameEn.trim() === '' ? {} : { nameEn }),
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: docKeys.templates(companyId) });
      if (created.id !== undefined) {
        onCreated(created.id);
      }
    },
  });

  return (
    <Panel title={t('docs.templateCreate')} description={t('docs.templateCreateHeading')}>
      <ErrorNote error={create.error} />
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '34rem' }}>
        <TextField
          label={t('docs.templateCode')}
          value={code}
          required
          onChange={(event) => {
            setCode(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('docs.templateNameKo')}
          value={nameKo}
          required
          onChange={(event) => {
            setNameKo(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('docs.templateNameEn')}
          value={nameEn}
          onChange={(event) => {
            setNameEn(event.currentTarget.value);
          }}
        />
        <TextField
          label={t('docs.templateDocumentType')}
          value={documentType}
          required
          onChange={(event) => {
            setDocumentType(event.currentTarget.value);
          }}
        />
        <div>
          <Button
            tone="primary"
            busy={create.isPending}
            disabled={code.trim() === '' || nameKo.trim() === '' || documentType.trim() === ''}
            onClick={() => {
              create.mutate();
            }}
          >
            {t('docs.templateCreate')}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
