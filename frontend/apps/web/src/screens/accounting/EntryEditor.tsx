/**
 * Writing an entry.
 *
 * The one rule this screen exists to enforce is that debits and credits match
 * **exactly**, measured in the book's base currency. So the difference is on
 * screen from the first keystroke, it is the exact figure rather than the word
 * "unbalanced", and a save while it is non-zero is refused here rather than by
 * the server.
 *
 * The arithmetic never leaves `@coreintra/money`. There is no `Number` in this
 * file, and there cannot be one: a sum of postings that went through a float
 * would balance on screen and fail on the server, which is the worst of the
 * available outcomes.
 *
 * Foreign currency carries both figures because the caller supplies the rate.
 * The rate field records what was used; nothing here multiplies by it and
 * nothing looks one up, and the hint says so, because a field called "rate"
 * beside an empty "base amount" invites the assumption that one fills the
 * other.
 */
import { businessInstant, type BusinessInstant } from '@coreintra/business-time';
import {
  ZERO,
  addDecimals,
  compareDecimals,
  formatGrouped,
  isValidAmountInput,
  parseAmountInput,
  subtractDecimals,
  toPlainString,
  type Decimal,
} from '@coreintra/money';
import { Banner, BusinessInstantField, Button, TextField } from '@coreintra/ui';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import { Checkbox, Select } from './fields.js';
import { accounting, keys, type Account, type Client, type PostingRequest } from './queries.js';
import { useQuery } from '@tanstack/react-query';

export type Side = 'debit' | 'credit';

export interface PostingDraft {
  readonly key: string;
  readonly accountId: string;
  readonly side: Side;
  readonly amount: string;
  readonly currencyCode: string;
  readonly baseAmount: string;
  readonly rate: string;
  readonly memo: string;
  readonly clientId: string;
}

export interface EntryDraft {
  readonly description: string;
  readonly postedAt: BusinessInstant | null;
  readonly clientId: string;
  readonly draft: boolean;
  readonly postings: readonly PostingDraft[];
}

let sequence = 0;

/** A stable React key per line, so reordering never reuses one. */
export function postingKey(): string {
  sequence += 1;
  return `line-${sequence}`;
}

export function emptyPosting(side: Side): PostingDraft {
  return {
    key: postingKey(),
    accountId: '',
    side,
    amount: '',
    currencyCode: '',
    baseAmount: '',
    rate: '',
    memo: '',
    clientId: '',
  };
}

export function emptyDraft(): EntryDraft {
  return {
    description: '',
    postedAt: null,
    clientId: '',
    draft: false,
    postings: [emptyPosting('debit'), emptyPosting('credit')],
  };
}

/** True when this line is denominated in something other than the book's base currency. */
function isForeign(line: PostingDraft, baseCurrencyCode: string): boolean {
  return line.currencyCode !== '' && line.currencyCode !== baseCurrencyCode;
}

/** The figure the balance is measured on: always the base-currency one. */
function baseText(line: PostingDraft, baseCurrencyCode: string): string {
  return isForeign(line, baseCurrencyCode) ? line.baseAmount : line.amount;
}

export interface BalanceState {
  /** Debits minus credits, over every line that parses. */
  readonly difference: Decimal;
  /** False when a line is missing an account or an amount the balance depends on. */
  readonly complete: boolean;
  readonly balanced: boolean;
}

export function balanceOf(postings: readonly PostingDraft[], baseCurrencyCode: string): BalanceState {
  let difference = ZERO;
  let complete = postings.length >= 2;

  for (const line of postings) {
    const text = baseText(line, baseCurrencyCode);
    if (line.accountId === '' || !isValidAmountInput(text)) {
      complete = false;
      continue;
    }
    const value = parseAmountInput(text);
    difference = line.side === 'debit' ? addDecimals(difference, value) : subtractDecimals(difference, value);
  }

  return { difference, complete, balanced: complete && compareDecimals(difference, ZERO) === 0 };
}

export function toPostingRequests(postings: readonly PostingDraft[], baseCurrencyCode: string): PostingRequest[] {
  return postings.map((line) => ({
    accountId: line.accountId,
    side: line.side,
    amount: toPlainString(parseAmountInput(line.amount)),
    ...(isForeign(line, baseCurrencyCode) && isValidAmountInput(line.baseAmount)
      ? { baseAmount: toPlainString(parseAmountInput(line.baseAmount)) }
      : {}),
    ...(line.currencyCode === '' ? {} : { currencyCode: line.currencyCode }),
    ...(line.rate.trim() === '' ? {} : { rate: line.rate.trim() }),
    ...(line.memo.trim() === '' ? {} : { memo: line.memo.trim() }),
    ...(line.clientId === '' ? {} : { clientId: line.clientId }),
  }));
}

export interface EntryEditorProps {
  readonly variant: 'create' | 'correct';
  readonly initial: EntryDraft;
  readonly busy: boolean;
  readonly error: unknown;
  readonly onSubmit: (draft: EntryDraft, reason: string) => void;
  readonly onCancel?: () => void;
}

export function EntryEditor({ variant, initial, busy, error, onSubmit, onCancel }: EntryEditorProps): ReactNode {
  const { t } = useTranslation();
  const { bookId, baseCurrencyCode, currencies } = useBook();

  const [draft, setDraft] = useState<EntryDraft>(initial);
  const [reason, setReason] = useState('');
  const [attempted, setAttempted] = useState(false);

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

  const postable = useMemo(
    () => (accountsQuery.data ?? []).filter((account) => account.postable === true),
    [accountsQuery.data],
  );
  const clients = useMemo(() => clientsQuery.data ?? [], [clientsQuery.data]);

  const balance = useMemo(() => balanceOf(draft.postings, baseCurrencyCode), [draft.postings, baseCurrencyCode]);

  const patchLine = (key: string, patch: Partial<PostingDraft>): void => {
    setDraft((current) => ({
      ...current,
      postings: current.postings.map((line) => (line.key === key ? { ...line, ...patch } : line)),
    }));
  };

  const reasonMissing = variant === 'correct' && reason.trim() === '';
  const descriptionMissing = draft.description.trim() === '';
  const presented = error === null || error === undefined ? null : presentError(error, t);

  const submit = (event: React.FormEvent): void => {
    event.preventDefault();
    setAttempted(true);
    if (!balance.balanced || descriptionMissing || reasonMissing) {
      // Refused here, on purpose. The server would refuse it too, but a person
      // who has just typed twelve lines deserves to be told before the round
      // trip and to keep what they typed.
      return;
    }
    onSubmit(draft, reason.trim());
  };

  return (
    <form className="acc-form" onSubmit={submit} noValidate>
      <TextField
        label={t('ledger.entries.description')}
        value={draft.description}
        onChange={(event) => setDraft((current) => ({ ...current, description: event.target.value }))}
        required
        {...(attempted && descriptionMissing ? { error: t('ledger.entries.descriptionRequired') } : {})}
      />

      <BusinessInstantField
        value={draft.postedAt}
        onChange={(value) => setDraft((current) => ({ ...current, postedAt: value }))}
        labels={{
          legend: variant === 'correct' ? t('ledger.entries.postedAt') : t('ledger.instant.legend'),
          businessDate: t('businessTime.businessDate'),
          clock: t('businessTime.clock'),
          hint: t('businessTime.hint'),
          outsideCalendarDay: (resolved: string) => t('ledger.instant.resolved', { resolved }),
          invalid: t('businessTime.invalid'),
        }}
      />

      <Select
        label={t('ledger.entries.client')}
        hint={t('ledger.entries.clientHint')}
        value={draft.clientId}
        onChange={(value) => setDraft((current) => ({ ...current, clientId: value }))}
      >
        <option value="">{t('ledger.entries.clientNone')}</option>
        {clients.map((client) => (
          <option key={client.id ?? ''} value={client.id ?? ''}>
            {client.name ?? client.id}
          </option>
        ))}
      </Select>

      <table className="ci-table acc-postings">
        <caption className="ci-visually-hidden">{t('ledger.entries.postings')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('ledger.entries.account')}</th>
            <th scope="col">{t('accounting.debit')} / {t('accounting.credit')}</th>
            <th scope="col" className="ci-numeric">
              {t('ledger.entries.amount')}
            </th>
            <th scope="col">{t('common.currency')}</th>
            <th scope="col" className="ci-numeric">
              {t('ledger.entries.baseAmount')}
            </th>
            <th scope="col" className="ci-numeric">
              {t('ledger.entries.rate')}
            </th>
            <th scope="col">{t('ledger.entries.memo')}</th>
            <th scope="col">{t('ledger.entries.client')}</th>
            <th scope="col" />
          </tr>
        </thead>
        <tbody>
          {draft.postings.map((line) => (
            <PostingRow
              key={line.key}
              line={line}
              accounts={postable}
              clients={clients}
              currencyCodes={currencies.map((currency) => currency.code ?? '').filter((code) => code !== '')}
              baseCurrencyCode={baseCurrencyCode}
              attempted={attempted}
              onChange={(patch) => patchLine(line.key, patch)}
              onRemove={
                draft.postings.length > 2
                  ? () =>
                      setDraft((current) => ({
                        ...current,
                        postings: current.postings.filter((candidate) => candidate.key !== line.key),
                      }))
                  : undefined
              }
            />
          ))}
        </tbody>
      </table>

      <p className="acc-note">{t('ledger.entries.baseAmountHint')}</p>

      <div className="acc-actions">
        <Button
          onClick={() =>
            setDraft((current) => ({ ...current, postings: [...current.postings, emptyPosting('debit')] }))
          }
        >
          {t('ledger.entries.addPosting')}
        </Button>
      </div>

      <BalanceNotice balance={balance} />

      {variant === 'correct' ? (
        <>
          <Banner tone="info" title={t('ledger.entries.correctTitle')}>
            {t('ledger.entries.correctExplain')}
          </Banner>
          <TextField
            label={t('ledger.entries.reason')}
            hint={t('ledger.entries.reasonHint')}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            required
            {...(attempted && reasonMissing ? { error: t('ledger.entries.reasonRequired') } : {})}
          />
        </>
      ) : (
        <Checkbox
          label={t('ledger.entries.draft')}
          hint={t('ledger.entries.draftHint')}
          checked={draft.draft}
          onChange={(checked) => setDraft((current) => ({ ...current, draft: checked }))}
        />
      )}

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
        <Button tone="primary" type="submit" busy={busy}>
          {variant === 'correct' ? t('ledger.entries.correctSubmit') : t('ledger.entries.save')}
        </Button>
        {onCancel === undefined ? null : <Button onClick={onCancel}>{t('action.cancel')}</Button>}
      </div>
    </form>
  );
}

/**
 * The running difference.
 *
 * This is the one figure in the accounting area that does not go through
 * `<Amount>`, and the reason is the rule `<Amount>` exists to serve rather than
 * an exception to it. A difference of 0.004 shown at KRW's zero decimals reads
 * as a balanced zero. `formatGrouped` shows every digit, always — strictly more
 * than `<Amount>` would, never less — because a balance check that rounds is
 * not a balance check.
 */
function BalanceNotice({ balance }: { readonly balance: BalanceState }): ReactNode {
  const { t } = useTranslation();
  const { baseCurrencyCode } = useBook();

  if (balance.balanced) {
    return (
      <Banner tone="positive" title={t('ledger.entries.balanced')}>
        {t('ledger.entries.baseNote', { code: baseCurrencyCode })}
      </Banner>
    );
  }

  return (
    <Banner
      tone="danger"
      title={t('ledger.entries.unbalanced', { difference: formatGrouped(balance.difference) })}
    >
      <p>{t('ledger.entries.baseNote', { code: baseCurrencyCode })}</p>
      {balance.complete ? null : <p>{t('ledger.entries.minimumPostings')}</p>}
      {/*
        The difference is shown exactly and never at the currency's display
        precision. Rounding it is what makes a 0.4 gap look like a balanced
        zero in a currency that prints no decimals, which is the entire failure
        this rule exists to catch.
      */}
      <p>
        {t('ledger.entries.difference')}:{' '}
        <strong className="ci-numeric">{formatGrouped(balance.difference)}</strong>
      </p>
    </Banner>
  );
}

function PostingRow({
  line,
  accounts,
  clients,
  currencyCodes,
  baseCurrencyCode,
  attempted,
  onChange,
  onRemove,
}: {
  readonly line: PostingDraft;
  readonly accounts: readonly Account[];
  readonly clients: readonly Client[];
  readonly currencyCodes: readonly string[];
  readonly baseCurrencyCode: string;
  readonly attempted: boolean;
  readonly onChange: (patch: Partial<PostingDraft>) => void;
  readonly onRemove?: (() => void) | undefined;
}): ReactNode {
  const { t } = useTranslation();
  const foreign = isForeign(line, baseCurrencyCode);
  const amountBad = line.amount !== '' && !isValidAmountInput(line.amount);
  const baseBad = foreign && line.baseAmount !== '' && !isValidAmountInput(line.baseAmount);
  const baseMissing = attempted && foreign && line.baseAmount === '';

  return (
    <tr>
      <td>
        <select
          className="ci-field__input"
          aria-label={t('ledger.entries.account')}
          value={line.accountId}
          onChange={(event) => onChange({ accountId: event.target.value })}
        >
          <option value="">{t('ledger.entries.accountPick')}</option>
          {accounts.map((account) => (
            <option key={account.id ?? ''} value={account.id ?? ''}>
              {account.id} {account.nameKo ?? account.nameEn ?? ''}
            </option>
          ))}
        </select>
      </td>
      <td>
        <select
          className="ci-field__input"
          aria-label={t('accounting.posting')}
          value={line.side}
          onChange={(event) => onChange({ side: event.target.value === 'credit' ? 'credit' : 'debit' })}
        >
          <option value="debit">{t('accounting.debit')}</option>
          <option value="credit">{t('accounting.credit')}</option>
        </select>
      </td>
      <td className="ci-numeric">
        <input
          className="ci-field__input ci-numeric"
          inputMode="decimal"
          aria-label={t('ledger.entries.amount')}
          aria-invalid={amountBad ? true : undefined}
          value={line.amount}
          onChange={(event) => onChange({ amount: event.target.value })}
        />
        {amountBad ? <p className="ci-field__error">{t('ledger.entries.amountInvalid')}</p> : null}
      </td>
      <td>
        <select
          className="ci-field__input"
          aria-label={t('common.currency')}
          value={line.currencyCode}
          onChange={(event) => onChange({ currencyCode: event.target.value })}
        >
          <option value="">{baseCurrencyCode}</option>
          {currencyCodes.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </td>
      <td className="ci-numeric">
        {foreign ? (
          <>
            <input
              className="ci-field__input ci-numeric"
              inputMode="decimal"
              aria-label={t('ledger.entries.baseAmount')}
              aria-invalid={baseBad ? true : undefined}
              value={line.baseAmount}
              onChange={(event) => onChange({ baseAmount: event.target.value })}
            />
            {baseBad ? <p className="ci-field__error">{t('ledger.entries.amountInvalid')}</p> : null}
            {baseMissing ? <p className="ci-field__error">{t('ledger.entries.baseAmountRequired')}</p> : null}
          </>
        ) : (
          <span className="acc-muted">—</span>
        )}
      </td>
      <td className="ci-numeric">
        {foreign ? (
          <input
            className="ci-field__input ci-numeric"
            aria-label={t('ledger.entries.rate')}
            title={t('ledger.entries.rateHint')}
            value={line.rate}
            onChange={(event) => onChange({ rate: event.target.value })}
          />
        ) : (
          <span className="acc-muted">—</span>
        )}
      </td>
      <td>
        <input
          className="ci-field__input"
          aria-label={t('ledger.entries.memo')}
          value={line.memo}
          onChange={(event) => onChange({ memo: event.target.value })}
        />
      </td>
      <td>
        <select
          className="ci-field__input"
          aria-label={t('ledger.entries.client')}
          value={line.clientId}
          onChange={(event) => onChange({ clientId: event.target.value })}
        >
          <option value="">{t('ledger.entries.clientNone')}</option>
          {clients.map((client) => (
            <option key={client.id ?? ''} value={client.id ?? ''}>
              {client.name ?? client.id}
            </option>
          ))}
        </select>
      </td>
      <td>
        {onRemove === undefined ? null : (
          <Button onClick={onRemove} aria-label={t('ledger.entries.removePosting')}>
            ×
          </Button>
        )}
      </td>
    </tr>
  );
}

/** Today, at the start of the business day, as a default for a new entry. */
export function startOfToday(): BusinessInstant {
  const now = new Date();
  const iso = `${String(now.getFullYear())}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  return businessInstant(iso, 0);
}
