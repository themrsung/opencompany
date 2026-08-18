import type { components } from '@coreintra/api-client';
import { parseBusinessInstant, type BusinessInstant } from '@coreintra/business-time';
import { api } from '../../api/client.js';

/**
 * Every call the 근태 screens make.
 *
 * Generated types throughout, reached through `components['schemas'][…]`: the
 * `GetResponse` helper resolves to `never` for these endpoints because the
 * generated document declares their responses under the wildcard media type
 * rather than the JSON one. That is a contract bug and it is in the report.
 */

export type WhoIsInEntry = components['schemas']['WhoIsInEntry'];
export type StatusType = components['schemas']['AttendanceStatusType'];
export type AttendanceRecord = components['schemas']['AttendanceRecord'];
export type Employee = components['schemas']['EmployeeView'];
export type OrgUnit = components['schemas']['OrgUnitView'];
export type Company = components['schemas']['CompanyView'];
export type LeaveBalance = components['schemas']['LeaveBalance'];
export type LeaveLedger = components['schemas']['LeaveLedger'];
export type LeaveTransaction = components['schemas']['LeaveTransaction'];
export type ExpiringLeave = components['schemas']['ExpiringLeave'];
export type EmploymentRules = components['schemas']['EmploymentRules'];

export const attendanceKeys = {
  companies: ['org', 'companies'] as const,
  employees: (companyId: string) => ['org', 'employees', companyId] as const,
  units: (companyId: string) => ['org', 'units', companyId] as const,
  statusTypes: (companyId: string) => ['attendance', 'status-types', companyId] as const,
  whosIn: (companyId: string, businessDate: string, orgUnitId: string | null) =>
    ['attendance', 'whos-in', companyId, businessDate, orgUnitId ?? 'all'] as const,
  records: (companyId: string, employeeId: string, businessDate: string) =>
    ['attendance', 'records', companyId, employeeId, businessDate] as const,
  leaveBalance: (companyId: string, employeeId: string, policyId: string, asOf: string) =>
    ['leave', 'balance', companyId, employeeId, policyId, asOf] as const,
  leaveLedger: (companyId: string, employeeId: string, policyId: string, asOf: string) =>
    ['leave', 'ledger', companyId, employeeId, policyId, asOf] as const,
  leaveExpiring: (companyId: string, employeeId: string, policyId: string, by: string) =>
    ['leave', 'expiring', companyId, employeeId, policyId, by] as const,
  employmentRules: (companyId: string, on: string) =>
    ['employment-rules', companyId, on] as const,
};

async function all<T>(
  path: string,
  query?: Readonly<Record<string, string | number | undefined>>,
): Promise<T[]> {
  const items: T[] = [];
  for await (const item of api.paginate<T>(path, query === undefined ? {} : { query })) {
    items.push(item);
  }
  return items;
}

export const attendance = {
  companies: (): Promise<Company[]> => all<Company>('/org/companies'),

  /**
   * The board, for one business date.
   *
   * Walked to the end rather than paged on screen: a who's-in board that stops
   * at the first fifty people answers "who is in" wrongly for the fifty-first.
   */
  whosIn: (
    companyId: string,
    businessDate: string,
    orgUnitId: string | null,
  ): Promise<WhoIsInEntry[]> =>
    all<WhoIsInEntry>('/whos-in', {
      companyId,
      businessDate,
      ...(orgUnitId === null ? {} : { orgUnitId }),
    }),

  /**
   * The statuses this company defined.
   *
   * The board could almost do without this — each entry carries its own label
   * and colour — but a status nobody is in today would then be invisible, and
   * the behaviour flags (연차 차감, 결재 필요, 비공개) would have nowhere to be read.
   * §5 makes these data rather than six hardcoded built-ins.
   */
  statusTypes: (companyId: string): Promise<StatusType[]> =>
    all<StatusType>('/attendance/status-types', { companyId }),

  /**
   * Names for the board.
   *
   * `/whos-in` returns an employee id and no name, so the board joins against
   * the roster. One extra request, cached for the session; see the report.
   */
  employees: (companyId: string): Promise<Employee[]> =>
    all<Employee>('/org/employees', { companyId, limit: 200 }),

  units: (companyId: string): Promise<OrgUnit[]> =>
    all<OrgUnit>('/org/units', { companyId, limit: 200 }),

  records: (
    companyId: string,
    employeeId: string,
    businessDate: string,
  ): Promise<AttendanceRecord[]> =>
    all<AttendanceRecord>('/attendance/records', { companyId, employeeId, businessDate }),

  leaveBalance: (
    companyId: string,
    employeeId: string,
    policyId: string,
    asOf: string,
  ): Promise<LeaveBalance> =>
    api.get<LeaveBalance>('/leave/balance', { query: { companyId, employeeId, policyId, asOf } }),

  leaveLedger: (
    companyId: string,
    employeeId: string,
    policyId: string,
    asOf: string,
  ): Promise<LeaveLedger> =>
    api.get<LeaveLedger>('/leave/ledger', { query: { companyId, employeeId, policyId, asOf } }),

  leaveExpiring: (
    companyId: string,
    employeeId: string,
    policyId: string,
    by: string,
  ): Promise<ExpiringLeave> =>
    api.get<ExpiringLeave>('/leave/expiring', { query: { companyId, employeeId, policyId, by } }),

  employmentRules: (companyId: string, on: string): Promise<EmploymentRules> =>
    api.get<EmploymentRules>('/employment-rules', { query: { companyId, on } }),
};

/* ------------------------------------------------------------ business time */

function pad2(value: number): string {
  return value.toString().padStart(2, '0');
}

/** Today as a business date. The board defaults to it and the URL never has to say so. */
export function todayBusinessDate(clock: Date = new Date()): string {
  return `${clock.getFullYear()}-${pad2(clock.getMonth() + 1)}-${pad2(clock.getDate())}`;
}

/** Adds days to a `YYYY-MM-DD` date without going near a timezone. */
export function shiftDate(businessDate: string, days: number): string {
  const [year, month, day] = businessDate.split('-').map((part) => Number(part));
  if (year === undefined || month === undefined || day === undefined) {
    return businessDate;
  }
  const moved = new Date(Date.UTC(year, month - 1, day + days));
  return `${moved.getUTCFullYear()}-${pad2(moved.getUTCMonth() + 1)}-${pad2(moved.getUTCDate())}`;
}

/**
 * A business instant off the wire, or null if this client cannot read it.
 *
 * Never `new Date(wire)`: `2026-08-30T27:00:00.000` is a correct value that the
 * `Date` constructor turns into either the 31st or `Invalid Date`, and both
 * answers destroy the one fact the board exists to show.
 */
export function readInstant(value: string | undefined): BusinessInstant | null {
  if (value === undefined || value === '') {
    return null;
  }
  try {
    return parseBusinessInstant(value);
  } catch {
    return null;
  }
}

/** True when a spell ran past the end of the calendar day it began on. */
export function crossesMidnight(entry: {
  readonly startedAt?: string;
  readonly endedAt?: string;
}): boolean {
  const ended = readInstant(entry.endedAt);
  return ended !== null && ended.offsetSeconds >= 86_400;
}

/**
 * A colour the server supplied, if it is one this UI is willing to paint.
 *
 * Statuses are client data and their colour is a free-text column; a value that
 * is not a plain hex colour goes in a `style` attribute unexamined otherwise.
 * Anything else falls back to the neutral token, which is a legible status chip
 * rather than a broken one.
 */
const HEX = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i;

export function safeColour(colour: string | undefined): string | null {
  return colour !== undefined && HEX.test(colour) ? colour : null;
}

/** The people on the board, by id, for the name join. */
export function nameIndex(
  employees: readonly Employee[],
  locale: string,
): ReadonlyMap<string, string> {
  const index = new Map<string, string>();
  for (const employee of employees) {
    if (employee.id === undefined) {
      continue;
    }
    const korean = employee.nameKo;
    const english = employee.nameEn;
    const name = locale.startsWith('en') ? (english ?? korean) : (korean ?? english);
    if (name !== undefined) {
      index.set(employee.id, name);
    }
  }
  return index;
}
