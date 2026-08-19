import type { Translated } from '../en.js';

/**
 * Leave: the balance, where it went, and the rules it was computed under.
 *
 * §5 makes accrual configuration rather than statute, so this screen shows the
 * ledger — every grant, use, carry-over and expiry with the reason on each —
 * rather than a number nobody can check. `policyHint` is an apology for an API
 * gap, and it says so plainly rather than pretending the field is a feature.
 */
export const leaveKo = {
  balanceTitle: '잔여 연차',
  asOf: '{{date}} 기준',
  ledgerTitle: '연차 원장',
  ledgerEmpty: '이 정책으로 기록된 연차 이동이 없습니다',
  columnKind: '구분',
  columnDays: '일수',
  columnEffective: '사용 가능일',
  columnExpires: '소멸일',
  columnOccurred: '기록 시각',
  columnSource: '근거 문서',
  kind: {
    GRANT: '부여',
    CARRY_OVER: '이월',
    USE: '사용',
    EXPIRY: '소멸',
    ADJUSTMENT: '조정',
    CANCELLATION: '취소',
  },
  expiringTitle: '소멸 예정',
  expiringBy: '{{date}} 까지 소멸하는 연차입니다',
  expiringEmpty: '이 시점까지 소멸하는 연차가 없습니다',
  horizonLabel: '소멸 기준일',
  rulesTitle: '적용 중인 취업규칙',
  rulesVersion: '제{{version}}판',
  rulesEffective: '{{date}} 부터 적용합니다',
  rulesApprovedUnder: '{{mode}}로 승인되었습니다',
  rulesEmpty: '이 날 적용되는 취업규칙이 없습니다',
  policyLabel: '휴가 정책 번호',
  policyHint: '정책 목록을 돌려주는 API가 없어서, 지금은 정책 번호를 직접 입력해야 합니다',
  policyMissing: '휴가 정책 번호를 입력하시면 잔여 연차와 원장을 보여 드립니다',
  employeeMissing: '사원을 선택해 주십시오',
  choosePerson: '사원 선택',
} as const;

export const leaveEn: Translated<typeof leaveKo> = {
  balanceTitle: 'Leave balance',
  asOf: 'As at {{date}}',
  ledgerTitle: 'Leave ledger',
  ledgerEmpty: 'Nothing has moved on this policy',
  columnKind: 'Kind',
  columnDays: 'Days',
  columnEffective: 'Spendable from',
  columnExpires: 'Expires',
  columnOccurred: 'Recorded',
  columnSource: 'Source document',
  kind: {
    GRANT: 'Grant',
    CARRY_OVER: 'Carry-over',
    USE: 'Use',
    EXPIRY: 'Expiry',
    ADJUSTMENT: 'Adjustment',
    CANCELLATION: 'Cancellation',
  },
  expiringTitle: 'About to lapse',
  expiringBy: 'Leave that lapses on or before {{date}}',
  expiringEmpty: 'Nothing lapses by then',
  horizonLabel: 'Lapsing by',
  rulesTitle: 'Employment rules in force',
  rulesVersion: 'Version {{version}}',
  rulesEffective: 'In force from {{date}}',
  rulesApprovedUnder: 'Approved under {{mode}}',
  rulesEmpty: 'No employment rules were in force on this date',
  policyLabel: 'Leave policy id',
  policyHint: 'No endpoint lists leave policies, so the id has to be typed in for now',
  policyMissing: 'Enter a leave policy id to see the balance and the ledger',
  employeeMissing: 'Choose a person',
  choosePerson: 'Choose a person…',
};
