package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.approval.notify.Notifier;
import com.coreintra.approval.repository.ApprovalActionRepository;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.repository.ApprovalStepRepository;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 / 반려 / 보류 / 전결 / 대결 / 회수 — everything an approver can do.
 *
 * <h2>Every action appends; nothing is ever amended</h2>
 *
 * <p>Each call writes exactly one row to {@code approval_action}: who, when in
 * business time, what they did, why, and the digest of the document as it stood
 * at that moment. The step and document states are updated alongside, but they
 * are a projection — {@link ApprovalLines#rebuild} derives the live line by
 * replaying this trail, and refuses to proceed if the two disagree.
 *
 * <p>Every row of one document's trail carries the <em>same</em> digest, and
 * that is the point rather than a redundancy: submission froze the payload, so
 * the document each approver signed really was the same one. A trail whose
 * digests differ would be evidence that something changed mid-flight, which is
 * exactly what an auditor is looking for and exactly what a single stored hash
 * could not tell them.
 *
 * <h2>Who may act is the line's answer, not a grant's</h2>
 *
 * <p>Eligibility was resolved and snapshotted at submission, and
 * {@link ApprovalStep#mayAct(String)} is the gate. Reading the document still
 * goes through {@link ApprovalDocumentService#read}, so the permission
 * evaluator is on the path of every call; see {@link ApprovalPermissions} for
 * why there is deliberately no {@code approval.document:act}.
 *
 * <h2>공동대표</h2>
 *
 * <p>A representative step under 공동대표 carries the quorum snapshotted at
 * submission. One 대표 approving leaves the document
 * {@link ApprovalState#PARTIALLY_APPROVED} — a distinct, visible state, not
 * "still waiting" — and the same person acting twice is refused by the domain.
 * A single approver can never satisfy a joint quorum.
 */
@Service
public class ApprovalActionService {

    private final ApprovalDocumentService documentService;
    private final ApprovalDocumentRepository documents;
    private final ApprovalStepRepository steps;
    private final ApprovalActionRepository actions;
    private final UserAccountRepository accounts;
    private final PermissionEvaluator permissions;
    private final ApprovalNotifications notifications;
    private final List<ApprovalOutcomeListener> outcomeListeners;
    private final List<AbsenceDirectory> absences;

    public ApprovalActionService(ApprovalDocumentService documentService,
            ApprovalDocumentRepository documents, ApprovalStepRepository steps,
            ApprovalActionRepository actions, UserAccountRepository accounts,
            PermissionEvaluator permissions, List<Notifier> notifiers,
            List<ApprovalOutcomeListener> outcomeListeners, List<AbsenceDirectory> absences) {
        this.documentService = documentService;
        this.documents = documents;
        this.steps = steps;
        this.actions = actions;
        this.accounts = accounts;
        this.permissions = permissions;
        this.notifications = new ApprovalNotifications(notifiers);
        this.outcomeListeners = outcomeListeners == null
                ? Immutables.<ApprovalOutcomeListener>listOf()
                : Immutables.copyOf(outcomeListeners);
        this.absences = absences == null
                ? Immutables.<AbsenceDirectory>listOf()
                : Immutables.copyOf(absences);
    }

    /** 승인 — approve this step and pass the document on. */
    @Transactional
    public ApprovalDocumentView approve(PermissionPrincipal actor, String documentId, String stepId,
            BusinessInstant actedAt, String comment) {
        return apply(actor, documentId, stepId, ApprovalAction.APPROVE, actedAt, comment, null);
    }

    /**
     * 반려 — send it back to the drafter.
     *
     * @param reason mandatory; a document that comes back with no explanation is
     *        re-submitted unchanged and the whole loop repeats
     */
    @Transactional
    public ApprovalDocumentView returnToDrafter(PermissionPrincipal actor, String documentId,
            String stepId, BusinessInstant actedAt, String reason) {
        return apply(actor, documentId, stepId, ApprovalAction.RETURN, actedAt, reason, null);
    }

    /** 보류 — hold at this step, visibly waiting on this person. */
    @Transactional
    public ApprovalDocumentView hold(PermissionPrincipal actor, String documentId, String stepId,
            BusinessInstant actedAt, String reason) {
        return apply(actor, documentId, stepId, ApprovalAction.HOLD, actedAt, reason, null);
    }

    /**
     * 전결 — finalise now, skipping the remaining 결재 steps.
     *
     * <p>Refused when nothing would be skipped: that is an ordinary 승인 wearing
     * a label that would tell an auditor the 대표's signature was delegated when
     * it was never needed.
     *
     * <p>The remaining steps are marked SKIPPED rather than approved. Nobody
     * signed them and the trail must not suggest otherwise.
     */
    @Transactional
    public ApprovalDocumentView delegateFinal(PermissionPrincipal actor, String documentId,
            String stepId, BusinessInstant actedAt, String reason) {
        return apply(actor, documentId, stepId, ApprovalAction.DELEGATED_FINAL, actedAt, reason,
                null);
    }

    /**
     * 대결 — approve in place of an absent approver.
     *
     * <p>Recorded as {@link ApprovalAction#ACTING} attributed to the actor, with
     * the absentee named in {@code on_behalf_of_account_id}. The trail therefore
     * says "김대리 acted for 박부장", never "박부장 approved".
     *
     * <p>Where an {@link AbsenceDirectory} is wired, the absence is checked
     * rather than taken on trust: acting for someone who is at their desk is not
     * 대결.
     *
     * @param absentAccountId must itself be one of the step's snapshotted
     *        approvers — acting on behalf of someone the document was never
     *        routed to would be a signature with no authority behind it
     */
    @Transactional
    public ApprovalDocumentView actFor(PermissionPrincipal actor, String documentId, String stepId,
            String absentAccountId, BusinessInstant actedAt, String reason) {
        return apply(actor, documentId, stepId, ApprovalAction.ACTING, actedAt, reason,
                absentAccountId);
    }

    /**
     * 회수 — the drafter withdraws the document.
     *
     * <p>Only before the first approval. Afterwards the domain refuses: with-
     * drawing it would erase a decision that was really made, and the drafter
     * must ask an approver to 반려 instead so the decision stays on the record.
     */
    @Transactional
    public ApprovalDocumentView recall(PermissionPrincipal actor, String documentId,
            BusinessInstant actedAt, String comment) {
        return apply(actor, documentId, null, ApprovalAction.RECALL, actedAt, comment, null);
    }

    private ApprovalDocumentView apply(PermissionPrincipal actor, String documentId, String stepId,
            ApprovalAction action, BusinessInstant actedAt, String comment,
            String onBehalfOfAccountId) {

        if (actedAt == null) {
            throw new NullPointerException(
                    "actedAt is required; an approval with no business time cannot be ordered "
                            + "against the others");
        }
        // Loading through the document service puts the permission evaluator on
        // the path of every action, and gives back the line rebuilt from the
        // trail rather than trusted from a state column.
        ApprovalDocumentView before = documentService.read(actor, documentId);
        ApprovalDocumentEntity document = before.document();
        ApprovalLine line = before.line();
        if (line == null) {
            throw new ApprovalDocumentService.DocumentStateException(
                    "아직 상신되지 않은 문서입니다. (Document " + documentId + " has no approval line yet; "
                            + "it has not been submitted.)");
        }

        String effectiveStepId = stepId;
        if (action == ApprovalAction.RECALL) {
            effectiveStepId = firstStepId(before);
            // 회수 is the drafter changing their own document rather than an
            // approver exercising the line's authority, so unlike the approving
            // actions it is a write and is authorised as one.
            permissions.check(actor, ApprovalPermissions.DOCUMENT_WRITE,
                    PermissionTarget.builder()
                            .companyId(document.companyId())
                            .orgUnitId(document.drafterOrgUnitId())
                            .asOfBusinessDate(ApprovalDocumentService.businessDateOf(document))
                            .description("recalling " + documentId)
                            .build()).orThrow();
        }
        if (action == ApprovalAction.ACTING) {
            requireSnapshottedApprover(before, effectiveStepId, onBehalfOfAccountId);
            requireActuallyAbsent(onBehalfOfAccountId, actedAt.businessDate());
        }
        if (action == ApprovalAction.DELEGATED_FINAL) {
            requireSomethingToSkip(line, effectiveStepId);
        }

        Set<String> pendingBefore = pendingStepIds(line);
        String actorName = displayNameOf(actor);
        String snapshotHash = document.submittedSnapshotHash();

        // The domain decides. Everything below this line is persistence.
        line.act(effectiveStepId, actor.accountId(), actorName, action, actedAt, comment,
                snapshotHash, onBehalfOfAccountId);

        // Consequences that must be part of the approval run here — before a
        // single row is written, so a listener that refuses leaves no trace even
        // where no transaction manager is in play.
        if (line.state().isTerminal()) {
            ApprovalOutcome outcome = new ApprovalOutcome(document, line.state(), action,
                    actor.accountId(), actedAt, snapshotHash);
            for (int i = 0; i < outcomeListeners.size(); i++) {
                ApprovalOutcomeListener listener = outcomeListeners.get(i);
                if (listener.appliesTo(document.documentType())) {
                    listener.documentSettled(outcome);
                }
            }
        }

        persist(before, line, new ApprovalActionEntity(UUID.randomUUID().toString(),
                effectiveStepId, actor.accountId(), actorName, action, actedAt, comment,
                snapshotHash, onBehalfOfAccountId));

        announce(document, line, before, pendingBefore, action, actorName, comment, actedAt);
        return documentService.read(actor, documentId);
    }

    private void persist(ApprovalDocumentView before, ApprovalLine line,
            ApprovalActionEntity record) {
        for (ApprovalStepEntity stepRow : before.steps()) {
            for (ApprovalStep domainStep : line.steps()) {
                if (domainStep.id().equals(stepRow.id())) {
                    stepRow.setState(domainStep.state());
                }
            }
            steps.save(stepRow);
        }
        ApprovalDocumentEntity document = before.document();
        document.setState(line.state());
        documents.save(document);
        actions.save(record);
    }

    // ------------------------------------------------------------------
    // Guards the domain cannot express on its own
    // ------------------------------------------------------------------

    /**
     * 대결 must name someone the document was actually routed to.
     *
     * <p>The domain insists that an ACTING record names <em>somebody</em>; only
     * the service knows the snapshotted approver list well enough to insist it
     * is one of them.
     */
    private void requireSnapshottedApprover(ApprovalDocumentView view, String stepId,
            String absentAccountId) {
        if (Texts.isBlank(absentAccountId)) {
            throw new ApprovalLine.ApprovalRuleException(
                    "대결은 누구를 대신한 결재인지 반드시 기록해야 합니다. (대결 must record whom it was "
                            + "performed for; otherwise the trail implies the absent approver "
                            + "signed personally.)");
        }
        for (ApprovalStepApproverEntity approver : view.approversOf(stepId)) {
            if (approver.accountId().equals(absentAccountId)) {
                return;
            }
        }
        throw new ApprovalLine.ApprovalRuleException(
                "이 단계의 결재자가 아닌 사람을 대신하여 결재할 수 없습니다. (" + absentAccountId + " is not one "
                        + "of the approvers this step was resolved to at submission, so there is "
                        + "no authority to act in their place.)");
    }

    /**
     * 대결 is for an absence, so where an absence can be checked, it is.
     *
     * <p>Silent where no {@link AbsenceDirectory} is wired: the written reason
     * is then the only record, which is the same position every installation was
     * in before one existed. Refusing outright would break an everyday action
     * over a missing integration.
     */
    private void requireActuallyAbsent(String absentAccountId, LocalDate on) {
        for (int i = 0; i < absences.size(); i++) {
            if (!absences.get(i).isAbsent(absentAccountId, on)) {
                throw new ApprovalLine.ApprovalRuleException(
                        "대결은 결재자가 부재중일 때만 가능합니다. (" + absentAccountId + " is not recorded "
                                + "as absent on " + on + ", so this is not 대결 — it is signing in "
                                + "someone's name while they are at their desk. Ask them to "
                                + "approve it, or record the absence first.)");
            }
        }
    }

    /**
     * 전결 that skips nothing is an ordinary approval mislabelled.
     *
     * <p>Counts every <em>other</em> unsettled step that could block, wherever
     * it sits — including a parallel 합의 peer at the same position, because the
     * domain marks those SKIPPED too. What matters is whether a signature that
     * would otherwise have been required is being displaced, not where in the
     * line it sat.
     */
    private void requireSomethingToSkip(ApprovalLine line, String stepId) {
        int wouldBeSkipped = 0;
        for (ApprovalStep step : line.steps()) {
            boolean otherAndUnsettled = !step.id().equals(stepId)
                    && step.kind().canBlock()
                    && (step.state() == ApprovalStep.StepState.UPCOMING
                            || step.state() == ApprovalStep.StepState.PENDING);
            if (otherAndUnsettled) {
                wouldBeSkipped++;
            }
        }
        if (wouldBeSkipped == 0) {
            throw new ApprovalLine.ApprovalRuleException(
                    "전결할 다른 결재 단계가 없습니다. 일반 승인으로 처리해 주십시오. (No other approval step "
                            + "would be skipped by a 전결 here. Recording one would tell a reviewer "
                            + "that a delegated final approval displaced a signature that was "
                            + "never required. Use 승인.)");
        }
    }

    private String firstStepId(ApprovalDocumentView view) {
        if (view.steps().isEmpty()) {
            throw new ApprovalDocumentService.DocumentStateException(
                    "document " + view.document().id() + " has no steps to record a recall on");
        }
        // Ordered by position: the 기안 step, which is where the domain records
        // a 회수 so the withdrawal sits against the person who wrote it.
        return view.steps().get(0).id();
    }

    private String displayNameOf(PermissionPrincipal actor) {
        Optional<UserAccount> account = accounts.findById(actor.accountId());
        if (account.isPresent() && Texts.hasText(account.get().displayName())) {
            return account.get().displayName();
        }
        return Texts.hasText(actor.displayName()) ? actor.displayName() : actor.accountId();
    }

    private Set<String> pendingStepIds(ApprovalLine line) {
        Set<String> ids = new LinkedHashSet<String>();
        for (ApprovalStep step : line.pendingSteps()) {
            ids.add(step.id());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Notifications — never allowed to fail the approval
    // ------------------------------------------------------------------

    private void announce(ApprovalDocumentEntity document, ApprovalLine line,
            ApprovalDocumentView view, Set<String> pendingBefore, ApprovalAction action,
            String actorName, String comment, BusinessInstant at) {

        if (line.state() == ApprovalState.RETURNED) {
            notifications.send(new ApprovalNotification(ApprovalNotification.Kind.RETURNED,
                    document.drafterAccountId(), document.id(), document.title(), action,
                    actorName, comment, at));
            return;
        }
        if (line.state() == ApprovalState.APPROVED) {
            notifications.send(new ApprovalNotification(ApprovalNotification.Kind.APPROVED,
                    document.drafterAccountId(), document.id(), document.title(), action,
                    actorName, comment, at));
            return;
        }
        if (line.state() == ApprovalState.RECALLED) {
            // The drafter did this themselves; telling them is noise. Anyone who
            // was waiting on it hears instead.
            for (String accountId : approversOfSteps(view, pendingBefore)) {
                notifications.send(new ApprovalNotification(ApprovalNotification.Kind.PROGRESSED,
                        accountId, document.id(), document.title(), action, actorName, comment,
                        at));
            }
            return;
        }

        Set<String> pendingNow = pendingStepIds(line);
        Set<String> newlyPending = new LinkedHashSet<String>(pendingNow);
        newlyPending.removeAll(pendingBefore);
        for (String accountId : approversOfSteps(view, newlyPending)) {
            notifications.send(new ApprovalNotification(
                    ApprovalNotification.Kind.AWAITING_YOUR_ACTION, accountId, document.id(),
                    document.title(), action, actorName, comment, at));
        }
        notifications.send(new ApprovalNotification(ApprovalNotification.Kind.PROGRESSED,
                document.drafterAccountId(), document.id(), document.title(), action, actorName,
                comment, at));
    }

    private List<String> approversOfSteps(ApprovalDocumentView view, Set<String> stepIds) {
        List<String> accountIds = new ArrayList<String>();
        for (ApprovalStepApproverEntity approver : view.approvers()) {
            if (stepIds.contains(approver.stepId()) && !accountIds.contains(approver.accountId())) {
                accountIds.add(approver.accountId());
            }
        }
        return accountIds;
    }
}
