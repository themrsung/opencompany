import type { Translated } from '../en.js';

/**
 * The approval inbox — §12 calls it one of the two screens that decide whether
 * people like this product, so its copy is worth writing rather than
 * assembling from the shared vocabulary.
 */
export const inboxKo = {
  title: '결재함',
  waitingOnMe: '내 결재 대기',
  draftedByMe: '내가 상신함',
  copiedToMe: '참조',
  emptyWaiting: '결재할 문서가 없습니다',
  emptyDrafted: '상신한 문서가 없습니다',
  emptyCopied: '참조 문서가 없습니다',
  columnTitle: '제목',
  columnType: '문서 종류',
  columnDrafter: '기안자',
  columnSubmitted: '상신 일시',
  columnAmount: '금액',
  columnStep: '현재 단계',
  overdueSince: '{{days}}일 경과',
  bulkApprove: '선택한 문서 승인',
  bulkApproveConfirm: '{{count}}건을 승인합니다. 되돌릴 수 없습니다',
  openDocument: '문서 열기',
  returnReasonLabel: '반려 사유',
  returnReasonPlaceholder: '무엇을 고쳐야 하는지 적어 주십시오',
  shortcutHint: 'J/K 로 이동, Enter 로 열기, A 로 승인합니다',
} as const;

export const inboxEn: Translated<typeof inboxKo> = {
  title: 'Approvals',
  waitingOnMe: 'Waiting on me',
  draftedByMe: 'Drafted by me',
  copiedToMe: 'Copied to me',
  emptyWaiting: 'Nothing is waiting for you',
  emptyDrafted: 'You have not submitted anything',
  emptyCopied: 'Nothing has been copied to you',
  columnTitle: 'Title',
  columnType: 'Type',
  columnDrafter: 'Drafter',
  columnSubmitted: 'Submitted',
  columnAmount: 'Amount',
  columnStep: 'Current step',
  overdueSince: '{{days}} days waiting',
  bulkApprove: 'Approve selected',
  bulkApproveConfirm: 'Approving {{count}} documents. This cannot be undone',
  openDocument: 'Open',
  returnReasonLabel: 'Reason for returning',
  returnReasonPlaceholder: 'Say what needs to change',
  shortcutHint: 'J/K to move, Enter to open, A to approve',
};
