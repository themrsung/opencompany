import { Banner, Button } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, postMultipart, readVersionText } from './api.js';
import { DOCUMENT_FORMATS, type DocumentVersionView } from './contract.js';
import { FileField, Panel, Select } from './controls.js';
import { ErrorNote } from './errors.js';

/**
 * The body surface.
 *
 * <h2>Why only mdv is edited here</h2>
 *
 * §6.2 wants a rich-text editor bound to a constrained docx subset. Saving one
 * means writing OOXML, and the only write this API offers is a multipart upload
 * of a complete document file — so an in-browser docx editor would have to
 * build the package itself, in the browser, and there is no docx writer on this
 * side of the wire to do it with.
 *
 * mdv is different and is the reason the format exists: its stored form is
 * canonical text, `mdv fmt` runs on save, so the bytes a textarea produces are
 * exactly the bytes the server stores. That makes a real edit-and-save loop
 * possible for mdv today, and for the office formats it makes download, edit,
 * upload the honest path — the upload is stored byte for byte, which is what
 * §6.6 promises about uploaded files anyway.
 */
export function BodyEditor({
  documentId,
  versionNo,
  format,
  etag,
  retired,
  onSaved,
}: {
  readonly documentId: string;
  readonly versionNo: number;
  readonly format: string;
  readonly etag: string | null;
  readonly retired: boolean;
  readonly onSaved: (version: DocumentVersionView) => void;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const isText = format.toUpperCase() === 'MDV';

  const content = useQuery({
    queryKey: docKeys.content(documentId, versionNo),
    queryFn: () => readVersionText(documentId, versionNo),
    enabled: isText,
  });

  const [draft, setDraft] = useState<string | null>(null);
  useEffect(() => {
    setDraft(null);
  }, [documentId, versionNo]);

  const stored = content.data ?? '';
  const text = draft ?? stored;
  const dirty = draft !== null && draft !== stored;

  const save = useMutation({
    mutationFn: async () => {
      const form = new FormData();
      form.append('file', new File([text], `${documentId}-v${versionNo + 1}.mdv`, { type: 'text/vnd.mdv' }));
      return postMultipart<DocumentVersionView>(
        `/documents/${encodeURIComponent(documentId)}/versions`,
        form,
        { query: { format: 'MDV' }, ifMatch: etag },
      );
    },
    onSuccess: (version) => {
      setDraft(null);
      void queryClient.invalidateQueries({ queryKey: docKeys.versions(documentId) });
      void queryClient.invalidateQueries({ queryKey: docKeys.document(documentId) });
      onSaved(version);
    },
  });

  return (
    <>
      <Panel
        title={t('docs.bodyCaption')}
        {...(isText ? { description: t('docs.bodyTextEditable') } : {})}
        actions={
          <a
            className="ci-button"
            href={`/api/v1/documents/${encodeURIComponent(documentId)}/versions/${versionNo}/content`}
            download
          >
            {t('docs.bodyDownloadCurrent')}
          </a>
        }
      >
        <ErrorNote error={content.error ?? save.error} title={t('docs.bodyLoadFailed')} />

        {isText ? (
          <>
            <label className="ci-field__label" htmlFor="body-text">
              {t('documents.body')}
            </label>
            <textarea
              id="body-text"
              className="ci-field__input"
              rows={20}
              spellCheck={false}
              value={text}
              disabled={retired}
              style={{ fontFamily: 'var(--ci-font-mono)', fontSize: 'var(--ci-text-sm)', width: '100%' }}
              onChange={(event) => {
                setDraft(event.currentTarget.value);
              }}
            />
            <div
              style={{
                display: 'flex',
                gap: 'var(--ci-space-3)',
                alignItems: 'center',
                marginTop: 'var(--ci-space-3)',
              }}
            >
              <Button
                tone="primary"
                disabled={!dirty || retired}
                busy={save.isPending}
                onClick={() => {
                  save.mutate();
                }}
              >
                {t('docs.bodySaveVersion')}
              </Button>
              <span style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
                {save.isSuccess && !dirty
                  ? t('docs.bodySavedVersion', { no: save.data.versionNo ?? '' })
                  : dirty
                    ? ''
                    : t('docs.bodyUnchanged')}
              </span>
            </div>
          </>
        ) : (
          <Banner tone="info" title={t('docs.bodyBinaryTitle')}>
            {t('docs.bodyBinaryExplain', { format: format.toUpperCase() })}
          </Banner>
        )}
      </Panel>

      <UploadVersion documentId={documentId} etag={etag} retired={retired} onSaved={onSaved} />
    </>
  );
}

function UploadVersion({
  documentId,
  etag,
  retired,
  onSaved,
}: {
  readonly documentId: string;
  readonly etag: string | null;
  readonly retired: boolean;
  readonly onSaved: (version: DocumentVersionView) => void;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [format, setFormat] = useState<string>('DOCX');

  const upload = useMutation({
    mutationFn: async () => {
      if (file === null) {
        throw new Error('no file');
      }
      const form = new FormData();
      form.append('file', file, file.name);
      return postMultipart<DocumentVersionView>(
        `/documents/${encodeURIComponent(documentId)}/versions`,
        form,
        { query: { format }, ifMatch: etag },
      );
    },
    onSuccess: (version) => {
      setFile(null);
      void queryClient.invalidateQueries({ queryKey: docKeys.versions(documentId) });
      void queryClient.invalidateQueries({ queryKey: docKeys.document(documentId) });
      onSaved(version);
    },
  });

  return (
    <Panel title={t('docs.bodyUploadVersion')} description={t('docs.uploadKeepsOriginal')}>
      <ErrorNote error={upload.error} />
      <div style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '34rem' }}>
        <FileField label={t('docs.bodyUploadPick')} onChange={setFile} />
        <Select
          label={t('docs.uploadFormat')}
          value={format}
          onChange={setFormat}
          options={DOCUMENT_FORMATS.map((candidate) => ({ value: candidate, label: candidate }))}
        />
        <div>
          <Button
            disabled={file === null || retired}
            busy={upload.isPending}
            onClick={() => {
              upload.mutate();
            }}
          >
            {t('docs.bodyUploadVersion')}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
