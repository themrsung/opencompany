import type { Translated } from '../en.js';

/**
 * One approval document: its line, its trail, and the buttons that end it.
 *
 * Registered as `approvalDoc`, not `approval`: the shared catalogue already
 * owns `approval` (the vocabulary — 결재선, 상태, 정족수) and the screen merge is
 * one level deep, so registering here under that name would delete every
 * shared string with no error anywhere. The catalogue test enforces this.
 *
 * Copy that names a *thing* (an action, a state, a document part) stays in the
 * shared catalogue and is reused from here; copy that only this screen says —
 * column headers, hints, refusals — lives below.
 */
export const approvalKo = {
  backToInbox: '결재함으로',
  awaitingYou: '지금 결재하실 차례입니다',
  notFound: '문서를 찾을 수 없습니다',
  loading: '문서를 불러오는 중입니다',
  stepPosition: '{{position}}단계',
  columnStep: '단계',
  columnRole: '지정 조건',
  columnApprovers: '결재자',
  columnStepState: '상태',
  columnActedAt: '시각',
  columnAction: '행위',
  columnActor: '행위자',
  columnComment: '의견',
  stepState: {
    UPCOMING: '차례 전',
    PENDING: '진행 중',
    COMPLETED: '완료',
    RETURNED: '반려',
    HELD: '보류',
    SKIPPED: '건너뜀',
  },
  representationLabel: '대표 결재 방식',
  representation: {
    SEVERAL: '각자대표',
    JOINT: '공동대표',
  },
  skipped: '전결로 건너뛴 단계입니다. 이 단계에 서명한 사람은 없습니다',
  signedBy: '{{name}} ({{rank}})',
  notSignedYet: '아직 결재하지 않았습니다',
  holdReasonLabel: '보류 사유',
  delegateFinalReasonLabel: '전결 사유',
  noStepForYou: '이 문서에서 지금 결재하실 단계가 없습니다',
  bodyElsewhere: '본문은 문서 모듈에 있습니다. 이 화면은 결재선과 이력만 보여 드립니다',
  documentId: '문서 번호',
  submitted: '상신',
  drafter: '기안자',
  actionResult: '{{action}} 처리했습니다',
} as const;

export const approvalEn: Translated<typeof approvalKo> = {
  backToInbox: 'Back to approvals',
  awaitingYou: 'It is your turn to sign',
  notFound: 'No such document',
  loading: 'Loading the document',
  stepPosition: 'Step {{position}}',
  columnStep: 'Step',
  columnRole: 'Routed to',
  columnApprovers: 'Approvers',
  columnStepState: 'State',
  columnActedAt: 'When',
  columnAction: 'Action',
  columnActor: 'Who',
  columnComment: 'Comment',
  stepState: {
    UPCOMING: 'Not yet',
    PENDING: 'In progress',
    COMPLETED: 'Done',
    RETURNED: 'Returned',
    HELD: 'Held',
    SKIPPED: 'Skipped',
  },
  representationLabel: 'Representation',
  representation: {
    SEVERAL: 'Several representation',
    JOINT: 'Joint representation',
  },
  skipped: 'Finalised earlier, so this step was skipped. Nobody signed it',
  signedBy: '{{name}} ({{rank}})',
  notSignedYet: 'Has not signed yet',
  holdReasonLabel: 'Reason for holding',
  delegateFinalReasonLabel: 'Reason for finalising now',
  noStepForYou: 'There is no step here for you to sign',
  bodyElsewhere: 'The body lives in the documents module. This screen shows the line and the trail',
  documentId: 'Document',
  submitted: 'Submitted',
  drafter: 'Drafter',
  actionResult: '{{action}} recorded',
};
