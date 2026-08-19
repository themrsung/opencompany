package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.approval.notify.Notifier;
import com.coreintra.approval.repository.ApprovalActionRepository;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.repository.ApprovalStepApproverRepository;
import com.coreintra.approval.repository.ApprovalStepRepository;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.math.BigDecimal;
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
 * Drafting, submitting and reading a 결재 document.
 *
 * <h2>Submission is the moment everything is frozen</h2>
 *
 * <p>Two freezes happen together and neither is optional.
 *
 * <p>The <b>line</b> is resolved from role expressions to actual people and
 * snapshotted onto the document, with the rank and name each person held that
 * day. A reorganisation next month then cannot reroute an approval that is
 * already in flight, and cannot rewrite who was supposed to have signed one that
 * is finished. Everything downstream — acting, the inbox, the printed 결재란 —
 * reads that snapshot and never asks the org chart again.
 *
 * <p>The <b>payload</b> is hashed ({@link DocumentSnapshot}) and the digest
 * stored on the document. From that point the submitted artefact is immutable:
 * {@link #updateDraft} refuses to touch it, and every action recorded against it
 * carries the digest, so a document that changed after someone signed is
 * provably a different document rather than a matter of anyone's word.
 *
 * <h2>Permissions are decided on the document</h2>
 *
 * <p>Every check here builds a {@link ApprovalContext#target(String)} from the
 * document's own company, org unit, owner and <b>business date</b> — not
 * today's. A back-dated document is authorised against the org as it stood on
 * the date it belongs to.
 */
@Service
public class ApprovalDocumentService {

    /** Refusing an operation the document's state does not allow. */
    public static class DocumentStateException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public DocumentStateException(String message) {
            super(message);
        }
    }

    private final ApprovalDocumentRepository documents;
    private final ApprovalStepRepository steps;
    private final ApprovalStepApproverRepository approvers;
    private final ApprovalActionRepository actions;
    private final UserAccountRepository accounts;
    private final PositionRepository positions;
    private final ApproverDirectory directory;
    private final ApprovalLineTemplateService templates;
    private final RepresentationDirectory representation;
    private final PermissionEvaluator permissions;
    private final ApprovalNotifications notifications;

    public ApprovalDocumentService(ApprovalDocumentRepository documents,
            ApprovalStepRepository steps, ApprovalStepApproverRepository approvers,
            ApprovalActionRepository actions, UserAccountRepository accounts,
            PositionRepository positions, ApproverDirectory directory,
            ApprovalLineTemplateService templates, RepresentationDirectory representation,
            PermissionEvaluator permissions, List<Notifier> notifiers) {
        this.documents = documents;
        this.steps = steps;
        this.approvers = approvers;
        this.actions = actions;
        this.accounts = accounts;
        this.positions = positions;
        this.directory = directory;
        this.templates = templates;
        this.representation = representation;
        this.permissions = permissions;
        this.notifications = new ApprovalNotifications(notifiers);
    }

    /** Creates a draft. Nothing is routed and nobody is notified until it is submitted. */
    @Transactional
    public ApprovalDocumentEntity draft(PermissionPrincipal actor, DraftRequest request) {
        ApprovalContext context = contextFor(actor, request.companyId(), request.businessDate());
        permissions.check(actor, ApprovalPermissions.DOCUMENT_WRITE,
                context.target("drafting a " + request.documentType())).orThrow();

        ApprovalDocumentEntity document = new ApprovalDocumentEntity(
                UUID.randomUUID().toString(), request.companyId(), request.documentType(),
                request.title(), actor.accountId());
        document.setDrafterOrgUnitId(context.drafterOrgUnitId());
        document.setAmount(request.amount());
        document.setCurrencyCode(request.currencyCode());
        return documents.save(document);
    }

    /**
     * Edits a draft.
     *
     * @throws DocumentStateException once the document has been submitted — the
     *         submitted artefact is immutable, and an amount edited after the
     *         부장 signed would make their signature a lie
     */
    @Transactional
    public ApprovalDocumentEntity updateDraft(PermissionPrincipal actor, String documentId,
            String title, BigDecimal amount, String currencyCode, LocalDate businessDate) {
        ApprovalDocumentEntity document = require(documentId);
        if (document.state() != ApprovalState.DRAFTING) {
            throw new DocumentStateException(
                    "상신된 문서는 수정할 수 없습니다. 회수하신 뒤 새로 작성해 주십시오. (This document was "
                            + "submitted and is now immutable; its state is " + document.state()
                            + ". Recall it, or ask an approver to return it, and draft the "
                            + "correction as a new document.)");
        }
        ApprovalContext context = contextFor(actor, document.companyId(), businessDate);
        permissions.check(actor, ApprovalPermissions.DOCUMENT_WRITE,
                context.target("editing draft " + documentId)).orThrow();
        requireDrafter(actor, document, "edit");

        if (Texts.hasText(title)) {
            document.setTitle(title);
        }
        document.setAmount(amount);
        document.setCurrencyCode(currencyCode);
        return documents.save(document);
    }

    /**
     * Submits a draft into its 결재선.
     *
     * @param bodyDigest the documents module's digest of the body, folded into
     *        the snapshot hash; null for a document that is only header fields
     * @throws DocumentStateException if it is not a draft
     * @throws ApproverDirectory.UnresolvableRoleException if a required step
     *         resolves to nobody — better a refused submission than a document
     *         that quietly skipped a signature
     */
    @Transactional
    public ApprovalDocumentView submit(PermissionPrincipal actor, String documentId,
            BusinessInstant submittedAt, String bodyDigest) {
        ApprovalDocumentEntity document = require(documentId);
        if (document.state() != ApprovalState.DRAFTING) {
            throw new DocumentStateException(
                    "이미 상신된 문서입니다. (This document has already been submitted; its state is "
                            + document.state() + ".)");
        }
        LocalDate businessDate = submittedAt.businessDate();
        ApprovalContext context = contextFor(actor, document.companyId(), businessDate);
        permissions.check(actor, ApprovalPermissions.DOCUMENT_WRITE,
                context.target("submitting " + documentId)).orThrow();
        requireDrafter(actor, document, "submit");

        RepresentationMode mode = representation.modeOn(document.companyId(), businessDate);
        ApprovalLineTemplate template = templates.resolve(
                document.companyId(), document.documentType(), document.drafterOrgUnitId());

        List<ResolvedStep> resolved = resolveSteps(template, document, context, mode);
        List<ApprovalStep> domainSteps = new ArrayList<ApprovalStep>();
        for (ResolvedStep step : resolved) {
            domainSteps.add(step.toDomain());
        }

        // Hash before stamping: the digest covers the submission instant, so it
        // has to be computed from the instant rather than read back off a
        // document that has already been changed.
        String snapshotHash = DocumentSnapshot.of(document, submittedAt, bodyDigest);
        ApprovalLine line = new ApprovalLine(document.id(), document.drafterAccountId(), mode,
                domainSteps);
        line.submit();

        document.setTemplateId(template.id());
        document.markSubmitted(submittedAt, snapshotHash);
        document.setState(line.state());

        for (ResolvedStep step : resolved) {
            steps.save(step.toEntity(document.id(), line));
            for (ResolvedApprover approver : step.approvers()) {
                approvers.save(new ApprovalStepApproverEntity(step.id(), approver.accountId(),
                        approver.rankLabel(), approver.displayName()));
            }
        }
        documents.save(document);

        announceSubmission(document, line, resolved, submittedAt);
        return read(actor, document.id());
    }

    /**
     * A document with its line and trail.
     *
     * <p>Costs four queries: the document, its steps, their snapshotted
     * approvers, and the trail. Deliberately not lazy associations — a 결재 screen
     * always shows all four, and a lazy line renders an empty 결재란 on the first
     * paint.
     */
    @Transactional(readOnly = true)
    public ApprovalDocumentView read(PermissionPrincipal actor, String documentId) {
        ApprovalDocumentEntity document = require(documentId);
        List<ApprovalStepEntity> stepRows = steps.findByDocumentIdOrderByPositionAsc(documentId);
        List<String> stepIds = new ArrayList<String>();
        for (ApprovalStepEntity step : stepRows) {
            stepIds.add(step.id());
        }
        List<ApprovalStepApproverEntity> approverRows = stepIds.isEmpty()
                ? Immutables.<ApprovalStepApproverEntity>listOf()
                : approvers.findByStepIdIn(stepIds);
        List<ApprovalActionEntity> trail = stepIds.isEmpty()
                ? Immutables.<ApprovalActionEntity>listOf()
                : actions.findByStepIdInOrderByActedAtBusinessDateAscActedAtOffsetSecondsAsc(
                        stepIds);

        authoriseRead(actor, document, approverRows);

        ApprovalLine line = stepRows.isEmpty() ? null : ApprovalLines.rebuild(document, stepRows,
                approverRows, trail,
                representation.modeOn(document.companyId(), businessDateOf(document)));
        return new ApprovalDocumentView(document, line, stepRows, approverRows, trail);
    }

    /** Documents this account drafted, newest first. */
    @Transactional(readOnly = true)
    public List<ApprovalDocumentEntity> draftedBy(PermissionPrincipal actor, String accountId) {
        if (!actor.accountId().equals(accountId)) {
            // Someone else's outbox is a sensitive read: it lists what they have
            // been spending and asking for. Today's date is the right one here
            // and the only honest one — the question is about a person, not
            // about a document, so there is no document date to use instead.
            permissions.check(actor, ApprovalPermissions.DOCUMENT_READ,
                    PermissionTarget.builder()
                            .asOfBusinessDate(LocalDate.now())
                            .description("documents drafted by " + accountId)
                            .build()).orThrow();
        }
        return documents.findByDrafterAccountIdOrderByCreatedAtDesc(accountId);
    }

    // ------------------------------------------------------------------
    // Line resolution
    // ------------------------------------------------------------------

    private List<ResolvedStep> resolveSteps(ApprovalLineTemplate template,
            ApprovalDocumentEntity document, ApprovalContext context, RepresentationMode mode) {

        List<ApprovalLineTemplate.TemplateStep> templateSteps =
                template.stepsFor(document.amount());
        List<ResolvedStep> resolved = new ArrayList<ResolvedStep>();
        boolean hasDraftStep = false;
        int lowestPosition = Integer.MAX_VALUE;

        for (int i = 0; i < templateSteps.size(); i++) {
            ApprovalLineTemplate.TemplateStep templateStep = templateSteps.get(i);
            RoleExpression role = templateStep.role();
            List<ResolvedApprover> people = directory.resolve(role, context);
            if (people.isEmpty()) {
                if (templateStep.isOptional()) {
                    // A 법무 concurrence in a company with no legal team.
                    continue;
                }
                throw new ApproverDirectory.UnresolvableRoleException(
                        "결재선의 \"" + role + "\" 단계에 해당하는 사람이 없습니다. (Step "
                                + templateStep.position() + " (" + templateStep.kind() + ") of "
                                + "template " + template.id() + " resolves \"" + role + "\" to "
                                + "nobody as of " + context.businessDate() + ". Submitting anyway "
                                + "would produce a document that silently skipped a signature.)");
            }
            int required = requiredApprovals(templateStep, mode);
            resolved.add(new ResolvedStep(UUID.randomUUID().toString(), templateStep.position(),
                    templateStep.kind(), role.toString(), people, required));
            hasDraftStep = hasDraftStep || templateStep.kind() == ApprovalStepKind.DRAFT;
            lowestPosition = Math.min(lowestPosition, templateStep.position());
        }

        if (!hasDraftStep) {
            // 회수 is recorded against the first step, and a 결재란 with no 기안
            // column has nowhere to print who wrote the thing. Synthesised
            // rather than demanded of the template, because forgetting it in a
            // template is a mistake nobody would catch until a recall failed.
            ResolvedApprover drafter = drafterAsApprover(document.drafterAccountId());
            List<ResolvedApprover> one = new ArrayList<ResolvedApprover>();
            one.add(drafter);
            resolved.add(0, new ResolvedStep(UUID.randomUUID().toString(),
                    lowestPosition == Integer.MAX_VALUE ? 0 : lowestPosition - 1,
                    ApprovalStepKind.DRAFT, "drafter", one, 1));
        }
        return resolved;
    }

    /**
     * How many distinct signatures a step needs.
     *
     * <p>Only a representative-level approving step carries a quorum, and it
     * takes it from the company's mode. Under 공동대표 that is 2-of-3 or all-of-N;
     * under 각자대표 it is 1, and any one 대표 finishes it. A 검토 step is one
     * person's job however many people could do it.
     */
    private int requiredApprovals(ApprovalLineTemplate.TemplateStep step,
            RepresentationMode mode) {
        boolean representative =
                step.role().selector() == RoleExpression.Selector.REPRESENTATIVE;
        if (representative && step.kind().canBlock()) {
            return mode.requiredApprovals();
        }
        return 1;
    }

    private ResolvedApprover drafterAsApprover(String accountId) {
        Optional<UserAccount> account = accounts.findById(accountId);
        String name = account.isPresent() ? account.get().displayName() : accountId;
        return new ResolvedApprover(accountId, name, null, 0);
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void announceSubmission(ApprovalDocumentEntity document, ApprovalLine line,
            List<ResolvedStep> resolved, BusinessInstant at) {
        Set<String> told = new LinkedHashSet<String>();
        for (ResolvedStep step : resolved) {
            boolean pending = false;
            for (ApprovalStep domainStep : line.pendingSteps()) {
                pending = pending || domainStep.id().equals(step.id());
            }
            for (ResolvedApprover approver : step.approvers()) {
                if (!told.add(approver.accountId())) {
                    continue;
                }
                if (pending) {
                    notifications.send(new ApprovalNotification(
                            ApprovalNotification.Kind.AWAITING_YOUR_ACTION, approver.accountId(),
                            document.id(), document.title(), null, null, null, at));
                } else if (step.kind() == ApprovalStepKind.CC) {
                    notifications.send(new ApprovalNotification(
                            ApprovalNotification.Kind.FOR_YOUR_INFORMATION, approver.accountId(),
                            document.id(), document.title(), null, null, null, at));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers, used by ApprovalActionService too
    // ------------------------------------------------------------------

    ApprovalDocumentEntity require(String documentId) {
        Optional<ApprovalDocumentEntity> found = documents.findById(documentId);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such approval document: " + documentId);
        }
        return found.get();
    }

    /**
     * The context for a document, resolved as of a business date.
     *
     * <p>The drafter's unit comes from their position <em>on that date</em>,
     * preferring the primary one. Someone who holds two posts drafts from the
     * one they were appointed to primarily, which is the one whose 부장 signs.
     */
    ApprovalContext contextFor(PermissionPrincipal actor, String companyId,
            LocalDate businessDate) {
        String employeeId = actor.employeeId();
        String orgUnitId = null;
        if (Texts.hasText(employeeId)) {
            List<Position> held = positions.findActiveOn(employeeId, businessDate);
            for (Position position : held) {
                if (orgUnitId == null || position.isPrimary()) {
                    orgUnitId = position.orgUnitId();
                }
                if (position.isPrimary()) {
                    break;
                }
            }
        }
        return new ApprovalContext(companyId, actor.accountId(), employeeId, orgUnitId,
                businessDate);
    }

    /** The date a document belongs to: its submission date, or today while it is a draft. */
    static LocalDate businessDateOf(ApprovalDocumentEntity document) {
        return document.submittedAt() == null
                ? LocalDate.now()
                : document.submittedAt().businessDate();
    }

    private void requireDrafter(PermissionPrincipal actor, ApprovalDocumentEntity document,
            String verb) {
        if (!document.drafterAccountId().equals(actor.accountId())) {
            throw new DocumentStateException(
                    "기안자만 " + ("submit".equals(verb) ? "상신" : "수정") + "할 수 있습니다. (Only the "
                            + "drafter may " + verb + " this document.)");
        }
    }

    /**
     * Reading a document you were routed needs no grant.
     *
     * <p>The drafter and everyone the line resolved to can always open it —
     * otherwise the inbox would list documents their permissions will not let
     * them open, which is a worse experience than not listing them and a worse
     * security story than either. Everyone else needs
     * {@link ApprovalPermissions#DOCUMENT_READ} against the document's own
     * target.
     */
    private void authoriseRead(PermissionPrincipal actor, ApprovalDocumentEntity document,
            List<ApprovalStepApproverEntity> approverRows) {
        if (document.drafterAccountId().equals(actor.accountId())) {
            return;
        }
        for (ApprovalStepApproverEntity approver : approverRows) {
            if (approver.accountId().equals(actor.accountId())) {
                return;
            }
        }
        permissions.check(actor, ApprovalPermissions.DOCUMENT_READ,
                PermissionTarget.builder()
                        .companyId(document.companyId())
                        .orgUnitId(document.drafterOrgUnitId())
                        .asOfBusinessDate(businessDateOf(document))
                        .description(document.documentType() + " " + document.id())
                        .build()).orThrow();
    }

    /** A step with its people, between resolution and persistence. */
    static final class ResolvedStep {
        private final String id;
        private final int position;
        private final ApprovalStepKind kind;
        private final String roleExpression;
        private final List<ResolvedApprover> approvers;
        private final int requiredApprovals;

        ResolvedStep(String id, int position, ApprovalStepKind kind, String roleExpression,
                List<ResolvedApprover> approvers, int requiredApprovals) {
            this.id = id;
            this.position = position;
            this.kind = kind;
            this.roleExpression = roleExpression;
            this.approvers = Immutables.copyOf(approvers);
            this.requiredApprovals = requiredApprovals;
        }

        String id() {
            return id;
        }

        ApprovalStepKind kind() {
            return kind;
        }

        List<ResolvedApprover> approvers() {
            return approvers;
        }

        ApprovalStep toDomain() {
            Set<String> eligible = new LinkedHashSet<String>();
            for (ResolvedApprover approver : approvers) {
                eligible.add(approver.accountId());
            }
            return new ApprovalStep(id, position, kind, roleExpression, eligible,
                    requiredApprovals);
        }

        ApprovalStepEntity toEntity(String documentId, ApprovalLine line) {
            ApprovalStepEntity entity = new ApprovalStepEntity(id, documentId, position, kind,
                    roleExpression, requiredApprovals);
            for (ApprovalStep step : line.steps()) {
                if (step.id().equals(id)) {
                    entity.setState(step.state());
                }
            }
            return entity;
        }
    }
}
