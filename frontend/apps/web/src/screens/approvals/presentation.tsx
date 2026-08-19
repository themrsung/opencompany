import { Badge } from '@coreintra/ui';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import type { Step } from './queries.js';

/**
 * How a document's state and step read on screen.
 *
 * Kept out of the list component because one of these decisions is a product
 * requirement rather than a style: 일부 승인 (PARTIALLY_APPROVED) is a 공동대표
 * document that one representative has signed and another has not. §4 makes it
 * a distinct visible state, so it gets its own tone *and* its own sentence —
 * a reader must not have to open the document to learn that it is waiting on a
 * second signature, and it must never look like ordinary 진행 중.
 */
const STATES = [
  'DRAFTING',
  'IN_PROGRESS',
  'PARTIALLY_APPROVED',
  'ON_HOLD',
  'APPROVED',
  'RETURNED',
  'RECALLED',
] as const;

type State = (typeof STATES)[number];

type Tone = 'default' | 'positive' | 'warning' | 'danger' | 'accent' | 'support';

const TONES: Readonly<Record<State, Tone>> = {
  DRAFTING: 'default',
  IN_PROGRESS: 'accent',
  // Not `accent`, and not the support tone either: that one belongs to the
  // temporary-master chrome and means something else entirely.
  PARTIALLY_APPROVED: 'warning',
  ON_HOLD: 'default',
  APPROVED: 'positive',
  RETURNED: 'danger',
  RECALLED: 'default',
};

function knownState(state: string | undefined): State | null {
  return STATES.find((candidate) => candidate === state) ?? null;
}

export function StateBadge({ state }: { readonly state: string | undefined }): ReactNode {
  const { t } = useTranslation();
  const known = knownState(state);

  if (known === null) {
    // An unknown state is a backend the frontend has not caught up with.
    // Showing the code beats showing nothing, and it names the thing to fix.
    return <Badge>{state ?? '—'}</Badge>;
  }

  return (
    <span className="approval-state">
      <Badge tone={TONES[known]}>{t(`approval.state.${known}`)}</Badge>
      {known === 'PARTIALLY_APPROVED' ? (
        <span className="approval-state__note">{t('approval.quorumPending')}</span>
      ) : null}
    </span>
  );
}

const STEP_KINDS = ['DRAFT', 'REVIEW', 'CONCURRENCE', 'APPROVE', 'CC'] as const;

export function stepKindLabel(kind: string | undefined, t: TFunction): string {
  return STEP_KINDS.some((candidate) => candidate === kind) && kind !== undefined
    ? t(`approval.step.${kind}`)
    : (kind ?? '—');
}

/**
 * A step as one line: which step, and how many signatures it still wants.
 *
 * The quorum is shown whenever a step needs more than one approval, which is
 * both a 공동대표 quorum and a parallel 합의 — in either case "2 of 3" is the
 * fact a reader needs and "in progress" is not.
 */
export function stepLabel(step: Step, t: TFunction): string {
  const kind = stepKindLabel(step.kind, t);
  const required = step.requiredApprovals ?? 1;
  if (required <= 1) {
    return kind;
  }
  return `${kind} · ${t('approval.quorum', { approved: step.approvalsGiven ?? 0, required })}`;
}

const STEP_STATES = ['UPCOMING', 'PENDING', 'COMPLETED', 'RETURNED', 'HELD', 'SKIPPED'] as const;

export function stepStateLabel(state: string | undefined, t: TFunction): string {
  return STEP_STATES.some((candidate) => candidate === state) && state !== undefined
    ? t(`approvalDoc.stepState.${state}`)
    : (state ?? '—');
}

const ACTIONS: Readonly<Record<string, string>> = {
  APPROVE: 'action.approve',
  RETURN: 'action.reject',
  HOLD: 'action.hold',
  DELEGATED_FINAL: 'action.delegatedFinal',
  ACTING: 'action.acting',
  RECALL: 'action.recall',
};

export function actionLabel(action: string | undefined, t: TFunction): string {
  const key = action === undefined ? undefined : ACTIONS[action];
  return key === undefined ? (action ?? '—') : t(key);
}
