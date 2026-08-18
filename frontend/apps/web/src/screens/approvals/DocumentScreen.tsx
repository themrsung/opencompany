import { Amount, Badge, Banner, BusinessInstantText, Button, TextField } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import { useCallback, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { actionLabel, StateBadge, stepKindLabel, stepStateLabel } from './presentation.js';
import {
  actedAtNow,
  approvalKeys,
  approvals,
  currentStep,
  displayDecimalsFor,
  idempotencyKeyFor,
  nameFromDocument,
  operationId,
  readInstant,
  releaseIdempotencyKey,
  type Step,
  type TrailAction,
} from './queries.js';
import './approvals.css';

/**
 * One 결재 document: the typed fields, the resolved 결재선, and the trail.
 *
 * All three in one request, because a line read a moment after the document
 * can already have been signed. Two things here are load-bearing rather than
 * decorative:
 *
 *  - **대결 never reads as the absent approver signing.** The trail says who
 *    acted and, separately, whom they acted for. A row that rendered
 *    `onBehalfOfAccountId` as the actor would be a forged signature in the
 *    only record anyone will ever consult.
 *  - **전결 leaves the skipped steps marked SKIPPED**, not approved. The step
 *    table says so in words, because an auditor reading a green tick against a
 *    step nobody signed is exactly the misreading 전결 invites.
 */
export function DocumentScreen(): ReactNode {
  const { t } = useTranslation();
  const params = useParams({ strict: false });
  const documentId = typeof params.documentId === 'string' ? params.documentId : '';
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const document = useQuery({
    queryKey: approvalKeys.document(documentId),
    queryFn: () => approvals.document(documentId),
    enabled: documentId !== '',
  });

  const detail = document.data;
  const summary = detail?.document;
  // The step the document is waiting at. Whether it is waiting on *this*
  // reader is `awaitingMe`, which the server decides — this screen can be
  // opened by a link, and it has no way to know which account it is.
  const step = currentStep(detail);

  const settle = useCallback(
    (operation: string) => {
      releaseIdempotencyKey(operation);
      setFailure(null);
      void queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
    [queryClient],
  );

  const act = useMutation({
    mutationFn: async ({ verb, stepId, reason }: ActRequest): Promise<string> => {
      const operation = operationId(verb, documentId, stepId);
      const key = idempotencyKeyFor(operation);
      const actedAt = actedAtNow();
      if (verb === 'approve') {
        await approvals.approve(documentId, stepId, { actedAt }, key);
      } else if (verb === 'return') {
        await approvals.returnToDrafter(documentId, stepId, { actedAt, reason }, key);
      } else if (verb === 'hold') {
        await approvals.hold(documentId, stepId, { actedAt, reason }, key);
      } else {
        await approvals.delegateFinal(documentId, stepId, { actedAt, reason }, key);
      }
      return operation;
    },
    onSuccess: (operation, request) => {
      settle(operation);
      // The verb on the button is the verb in the confirmation, so nobody has
      // to work out whether 보류 and "held" are the same thing.
      setDone(VERB_LABELS[request.verb]);
    },
    onError: (error: unknown) => {
      setFailure(error);
    },
  });

  if (document.isPending) {
    return <Page title={t('approvalDoc.loading')}>{null}</Page>;
  }

  if (detail === undefined || summary === undefined) {
    return (
      <Page title={t('approvalDoc.notFound')}>
        <Banner tone="danger" title={presentError(document.error, t).message}>
          {t('approvalDoc.notFound')}
        </Banner>
      </Page>
    );
  }

  const presented = failure === null ? null : presentError(failure, t);
  const representation = detail.representation;

  return (
    <Page title={summary.title ?? documentId} actions={<StateBadge state={summary.state} />}>
      <p className="document-note">
        <Link to="/" className="inbox-row__open">
          ← {t('approvalDoc.backToInbox')}
        </Link>
      </p>

      {done === null ? null : (
        <Banner
          tone="positive"
          title={t('approvalDoc.actionResult', { action: t(done) })}
          onDismiss={() => {
            setDone(null);
          }}
          dismissLabel={t('action.close')}
        >
          {detail.lineSummary ?? ''}
        </Banner>
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

      {detail.awaitingMe === true ? (
        <Banner tone="info" title={t('approvalDoc.awaitingYou')}>
          {detail.lineSummary ?? ''}
        </Banner>
      ) : null}

      {representation?.kind === 'JOINT' ? (
        <Banner tone="warning" title={t('approvalDoc.representation.JOINT')}>
          {t('approval.quorum', {
            approved: step?.approvalsGiven ?? 0,
            required: representation.requiredApprovals ?? 0,
          })}
        </Banner>
      ) : null}

      <dl className="document-fields page__surface">
        <div>
          <dt>{t('approvalDoc.documentId')}</dt>
          <dd className="ci-numeric">{summary.id ?? documentId}</dd>
        </div>
        <div>
          <dt>{t('inbox.columnType')}</dt>
          <dd>{summary.documentType ?? '—'}</dd>
        </div>
        <div>
          <dt>{t('approvalDoc.drafter')}</dt>
          <dd>
            {nameFromDocument(detail, summary.drafterAccountId) ??
              summary.drafterAccountId ??
              '—'}
          </dd>
        </div>
        {representation === undefined ? null : (
          <div>
            <dt>{t('approvalDoc.representationLabel')}</dt>
            <dd>
              {representation.kind === 'JOINT'
                ? t('approvalDoc.representation.JOINT')
                : t('approvalDoc.representation.SEVERAL')}
              {representation.label === undefined ? null : (
                <span className="appr-muted"> · {representation.label}</span>
              )}
            </dd>
          </div>
        )}
        <div>
          <dt>{t('inbox.columnAmount')}</dt>
          <dd className="ci-numeric">
            {summary.amount === undefined ? (
              '—'
            ) : (
              <Amount
                value={summary.amount}
                displayDecimals={displayDecimalsFor(summary.currencyCode)}
                {...(summary.currencyCode === undefined
                  ? {}
                  : { currencyCode: summary.currencyCode })}
                labels={{ roundedNotice: (exact) => t('common.roundedNotice', { exact }) }}
              />
            )}
          </dd>
        </div>
        <div>
          <dt>{t('approvalDoc.submitted')}</dt>
          <dd>
            <Instant wire={summary.submittedAt} />
          </dd>
        </div>
        <div>
          <dt>{t('approval.line')}</dt>
          <dd>{detail.lineSummary ?? '—'}</dd>
        </div>
      </dl>

      <p className="appr-muted document-note">{t('approvalDoc.bodyElsewhere')}</p>

      <section className="page__surface document-section" aria-labelledby="approval-line">
        <h2 id="approval-line" className="document-section__title">
          {t('approval.line')}
        </h2>
        <table className="ci-table">
          <thead>
            <tr>
              <th scope="col">{t('approvalDoc.columnStep')}</th>
              <th scope="col">{t('approvalDoc.columnRole')}</th>
              <th scope="col">{t('approvalDoc.columnApprovers')}</th>
              <th scope="col">{t('approvalDoc.columnStepState')}</th>
            </tr>
          </thead>
          <tbody>
            {(detail.steps ?? []).map((line) => (
              <StepRow key={line.id ?? String(line.position)} step={line} />
            ))}
          </tbody>
        </table>
      </section>

      <section className="page__surface document-section" aria-labelledby="approval-trail">
        <h2 id="approval-trail" className="document-section__title">
          {t('approval.trail')}
        </h2>
        <table className="ci-table">
          <thead>
            <tr>
              <th scope="col">{t('approvalDoc.columnActedAt')}</th>
              <th scope="col">{t('approvalDoc.columnAction')}</th>
              <th scope="col">{t('approvalDoc.columnActor')}</th>
              <th scope="col">{t('approvalDoc.columnComment')}</th>
              <th scope="col">{t('approval.snapshotHash')}</th>
            </tr>
          </thead>
          <tbody>
            {(detail.trail ?? []).map((action, index) => (
              <TrailRow
                key={action.id ?? String(index)}
                action={action}
                nameOf={(accountId) => nameFromDocument(detail, accountId) ?? accountId ?? '—'}
              />
            ))}
          </tbody>
        </table>
      </section>

      {step?.id === undefined || detail.awaitingMe !== true ? null : (
        <Decisions
          busy={act.isPending}
          onAct={(verb, reason) => {
            act.mutate({ verb, stepId: step.id ?? '', reason });
          }}
        />
      )}
    </Page>
  );
}

type Verb = 'approve' | 'return' | 'hold' | 'delegate-final';

/** Every decision except 승인 carries a mandatory reason. */
type ReasonedVerb = Exclude<Verb, 'approve'>;

const VERB_LABELS: Readonly<Record<Verb, string>> = {
  approve: 'action.approved',
  return: 'action.rejected',
  hold: 'action.held',
  'delegate-final': 'action.delegatedFinal',
};

interface ActRequest {
  readonly verb: Verb;
  readonly stepId: string;
  readonly reason: string;
}

function Instant({ wire }: { readonly wire: string | undefined }): ReactNode {
  const { t } = useTranslation();
  const instant = readInstant(wire);
  if (instant === null) {
    return wire === undefined ? (
      <span className="appr-muted">—</span>
    ) : (
      <span className="ci-numeric" title={t('businessTime.invalid')}>
        {wire}
      </span>
    );
  }
  return <BusinessInstantText value={instant} />;
}

function StepRow({ step }: { readonly step: Step }): ReactNode {
  const { t } = useTranslation();
  const approvers = step.approvers ?? [];
  const required = step.requiredApprovals ?? 1;

  return (
    <tr>
      <th scope="row">
        {t('approvalDoc.stepPosition', { position: (step.position ?? 0) + 1 })} ·{' '}
        {stepKindLabel(step.kind, t)}
      </th>
      <td className="ci-numeric">{step.roleExpression ?? '—'}</td>
      <td>
        <ul className="document-approvers">
          {approvers.map((approver) => (
            <li key={approver.accountId ?? approver.displayName}>
              {t('approvalDoc.signedBy', {
                name: approver.displayName ?? approver.accountId ?? '—',
                rank: approver.rankLabel ?? '—',
              })}
              {approver.acted === true ? null : (
                <span className="appr-muted"> · {t('approvalDoc.notSignedYet')}</span>
              )}
            </li>
          ))}
        </ul>
      </td>
      <td>
        <Badge tone={step.state === 'SKIPPED' ? 'warning' : 'default'}>
          {stepStateLabel(step.state, t)}
        </Badge>
        {required > 1 ? (
          <div className="appr-muted">
            {t('approval.quorum', { approved: step.approvalsGiven ?? 0, required })}
          </div>
        ) : null}
        {step.state === 'SKIPPED' ? <div className="appr-muted">{t('approvalDoc.skipped')}</div> : null}
      </td>
    </tr>
  );
}

/**
 * One line of the trail.
 *
 * 대결 is the reason this is its own component. The actor is the person who
 * signed; `onBehalfOfAccountId` is whom they signed *for*. Both are shown, in
 * that order, and the sentence that joins them is the shared
 * `approval.actingFor` — "…님을 대신하여 결재했습니다". Collapsing the two into one
 * name would put a signature in an absent person's hand.
 */
function TrailRow({
  action,
  nameOf,
}: {
  readonly action: TrailAction;
  readonly nameOf: (accountId: string | undefined) => string;
}): ReactNode {
  const { t } = useTranslation();

  return (
    <tr>
      <td>
        <Instant wire={action.actedAt} />
      </td>
      <td>{actionLabel(action.action, t)}</td>
      <td>
        {action.actorDisplayName ?? action.actorAccountId ?? '—'}
        {action.onBehalfOfAccountId === undefined ? null : (
          <div className="appr-muted">
            {t('approval.actingFor', { name: nameOf(action.onBehalfOfAccountId) })}
          </div>
        )}
      </td>
      <td>{action.comment ?? '—'}</td>
      <td className="ci-numeric document-hash" title={action.documentSnapshotHash ?? ''}>
        {action.documentSnapshotHash ?? '—'}
      </td>
    </tr>
  );
}

/**
 * The four decisions this screen can record.
 *
 * 승인 goes straight through, because a comment on it is optional and asking
 * for one every time trains people to type nothing. The other three take a
 * mandatory reason, so each opens its field first and stays disabled until
 * there is something to send — the same rule as 반려 in the inbox, for the same
 * reason.
 */
function Decisions({
  busy,
  onAct,
}: {
  readonly busy: boolean;
  readonly onAct: (verb: Verb, reason: string) => void;
}): ReactNode {
  const { t } = useTranslation();
  const [asking, setAsking] = useState<ReasonedVerb | null>(null);
  const [reason, setReason] = useState('');
  const ready = reason.trim() !== '';

  // The button says the decision; the field says what it needs. A button
  // labelled "반려 사유" would be asking somebody to press a noun.
  const verbs: Readonly<Record<ReasonedVerb, string>> = {
    return: t('action.reject'),
    hold: t('action.hold'),
    'delegate-final': t('action.delegatedFinal'),
  };
  const reasonLabels: Readonly<Record<ReasonedVerb, string>> = {
    return: t('inbox.returnReasonLabel'),
    hold: t('approvalDoc.holdReasonLabel'),
    'delegate-final': t('approvalDoc.delegateFinalReasonLabel'),
  };

  return (
    <section className="page__surface document-decisions" aria-label={t('approval.title')}>
      <div className="document-decisions__buttons">
        <Button
          tone="primary"
          busy={busy}
          onClick={() => {
            onAct('approve', '');
          }}
        >
          {t('action.approve')}
        </Button>
        {(['return', 'hold', 'delegate-final'] as const).map((verb) => (
          <Button
            key={verb}
            tone={verb === 'return' ? 'danger' : 'default'}
            onClick={() => {
              setAsking(verb);
              setReason('');
            }}
          >
            {verbs[verb]}
          </Button>
        ))}
      </div>

      {asking === null ? null : (
        <form
          className="document-decisions__reason"
          aria-label={reasonLabels[asking]}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault();
              setAsking(null);
            }
          }}
          onSubmit={(event) => {
            event.preventDefault();
            if (ready) {
              onAct(asking, reason.trim());
              setAsking(null);
            }
          }}
        >
          <TextField
            label={reasonLabels[asking]}
            hint={t('approval.reasonRequired')}
            required
            value={reason}
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />
          <Button type="submit" tone="danger" disabled={!ready} busy={busy}>
            {verbs[asking]}
          </Button>
          <Button
            onClick={() => {
              setAsking(null);
            }}
          >
            {t('action.cancel')}
          </Button>
        </form>
      )}
    </section>
  );
}
