package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연차 — the leave balance, as a ledger rather than a number.
 *
 * <h2>Policy is configuration</h2>
 *
 * <p>Nothing here knows how many days anyone gets. Monthly accrual, the annual
 * grant and the tenure at which it starts, the tenure ladder above it, the
 * carry-over limit and how long carried days last, and the smallest bookable
 * unit are all read from a {@link LeaveAccrualPolicy} row. The Korean 연차
 * default is seeded and editable, with the statutory reference in the
 * migration's comment; searching this file for "15" or "연차" finds nothing that
 * decides an entitlement, which is the point.
 *
 * <h2>Approval writes the balance</h2>
 *
 * <p>{@link #recordApprovedLeave} is called by the approval, inside the approval,
 * before it is persisted. If it refuses — the request is not a whole number of
 * bookable units, or the balance will not cover it — the exception travels back
 * out through the approval and nothing is written anywhere: no leave
 * transaction, and no approval either. An employee cannot end up with an
 * approved 휴가 and an unchanged balance, which is the discrepancy nobody finds
 * until it is disputed.
 *
 * <p>It is idempotent on the 결재 document id, so an approval retried after a
 * rollback deducts once, not twice.
 */
@Service
public class LeaveService {

    /** A leave operation the policy or the balance does not allow. */
    public static class LeaveRefusedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public LeaveRefusedException(String message) {
            super(message);
        }
    }

    private final LeaveLedgerStore ledgers;
    private final LeavePolicyStore policies;
    private final PermissionEvaluator permissions;
    private final EmployeeTargets targets;

    public LeaveService(LeaveLedgerStore ledgers, LeavePolicyStore policies,
            PermissionEvaluator permissions, EmployeeTargets targets) {
        this.ledgers = ledgers;
        this.policies = policies;
        this.permissions = permissions;
        this.targets = targets;
    }

    /**
     * Writes the deduction an approved 휴가 request authorises.
     *
     * <h2>Exactly one transaction, or none</h2>
     *
     * <p>One {@code USE} row per approval document, ever. A second call with the
     * same document id returns the row the first one wrote and touches nothing.
     * Every refusal below happens before the append, so a rejected call leaves
     * the ledger exactly as it found it.
     *
     * <h2>No principal, and why</h2>
     *
     * <p>The authority for this write is the approval, not a grant. The person
     * who approved a 휴가 request is a 팀장 exercising the line's authority, and
     * requiring them to also hold {@code hr.leave:write} would mean either
     * granting every approver the right to edit anyone's balance directly, or
     * having approvals fail for people who were legitimately routed them.
     * {@link ApprovedLeave} therefore refuses to exist without a document id,
     * and that document is the authorisation.
     *
     * @throws LeaveRefusedException if the policy does not allow the request or
     *         the balance will not cover it
     */
    @Transactional
    public LeaveLedger.Transaction recordApprovedLeave(ApprovedLeave approval) {
        List<LeaveLedger.Transaction> already =
                ledgers.findBySourceDocumentId(approval.sourceDocumentId());
        for (LeaveLedger.Transaction existing : already) {
            if (existing.kind() == LeaveLedger.TransactionKind.USE) {
                return existing;
            }
        }

        LeaveAccrualPolicy policy = requirePolicy(approval.policyId());
        if (!policy.isBookable(approval.days())) {
            throw new LeaveRefusedException(
                    approval.days() + "일은 " + policy.minimumBookableUnitDays()
                            + "일 단위로 신청할 수 없습니다. (" + approval.days() + " is not a whole "
                            + "multiple of this policy's minimum bookable unit of "
                            + policy.minimumBookableUnitDays() + " days. Adjust the request to "
                            + policy.roundToBookableUnit(approval.days()) + " days.)");
        }

        LeaveLedger ledger = ledgerOf(approval.employeeId(), approval.policyId());
        LocalDate on = approval.occurredAt().businessDate();
        if (!ledger.canAfford(approval.days(), on)) {
            throw new LeaveRefusedException(
                    "잔여 연차가 부족합니다. 잔여 " + ledger.balanceOn(on) + "일, 신청 " + approval.days()
                            + "일. (Balance on " + on + " is " + ledger.balanceOn(on)
                            + " days and the request is for " + approval.days() + ". Approving "
                            + "this would put the balance below zero, so the approval is refused "
                            + "rather than the ledger being allowed to go negative quietly.)");
        }

        LeaveLedger.Transaction use = new LeaveLedger.Transaction(
                UUID.randomUUID().toString(), LeaveLedger.TransactionKind.USE, approval.days(),
                approval.occurredAt(), null, null, approval.reason(),
                approval.sourceDocumentId(), approval.actorAccountId());
        ledgers.append(approval.employeeId(), approval.policyId(), use);
        return use;
    }

    /**
     * Gives back the days a recalled or reversed approval had taken.
     *
     * <p>A {@code CANCELLATION} rather than the removal of the {@code USE}: the
     * leave really was booked and really was given back, and both facts belong
     * on the record. Idempotent — a document already cancelled is left alone.
     */
    @Transactional
    public Optional<LeaveLedger.Transaction> cancelApprovedLeave(String employeeId,
            String policyId, String sourceDocumentId, BusinessInstant occurredAt, String reason,
            String actorAccountId) {

        BigDecimal used = BigDecimal.ZERO;
        for (LeaveLedger.Transaction transaction
                : ledgers.findBySourceDocumentId(sourceDocumentId)) {
            if (transaction.kind() == LeaveLedger.TransactionKind.CANCELLATION) {
                return Optional.empty();
            }
            if (transaction.kind() == LeaveLedger.TransactionKind.USE) {
                used = used.add(transaction.days());
            }
        }
        if (used.signum() <= 0) {
            return Optional.empty();
        }
        LeaveLedger.Transaction cancellation = new LeaveLedger.Transaction(
                UUID.randomUUID().toString(), LeaveLedger.TransactionKind.CANCELLATION, used,
                occurredAt, null, null,
                Texts.hasText(reason) ? reason : "결재 회수에 따른 취소 (approval withdrawn)",
                sourceDocumentId, actorAccountId);
        ledgers.append(employeeId, policyId, cancellation);
        return Optional.of(cancellation);
    }

    /**
     * Grants whatever the policy says this employee is owed and has not yet had.
     *
     * <p>Idempotent by construction rather than by a flag: it grants the
     * difference between the entitlement the policy computes and the grants
     * already on the ledger for this period, so running it twice on the same day
     * writes nothing the second time. A monthly accrual job that fires twice
     * after a restart therefore cannot double someone's leave.
     *
     * @return the transaction written, or empty when there was nothing to grant
     */
    @Transactional
    public Optional<LeaveLedger.Transaction> grantEntitlement(PermissionPrincipal actor,
            String companyId, String employeeId, String policyId, LocalDate hiredOn,
            BusinessInstant asOf, LocalDate periodStart, LocalDate expiresOn) {

        authoriseWrite(actor, companyId, employeeId, asOf.businessDate(),
                "granting leave entitlement");
        LeaveAccrualPolicy policy = requirePolicy(policyId);
        BigDecimal entitlement = policy.entitlementFor(hiredOn, asOf.businessDate());
        BigDecimal alreadyGranted = grantedSince(employeeId, policyId, periodStart,
                asOf.businessDate());
        BigDecimal owed = entitlement.subtract(alreadyGranted);
        if (owed.signum() <= 0) {
            return Optional.empty();
        }
        LeaveLedger.Transaction grant = new LeaveLedger.Transaction(
                UUID.randomUUID().toString(), LeaveLedger.TransactionKind.GRANT, owed, asOf,
                periodStart, expiresOn, null, null, actor.accountId());
        ledgers.append(employeeId, policyId, grant);
        return Optional.of(grant);
    }

    /**
     * Rolls an unused balance into the next period, lapsing whatever does not fit.
     *
     * <p>Two rows, not one: the {@code CARRY_OVER} that survives and the
     * {@code EXPIRY} that does not. Netting them into a single adjustment would
     * leave an employee unable to see how many days they lost, which is the
     * question they will actually ask.
     */
    @Transactional
    public List<LeaveLedger.Transaction> closePeriod(PermissionPrincipal actor, String companyId,
            String employeeId, String policyId, BusinessInstant at) {

        authoriseWrite(actor, companyId, employeeId, at.businessDate(), "closing a leave period");
        LeaveAccrualPolicy policy = requirePolicy(policyId);
        LocalDate on = at.businessDate();
        BigDecimal unused = ledgerOf(employeeId, policyId).balanceOn(on);
        List<LeaveLedger.Transaction> written =
                new ArrayList<LeaveLedger.Transaction>();
        if (unused.signum() <= 0) {
            return Immutables.copyOf(written);
        }
        BigDecimal carried = policy.carryOverFrom(unused);
        BigDecimal lapsed = unused.subtract(carried);

        if (lapsed.signum() > 0) {
            LeaveLedger.Transaction expiry = new LeaveLedger.Transaction(
                    UUID.randomUUID().toString(), LeaveLedger.TransactionKind.EXPIRY, lapsed, at,
                    null, null, "기간 종료에 따른 소멸 (lapsed at period end)", null,
                    actor.accountId());
            ledgers.append(employeeId, policyId, expiry);
            written.add(expiry);
        }
        if (carried.signum() > 0 && policy.carryOverExpiryFrom(on) != null) {
            // Carried days are re-booked with their own expiry so the ledger can
            // lapse them later without having to remember where they came from.
            LeaveLedger.Transaction expiry = new LeaveLedger.Transaction(
                    UUID.randomUUID().toString(), LeaveLedger.TransactionKind.EXPIRY, carried, at,
                    null, null, "이월 처리 (rebooked as carry-over)", null, actor.accountId());
            LeaveLedger.Transaction carryOver = new LeaveLedger.Transaction(
                    UUID.randomUUID().toString(), LeaveLedger.TransactionKind.CARRY_OVER, carried,
                    at, null, policy.carryOverExpiryFrom(on),
                    "전기 이월 (carried from the previous period)", null, actor.accountId());
            ledgers.append(employeeId, policyId, expiry);
            ledgers.append(employeeId, policyId, carryOver);
            written.add(expiry);
            written.add(carryOver);
        }
        return Immutables.copyOf(written);
    }

    /**
     * A manual correction <em>in the employee's favour</em>.
     *
     * <p>Named for what it does rather than for what it sounds like it does.
     * {@link LeaveLedger.TransactionKind#ADJUSTMENT} increases a balance by
     * construction — direction comes from the kind, and magnitudes are positive
     * — so this method can only ever add days. A method called {@code adjust}
     * that silently refused to subtract would be a trap.
     *
     * <p>There is <b>no downward correction</b> today, and it would be wrong to
     * fake one: {@code EXPIRY} means "lapsed under policy" and {@code USE} means
     * "taken", and recording an over-grant as either would put a false statement
     * in the one place an employee goes to find out where their days went. A
     * {@code CORRECTION} kind that decreases belongs in the ledger's own enum —
     * see the report accompanying this work.
     *
     * <p>The reason is mandatory and the domain enforces it: an unexplained
     * change to someone's leave balance is indistinguishable from a bug.
     */
    @Transactional
    public LeaveLedger.Transaction credit(PermissionPrincipal actor, String companyId,
            String employeeId, String policyId, BigDecimal days, BusinessInstant at,
            String reason) {

        authoriseWrite(actor, companyId, employeeId, at.businessDate(), "crediting leave days");
        LeaveLedger.Transaction adjustment = new LeaveLedger.Transaction(
                UUID.randomUUID().toString(), LeaveLedger.TransactionKind.ADJUSTMENT, days, at,
                null, null, reason, null, actor.accountId());
        ledgers.append(employeeId, policyId, adjustment);
        return adjustment;
    }

    /** The balance on a date. Computed from the rows, never stored. */
    @Transactional(readOnly = true)
    public BigDecimal balanceOn(PermissionPrincipal actor, String companyId, String employeeId,
            String policyId, LocalDate asOf) {
        authoriseRead(actor, companyId, employeeId, asOf);
        return ledgerOf(employeeId, policyId).balanceOn(asOf);
    }

    /** The whole ledger, for the "where did my days go?" screen. */
    @Transactional(readOnly = true)
    public LeaveLedger ledger(PermissionPrincipal actor, String companyId, String employeeId,
            String policyId, LocalDate asOf) {
        authoriseRead(actor, companyId, employeeId, asOf);
        return ledgerOf(employeeId, policyId);
    }

    /** Grants that will lapse by a date. Drives the "your 연차 expires soon" reminder. */
    @Transactional(readOnly = true)
    public List<LeaveLedger.Transaction> expiringBy(PermissionPrincipal actor, String companyId,
            String employeeId, String policyId, LocalDate by, LocalDate asOf) {
        authoriseRead(actor, companyId, employeeId, asOf);
        return ledgerOf(employeeId, policyId).expiringBy(by, asOf);
    }

    /**
     * What a request would cost, before anyone approves it.
     *
     * <p>Lets the 휴가 form say "this needs 1.5 days and you have 1" while the
     * drafter is still typing, rather than at the moment their 팀장 tries to
     * approve it.
     */
    @Transactional(readOnly = true)
    public LeaveQuote quote(PermissionPrincipal actor, String companyId, String employeeId,
            String policyId, BigDecimal requestedDays, LocalDate asOf) {
        authoriseRead(actor, companyId, employeeId, asOf);
        LeaveAccrualPolicy policy = requirePolicy(policyId);
        BigDecimal bookable = policy.roundToBookableUnit(requestedDays);
        BigDecimal balance = ledgerOf(employeeId, policyId).balanceOn(asOf);
        return new LeaveQuote(requestedDays, bookable, balance,
                balance.compareTo(bookable) >= 0);
    }

    // ------------------------------------------------------------------

    private LeaveLedger ledgerOf(String employeeId, String policyId) {
        return new LeaveLedger(employeeId, ledgers.transactionsFor(employeeId, policyId));
    }

    private LeaveAccrualPolicy requirePolicy(String policyId) {
        Optional<LeaveAccrualPolicy> policy = policies.findById(policyId);
        if (!policy.isPresent()) {
            throw new LeaveRefusedException(
                    "no leave policy " + policyId + " exists. Entitlements come from policy rows, "
                            + "so there is nothing to compute this against.");
        }
        return policy.get();
    }

    private BigDecimal grantedSince(String employeeId, String policyId, LocalDate periodStart,
            LocalDate asOf) {
        BigDecimal granted = BigDecimal.ZERO;
        for (LeaveLedger.Transaction transaction : ledgers.transactionsFor(employeeId, policyId)) {
            if (transaction.kind() != LeaveLedger.TransactionKind.GRANT) {
                continue;
            }
            LocalDate on = transaction.occurredAt().businessDate();
            if (periodStart != null && on.isBefore(periodStart)) {
                continue;
            }
            if (on.isAfter(asOf)) {
                continue;
            }
            granted = granted.add(transaction.days());
        }
        return granted;
    }

    /**
     * The target carries the employee who owns the balance, their unit, and the
     * date the operation belongs to — never today's, so a correction back-dated
     * to last quarter is authorised against the org as it stood then.
     */
    private void authoriseRead(PermissionPrincipal actor, String companyId, String employeeId,
            LocalDate asOf) {
        permissions.check(actor, AttendancePermissions.LEAVE_READ,
                targets.of(companyId, employeeId, asOf, "leave balance of " + employeeId))
                .orThrow();
    }

    private void authoriseWrite(PermissionPrincipal actor, String companyId, String employeeId,
            LocalDate asOf, String what) {
        permissions.check(actor, AttendancePermissions.LEAVE_WRITE,
                targets.of(companyId, employeeId, asOf, what + " for " + employeeId)).orThrow();
    }
}
