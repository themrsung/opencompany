import { Amount, Banner, Button, EmptyState, TextField } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';
import { useTranslation } from 'react-i18next';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { StateBadge, stepLabel } from './presentation.js';
import {
  actedAtNow,
  approvalKeys,
  approvals,
  bucketRows,
  BUCKETS,
  currentStep,
  daysWaiting,
  displayDecimalsFor,
  idempotencyKeyFor,
  nameFromDocument,
  NoStepForYou,
  operationId,
  pendingStepFor,
  releaseIdempotencyKey,
  type Bucket,
  type InboxRow,
} from './queries.js';
import { useCompanyChoice } from './useCompanyChoice.js';
import './approvals.css';

/**
 * 결재함 — the screen §12 says decides whether people like this product.
 *
 * Three things follow from that and shape everything below.
 *
 * **One request.** The whole inbox — three lists and the counts — arrives from
 * `/approvals/inbox` in a single round trip. The only other call this screen
 * makes is for the row under the cursor, and that one is what makes 승인 and
 * 반려 possible at all: the list summary carries no step, so the step being
 * approved has to be read from the document itself.
 *
 * **The keyboard is the interface.** J/K move, Enter opens, A approves, R
 * returns. Somebody clearing twenty documents never reaches for the mouse, and
 * the shortcuts are printed on the screen rather than left to be discovered.
 *
 * **A 반려 asks for its reason first.** The server refuses one without a
 * reason, and finding that out after pressing the button is a decision wasted;
 * here the reason field is part of pressing it.
 */
export function InboxScreen(): ReactNode {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { companies, companyId, choose } = useCompanyChoice();

  const inbox = useQuery({
    queryKey: approvalKeys.inbox(companyId ?? ''),
    queryFn: () => approvals.inbox(companyId ?? ''),
    enabled: companyId !== null,
  });

  const [bucket, setBucket] = useState<Bucket>('awaitingMe');
  const [cursor, setCursor] = useState(0);
  const [returning, setReturning] = useState<InboxRow | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const rowRefs = useRef<Array<HTMLTableRowElement | null>>([]);

  const buckets = useMemo(() => bucketRows(inbox.data), [inbox.data]);
  const rows = buckets[bucket];
  const accountId = inbox.data?.accountId;
  const counts = inbox.data?.counts;
  const amountLabels = useMemo(
    () => ({ roundedNotice: (exact: string) => t('common.roundedNotice', { exact }) }),
    [t],
  );

  useEffect(() => {
    setCursor(0);
    setReturning(null);
  }, [bucket]);

  // A document approved out of the list takes its row with it.
  useEffect(() => {
    setCursor((current) => Math.min(current, Math.max(0, rows.length - 1)));
  }, [rows.length]);

  const focusRow = useCallback((index: number) => {
    setCursor(index);
    rowRefs.current[index]?.focus();
  }, []);

  /**
   * The step to act on, fetched rather than assumed.
   *
   * `fetchQuery` rather than a bare call, so the row under the cursor — whose
   * document this screen has already loaded — acts without a second round trip.
   */
  const stepIdFor = useCallback(
    async (row: InboxRow): Promise<string> => {
      const detail = await queryClient.fetchQuery({
        queryKey: approvalKeys.document(row.id),
        queryFn: () => approvals.document(row.id),
      });
      const step = pendingStepFor(detail, accountId);
      if (step?.id === undefined) {
        throw new NoStepForYou();
      }
      return step.id;
    },
    [accountId, queryClient],
  );

  const settle = useCallback(
    (operation: string) => {
      releaseIdempotencyKey(operation);
      setFailure(null);
      void queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
    [queryClient],
  );

  const approve = useMutation({
    mutationFn: async (row: InboxRow) => {
      const stepId = await stepIdFor(row);
      const operation = operationId('approve', row.id, stepId);
      await approvals.approve(
        row.id,
        stepId,
        { actedAt: actedAtNow() },
        idempotencyKeyFor(operation),
      );
      return operation;
    },
    onSuccess: settle,
    onError: (error: unknown) => {
      setFailure(error);
    },
  });

  const sendBack = useMutation({
    mutationFn: async ({ row, reason }: { row: InboxRow; reason: string }) => {
      const stepId = await stepIdFor(row);
      const operation = operationId('return', row.id, stepId);
      await approvals.returnToDrafter(
        row.id,
        stepId,
        { actedAt: actedAtNow(), reason },
        idempotencyKeyFor(operation),
      );
      return operation;
    },
    onSuccess: (operation) => {
      settle(operation);
      setReturning(null);
    },
    onError: (error: unknown) => {
      setFailure(error);
    },
  });

  const open = useCallback(
    (row: InboxRow) => {
      void navigate({ to: `/approvals/${row.id}` });
    },
    [navigate],
  );

  const actionable = bucket === 'awaitingMe';

  /**
   * The server caps each list and says where the cap was. A list that came back
   * exactly that long may be missing rows, and quietly showing the first fifty
   * of an unknown number is how somebody's document waits a week.
   */
  const listLimit = inbox.data?.listLimit;
  const truncatedAt =
    listLimit !== undefined && (inbox.data?.[bucket]?.length ?? 0) >= listLimit ? listLimit : null;

  const onKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLTableElement>) => {
      // Somebody typing a 반려 사유 is typing, not pressing shortcuts.
      const target = event.target;
      if (target instanceof HTMLElement && EDITABLE.has(target.tagName)) {
        return;
      }
      const row = rows[cursor];
      const key = event.key.toLowerCase();

      if (key === 'j' || event.key === 'ArrowDown') {
        focusRow(Math.min(cursor + 1, rows.length - 1));
      } else if (key === 'k' || event.key === 'ArrowUp') {
        focusRow(Math.max(cursor - 1, 0));
      } else if (event.key === 'Home') {
        focusRow(0);
      } else if (event.key === 'End') {
        focusRow(rows.length - 1);
      } else if (event.key === 'Enter' && row !== undefined) {
        open(row);
      } else if (key === 'a' && row !== undefined && actionable) {
        approve.mutate(row);
      } else if (key === 'r' && row !== undefined && actionable) {
        setReturning(row);
      } else {
        return;
      }
      event.preventDefault();
    },
    [actionable, approve, cursor, focusRow, open, rows],
  );

  const presented =
    failure === null
      ? null
      : failure instanceof NoStepForYou
        ? { message: t('approvalDoc.noStepForYou'), violations: [] }
        : presentError(failure, t);

  return (
    <Page
      title={t('inbox.title')}
      actions={
        companies.length > 1 ? (
          <label className="ci-field inbox-company">
            <span className="ci-field__label">{t('common.company')}</span>
            <select
              className="ci-field__input"
              value={companyId ?? ''}
              onChange={(event) => {
                choose(event.target.value);
              }}
            >
              {companies.map((company) => (
                <option key={company.id} value={company.id}>
                  {company.nameKo ?? company.nameEn ?? company.id}
                </option>
              ))}
            </select>
          </label>
        ) : undefined
      }
    >
      <p className="inbox-shortcuts" role="note">
        {t('inbox.shortcutHint')}
      </p>

      {counts === undefined ? null : (
        <p className="inbox-chips">
          <span>
            {t('inbox.inProgressByMe')}{' '}
            <strong className="ci-numeric">{counts.inProgressByMe ?? 0}</strong>
          </span>
          <span>
            {t('inbox.returnedToMe')}{' '}
            <strong className="ci-numeric">{counts.returnedToMe ?? 0}</strong>
          </span>
        </p>
      )}

      {presented === null ? null : (
        <Banner
          tone="danger"
          title={presented.message}
          onDismiss={() => {
            setFailure(null);
          }}
          dismissLabel={t('action.close')}
        >
          <ul className="inbox-violations">
            {presented.violations.map((violation) => (
              <li key={`${violation.field}:${violation.code}`}>
                {violation.field} — {violation.message}
              </li>
            ))}
          </ul>
        </Banner>
      )}

      {inbox.isError ? (
        <Banner
          tone="danger"
          title={presentError(inbox.error, t).message}
          actions={
            <Button
              onClick={() => {
                void inbox.refetch();
              }}
            >
              {t('action.retry')}
            </Button>
          }
        >
          {t('error.unexpected')}
        </Banner>
      ) : null}

      <div className="inbox-tabs" role="tablist" aria-label={t('inbox.title')}>
        {BUCKETS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            id={`inbox-tab-${name}`}
            aria-selected={bucket === name}
            aria-controls="inbox-list"
            className={bucket === name ? 'inbox-tab inbox-tab--current' : 'inbox-tab'}
            onClick={() => {
              setBucket(name);
            }}
          >
            {t(BUCKET_LABELS[name])}{' '}
            <span className="ci-numeric inbox-tab__count">{buckets[name].length}</span>
          </button>
        ))}
      </div>

      {truncatedAt === null ? null : (
        <p className="appr-muted inbox-shortcuts">{t('inbox.truncated', { count: truncatedAt })}</p>
      )}

      <div
        className="page__surface"
        id="inbox-list"
        role="tabpanel"
        aria-labelledby={`inbox-tab-${bucket}`}
      >
        {inbox.isPending ? (
          // Not the empty state: "nothing is waiting for you" is a claim, and
          // making it before the answer has arrived is how somebody closes the
          // tab on a document that is in fact waiting for them.
          <p className="appr-muted inbox-loading" role="status">
            {t('app.loading')}
          </p>
        ) : rows.length === 0 ? (
          <EmptyState message={t(EMPTY_MESSAGES[bucket])} />
        ) : (
          <table className="ci-table inbox-table" onKeyDown={onKeyDown}>
            <caption className="ci-visually-hidden">
              {t(BUCKET_LABELS[bucket])} — {t('inbox.shortcutHint')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('inbox.columnTitle')}</th>
                <th scope="col">{t('inbox.columnType')}</th>
                <th scope="col">{t('inbox.columnDrafter')}</th>
                <th scope="col" className="ci-numeric">
                  {t('inbox.columnAmount')}
                </th>
                <th scope="col" className="ci-numeric">
                  {t('inbox.columnWaiting')}
                </th>
                <th scope="col">{t('inbox.columnStep')}</th>
                <th scope="col">{t('inbox.columnState')}</th>
                {actionable ? <th scope="col">{t('inbox.columnActions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <Row
                  key={row.id}
                  row={row}
                  index={index}
                  focused={index === cursor}
                  actionable={actionable}
                  busy={approve.isPending && approve.variables.id === row.id}
                  amountLabels={amountLabels}
                  onFocus={focusRow}
                  onOpen={open}
                  onApprove={(target) => {
                    approve.mutate(target);
                  }}
                  onReturn={setReturning}
                  register={(element) => {
                    rowRefs.current[index] = element;
                  }}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {returning === null ? null : (
        <ReturnPanel
          row={returning}
          busy={sendBack.isPending}
          onCancel={() => {
            setReturning(null);
            focusRow(cursor);
          }}
          onSubmit={(reason) => {
            sendBack.mutate({ row: returning, reason });
          }}
        />
      )}
    </Page>
  );
}

const EDITABLE = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

const BUCKET_LABELS: Readonly<Record<Bucket, string>> = {
  awaitingMe: 'inbox.waitingOnMe',
  draftedByMe: 'inbox.draftedByMe',
  copiedToMe: 'inbox.copiedToMe',
};

const EMPTY_MESSAGES: Readonly<Record<Bucket, string>> = {
  awaitingMe: 'inbox.emptyWaiting',
  draftedByMe: 'inbox.emptyDrafted',
  copiedToMe: 'inbox.emptyCopied',
};

interface RowProps {
  readonly row: InboxRow;
  readonly index: number;
  readonly focused: boolean;
  readonly actionable: boolean;
  readonly busy: boolean;
  readonly amountLabels: { readonly roundedNotice: (exact: string) => string };
  readonly onFocus: (index: number) => void;
  readonly onOpen: (row: InboxRow) => void;
  readonly onApprove: (row: InboxRow) => void;
  readonly onReturn: (row: InboxRow) => void;
  readonly register: (element: HTMLTableRowElement | null) => void;
}

/**
 * One row, and the only place this screen fans out.
 *
 * The row under the cursor loads its own document; every other row renders
 * from whatever is already in the cache. The list endpoint's summary carries
 * neither the current step nor the drafter's name, so without this the step
 * column would be permanently empty and A would have nothing to approve.
 * Doing it for the focused row alone keeps it to one request per row somebody
 * actually looks at, and walking back up the list costs nothing.
 */
function Row({
  row,
  index,
  focused,
  actionable,
  busy,
  amountLabels,
  onFocus,
  onOpen,
  onApprove,
  onReturn,
  register,
}: RowProps): ReactNode {
  const { t } = useTranslation();
  const detail = useQuery({
    queryKey: approvalKeys.document(row.id),
    queryFn: () => approvals.document(row.id),
    enabled: focused,
  });

  const step = currentStep(detail.data);
  const drafter = nameFromDocument(detail.data, row.drafterAccountId);
  const waiting = daysWaiting(row.submittedAt);

  return (
    <tr
      ref={register}
      tabIndex={focused ? 0 : -1}
      aria-current={focused ? 'true' : undefined}
      className={focused ? 'inbox-row inbox-row--cursor' : 'inbox-row'}
      onFocus={() => {
        onFocus(index);
      }}
      onClick={() => {
        onFocus(index);
      }}
      onDoubleClick={() => {
        onOpen(row);
      }}
    >
      <td>
        {/*
         * A real link, so the mouse has the same affordance the keyboard has
         * and a middle click opens a tab. Taken out of the tab order because
         * the row itself is what Tab lands on — otherwise clearing an inbox
         * from the keyboard would mean two stops per row.
         */}
        <Link
          to={`/approvals/${row.id}`}
          tabIndex={-1}
          title={t('inbox.openDocument')}
          className="inbox-row__open"
        >
          {row.title ?? row.id}
        </Link>
      </td>
      <td>{row.documentType ?? '—'}</td>
      <td>
        {drafter ?? (
          <span className="appr-muted" title={t('inbox.drafterPending')}>
            —
          </span>
        )}
      </td>
      <td className="ci-numeric">
        {row.amount === undefined ? (
          '—'
        ) : (
          <Amount
            value={row.amount}
            displayDecimals={displayDecimalsFor(row.currencyCode)}
            {...(row.currencyCode === undefined ? {} : { currencyCode: row.currencyCode })}
            labels={amountLabels}
          />
        )}
      </td>
      <td className="ci-numeric">
        {waiting === null ? '—' : t('inbox.overdueSince', { days: waiting })}
      </td>
      <td>
        {step === null ? (
          <span className="appr-muted">{detail.isFetching ? t('inbox.stepPending') : '—'}</span>
        ) : (
          stepLabel(step, t)
        )}
      </td>
      <td>
        <StateBadge state={row.state} />
      </td>
      {actionable ? (
        <td className="inbox-row__actions">
          <Button
            tone="primary"
            busy={busy}
            onClick={() => {
              onApprove(row);
            }}
          >
            {t('action.approve')}
          </Button>
          <Button
            tone="danger"
            onClick={() => {
              onReturn(row);
            }}
          >
            {t('action.reject')}
          </Button>
        </td>
      ) : null}
    </tr>
  );
}

interface ReturnPanelProps {
  readonly row: InboxRow;
  readonly busy: boolean;
  readonly onCancel: () => void;
  readonly onSubmit: (reason: string) => void;
}

/**
 * 반려, with its reason.
 *
 * Not a modal: somebody clearing an inbox is mid-flow, and a dialog that takes
 * the page to ask one question costs more than it protects. It behaves like one
 * where that matters — focus moves in, Escape closes it and puts focus back on
 * the row — and 반려 stays disabled until there is something to send, so the
 * refusal the server would give never has to happen.
 */
function ReturnPanel({ row, busy, onCancel, onSubmit }: ReturnPanelProps): ReactNode {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const form = useRef<HTMLFormElement | null>(null);

  useEffect(() => {
    form.current?.querySelector('input')?.focus();
  }, []);

  const ready = reason.trim() !== '';

  return (
    <form
      ref={form}
      className="page__surface inbox-return"
      aria-label={t('inbox.returnReasonLabel')}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          onCancel();
        }
      }}
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) {
          onSubmit(reason.trim());
        }
      }}
    >
      <h2 className="inbox-return__title">{row.title ?? row.id}</h2>
      <TextField
        label={t('inbox.returnReasonLabel')}
        placeholder={t('inbox.returnReasonPlaceholder')}
        hint={t('inbox.returnHint')}
        required
        value={reason}
        onChange={(event) => {
          setReason(event.target.value);
        }}
      />
      <div className="inbox-return__actions">
        <Button type="submit" tone="danger" disabled={!ready} busy={busy}>
          {t('action.reject')}
        </Button>
        <Button onClick={onCancel}>{t('action.cancel')}</Button>
      </div>
    </form>
  );
}
