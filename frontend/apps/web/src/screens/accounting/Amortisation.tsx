/**
 * Amortisation: compute and preview, and nothing else.
 *
 * The endpoint returns entries that are ready to post. It does not post them,
 * and neither does this screen — pressing **Preview** writes nothing at all.
 * Posting is a second, separate, deliberate action with its own confirmation,
 * because a schedule that posts itself is a schedule nobody checked.
 *
 * Until that second action, everything on this screen is a proposal, and it
 * says so where it can be read rather than in a tooltip.
 */
import { isValidAmountInput, parseAmountInput, toPlainString } from '@coreintra/money';
import { Badge, Banner, Button, DataTable, EmptyState, TextField, parseClockFace, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import { Select } from './fields.js';
import { Money } from './money.js';
import {
  accounting,
  keys,
  readInstant,
  type PostEntryRequest,
  type PostingRequest,
  type PreviewRequest,
  type PreviewResponse,
  type WriteBatchRequest,
} from './queries.js';

type Instalment = NonNullable<PreviewResponse['instalments']>[number];
type PendingEntry = NonNullable<PreviewResponse['entries']>[number];
type PreviewPosting = NonNullable<PendingEntry['postings']>[number];

const START_MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

function wholeNumber(text: string): number | null {
  if (!/^\d+$/.test(text.trim())) {
    return null;
  }
  return Number.parseInt(text.trim(), 10);
}

export function AmortisationScreen(): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const queryClient = useQueryClient();

  const [debitAccountId, setDebitAccountId] = useState('');
  const [creditAccountId, setCreditAccountId] = useState('');
  const [clientId, setClientId] = useState('');
  const [description, setDescription] = useState('');
  const [baseAmount, setBaseAmount] = useState('');
  const [residualValue, setResidualValue] = useState('0');
  const [months, setMonths] = useState('12');
  const [startMonth, setStartMonth] = useState('');
  const [roundingDigits, setRoundingDigits] = useState('0');
  const [remainderTo, setRemainderTo] = useState<'START' | 'END'>('END');
  const [postingDay, setPostingDay] = useState('1');
  const [postingClock, setPostingClock] = useState('00:00');
  const [attempted, setAttempted] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const accountsQuery = useQuery({
    queryKey: keys.accounts(bookId ?? ''),
    queryFn: () => accounting.accounts(bookId ?? ''),
    enabled: bookId !== null,
  });
  const clientsQuery = useQuery({
    queryKey: keys.clients(bookId ?? ''),
    queryFn: () => accounting.clients(bookId ?? ''),
    enabled: bookId !== null,
  });
  const postable = (accountsQuery.data ?? []).filter((account) => account.postable === true);

  const preview = useMutation({
    mutationFn: (body: PreviewRequest) => accounting.preview(bookId ?? '', body),
  });

  /** A fresh key per posting attempt, reused while the same schedule is retried. */
  const [idempotencyKey, setIdempotencyKey] = useState(() => globalThis.crypto.randomUUID());
  const postBatch = useMutation({
    mutationFn: (body: WriteBatchRequest) => accounting.writeBatch(bookId ?? '', body, idempotencyKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.batches(bookId ?? '') });
      await queryClient.invalidateQueries({ queryKey: keys.entries(bookId ?? '') });
      setConfirming(false);
      setIdempotencyKey(globalThis.crypto.randomUUID());
      preview.reset();
    },
  });

  const amountBad = baseAmount !== '' && !isValidAmountInput(baseAmount);
  const residualBad = residualValue !== '' && !isValidAmountInput(residualValue);
  const monthsValue = wholeNumber(months);
  const startMonthBad = startMonth !== '' && !START_MONTH.test(startMonth);
  const accountsMissing = debitAccountId === '' || creditAccountId === '';
  const clockSeconds = parseClockFace(postingClock);

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  const runPreview = (event: React.FormEvent): void => {
    event.preventDefault();
    setAttempted(true);
    if (
      accountsMissing ||
      amountBad ||
      residualBad ||
      baseAmount === '' ||
      monthsValue === null ||
      monthsValue < 1 ||
      startMonthBad ||
      startMonth === ''
    ) {
      return;
    }
    const digits = wholeNumber(roundingDigits);
    const day = wholeNumber(postingDay);
    preview.mutate({
      baseAmount: toPlainString(parseAmountInput(baseAmount)),
      debitAccountId,
      creditAccountId,
      description: description.trim(),
      startMonth,
      months: monthsValue,
      remainderTo,
      ...(residualValue === '' ? {} : { residualValue: toPlainString(parseAmountInput(residualValue)) }),
      ...(digits === null ? {} : { roundingDigits: digits }),
      ...(day === null ? {} : { postingDay: day }),
      ...(clockSeconds === null ? {} : { offsetSeconds: clockSeconds }),
      ...(clientId === '' ? {} : { clientId }),
    });
  };

  const result = preview.data;

  const instalmentColumns: readonly Column<Instalment>[] = [
    {
      key: 'number',
      header: t('ledger.amortisation.instalment'),
      numeric: true,
      width: '6rem',
      render: (row) => <span className="ci-numeric">{row.number}</span>,
    },
    { key: 'date', header: t('ledger.amortisation.businessDate'), width: '10rem', render: (row) => row.businessDate ?? '' },
    {
      key: 'amount',
      header: t('ledger.amortisation.instalmentAmount'),
      numeric: true,
      render: (row) => <Money value={row.amount} />,
    },
    {
      key: 'carrying',
      header: t('ledger.amortisation.carryingAmount'),
      numeric: true,
      render: (row) => <Money value={row.carryingAmount} />,
    },
  ];

  const entryColumns: readonly Column<PendingEntry>[] = [
    {
      key: 'postedAt',
      header: t('ledger.entries.columnPostedAt'),
      width: '13rem',
      render: (entry) => {
        const instant = readInstant(entry.postedAt);
        return instant === null ? <span className="acc-muted">—</span> : <span className="ci-numeric">{instant.businessDate}</span>;
      },
    },
    { key: 'description', header: t('ledger.entries.columnDescription'), render: (entry) => entry.description ?? '' },
    {
      key: 'postings',
      header: t('ledger.entries.postings'),
      render: (entry) => (
        <ul className="acc-inline-list">
          {(entry.postings ?? []).map((posting, index) => (
            <li key={`${posting.accountId ?? ''}-${String(index)}`}>
              <span className="ci-numeric">{posting.accountId}</span>{' '}
              {posting.side === 'credit' ? t('accounting.credit') : t('accounting.debit')}{' '}
              <Money value={posting.amount} currencyCode={posting.currencyCode} />
            </li>
          ))}
        </ul>
      ),
    },
  ];

  return (
    <>
      <Banner tone="info" title={t('ledger.amortisation.title')}>
        {t('ledger.amortisation.explain')}
      </Banner>

      <form className="acc-form" onSubmit={runPreview} noValidate>
        <div className="acc-grid">
          <Select label={t('ledger.amortisation.debitAccount')} value={debitAccountId} onChange={setDebitAccountId}>
            <option value="">{t('ledger.entries.accountPick')}</option>
            {postable.map((account) => (
              <option key={account.id ?? ''} value={account.id ?? ''}>
                {account.id} {account.nameKo ?? ''}
              </option>
            ))}
          </Select>

          <Select label={t('ledger.amortisation.creditAccount')} value={creditAccountId} onChange={setCreditAccountId}>
            <option value="">{t('ledger.entries.accountPick')}</option>
            {postable.map((account) => (
              <option key={account.id ?? ''} value={account.id ?? ''}>
                {account.id} {account.nameKo ?? ''}
              </option>
            ))}
          </Select>

          <Select label={t('ledger.amortisation.client')} value={clientId} onChange={setClientId}>
            <option value="">{t('ledger.entries.clientNone')}</option>
            {(clientsQuery.data ?? []).map((client) => (
              <option key={client.id ?? ''} value={client.id ?? ''}>
                {client.name ?? client.id}
              </option>
            ))}
          </Select>

          <TextField
            label={t('ledger.amortisation.description')}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            required
          />

          <TextField
            label={t('ledger.amortisation.baseAmount')}
            value={baseAmount}
            inputMode="decimal"
            onChange={(event) => setBaseAmount(event.target.value)}
            required
            {...(amountBad ? { error: t('ledger.entries.amountInvalid') } : {})}
          />

          <TextField
            label={t('ledger.amortisation.residualValue')}
            value={residualValue}
            inputMode="decimal"
            onChange={(event) => setResidualValue(event.target.value)}
            {...(residualBad ? { error: t('ledger.entries.amountInvalid') } : {})}
          />

          <TextField
            label={t('ledger.amortisation.months')}
            value={months}
            inputMode="numeric"
            onChange={(event) => setMonths(event.target.value)}
            {...(attempted && (monthsValue === null || monthsValue < 1)
              ? { error: t('ledger.amortisation.monthsInvalid') }
              : {})}
          />

          <TextField
            label={t('ledger.amortisation.startMonth')}
            hint={t('ledger.amortisation.startMonthHint')}
            value={startMonth}
            placeholder="2026-01"
            onChange={(event) => setStartMonth(event.target.value)}
            {...(startMonthBad || (attempted && startMonth === '')
              ? { error: t('ledger.amortisation.startMonthInvalid') }
              : {})}
          />

          <TextField
            label={t('ledger.amortisation.roundingDigits')}
            value={roundingDigits}
            inputMode="numeric"
            onChange={(event) => setRoundingDigits(event.target.value)}
          />

          <Select
            label={t('ledger.amortisation.remainderTo')}
            hint={t('ledger.amortisation.remainderHint')}
            value={remainderTo}
            onChange={(value) => setRemainderTo(value === 'START' ? 'START' : 'END')}
          >
            <option value="START">{t('ledger.amortisation.remainder.START')}</option>
            <option value="END">{t('ledger.amortisation.remainder.END')}</option>
          </Select>

          <TextField
            label={t('ledger.amortisation.postingDay')}
            hint={t('ledger.amortisation.postingDayHint')}
            value={postingDay}
            inputMode="numeric"
            onChange={(event) => setPostingDay(event.target.value)}
          />

          <TextField
            label={t('ledger.amortisation.postingClock')}
            hint={t('businessTime.hint')}
            value={postingClock}
            onChange={(event) => setPostingClock(event.target.value)}
            {...(clockSeconds === null ? { error: t('businessTime.invalid') } : {})}
          />
        </div>

        {attempted && accountsMissing ? (
          <Banner tone="danger">{t('ledger.amortisation.accountsRequired')}</Banner>
        ) : null}
        {preview.isError ? <Banner tone="danger">{presentError(preview.error, t).message}</Banner> : null}

        <div className="acc-actions">
          <Button tone="primary" type="submit" busy={preview.isPending}>
            {result === undefined ? t('ledger.amortisation.preview') : t('ledger.amortisation.previewAgain')}
          </Button>
        </div>
      </form>

      {result === undefined ? null : (
        <section className="acc-preview" aria-label={t('ledger.amortisation.schedule')}>
          <div className="acc-entry-facts">
            <Badge tone="warning">{t('ledger.amortisation.nothingPosted')}</Badge>
            <span className="acc-muted">
              {t('ledger.amortisation.total')}: <Money value={result.total} withCurrency />
            </span>
          </div>

          <DataTable
            caption={t('ledger.amortisation.schedule')}
            columns={instalmentColumns}
            rows={result.instalments ?? []}
            rowKey={(row) => String(row.number ?? '')}
            emptyMessage={t('common.empty')}
          />

          <h3 className="acc-section-title">
            {t('ledger.amortisation.entries')} · {t('ledger.amortisation.entryCount', { count: (result.entries ?? []).length })}
          </h3>
          <DataTable
            caption={t('ledger.amortisation.entries')}
            columns={entryColumns}
            rows={result.entries ?? []}
            rowKey={(entry) => `${entry.description ?? ''}-${String(readInstant(entry.postedAt)?.businessDate ?? '')}`}
            emptyMessage={t('common.empty')}
          />

          {confirming ? (
            <Banner tone="warning" actions={ <> <Button tone="primary" busy={postBatch.isPending} onClick={() => postBatch.mutate(toBatch(result, description))} > {t('ledger.amortisation.post')} </Button> <Button onClick={() => setConfirming(false)}>{t('action.cancel')}</Button> </> }>{t('ledger.amortisation.postConfirm', { count: (result.entries ?? []).length })}</Banner>
          ) : (
            <div className="acc-actions">
              <Button onClick={() => setConfirming(true)}>{t('ledger.amortisation.post')}</Button>
            </div>
          )}

          {postBatch.isError ? <Banner tone="danger">{presentError(postBatch.error, t).message}</Banner> : null}
          {postBatch.isSuccess ? <Banner tone="positive">{t('ledger.amortisation.posted')}</Banner> : null}
        </section>
      )}
    </>
  );
}

/**
 * Turns the preview into the batch a person is about to post.
 *
 * The generator parameters travel with it as audit lineage. They are a record
 * of how the figures were arrived at, not something the server will ever run
 * again.
 */
function previewPosting(posting: PreviewPosting): PostingRequest {
  return {
    accountId: posting.accountId ?? '',
    side: posting.side ?? 'debit',
    amount: posting.amount ?? '0',
    ...(posting.baseAmount === undefined ? {} : { baseAmount: posting.baseAmount }),
    ...(posting.currencyCode === undefined ? {} : { currencyCode: posting.currencyCode }),
    ...(posting.clientId === undefined ? {} : { clientId: posting.clientId }),
    ...(posting.memo === undefined ? {} : { memo: posting.memo }),
    ...(posting.rate === undefined ? {} : { rate: posting.rate }),
  };
}

function previewEntry(entry: PendingEntry, label: string): PostEntryRequest {
  return {
    description: entry.description ?? label,
    postings: (entry.postings ?? []).map(previewPosting),
    ...(entry.postedAt === undefined ? {} : { postedAt: entry.postedAt }),
  };
}

function toBatch(preview: PreviewResponse, label: string): WriteBatchRequest {
  const entries: PostEntryRequest[] = (preview.entries ?? []).map((entry) => previewEntry(entry, label));

  return {
    kind: 'AMORTIZATION',
    label,
    entries,
    ...(preview.generatorParams === undefined ? {} : { generatorParams: preview.generatorParams }),
  };
}
