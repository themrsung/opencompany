import { formatBusinessInstant, type BusinessInstant } from '@coreintra/business-time';
import { isValidAmountInput, parseAmountInput, toPlainString } from '@coreintra/money';
import { Amount, Badge, Banner, BusinessInstantField, Button, TextField } from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads } from './api.js';
import { isFieldType, type DocumentView, type FieldType, type FieldValueView, type TemplateField } from './contract.js';
import { Panel, Select } from './controls.js';
import { ErrorNote } from './errors.js';
import { useAmountLabels, useInstantLabels, useLocalName, tryParseInstant } from './format.js';

/**
 * Typed field entry — how most documents get filled in (§6.2).
 *
 * <h2>Fast and keyboard-driven</h2>
 *
 * Enter moves to the next field and Shift+Enter to the previous, so a whole
 * 지출결의서 is filled without the hand leaving the keyboard; Escape puts the
 * field back the way the server sent it. Enter is left alone inside a
 * multi-line field, where it means a new line and nothing else.
 *
 * <h2>What this surface cannot do, and says so</h2>
 *
 * There is no endpoint that writes typed field values. `POST /documents/{id}/
 * versions` takes a whole document file, and the values are extracted from the
 * docx content controls server-side on the way in. The documents module has
 * `ContentControls.write(documentPart, values)` — the capability exists — but
 * nothing exposes it over HTTP, so a browser would have to rewrite the OOXML
 * package itself to save a single 금액.
 *
 * Rather than fake it, the editor validates everything, tells the person
 * exactly how many fields they have changed, and says in a full sentence that
 * saving means uploading a new version, with the route to do that one tab away.
 * A greyed-out Save with no explanation is the version of this that generates
 * support tickets.
 */
export function FieldEntry({
  document: documentRow,
  versionNo,
  companyId,
}: {
  readonly document: DocumentView;
  readonly versionNo: number;
  readonly companyId: string;
}): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();
  const documentId = documentRow.id ?? '';
  const templateId = documentRow.templateId ?? null;
  const templateVersionNo = documentRow.templateVersionNo ?? null;

  const values = useQuery({
    queryKey: docKeys.fields(documentId, versionNo),
    queryFn: () => reads.fields(documentId, versionNo),
  });

  const manifest = useQuery({
    queryKey: docKeys.templateFields(templateId ?? '', templateVersionNo ?? 0),
    queryFn: () => reads.templateFields(templateId ?? '', templateVersionNo ?? 0),
    enabled: templateId !== null && templateVersionNo !== null,
  });

  const missing = useQuery({
    queryKey: docKeys.missingRequired(documentId, versionNo),
    queryFn: () => reads.missingRequired(documentId, versionNo),
  });

  const employees = useQuery({
    queryKey: docKeys.employees(companyId),
    queryFn: () => reads.employees(companyId),
  });

  const units = useQuery({
    queryKey: docKeys.units(companyId),
    queryFn: () => reads.units(companyId),
  });

  const [edited, setEdited] = useState<ReadonlyMap<string, string>>(new Map());
  const container = useRef<HTMLDivElement | null>(null);

  const rows = useMemo(
    () => mergeRows(manifest.data ?? [], values.data ?? []),
    [manifest.data, values.data],
  );

  const missingSet = new Set(missing.data ?? []);
  const changedCount = edited.size;

  const setValue = (fieldId: string, next: string, original: string): void => {
    setEdited((current) => {
      const copy = new Map(current);
      if (next === original) {
        copy.delete(fieldId);
      } else {
        copy.set(fieldId, next);
      }
      return copy;
    });
  };

  const revert = (fieldId: string): void => {
    setEdited((current) => {
      const copy = new Map(current);
      copy.delete(fieldId);
      return copy;
    });
  };

  /** Enter and Shift+Enter walk the inputs; Escape reverts the one in hand. */
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const target = event.target as HTMLElement;
    if (event.key === 'Escape') {
      const fieldId = target.closest('[data-field-id]')?.getAttribute('data-field-id');
      if (fieldId !== null && fieldId !== undefined) {
        revert(fieldId);
      }
      return;
    }
    if (event.key !== 'Enter' || event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    if (target.tagName === 'TEXTAREA') {
      // Enter is a new line here and nothing else.
      return;
    }
    const inputs = Array.from(
      container.current?.querySelectorAll<HTMLElement>('input, select, textarea') ?? [],
    );
    const index = inputs.indexOf(target);
    if (index === -1) {
      return;
    }
    event.preventDefault();
    const next = inputs[index + (event.shiftKey ? -1 : 1)];
    next?.focus();
  };

  if (templateId === null) {
    return (
      <Panel title={t('docs.fieldsCaption')} description={t('docs.noTemplateHint')}>
        <Banner tone="info" title={t('docs.noTemplate')}>
          {t('docs.noTemplateHint')}
        </Banner>
      </Panel>
    );
  }

  return (
    <Panel title={t('docs.fieldsCaption')} description={t('docs.fieldsKeyboardHint')}>
      <ErrorNote error={values.error ?? manifest.error ?? missing.error} />

      {missing.isSuccess ? (
        missingSet.size === 0 ? (
          <Banner tone="positive">{t('docs.fieldMissingRequiredNone')}</Banner>
        ) : (
          <Banner tone="warning">{t('docs.fieldMissingRequired', { count: missingSet.size })}</Banner>
        )
      ) : null}

      <div style={{ height: 'var(--ci-space-3)' }} />

      {rows.length === 0 ? (
        <p style={{ color: 'var(--ci-fg-muted)' }}>
          {values.isPending || manifest.isPending ? t('app.loading') : t('docs.fieldsEmpty')}
        </p>
      ) : (
        <div
          ref={container}
          onKeyDown={onKeyDown}
          style={{ display: 'grid', gap: 'var(--ci-space-3)', maxWidth: '44rem' }}
        >
          {rows.map((row) => (
            <div key={row.fieldId} data-field-id={row.fieldId}>
              <FieldRow
                row={row}
                label={localName(row.labelKo, row.labelEn)}
                missing={missingSet.has(row.fieldId)}
                draft={edited.get(row.fieldId)}
                onChange={(next) => {
                  setValue(row.fieldId, next, row.raw);
                }}
                employees={(employees.data ?? []).map((employee) => ({
                  value: employee.id ?? '',
                  label: `${localName(employee.nameKo, employee.nameEn)} · ${employee.employeeNumber ?? ''}`,
                }))}
                units={(units.data ?? []).map((unit) => ({
                  value: unit.id ?? '',
                  label: localName(unit.nameKo, unit.nameEn),
                }))}
              />
            </div>
          ))}
        </div>
      )}

      <div style={{ marginTop: 'var(--ci-space-4)' }}>
        <Banner tone="warning" title={t('docs.fieldSaveTitle')}>
          <p style={{ margin: 0 }} id="field-save-unavailable">
            {t('docs.fieldSaveUnavailable')}
          </p>
          <p style={{ margin: 'var(--ci-space-2) 0 0' }}>{t('docs.fieldSaveUnavailableWhat')}</p>
        </Banner>
        <p style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
          {changedCount === 0 ? t('docs.fieldUnchanged') : t('docs.fieldChangedCount', { count: changedCount })}
        </p>
        {/* Disabled, with the reason on screen and bound to the control rather
            than hidden in a tooltip: the rule is that a refusal says what
            happened and what to do. */}
        <Button disabled aria-describedby="field-save-unavailable">
          {t('action.save')}
        </Button>
      </div>
    </Panel>
  );
}

interface MergedRow {
  readonly fieldId: string;
  readonly type: FieldType;
  readonly labelKo: string | undefined;
  readonly labelEn: string | undefined;
  readonly required: boolean;
  readonly helpKo: string | undefined;
  readonly helpEn: string | undefined;
  /** The value as the server holds it, in the form the input edits. */
  readonly raw: string;
  readonly currencyCode: string | undefined;
  readonly blobSha256: string | undefined;
}

/**
 * The manifest decides which fields exist and in what order; the values fill
 * them in.
 *
 * The value response has no row for a blank field, so a manifest-driven merge
 * is the only way an empty required field appears on screen at all — which is
 * the whole point of showing it.
 */
function mergeRows(manifest: readonly TemplateField[], values: readonly FieldValueView[]): readonly MergedRow[] {
  const byId = new Map<string, FieldValueView>();
  for (const value of values) {
    if (value.fieldId !== undefined) {
      byId.set(value.fieldId, value);
    }
  }

  const rows: MergedRow[] = [];
  for (const field of manifest) {
    const fieldId = field.tag;
    if (fieldId === undefined) {
      continue;
    }
    const value = byId.get(fieldId);
    byId.delete(fieldId);
    rows.push(toRow(fieldId, field, value));
  }
  // A value with no manifest entry should be impossible — cross-validation at
  // publish exists to stop exactly that — so if one turns up it is shown rather
  // than dropped.
  for (const [fieldId, value] of byId) {
    rows.push(toRow(fieldId, undefined, value));
  }
  return rows;
}

function toRow(fieldId: string, field: TemplateField | undefined, value: FieldValueView | undefined): MergedRow {
  const declared = field?.type ?? value?.type;
  return {
    fieldId,
    type: isFieldType(declared) ? declared : 'TEXT',
    labelKo: field?.labelKo ?? value?.labelKo,
    labelEn: field?.labelEn ?? value?.labelEn,
    required: field?.required ?? value?.required ?? false,
    helpKo: field?.helpKo,
    helpEn: field?.helpEn,
    raw: rawOf(value),
    currencyCode: value?.currencyCode,
    blobSha256: value?.blobSha256,
  };
}

function rawOf(value: FieldValueView | undefined): string {
  if (value === undefined) {
    return '';
  }
  return value.amount ?? value.number ?? value.instant ?? value.date ?? value.refId ?? value.text ?? '';
}

function FieldRow({
  row,
  label,
  missing,
  draft,
  onChange,
  employees,
  units,
}: {
  readonly row: MergedRow;
  readonly label: string;
  readonly missing: boolean;
  readonly draft: string | undefined;
  readonly onChange: (next: string) => void;
  readonly employees: readonly { readonly value: string; readonly label: string }[];
  readonly units: readonly { readonly value: string; readonly label: string }[];
}): ReactNode {
  const { t } = useTranslation();
  const amountLabels = useAmountLabels();
  const instantLabels = useInstantLabels();
  const current = draft ?? row.raw;
  const dirty = draft !== undefined;

  const caption = (
    <span>
      {label === '' ? row.fieldId : label}
      {row.required ? (
        <>
          {' '}
          <Badge tone={missing ? 'danger' : 'default'}>{t('docs.fieldRequired')}</Badge>
        </>
      ) : null}
      {dirty ? <span aria-hidden="true"> •</span> : null}
    </span>
  );
  const labelText = `${label === '' ? row.fieldId : label}${row.required ? ` (${t('docs.fieldRequired')})` : ''}`;

  switch (row.type) {
    case 'MULTILINE_TEXT':
      return (
        <div className="ci-field">
          <label className="ci-field__label" htmlFor={`field-${row.fieldId}`}>
            {caption}
          </label>
          <textarea
            id={`field-${row.fieldId}`}
            className="ci-field__input"
            rows={4}
            value={current}
            aria-label={labelText}
            onChange={(event) => {
              onChange(event.currentTarget.value);
            }}
          />
        </div>
      );

    case 'MONEY': {
      const valid = current === '' || isValidAmountInput(current);
      // The stored form, not the typed form: `1,234` and `+7` are things people
      // paste, and `<Amount>` is handed the exact decimal string the API would
      // hold. Nothing here goes near `Number`.
      const exact = valid && current !== '' ? toPlainString(parseAmountInput(current)) : null;
      return (
        <div>
          <TextField
            id={`field-${row.fieldId}`}
            label={labelText}
            value={current}
            inputMode="decimal"
            {...(valid ? {} : { error: t('docs.fieldMoneyInvalid') })}
            onChange={(event) => {
              onChange(event.currentTarget.value);
            }}
          />
          {exact === null ? null : (
            <p style={{ margin: 'var(--ci-space-1) 0 0', fontSize: 'var(--ci-text-sm)' }}>
              <Amount
                value={exact}
                displayDecimals={row.currencyCode === 'KRW' ? 0 : 2}
                {...(row.currencyCode === undefined ? {} : { currencyCode: row.currencyCode })}
                labels={amountLabels}
              />
            </p>
          )}
        </div>
      );
    }

    case 'NUMBER': {
      const valid = current === '' || /^-?\d+(?:\.\d+)?$/.test(current.trim());
      return (
        <TextField
          id={`field-${row.fieldId}`}
          label={labelText}
          value={current}
          inputMode="decimal"
          {...(valid ? {} : { error: t('docs.fieldNumberInvalid') })}
          onChange={(event) => {
            onChange(event.currentTarget.value);
          }}
        />
      );
    }

    case 'DATE':
      return (
        <TextField
          id={`field-${row.fieldId}`}
          type="date"
          label={labelText}
          value={current}
          onChange={(event) => {
            onChange(event.currentTarget.value);
          }}
        />
      );

    case 'BUSINESS_INSTANT':
      return (
        <div>
          <BusinessInstantField
            id={`field-${row.fieldId}`}
            value={tryParseInstant(current)}
            labels={{ ...instantLabels, legend: labelText }}
            onChange={(next: BusinessInstant | null) => {
              onChange(next === null ? '' : formatBusinessInstant(next));
            }}
            required={row.required}
          />
        </div>
      );

    case 'EMPLOYEE_REF':
      return (
        <Select
          id={`field-${row.fieldId}`}
          label={labelText}
          value={current}
          onChange={onChange}
          options={[{ value: '', label: t('docs.fieldPickEmployee') }, ...employees]}
        />
      );

    case 'ORG_REF':
      return (
        <Select
          id={`field-${row.fieldId}`}
          label={labelText}
          value={current}
          onChange={onChange}
          options={[{ value: '', label: t('docs.fieldPickOrgUnit') }, ...units]}
        />
      );

    case 'FILE':
      return (
        <div className="ci-field">
          <span className="ci-field__label">{caption}</span>
          <p className="ci-field__hint" style={{ margin: 0 }}>
            {row.blobSha256 === undefined
              ? t('common.none')
              : t('docs.fieldFileStored', { hash: row.blobSha256 })}
          </p>
        </div>
      );

    case 'TABLE':
      return (
        <div className="ci-field">
          <span className="ci-field__label">{caption}</span>
          <p className="ci-field__hint" style={{ margin: 0 }}>
            {t('docs.fieldTableUnsupported')}
          </p>
        </div>
      );

    case 'TEXT':
    default:
      return (
        <TextField
          id={`field-${row.fieldId}`}
          label={labelText}
          value={current}
          onChange={(event) => {
            onChange(event.currentTarget.value);
          }}
        />
      );
  }
}
