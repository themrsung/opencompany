import type { Translated } from '../en.js';

/**
 * The who's-in board — §12's other screen that decides whether people like
 * this product.
 *
 * Two strings here carry most of the weight. `private` is what a person sees
 * of a colleague whose status is not visible to peers: the row stays, because
 * a missing row is itself an answer about somebody. `crossesMidnight` (shared,
 * in the main catalogue) explains 27:00 the first time somebody meets it,
 * which on this board is most people.
 */
export const whosinKo = {
  columnPerson: '이름',
  columnStatus: '상태',
  columnStarted: '시작',
  columnEnded: '종료',
  stillOpen: '근무 중',
  private: '비공개',
  privateHint: '동료에게 공개하지 않는 상태입니다',
  noRecord: '기록 없음',
  noRecordHint: '이 날 아직 아무것도 기록하지 않았습니다',
  empty: '이 날 이 조직에 표시할 사람이 없습니다',
  allUnits: '전체 조직',
  unitLabel: '조직',
  summary: '{{total}}명 가운데 {{working}}명이 근무 중입니다',
  legendTitle: '이 회사가 쓰는 상태',
  behaviourCountsAsWorking: '근무 시간에 포함',
  behaviourRequiresApproval: '결재 필요',
  behaviourDeductsLeave: '연차 차감',
  behaviourPrivate: '동료에게 비공개',
  behaviourExpires: '{{hours}}시간 뒤 자동 종료',
  dayTitle: '{{name}} 님의 {{date}} 기록',
  openDay: '하루 기록 보기',
  closeDay: '닫기',
  dayEmpty: '이 날 기록이 없습니다',
  columnDuration: '길이',
  columnNote: '메모',
  duration: '{{hours}}시간 {{minutes}}분',
  approvedBy: '근거 문서',
} as const;

export const whosinEn: Translated<typeof whosinKo> = {
  columnPerson: 'Name',
  columnStatus: 'Status',
  columnStarted: 'Started',
  columnEnded: 'Ended',
  stillOpen: 'Still open',
  private: 'Private',
  privateHint: 'A status this person does not share with colleagues',
  noRecord: 'Nothing recorded',
  noRecordHint: 'Nothing has been recorded for this day yet',
  empty: 'Nobody to show for this day and unit',
  allUnits: 'Whole company',
  unitLabel: 'Unit',
  summary: '{{working}} of {{total}} people are working',
  legendTitle: 'Statuses this company uses',
  behaviourCountsAsWorking: 'Counts as working time',
  behaviourRequiresApproval: 'Needs an approval first',
  behaviourDeductsLeave: 'Spends leave',
  behaviourPrivate: 'Not shown to peers',
  behaviourExpires: 'Closes itself after {{hours}} hours',
  dayTitle: '{{name}} — {{date}}',
  openDay: 'Open the day',
  closeDay: 'Close',
  dayEmpty: 'Nothing recorded on this day',
  columnDuration: 'Length',
  columnNote: 'Note',
  duration: '{{hours}}h {{minutes}}m',
  approvedBy: 'Authorised by',
};
