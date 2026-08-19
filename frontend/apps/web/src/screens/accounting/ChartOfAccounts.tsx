/**
 * The chart is a tree, and three of its rules are only visible if the screen
 * says them out loud:
 *
 *   - the type is fixed by the first digit of the code and can never change;
 *   - an account with children is a subtotal and stops accepting postings;
 *   - retiring keeps every figure that was already posted.
 *
 * Each of those is a sentence on this screen rather than an error the person
 * meets after they have typed everything in.
 */
import { Badge, Banner, Button, DataTable, EmptyState, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import { Checkbox, Select } from './fields.js';
import { accounting, keys, type Account, type OpenAccountRequest } from './queries.js';

const TYPE_BY_PREFIX: Readonly<Record<string, string>> = {
  '1': 'ASSET',
  '2': 'LIABILITY',
  '3': 'EQUITY',
  '4': 'INCOME',
  '5': 'EXPENSE',
};

const CATEGORIES = [
  'CASH',
  'CASH_EQUIVALENT',
  'RECEIVABLE',
  'PREPAID_EXPENSE',
  'OPERATING_ASSET',
  'INVESTMENT',
  'CREDIT_CARD',
  'UNPAID_EXPENSE',
  'LINE_OF_CREDIT',
  'AMORTIZING_LOAN',
  'PROVISION',
  'GUARANTEE',
  'OTHER',
] as const;

const CLASSIFICATIONS = ['OPERATING', 'INVESTING', 'FINANCING', 'TAX', 'FX', 'OTHER'] as const;

interface TreeRow {
  readonly account: Account;
  readonly id: string;
  readonly depth: number;
  readonly hasChildren: boolean;
}

/**
 * Depth-first, ordered by code, because a chart of accounts is read in code
 * order by everyone who has ever used one.
 */
function buildTree(accounts: readonly Account[]): TreeRow[] {
  const byParent = new Map<string, Account[]>();
  const ids = new Set(accounts.map((account) => account.id));
  for (const account of accounts) {
    const parent = account.parentId !== undefined && ids.has(account.parentId) ? account.parentId : '';
    const siblings = byParent.get(parent) ?? [];
    siblings.push(account);
    byParent.set(parent, siblings);
  }
  const rows: TreeRow[] = [];
  const walk = (parent: string, depth: number): void => {
    const children = [...(byParent.get(parent) ?? [])].sort((a, b) => (a.id ?? '').localeCompare(b.id ?? ''));
    for (const account of children) {
      const id = account.id ?? '';
      rows.push({ account, id, depth, hasChildren: (byParent.get(id) ?? []).length > 0 });
      walk(id, depth + 1);
    }
  };
  walk('', 0);
  return rows;
}

export function ChartOfAccountsScreen(): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [includeRetired, setIncludeRetired] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [confirmingRetire, setConfirmingRetire] = useState(false);

  const accountsQuery = useQuery({
    queryKey: keys.accounts(bookId ?? ''),
    queryFn: () => accounting.accounts(bookId ?? ''),
    enabled: bookId !== null,
  });

  const accounts = useMemo(() => accountsQuery.data ?? [], [accountsQuery.data]);
  const tree = useMemo(() => buildTree(accounts), [accounts]);

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return tree.filter((row) => {
      if (!includeRetired && row.account.retired === true) {
        return false;
      }
      if (needle === '') {
        return true;
      }
      const haystack = [row.id, row.account.nameKo, row.account.nameEn].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(needle);
    });
  }, [tree, search, includeRetired]);

  const selected = tree.find((row) => row.id === selectedId) ?? null;

  const retire = useMutation({
    mutationFn: (accountId: string) => accounting.retireAccount(bookId ?? '', accountId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.accounts(bookId ?? '') }),
  });

  const columns: readonly Column<TreeRow>[] = [
    {
      key: 'code',
      header: t('ledger.accounts.columnCode'),
      width: '10rem',
      render: (row) => <span className="ci-numeric">{row.id}</span>,
    },
    {
      key: 'name',
      header: t('ledger.accounts.columnName'),
      render: (row) => (
        <span style={{ paddingInlineStart: `calc(var(--ci-space-3) * ${row.depth})`, display: 'inline-block' }}>
          {row.account.nameKo ?? row.account.nameEn ?? row.id}
          {row.account.contra === true ? (
            <>
              {' '}
              <Badge tone="accent">{t('ledger.accounts.contra')}</Badge>
            </>
          ) : null}
        </span>
      ),
    },
    {
      key: 'type',
      header: t('ledger.accounts.columnType'),
      width: '8rem',
      render: (row) =>
        row.account.type === undefined
          ? t('ledger.accounts.type.UNKNOWN')
          : t(`ledger.accounts.type.${row.account.type}`, { defaultValue: row.account.type }),
    },
    {
      key: 'status',
      header: t('ledger.accounts.columnStatus'),
      width: '14rem',
      render: (row) => <AccountStatus row={row} />,
    },
    {
      key: 'currency',
      header: t('common.currency'),
      width: '6rem',
      render: (row) => row.account.currencyCode ?? '',
    },
  ];

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  return (
    <div className="acc-split">
      <div>
        <div className="acc-toolbar">
          <TextField
            label={t('ledger.accounts.searchLabel')}
            hint={t('ledger.accounts.searchHint')}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Checkbox
            label={t('ledger.accounts.includeRetired')}
            checked={includeRetired}
            onChange={setIncludeRetired}
          />
          <Button onClick={() => setCreating((open) => !open)}>{t('ledger.accounts.open')}</Button>
        </div>

        <p className="acc-note">{t('ledger.accounts.treeHint')}</p>

        {creating ? (
          <NewAccountForm
            bookId={bookId}
            accounts={accounts}
            tree={tree}
            initialParentId={selected?.id ?? null}
            onClose={() => setCreating(false)}
          />
        ) : null}

        {accountsQuery.isError ? (
          <Banner tone="danger">{presentError(accountsQuery.error, t).message}</Banner>
        ) : null}
        {retire.isSuccess ? <Banner tone="positive">{t('ledger.accounts.updated')}</Banner> : null}

        <DataTable
          caption={t('ledger.accounts.tree')}
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          emptyMessage={search.trim() === '' ? t('ledger.accounts.empty') : t('ledger.accounts.searchEmpty')}
          onRowActivate={(row) => setSelectedId(row.id)}
        />
      </div>

      {selected === null ? null : (
        <aside className="acc-panel" aria-label={selected.account.nameKo ?? selected.id}>
          <h2 className="acc-panel__title">
            <span className="ci-numeric">{selected.id}</span> {selected.account.nameKo ?? ''}
          </h2>
          <dl className="acc-facts">
            <dt>{t('ledger.accounts.nameEn')}</dt>
            <dd>{selected.account.nameEn ?? '—'}</dd>
            <dt>{t('ledger.accounts.columnType')}</dt>
            <dd>{t(`ledger.accounts.type.${selected.account.type ?? 'UNKNOWN'}`, { defaultValue: selected.account.type ?? '' })}</dd>
            <dt>{t('ledger.accounts.category')}</dt>
            <dd>
              <Resolved own={selected.account.ownCategory} effective={selected.account.category} prefix="categoryValue" />
            </dd>
            <dt>{t('ledger.accounts.classification')}</dt>
            <dd>
              <Resolved
                own={selected.account.ownClassification}
                effective={selected.account.classification}
                prefix="classificationValue"
              />
            </dd>
            <dt>{t('common.currency')}</dt>
            <dd>{selected.account.currencyCode ?? '—'}</dd>
            {selected.account.contra === true && selected.account.parentId !== undefined ? (
              <>
                <dt>{t('ledger.accounts.contra')}</dt>
                <dd>{t('ledger.accounts.contraOf', { name: selected.account.parentId })}</dd>
              </>
            ) : null}
          </dl>

          <p className="acc-note">
            <RefusalNote row={selected} />
          </p>

          {selected.account.retired === true ? (
            <Banner tone="warning" title={t('ledger.accounts.retired')}>
              {t('ledger.accounts.refusalRetired')}
            </Banner>
          ) : confirmingRetire ? (
            <Banner tone="danger" actions={ <> <Button tone="danger" busy={retire.isPending} onClick={() => { retire.mutate(selected.id); setConfirmingRetire(false); }} > {t('action.retire')} </Button> <Button onClick={() => setConfirmingRetire(false)}>{t('action.cancel')}</Button> </> }>{t('ledger.accounts.retireConfirm', { name: selected.account.nameKo ?? selected.id })}</Banner>
          ) : (
            <Button tone="danger" onClick={() => setConfirmingRetire(true)}>
              {t('action.retire')}
            </Button>
          )}
          {retire.isError ? <Banner tone="danger">{presentError(retire.error, t).message}</Banner> : null}
        </aside>
      )}
    </div>
  );
}

function Resolved({
  own,
  effective,
  prefix,
}: {
  readonly own: string | undefined;
  readonly effective: string | undefined;
  readonly prefix: 'categoryValue' | 'classificationValue';
}): ReactNode {
  const { t } = useTranslation();
  if (effective === undefined || effective === '') {
    return <>—</>;
  }
  const label = t(`ledger.accounts.${prefix}.${effective}`, { defaultValue: effective });
  if (own !== undefined && own !== '') {
    return (
      <>
        {label} <span className="acc-muted">({t('ledger.accounts.own')})</span>
      </>
    );
  }
  return (
    <>
      {label} <span className="acc-muted">({t('ledger.accounts.inherited')})</span>
    </>
  );
}

function AccountStatus({ row }: { readonly row: TreeRow }): ReactNode {
  const { t } = useTranslation();
  if (row.account.retired === true) {
    return <Badge tone="warning">{t('ledger.accounts.retired')}</Badge>;
  }
  if (row.hasChildren) {
    return <Badge>{t('ledger.accounts.notPostable')}</Badge>;
  }
  if (row.account.postable === true) {
    return <Badge tone="positive">{t('ledger.accounts.postable')}</Badge>;
  }
  return <Badge>{t('ledger.accounts.notPostable')}</Badge>;
}

/**
 * The server also sends `postingRefusalReason`, but it is English prose. The
 * shape of the refusal is knowable from the row itself, so the reason is said
 * here in the reader's language instead.
 */
function RefusalNote({ row }: { readonly row: TreeRow }): ReactNode {
  const { t } = useTranslation();
  if (row.account.retired === true) {
    return <>{t('ledger.accounts.refusalRetired')}</>;
  }
  if (row.hasChildren) {
    return <>{t('ledger.accounts.refusalSubtotal')}</>;
  }
  if (row.account.postable === true) {
    return <>{t('ledger.accounts.postable')}</>;
  }
  return <>{t('ledger.accounts.refusalOther')}</>;
}

function NewAccountForm({
  bookId,
  accounts,
  tree,
  initialParentId,
  onClose,
}: {
  readonly bookId: string;
  readonly accounts: readonly Account[];
  readonly tree: readonly TreeRow[];
  readonly initialParentId: string | null;
  readonly onClose: () => void;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const [id, setId] = useState('');
  const [nameKo, setNameKo] = useState('');
  const [nameEn, setNameEn] = useState('');
  const [parentId, setParentId] = useState(initialParentId ?? '');
  const [category, setCategory] = useState('');
  const [classification, setClassification] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [contra, setContra] = useState(false);

  const create = useMutation({
    mutationFn: (body: OpenAccountRequest) => accounting.openAccount(bookId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.accounts(bookId) });
      onClose();
    },
  });

  const prefix = id.trim().slice(0, 1);
  const derivedType = TYPE_BY_PREFIX[prefix];
  const parent = accounts.find((account) => account.id === parentId) ?? null;
  const parentRow = tree.find((row) => row.id === parentId) ?? null;
  const typeMismatch = parent !== null && derivedType !== undefined && parent.type !== derivedType;
  const presented = create.isError ? presentError(create.error, t) : null;

  return (
    <form
      className="acc-form"
      onSubmit={(event) => {
        event.preventDefault();
        create.mutate({
          id: id.trim(),
          nameKo: nameKo.trim(),
          ...(nameEn.trim() === '' ? {} : { nameEn: nameEn.trim() }),
          ...(parentId === '' ? {} : { parentId }),
          ...(category === '' ? {} : { category }),
          ...(classification === '' ? {} : { classification }),
          ...(currencyCode === '' ? {} : { currencyCode }),
          ...(contra ? { contra: true } : {}),
        });
      }}
    >
      <h2 className="acc-panel__title">{t('ledger.accounts.openTitle')}</h2>

      <TextField
        label={t('ledger.accounts.idLabel')}
        hint={t('ledger.accounts.idHint')}
        value={id}
        onChange={(event) => setId(event.target.value)}
        required
        {...(id.trim() !== '' && derivedType === undefined ? { error: t('ledger.accounts.idInvalid') } : {})}
        className="ci-numeric"
      />
      {derivedType === undefined ? null : (
        <p className="acc-note">
          {t('ledger.accounts.typeOfPrefix', {
            prefix,
            type: t(`ledger.accounts.type.${derivedType}`, { defaultValue: derivedType }),
          })}
        </p>
      )}
      <p className="acc-note">{t('ledger.accounts.typeFixed')}</p>

      <TextField label={t('ledger.accounts.nameKo')} value={nameKo} onChange={(event) => setNameKo(event.target.value)} required />
      <TextField label={t('ledger.accounts.nameEn')} value={nameEn} onChange={(event) => setNameEn(event.target.value)} />

      <Select label={t('ledger.accounts.parent')} value={parentId} onChange={setParentId}>
        <option value="">{t('ledger.accounts.parentNone')}</option>
        {tree.map((row) => (
          <option key={row.id} value={row.id}>
            {' '.repeat(row.depth * 2)}
            {row.id} {row.account.nameKo ?? ''}
          </option>
        ))}
      </Select>

      {typeMismatch ? (
        <Banner tone="danger">{t('ledger.accounts.parentTypeMismatch', {
          type: t(`ledger.accounts.type.${parent?.type ?? 'UNKNOWN'}`, { defaultValue: parent?.type ?? '' }),
        })}</Banner>
      ) : null}

      {parentRow !== null && !parentRow.hasChildren ? (
        <Banner tone="warning">{t('ledger.accounts.parentBecomesSubtotal', { name: parentRow.account.nameKo ?? parentRow.id })}</Banner>
      ) : null}

      <Select label={t('ledger.accounts.category')} value={category} onChange={setCategory}>
        <option value="">{t('ledger.accounts.inherited')}</option>
        {CATEGORIES.map((value) => (
          <option key={value} value={value}>
            {t(`ledger.accounts.categoryValue.${value}`, { defaultValue: value })}
          </option>
        ))}
      </Select>

      <Select label={t('ledger.accounts.classification')} value={classification} onChange={setClassification}>
        <option value="">{t('ledger.accounts.inherited')}</option>
        {CLASSIFICATIONS.map((value) => (
          <option key={value} value={value}>
            {t(`ledger.accounts.classificationValue.${value}`, { defaultValue: value })}
          </option>
        ))}
      </Select>

      <TextField
        label={t('ledger.accounts.currency')}
        value={currencyCode}
        onChange={(event) => setCurrencyCode(event.target.value.toUpperCase())}
      />

      <Checkbox
        label={t('ledger.accounts.contra')}
        hint={t('ledger.accounts.contraHint')}
        checked={contra}
        onChange={setContra}
      />

      {create.isSuccess ? <Banner tone="positive">{t('ledger.accounts.created')}</Banner> : null}

      {presented === null ? null : (
        <Banner tone="danger" title={presented.message}>
          {presented.violations.length === 0 ? null : (
            <ul>
              {presented.violations.map((violation) => (
                <li key={`${violation.field}:${violation.code}`}>
                  {violation.field}: {violation.message}
                </li>
              ))}
            </ul>
          )}
        </Banner>
      )}

      <div className="acc-actions">
        <Button tone="primary" type="submit" busy={create.isPending}>
          {t('action.save')}
        </Button>
        <Button onClick={onClose}>{t('action.cancel')}</Button>
      </div>
    </form>
  );
}
