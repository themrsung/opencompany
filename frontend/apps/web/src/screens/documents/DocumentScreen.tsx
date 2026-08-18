import { Badge, Banner, Button } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads, writes } from './api.js';
import { BodyEditor } from './BodyEditor.js';
import { ConfirmPanel, KeyValues, Mono, Panel, TabPanel, Tabs } from './controls.js';
import { ErrorNote } from './errors.js';
import { ExportPanel } from './ExportPanel.js';
import { FieldEntry } from './FieldEntry.js';
import { VersionHistory } from './VersionHistory.js';

/**
 * One document: its header, and the four things you can do to it.
 *
 * The two editing surfaces §6.2 requires are the first two tabs — typed field
 * entry and the body — and they are tabs rather than one long page because a
 * 지출결의서 filled from the keyboard should not have a rich-text editor
 * stealing the Tab key halfway down.
 */
export function DocumentScreen({
  documentId,
  companyId,
}: {
  readonly documentId: string;
  readonly companyId: string;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('fields');
  const [retiring, setRetiring] = useState(false);

  const header = useQuery({
    queryKey: docKeys.document(documentId),
    queryFn: () => reads.document(documentId),
  });

  const versions = useQuery({
    queryKey: docKeys.versions(documentId),
    queryFn: () => reads.versions(documentId),
  });

  const retire = useMutation({
    mutationFn: () => writes.retireDocument(documentId, header.data?.etag ?? null),
    onSuccess: () => {
      setRetiring(false);
      void queryClient.invalidateQueries({ queryKey: docKeys.document(documentId) });
      void queryClient.invalidateQueries({ queryKey: ['docs', 'documents'] });
    },
  });

  if (header.isPending) {
    return <p>{t('app.loading')}</p>;
  }
  if (header.error !== null) {
    return <ErrorNote error={header.error} title={t('docs.documentNotFound')} />;
  }

  const document = header.data.data;
  const currentVersionNo = document.currentVersionNo ?? 1;
  const currentVersion = (versions.data ?? []).find((row) => row.versionNo === currentVersionNo);
  const storedFormat = currentVersion?.format ?? 'DOCX';
  const retired = document.retired === true;

  const tabs = [
    { id: 'fields', label: t('docs.tabFields') },
    { id: 'body', label: t('docs.tabBody') },
    { id: 'versions', label: t('docs.tabVersions') },
    { id: 'export', label: t('docs.tabExport') },
  ];

  return (
    <>
      <Panel
        title={document.title ?? documentId}
        actions={
          retired ? (
            <Badge tone="warning">{t('docs.retiredBadge')}</Badge>
          ) : (
            <Button
              tone="danger"
              onClick={() => {
                setRetiring(true);
              }}
            >
              {t('docs.retireDocument')}
            </Button>
          )
        }
      >
        <KeyValues
          rows={[
            { key: 'type', label: t('docs.columnType'), value: document.documentType ?? '—' },
            { key: 'format', label: t('docs.storedFormat'), value: storedFormat },
            {
              key: 'version',
              label: t('docs.currentVersion'),
              value: t('documents.version', { no: currentVersionNo }),
            },
            {
              key: 'template',
              label: t('docs.pinnedTemplate'),
              value:
                document.templateId === undefined || document.templateId === null ? (
                  t('docs.noTemplate')
                ) : (
                  <>
                    <Mono>{document.templateId}</Mono>{' '}
                    {t('docs.pinnedTemplateHint', { version: document.templateVersionNo ?? '?' })}
                  </>
                ),
            },
          ]}
        />

        {retired ? (
          <div style={{ marginTop: 'var(--ci-space-3)' }}>
            <Banner tone="warning">{t('docs.retiredNotice')}</Banner>
          </div>
        ) : null}

        {retiring ? (
          <ConfirmPanel
            title={t('docs.retireDocument')}
            onCancel={() => {
              setRetiring(false);
            }}
          >
            <p style={{ marginTop: 0 }}>{t('docs.retireDocumentWarning')}</p>
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

        {/*
          Stated once, on the screen where someone would go looking. §6.3 makes
          the 도장 a server-side composite at render time, and there is no
          endpoint that serves one — this says so rather than leaving its
          absence to look like an oversight.
        */}
        <p style={{ marginTop: 'var(--ci-space-3)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-xs)' }}>
          {t('docs.sealNeverServed')}
        </p>
      </Panel>

      <Tabs tabs={tabs} active={tab} onSelect={setTab} label={t('docs.listTitle')} />

      {tab === 'fields' ? (
        <TabPanel id="fields">
          <FieldEntry document={document} versionNo={currentVersionNo} companyId={companyId} />
        </TabPanel>
      ) : null}

      {tab === 'body' ? (
        <TabPanel id="body">
          <BodyEditor
            documentId={documentId}
            versionNo={currentVersionNo}
            format={storedFormat}
            etag={header.data.etag}
            retired={retired}
            onSaved={() => {
              void queryClient.invalidateQueries({ queryKey: docKeys.document(documentId) });
            }}
          />
        </TabPanel>
      ) : null}

      {tab === 'versions' ? (
        <TabPanel id="versions">
          <VersionHistory documentId={documentId} currentVersionNo={currentVersionNo} />
        </TabPanel>
      ) : null}

      {tab === 'export' ? (
        <TabPanel id="export">
          <ExportPanel
            documentId={documentId}
            storedFormat={storedFormat}
            currentVersionNo={currentVersionNo}
          />
        </TabPanel>
      ) : null}
    </>
  );
}
