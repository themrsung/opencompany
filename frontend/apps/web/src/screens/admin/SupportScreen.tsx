import type { components } from '@coreintra/api-client';
import { Badge, Banner, Button, EmptyState, TextField } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { businessInstantLabel } from '../../session/instants.js';
import {
  LIVE_SUPPORT_KEY,
  useLiveSupportSessions,
  useRevokeSupportSession,
} from '../../session/supportSession.js';
import {
  CompanySelect,
  todayBusinessDate,
  useCompanyChoice,
  type CompanyChoice,
} from '../../session/viewingContext.js';

type Capability = components['schemas']['Capability'];
// TemporaryMasterIssueRequest, not IssueRequest: the API-key endpoint owns
// that name, and until it was renamed both schemas collided and this screen
// was typed against the wrong one.
type IssueRequest = components['schemas']['TemporaryMasterIssueRequest'];
type IssuedSession = components['schemas']['IssuedSession'];
type Approver = components['schemas']['TemporaryMasterApproverRequest'];
type ApprovalDocumentDetail = components['schemas']['ApprovalDocumentDetail'];

const DEFAULT_HOURS = 4;
const MAX_HOURS = 24;

/**
 * Remote support — issuing, watching and killing a temporary master account.
 *
 * §8 calls this the highest-risk feature in the product, and the screen is
 * built to slow the reader down in the places where being fast is the danger:
 *
 *  - Every capability starts off. There is no "select all", not as a
 *    convenience and not behind a menu; ticking twelve boxes one at a time is
 *    the cost of opening twelve doors into someone else's company.
 *  - Each capability carries the server's own plain-English wording. One with
 *    no wording cannot be ticked at all — nobody can consent to a thing that
 *    nothing describes.
 *  - The window is four hours by default and twenty-four at most, and there is
 *    no extend button anywhere, because there is no extend endpoint and an
 *    affordance that implies one is a lie about what will happen at hour 24.
 *  - Issuance is four steps ending in typing the company's own name. The API
 *    refuses a mismatch too; this is the half a person can see.
 */
export function SupportScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const choice = useCompanyChoice(todayBusinessDate());
  const company = choice.companies.find((candidate) => candidate.id === choice.companyId);

  // The registered name, Korean first: it is what the company is called on
  // the paperwork, and the server compares against its own record rather than
  // against whatever the reader's language setting says.
  const legalName = company?.nameKo ?? company?.nameEn ?? '';

  const live = useLiveSupportSessions(true);
  const revoke = useRevokeSupportSession();

  return (
    <Page title={t('support.title')}>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '48rem' }}>{t('support.intro')}</p>

      <div className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
        <CompanySelect choice={choice} language={i18n.language} />
      </div>

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('support.liveTitle')}</h2>
        {(live.data ?? []).length === 0 ? (
          <EmptyState message={t('support.liveNone')} />
        ) : (
          <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {(live.data ?? []).map((session) => (
              <li key={session.grantId ?? session.engineerName} style={{ marginBottom: 'var(--ci-space-3)' }}>
                <Banner
                  tone="support"
                  title={t('support.liveWho', { name: session.engineerName ?? '' })}
                  actions={
                    session.revocableByYou === true ? (
                      <Button
                        tone="danger"
                        busy={revoke.isPending}
                        onClick={() => {
                          if (session.grantId !== undefined) {
                            revoke.mutate(session.grantId);
                          }
                        }}
                      >
                        {revoke.isPending ? t('support.revoking') : t('support.revoke')}
                      </Button>
                    ) : undefined
                  }
                >
                  <p>
                    {t('support.expiresAt')}: {businessInstantLabel(session.expiresAt)}
                  </p>
                  <p>
                    {t('support.capabilitiesLive')}: {(session.capabilities ?? []).join(', ')}
                  </p>
                  {session.grantId === undefined ? null : (
                    <Link to="/support/report/$grantId" params={{ grantId: session.grantId }}>
                      {t('support.viewReport')}
                    </Link>
                  )}
                </Banner>
              </li>
            ))}
          </ul>
        )}
      </section>

      <IssueWizard choice={choice} legalName={legalName} />

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('support.cannotTitle')}</h2>
        <ul>
          <li>{t('support.cannot1')}</li>
          <li>{t('support.cannot2')}</li>
          <li>{t('support.cannot3')}</li>
          <li>{t('support.cannot4')}</li>
          <li>{t('support.cannot5')}</li>
        </ul>
      </section>

      <KillSwitch companyId={choice.companyId} />
    </Page>
  );
}

function IssueWizard({
  choice,
  legalName,
}: {
  readonly choice: CompanyChoice;
  readonly legalName: string;
}): ReactNode {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const companyId = choice.companyId;

  const [step, setStep] = useState(1);
  const [chosen, setChosen] = useState<readonly string[]>([]);
  const [engineerName, setEngineerName] = useState('');
  const [accountId, setAccountId] = useState('');
  const [hours, setHours] = useState(DEFAULT_HOURS);
  const [reason, setReason] = useState('');
  const [ticketReference, setTicketReference] = useState('');
  const [approvalDocumentId, setApprovalDocumentId] = useState('');
  const [representationMode, setRepresentationMode] = useState('SEVERAL');
  const [requiredApprovals, setRequiredApprovals] = useState(1);
  const [approvers, setApprovers] = useState<readonly Approver[]>([{ accountId: '', name: '' }]);
  const [typed, setTyped] = useState('');
  const [issued, setIssued] = useState<IssuedSession | null>(null);

  // One key for one issuance, reused if the button is pressed again after a
  // failure: a retried POST must not open a second session.
  const idempotencyKey = useRef<string | null>(null);

  const capabilities = useQuery({
    queryKey: ['support', 'capabilities'],
    queryFn: () => api.get<Capability[]>('/support/temporary-master/capabilities'),
  });

  // Read-only, and quietly: the approval document carries the representation
  // mode and its quorum, so the numbers on this form come from the document
  // that authorised it rather than from whoever is filling the form in.
  const approval = useQuery({
    queryKey: ['approvals', 'detail', approvalDocumentId],
    enabled: approvalDocumentId.trim() !== '',
    retry: false,
    queryFn: () =>
      api.get<ApprovalDocumentDetail>(`/approvals/${encodeURIComponent(approvalDocumentId.trim())}`),
  });

  const representation = approval.data?.representation;

  const issue = useMutation({
    mutationFn: (request: IssueRequest) => {
      idempotencyKey.current ??= api.idempotencyKey();
      return api.post<IssuedSession>('/support/temporary-master', request, {
        idempotencyKey: idempotencyKey.current,
      });
    },
    onSuccess: (result) => {
      setIssued(result);
      idempotencyKey.current = null;
      void queryClient.invalidateQueries({ queryKey: LIVE_SUPPORT_KEY });
    },
  });

  const usable = (capability: Capability): boolean =>
    (capability.key ?? '') !== '' && (capability.description ?? '').trim() !== '';

  const toggle = (key: string): void => {
    setChosen((previous) =>
      previous.includes(key) ? previous.filter((one) => one !== key) : [...previous, key],
    );
  };

  const quorumMet = approvers.filter(isCompleteApprover).length >= Math.max(1, requiredApprovals);
  const nameMatches = typed === legalName && legalName !== '';

  const stepReady: Record<number, boolean> = {
    1: chosen.length > 0,
    2:
      engineerName.trim() !== '' &&
      accountId.trim() !== '' &&
      hours >= 1 &&
      hours <= MAX_HOURS &&
      reason.trim().length >= 10,
    3: approvalDocumentId.trim() !== '' && quorumMet,
    4: nameMatches,
  };

  const submit = (): void => {
    if (companyId === null || !nameMatches) {
      return;
    }
    issue.mutate({
      accountId: accountId.trim(),
      approvalDocumentId: approvalDocumentId.trim(),
      approvedBy: approvers.filter(isCompleteApprover),
      capabilities: [...chosen],
      companyId,
      companyName: legalName,
      engineerName: engineerName.trim(),
      hours,
      reason: reason.trim(),
      representationMode: representation?.kind ?? representationMode,
      requiredApprovals: representation?.requiredApprovals ?? requiredApprovals,
      typedCompanyName: typed,
      ...(ticketReference.trim() === '' ? {} : { ticketReference: ticketReference.trim() }),
    });
  };

  if (issued !== null) {
    return (
      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('support.issueTitle')}</h2>
        <Banner tone="support" title={t('support.issued')}>
          <p>{t('support.issuedExpires', { at: businessInstantLabel(issued.expiresAt) })}</p>
          <p>
            {t('support.confirmCapabilities')}: {(issued.capabilities ?? []).join(', ')}
          </p>
          {issued.grantId === undefined ? null : (
            <Link to="/support/report/$grantId" params={{ grantId: issued.grantId }}>
              {t('support.viewReport')}
            </Link>
          )}
        </Banner>
      </section>
    );
  }

  return (
    <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
      <h2>{t('support.issueTitle')}</h2>
      <ol style={{ display: 'flex', gap: 'var(--ci-space-4)', listStyle: 'none', padding: 0 }}>
        {[t('support.stepCapabilities'), t('support.stepTerms'), t('support.stepApproval'), t('support.stepConfirm')].map(
          (label, index) => (
            <li key={label} aria-current={step === index + 1 ? 'step' : undefined}>
              {step === index + 1 ? <Badge tone="support">{label}</Badge> : <Badge>{label}</Badge>}
            </li>
          ),
        )}
      </ol>

      {issue.error === null || issue.error === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(issue.error, t).message}
        </Banner>
      )}

      {step === 1 ? (
        <div>
          <p>{t('temporaryMaster.everythingOff')}</p>
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('support.capabilitiesIntro')}</p>
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('temporaryMaster.noGrantAll')}</p>
          {(capabilities.data ?? []).length === 0 ? (
            <EmptyState message={t('support.capabilitiesEmpty')} />
          ) : (
            <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
              {(capabilities.data ?? []).map((capability) => {
                const key = capability.key ?? '';
                const allowed = usable(capability);
                return (
                  <li key={key} style={{ marginBottom: 'var(--ci-space-3)' }}>
                    <label style={{ display: 'flex', gap: 'var(--ci-space-2)', alignItems: 'baseline' }}>
                      <input
                        type="checkbox"
                        checked={chosen.includes(key)}
                        disabled={!allowed}
                        onChange={() => {
                          toggle(key);
                        }}
                      />
                      <span>{key}</span>
                    </label>
                    <p style={{ margin: 0, color: allowed ? 'var(--ci-fg)' : 'var(--ci-danger)' }}>
                      <strong>{t('support.exposes')}: </strong>
                      {allowed ? capability.description : t('support.capabilityNoWording')}
                    </p>
                  </li>
                );
              })}
            </ul>
          )}
          <p>
            {chosen.length === 0
              ? t('support.capabilitiesNoneChosen')
              : t('support.capabilitiesChosen', { count: chosen.length })}
          </p>
        </div>
      ) : null}

      {step === 2 ? (
        <div style={{ display: 'flex', gap: 'var(--ci-space-4)', flexWrap: 'wrap' }}>
          <TextField
            label={t('support.engineerName')}
            hint={t('support.engineerNameHint')}
            value={engineerName}
            onChange={(event) => {
              setEngineerName(event.target.value);
            }}
          />
          <TextField
            label={t('support.accountId')}
            hint={t('support.accountIdHint')}
            value={accountId}
            onChange={(event) => {
              setAccountId(event.target.value);
            }}
          />
          <TextField
            label={`${t('temporaryMaster.ttl')} (${t('support.hoursUnit')})`}
            hint={t('temporaryMaster.ttlMax')}
            type="number"
            min={1}
            max={MAX_HOURS}
            value={String(hours)}
            onChange={(event) => {
              const parsed = Number.parseInt(event.target.value, 10);
              setHours(Number.isNaN(parsed) ? DEFAULT_HOURS : Math.min(MAX_HOURS, Math.max(1, parsed)));
            }}
          />
          <TextField
            label={t('temporaryMaster.reason')}
            hint={t('support.reasonHint')}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value);
            }}
            {...(reason.trim() !== '' && reason.trim().length < 10
              ? { error: t('support.reasonHint') }
              : {})}
          />
          <TextField
            label={`${t('temporaryMaster.ticket')} (${t('common.optional')})`}
            hint={t('support.ticketHint')}
            value={ticketReference}
            onChange={(event) => {
              setTicketReference(event.target.value);
            }}
          />
        </div>
      ) : null}

      {step === 3 ? (
        <div>
          <TextField
            label={t('support.approvalDocumentId')}
            hint={t('support.approvalDocumentHint')}
            value={approvalDocumentId}
            onChange={(event) => {
              setApprovalDocumentId(event.target.value);
            }}
          />
          {representation === undefined ? (
            <div style={{ display: 'flex', gap: 'var(--ci-space-4)', alignItems: 'flex-end' }}>
              <div className="ci-field">
                <label className="ci-field__label" htmlFor="support-representation">
                  {t('org.representationMode')}
                </label>
                <select
                  id="support-representation"
                  className="ci-field__input"
                  value={representationMode}
                  onChange={(event) => {
                    setRepresentationMode(event.target.value);
                  }}
                >
                  <option value="SEVERAL">{t('org.representationSeveral')}</option>
                  <option value="JOINT">{t('org.representationJoint')}</option>
                </select>
              </div>
              <TextField
                label={t('support.requiredApprovals')}
                hint={t('support.approversHint')}
                type="number"
                min={1}
                value={String(requiredApprovals)}
                onChange={(event) => {
                  const parsed = Number.parseInt(event.target.value, 10);
                  setRequiredApprovals(Number.isNaN(parsed) ? 1 : Math.max(1, parsed));
                }}
              />
            </div>
          ) : (
            <p>
              {representation.label ?? representation.kind ?? ''} · {t('support.representationHint')}
            </p>
          )}

          <h3>{t('support.approvers')}</h3>
          {approvers.map((approver, index) => (
            <div
              key={`approver-${String(index)}`}
              style={{ display: 'flex', gap: 'var(--ci-space-3)', alignItems: 'flex-end' }}
            >
              <TextField
                label={t('support.approverName')}
                value={approver.name}
                onChange={(event) => {
                  const value = event.target.value;
                  setApprovers((previous) =>
                    previous.map((one, at) => (at === index ? { ...one, name: value } : one)),
                  );
                }}
              />
              <TextField
                label={t('support.approverAccount')}
                value={approver.accountId}
                onChange={(event) => {
                  const value = event.target.value;
                  setApprovers((previous) =>
                    previous.map((one, at) => (at === index ? { ...one, accountId: value } : one)),
                  );
                }}
              />
              <Button
                onClick={() => {
                  setApprovers((previous) => previous.filter((_one, at) => at !== index));
                }}
              >
                {t('support.removeApprover')}
              </Button>
            </div>
          ))}
          <Button
            onClick={() => {
              setApprovers((previous) => [...previous, { accountId: '', name: '' }]);
            }}
          >
            {t('support.addApprover')}
          </Button>
          <p>
            {quorumMet
              ? t('support.quorumMet')
              : t('support.quorumNeeded', {
                  required: representation?.requiredApprovals ?? requiredApprovals,
                  have: approvers.filter(isCompleteApprover).length,
                })}
          </p>
        </div>
      ) : null}

      {step === 4 ? (
        <div>
          <p>{t('support.confirmIntro')}</p>
          <h3>{t('support.confirmExposureTitle')}</h3>
          <ul>
            {chosen.map((key) => {
              const capability = (capabilities.data ?? []).find((one) => one.key === key);
              return <li key={key}>{capability?.description ?? key}</li>;
            })}
          </ul>
          <p>{t('support.confirmWindow', { hours })}</p>
          <p>
            {t('temporaryMaster.reason')}: {reason.trim()}
          </p>
          <TextField
            label={t('temporaryMaster.confirmCompanyName', { company: legalName })}
            hint={t('support.confirmNameHint')}
            value={typed}
            onChange={(event) => {
              setTyped(event.target.value);
            }}
            {...(typed !== '' && !nameMatches ? { error: t('support.nameMismatch') } : {})}
          />
        </div>
      ) : null}

      <div style={{ display: 'flex', gap: 'var(--ci-space-3)', marginTop: 'var(--ci-space-4)' }}>
        {step > 1 ? (
          <Button
            onClick={() => {
              setStep((previous) => previous - 1);
            }}
          >
            {t('support.back')}
          </Button>
        ) : null}
        {step < 4 ? (
          <Button
            tone="primary"
            disabled={stepReady[step] !== true}
            onClick={() => {
              setStep((previous) => previous + 1);
            }}
          >
            {t('support.next')}
          </Button>
        ) : (
          <Button tone="danger" disabled={!nameMatches} busy={issue.isPending} onClick={submit}>
            {issue.isPending ? t('support.issuing') : t('temporaryMaster.issue')}
          </Button>
        )}
      </div>
    </section>
  );
}

function KillSwitch({ companyId }: { readonly companyId: string | null }): ReactNode {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const [armed, setArmed] = useState(false);

  const disable = useMutation({
    mutationFn: () =>
      api.post<void>('/support/temporary-master/kill-switch', undefined, {
        query: { companyId: companyId ?? '', reason: reason.trim() },
      }),
  });

  return (
    <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
      <h2>{t('support.killSwitchTitle')}</h2>
      <p>{t('support.killSwitchBody')}</p>
      <p style={{ color: 'var(--ci-fg-muted)' }}>{t('temporaryMaster.killSwitchWarning')}</p>

      {disable.isSuccess ? (
        <Banner tone="warning">{t('support.killSwitchDone')}</Banner>
      ) : (
        <>
          <TextField
            label={t('support.killSwitchReason')}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />
          {armed ? (
            <Banner tone="danger" title={t('support.killSwitchConfirm')}>
              <div style={{ display: 'flex', gap: 'var(--ci-space-3)' }}>
                <Button
                  tone="danger"
                  busy={disable.isPending}
                  onClick={() => {
                    disable.mutate();
                  }}
                >
                  {t('temporaryMaster.killSwitch')}
                </Button>
                <Button
                  onClick={() => {
                    setArmed(false);
                  }}
                >
                  {t('action.cancel')}
                </Button>
              </div>
            </Banner>
          ) : (
            <Button
              tone="danger"
              disabled={companyId === null || reason.trim() === ''}
              onClick={() => {
                setArmed(true);
              }}
            >
              {t('temporaryMaster.killSwitch')}
            </Button>
          )}
        </>
      )}

      {disable.error === null || disable.error === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(disable.error, t).message}
        </Banner>
      )}
    </section>
  );
}

function isCompleteApprover(approver: Approver): boolean {
  return approver.name.trim() !== '' && approver.accountId.trim() !== '';
}
