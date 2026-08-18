import type { components } from '@coreintra/api-client';
import { page, route, type Reply } from './api.js';

/**
 * Bodies shaped like the ones the server sends.
 *
 * Typed against the generated schemas rather than written freehand: a fixture
 * that has drifted from the contract makes a test that passes against a screen
 * nobody can use.
 */
type Summary = components['schemas']['ApprovalDocumentSummary'];
type Detail = components['schemas']['ApprovalDocumentDetail'];
type Inbox = components['schemas']['ApprovalInbox'];
type WhoIsInEntry = components['schemas']['WhoIsInEntry'];
type StatusType = components['schemas']['AttendanceStatusType'];
type Employee = components['schemas']['EmployeeView'];
type Company = components['schemas']['CompanyView'];
type OrgUnit = components['schemas']['OrgUnitView'];

export const COMPANY: Company = {
  id: 'co-1',
  nameKo: '코어인트라 주식회사',
  nameEn: 'CoreIntra Inc.',
  active: true,
  baseCurrencyCode: 'KRW',
};

export const EXPENSE: Summary = {
  id: 'doc-expense',
  companyId: 'co-1',
  title: '출장비 지출결의서',
  documentType: 'EXPENSE_CLAIM',
  drafterAccountId: 'acc-kim',
  amount: '5000000',
  currencyCode: 'KRW',
  state: 'IN_PROGRESS',
  submittedAt: '2026-08-15T09:12:00.000',
  recordedAt: '2026-08-15T00:12:00Z',
};

export const JOINT: Summary = {
  id: 'doc-joint',
  companyId: 'co-1',
  title: '취업규칙 개정안',
  documentType: 'EMPLOYMENT_RULES',
  drafterAccountId: 'acc-lee',
  state: 'PARTIALLY_APPROVED',
  submittedAt: '2026-08-17T14:00:00.000',
};

export const MINE: Summary = {
  id: 'doc-mine',
  companyId: 'co-1',
  title: '노트북 구매 품의서',
  documentType: 'PURCHASE_REQUEST',
  drafterAccountId: 'acc-me',
  amount: '1400000.25',
  currencyCode: 'KRW',
  state: 'IN_PROGRESS',
  submittedAt: '2026-08-18T10:00:00.000',
};

export const COPIED: Summary = {
  id: 'doc-copied',
  companyId: 'co-1',
  title: '3분기 영업 보고',
  documentType: 'REPORT',
  drafterAccountId: 'acc-park',
  state: 'APPROVED',
  submittedAt: '2026-08-10T09:00:00.000',
};

/**
 * The inbox as the server really returns it, duplicate and all: `doc-expense`
 * is both drafted by this account and waiting on it, so the server lists it
 * twice. What the screen does with that is the point of one of the tests.
 */
export const INBOX: Inbox = {
  accountId: 'acc-me',
  counts: { awaiting: 2, draftedByMe: 2, copiedToMe: 1, inProgressByMe: 2, returnedToMe: 1 },
  awaitingMe: [EXPENSE, JOINT],
  draftedByMe: [MINE, EXPENSE],
  copiedToMe: [COPIED],
  listLimit: 50,
};

export function detailFor(summary: Summary, overrides: Partial<Detail> = {}): Detail {
  return {
    document: summary,
    awaitingMe: true,
    lineSummary: '부장 결재 대기 중입니다',
    steps: [
      {
        id: 'step-1',
        position: 0,
        kind: 'APPROVE',
        state: 'PENDING',
        pending: true,
        satisfied: false,
        requiredApprovals: 1,
        approvalsGiven: 0,
        remainingApprovals: 1,
        roleExpression: 'rank:bujang@DRAFTER_UNIT',
        approvers: [
          { accountId: 'acc-me', displayName: '박부장', rankLabel: '부장', acted: false },
        ],
      },
    ],
    trail: [],
    ...overrides,
  };
}

/** A 공동대표 document one representative has signed and another has not. */
export function jointDetail(): Detail {
  return detailFor(JOINT, {
    lineSummary: '공동대표 두 명 가운데 한 명이 결재했습니다',
    representation: {
      kind: 'JOINT',
      label: '공동대표 (2 of 2)',
      requiredApprovals: 2,
      designatedRepresentatives: 2,
    },
    steps: [
      {
        id: 'step-rep',
        position: 0,
        kind: 'APPROVE',
        state: 'PENDING',
        pending: true,
        satisfied: false,
        requiredApprovals: 2,
        approvalsGiven: 1,
        remainingApprovals: 1,
        roleExpression: 'representative:ANY',
        approvers: [
          { accountId: 'acc-rep-1', displayName: '김대표', rankLabel: '대표이사', acted: true },
          { accountId: 'acc-me', displayName: '이대표', rankLabel: '대표이사', acted: false },
        ],
      },
    ],
    trail: [
      {
        id: 'act-1',
        stepId: 'step-rep',
        action: 'APPROVE',
        actorAccountId: 'acc-rep-1',
        actorDisplayName: '김대표',
        actedAt: '2026-08-17T15:00:00.000',
        documentSnapshotHash: 'sha256:abc123',
      },
    ],
  });
}

export const EMPLOYEES: Employee[] = [
  { id: 'emp-1', companyId: 'co-1', nameKo: '김주간', nameEn: 'Kim Jugan' },
  { id: 'emp-2', companyId: 'co-1', nameKo: '이야근', nameEn: 'Lee Yageun' },
  { id: 'emp-3', companyId: 'co-1', nameKo: '박비밀', nameEn: 'Park Bimil' },
];

export const UNITS: OrgUnit[] = [
  { id: 'unit-1', companyId: 'co-1', nameKo: '개발팀', nameEn: 'Engineering', depth: 1 },
];

export const STATUS_TYPES: StatusType[] = [
  {
    id: 'st-working',
    companyId: 'co-1',
    code: 'WORKING',
    labelKo: '근무',
    labelEn: 'Working',
    colour: '#2f6f4f',
    icon: '●',
    builtIn: true,
    active: true,
    behaviour: {
      countsAsWorking: true,
      requiresApproval: false,
      deductsLeaveBalance: false,
      visibleToPeers: true,
    },
  },
  {
    id: 'st-remote',
    companyId: 'co-1',
    code: 'REMOTE',
    labelKo: '재택',
    labelEn: 'Working from home',
    colour: '#3a5fa8',
    icon: '⌂',
    builtIn: true,
    active: true,
    behaviour: {
      countsAsWorking: true,
      requiresApproval: false,
      deductsLeaveBalance: false,
      visibleToPeers: true,
    },
  },
  {
    // Not one of the six built-ins: the board must render it from data.
    id: 'st-training',
    companyId: 'co-1',
    code: 'TRAINING',
    labelKo: '교육',
    labelEn: 'Training',
    colour: '#8a5a1a',
    icon: '✎',
    builtIn: false,
    active: true,
    behaviour: {
      countsAsWorking: true,
      requiresApproval: true,
      deductsLeaveBalance: false,
      visibleToPeers: true,
      autoExpiresAfterSeconds: 28_800,
    },
  },
];

/**
 * A board with the three cases that matter: a shift that ran past midnight, a
 * status its owner does not share, and somebody who has recorded nothing.
 */
export const BOARD: WhoIsInEntry[] = [
  {
    employeeId: 'emp-1',
    businessDate: '2026-08-30',
    statusCode: 'WORKING',
    labelKo: '근무',
    labelEn: 'Working',
    colour: '#2f6f4f',
    icon: '●',
    countsAsWorking: true,
    current: false,
    startedAt: '2026-08-30T18:00:00.000',
    endedAt: '2026-08-30T27:00:00.000',
    visible: true,
  },
  {
    employeeId: 'emp-2',
    businessDate: '2026-08-30',
    statusCode: 'TRAINING',
    labelKo: '교육',
    labelEn: 'Training',
    colour: '#8a5a1a',
    icon: '✎',
    countsAsWorking: true,
    current: true,
    startedAt: '2026-08-30T09:00:00.000',
    visible: true,
  },
  {
    // 비공개: the server withholds the status and says so with `visible`.
    employeeId: 'emp-3',
    businessDate: '2026-08-30',
    statusCode: 'REMOTE',
    labelKo: '재택',
    labelEn: 'Working from home',
    current: true,
    startedAt: '2026-08-30T09:30:00.000',
    visible: false,
  },
];

/** The calls every screen in these areas makes before it can draw anything. */
export function stubCompanies(): void {
  route('GET /org/companies', { body: page([COMPANY]) });
}

export function stubBoard(entries: readonly WhoIsInEntry[] = BOARD): void {
  stubCompanies();
  route('GET /org/employees', { body: page(EMPLOYEES) });
  route('GET /org/units', { body: page(UNITS) });
  route('GET /attendance/status-types', { body: page(STATUS_TYPES) });
  route('GET /whos-in', { body: page(entries) });
}

export function stubInbox(inbox: Inbox = INBOX, ...documents: Array<[string, Detail]>): void {
  stubCompanies();
  route('GET /approvals/inbox', { body: inbox });
  for (const [id, detail] of documents) {
    route(`GET /approvals/${id}`, { body: detail });
  }
}

export function accepted(detail: Detail): Reply {
  return { body: detail };
}
