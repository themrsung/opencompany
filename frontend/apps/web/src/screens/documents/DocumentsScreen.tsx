import { Badge, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, postMultipart, reads, writes } from './api.js';
import { DOCUMENT_FORMATS, type DocumentView, type TemplateView } from './contract.js';
import { Panel, Select, FileField, useShortcut } from './controls.js';
import { ErrorNote } from './errors.js';
import { useLocalName } from './format.js';

/**
 * The document list, and the two ways a document comes into existence: drafted
 * from a template version, or uploaded as a file that is kept byte for byte.
 */
export function DocumentsScreen({
  companyId,
  onOpenDocument,
}: {
  readonly companyId: string;
  readonly onOpenDocument: (documentId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const [documentType, setDocumentType] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [uploading, setUploading] = useState(false);

  const filter = documentType.trim() === '' ? null : documentType.trim();
  const list = useInfiniteQuery({
    queryKey: docKeys.documents(companyId, filter),
    queryFn: ({ pageParam }) => reads.documents(companyId, filter, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });

  const rows: readonly DocumentView[] = list.data?.pages.flatMap((page) => [...page.items]) ?? [];

  // The primary action of this screen, on a key. §12 asks for one per screen.
  useShortcut(
    useCallback((event: KeyboardEvent) => event.key === 'n' && !event.metaKey && !event.ctrlKey, []),
    useCallback(() => {
      setDrafting(true);
      setUploading(false);
    }, []),
  );

  const columns: readonly Column<DocumentView>[] = [
    {
      key: 'title',
      header: t('docs.columnTitle'),
      render: (row) => (
        <span>
          {row.title ?? '—'}{' '}
          {row.retired === true ? <Badge tone="warning">{t('docs.retiredBadge')}</Badge> : null}
        </span>
      ),
    },
    { key: 'type', header: t('docs.columnType'), render: (row) => row.documentType ?? '—' },
    {
      key: 'template',
      header: t('docs.columnTemplate'),
      render: (row) =>
        row.templateId === undefined || row.templateId === null
          ? t('docs.noTemplate')
          : `${row.templateId} · ${t('documents.version', { no: row.templateVersionNo ?? '?' })}`,
    },
    {
      key: 'version',
      header: t('docs.columnVersion'),
      numeric: true,
      render: (row) => row.currentVersionNo ?? '—',
    },
  ];

  return (
    <>
      <Panel
        title={t('docs.listTitle')}
        actions={
          <div style={{ display: 'flex', gap: 'var(--ci-space-2)' }}>
            <Button
              tone="primary"
              onClick={() => {
                setDrafting((open) => !open);
                setUploading(false);
              }}
              aria-expanded={drafting}
            >
              {t('docs.newFromTemplate')}
            </Button>
            <Button
              onClick={() => {
                setUploading((open) => !open);
                setDrafting(false);
              }}
              aria-expanded={uploading}
            >
              {t('docs.uploadTitle')}
            </Button>
          </div>
        }
      >
        <div style={{ maxWidth: '22rem', marginBottom: 'var(--ci-space-3)' }}>
          <TextField
            label={t('docs.filterType')}
            value={documentType}
            onChange={(event) => {
              setDocumentType(event.currentTarget.value);
            }}
            placeholder={t('docs.filterTypeAll')}
          />
        </div>

        {drafting ? (
          <DraftFromTemplate
            companyId={companyId}
            onDrafted={(documentId) => {
              setDrafting(false);
              onOpenDocument(documentId);
            }}
          />
        ) : null}

        {uploading ? (
          <UploadDocument
            companyId={companyId}
            onUploaded={(documentId) => {
              setUploading(false);
              onOpenDocument(documentId);
            }}
          />
        ) : null}
      </Panel>

      <ErrorNote
        error={list.error}
        onRetry={() => {
          void list.refetch();
        }}
      />

      <div className="page__surface">
        <DataTable
          caption={t('docs.listCaption')}
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id ?? ''}
          emptyMessage={list.isPending ? t('app.loading') : t('docs.empty')}
          onRowActivate={(row) => {
            if (row.id !== undefined) {
              onOpenDocument(row.id);
            }
          }}
        />
      </div>
      <p style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
        {t('docs.openHint')} · {t('docs.shortcutNew')}
      </p>

      {list.hasNextPage ? (
        <Button
          onClick={() => {
            void list.fetchNextPage();
          }}
          busy={list.isFetchingNextPage}
        >
          {t('docs.loadMore')}
        </Button>
      ) : null}
    </>
  );
}

function DraftFromTemplate({
  companyId,
  onDrafted,
}: {
  readonly companyId: string;
  readonly onDrafted: (documentId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();
  const queryClient = useQueryClient();
  const [templateId, setTemplateId] = useState('');
  const [title, setTitle] = useState('');
  const [locale, setLocale] = useState('ko');
  const [currency, setCurrency] = useState('KRW');

  const templates = useQuery({
    queryKey: docKeys.templates(companyId),
    queryFn: () => reads.templates(companyId),
  });

  const publishable: readonly TemplateView[] = (templates.data ?? []).filter(
    (template) => template.currentVersionNo !== undefined && template.currentVersionNo !== null,
  );

  const draft = useMutation({
    mutationFn: () =>
      writes.createFromTemplate({
        companyId,
        templateId,
        title,
        locale,
        ...(currency.trim() === '' ? {} : { defaultCurrencyCode: currency.trim() }),
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['docs', 'documents'] });
      if (created.id !== undefined) {
        onDrafted(created.id);
      }
    },
  });

  return (
    <Panel title={t('docs.newFromTemplateTitle')} id="draft-from-template">
      <ErrorNote error={draft.error} />
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '34rem' }}>
        <Select
          label={t('docs.newTemplate')}
          value={templateId}
          onChange={setTemplateId}
          options={[
            { value: '', label: '—' },
            ...publishable.map((template) => ({
              value: template.id ?? '',
              label: `${template.code ?? ''} · ${localName(template.nameKo, template.nameEn)}`,
            })),
          ]}
          {...(templateId === '' ? { hint: t('docs.newNeedsTemplate') } : {})}
        />
        <TextField
          label={t('docs.newDocumentTitle')}
          value={title}
          onChange={(event) => {
            setTitle(event.currentTarget.value);
          }}
          required
        />
        <Select
          label={t('docs.newLocale')}
          value={locale}
          onChange={setLocale}
          options={[
            { value: 'ko', label: t('app.korean') },
            { value: 'en', label: t('app.english') },
          ]}
        />
        <TextField
          label={t('docs.newCurrency')}
          hint={t('docs.newCurrencyHint')}
          value={currency}
          onChange={(event) => {
            setCurrency(event.currentTarget.value);
          }}
        />
        <div>
          <Button
            tone="primary"
            disabled={templateId === '' || title.trim() === ''}
            busy={draft.isPending}
            onClick={() => {
              draft.mutate();
            }}
          >
            {t('docs.newSubmit')}
          </Button>
        </div>
      </div>
    </Panel>
  );
}

function UploadDocument({
  companyId,
  onUploaded,
}: {
  readonly companyId: string;
  readonly onUploaded: (documentId: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [documentType, setDocumentType] = useState('');
  const [format, setFormat] = useState<string>('DOCX');

  const upload = useMutation({
    mutationFn: async () => {
      if (file === null) {
        throw new Error('no file');
      }
      const form = new FormData();
      form.append('file', file, file.name);
      return postMultipart<DocumentView>('/documents/upload', form, {
        query: { companyId, documentType, title, format },
      });
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['docs', 'documents'] });
      if (created.id !== undefined) {
        onUploaded(created.id);
      }
    },
  });

  return (
    <Panel title={t('docs.uploadHeading')} description={t('docs.uploadKeepsOriginal')} id="upload-document">
      <ErrorNote error={upload.error} />
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '34rem' }}>
        <FileField
          label={t('docs.uploadFile')}
          onChange={setFile}
          {...(file === null ? { hint: t('docs.uploadNeedsFile') } : {})}
        />
        <TextField
          label={t('docs.uploadDocumentTitle')}
          value={title}
          onChange={(event) => {
            setTitle(event.currentTarget.value);
          }}
          required
        />
        <TextField
          label={t('docs.uploadDocumentType')}
          value={documentType}
          onChange={(event) => {
            setDocumentType(event.currentTarget.value);
          }}
          required
        />
        <Select
          label={t('docs.uploadFormat')}
          value={format}
          onChange={setFormat}
          options={DOCUMENT_FORMATS.map((candidate) => ({ value: candidate, label: candidate }))}
        />
        <div>
          <Button
            tone="primary"
            disabled={file === null || title.trim() === '' || documentType.trim() === ''}
            busy={upload.isPending}
            onClick={() => {
              upload.mutate();
            }}
          >
            {t('docs.uploadSubmit')}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
