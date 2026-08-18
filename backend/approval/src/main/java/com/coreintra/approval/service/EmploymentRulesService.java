package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.repository.ApprovalActionRepository;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.repository.ApprovalStepApproverRepository;
import com.coreintra.approval.repository.ApprovalStepRepository;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
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
 * 취업규칙 — employment rules, and the one invariant in this system that no
 * account can talk its way past.
 *
 * <h2>Creating, amending or repealing requires 대표자 결재</h2>
 *
 * <p>Not "requires a permission that only administrators have". A permission
 * asks <em>may this caller do this?</em>, and for a master account the answer is
 * usually yes. This asks <em>has the 대표 approved it?</em>, and for a master
 * account the answer is still no, because a master account does not have a
 * completed approval document and cannot conjure one.
 *
 * <p>The invariant is structural rather than procedural. {@link EmploymentRules}
 * has no public constructor; {@link EmploymentRules#publish} is the only way to
 * produce a version, and it demands an approval document that is
 * {@link ApprovalState#APPROVED} with enough <em>distinct</em> representative
 * signatures for the mode in force. This service adds the checks that need the
 * database — that the document exists, that it is the right kind of document,
 * that its approvers really were representatives — and then hands over. It
 * cannot skip the domain call because there is nothing else to call.
 *
 * <p>{@link ApprovalPermissions#RULES_WRITE} is checked on proposing a change
 * and again on enacting one. Neither check is the invariant: both are about who
 * may operate the machinery, and the 대표's signature is about whether it may
 * run at all. A caller holding the permission and no approval gets nowhere.
 *
 * <h2>공동대표</h2>
 *
 * <p>The quorum is the one in force on the approval's own date, and it counts
 * distinct people. A single 대표 approving twice, or one 대표 approving a document
 * whose company requires two, leaves the document
 * {@link ApprovalState#PARTIALLY_APPROVED} and the publish call refuses it.
 */
@Service
public class EmploymentRulesService {

    /** The document type a 취업규칙 change must be filed as. */
    public static final String DOCUMENT_TYPE = "EMPLOYMENT_RULES";

    private final EmploymentRulesStore store;
    private final ApprovalDocumentRepository documents;
    private final ApprovalStepRepository steps;
    private final ApprovalStepApproverRepository approvers;
    private final ApprovalActionRepository actions;
    private final RepresentationDirectory representation;
    private final ApproverDirectory directory;
    private final PermissionEvaluator permissions;
    private final ApprovalDocumentService drafts;

    public EmploymentRulesService(EmploymentRulesStore store,
            ApprovalDocumentRepository documents, ApprovalStepRepository steps,
            ApprovalStepApproverRepository approvers, ApprovalActionRepository actions,
            RepresentationDirectory representation, ApproverDirectory directory,
            PermissionEvaluator permissions, ApprovalDocumentService drafts) {
        this.store = store;
        this.documents = documents;
        this.steps = steps;
        this.approvers = approvers;
        this.actions = actions;
        this.representation = representation;
        this.directory = directory;
        this.permissions = permissions;
        this.drafts = drafts;
    }

    /**
     * Enacts a version, given the 결재 document that authorised it.
     *
     * <p>There is no other method that writes employment rules. No overload
     * takes an "administrative override" flag, and none skips the approval
     * document: adding one would be the bypass this class exists to prevent, and
     * {@code EmploymentRulesServiceTest} asserts that none has appeared.
     *
     * @param approvalDocumentId a document of type {@link #DOCUMENT_TYPE} that
     *        has reached {@link ApprovalState#APPROVED}
     * @throws EmploymentRules.RepresentativeApprovalRequiredException if the
     *         approval is missing, incomplete, of the wrong kind, or signed by
     *         too few distinct representatives for the mode in force
     */
    @Transactional
    public EmploymentRules publish(PermissionPrincipal actor, String companyId,
            LocalDate effectiveFrom, List<EmploymentRules.Section> sections,
            String approvalDocumentId) {

        if (Texts.isBlank(approvalDocumentId)) {
            throw new EmploymentRules.RepresentativeApprovalRequiredException(
                    "취업규칙의 제정·변경·폐지는 대표자 결재가 필요합니다. (Employment rules cannot be "
                            + "created, amended or repealed without an approval document. There "
                            + "is no administrative override, and a master account does not have "
                            + "one either.)");
        }
        ApprovalDocumentEntity approval = requireApproval(companyId, approvalDocumentId);
        permissions.check(actor, ApprovalPermissions.RULES_WRITE,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(effectiveFrom)
                        .description("enacting 취업규칙 under approval " + approvalDocumentId)
                        .build()).orThrow();
        if (approval.state() == ApprovalState.APPROVED) {
            // Only meaningful once there is an approval to compare against. An
            // unfinished one should say so, rather than complaining about text
            // nobody has agreed to yet.
            requireApprovedText(approval, sections);
        }
        LocalDate approvedOn = approval.submittedAt() == null
                ? effectiveFrom
                : approval.submittedAt().businessDate();
        RepresentationMode mode = representation.modeOn(companyId, approvedOn);

        // Only signatures from people who were representatives on the approval's
        // own date count. Someone promoted to 대표 last week did not approve this
        // as a 대표 last month, and someone who has since stepped down did.
        List<String> representativeSignatures = representativeSignatories(approval, companyId,
                approvedOn);

        EmploymentRules published = EmploymentRules.publish(
                UUID.randomUUID().toString(), companyId, store.nextVersionNumber(companyId),
                effectiveFrom, sections, approvalDocumentId, approval.state(), mode,
                representativeSignatures);
        store.save(published);
        return published;
    }

    /**
     * Drafts a proposed change and files it for 대표자 결재.
     *
     * <p>An HR manager may propose an amendment. Whether it takes effect is not
     * theirs to decide.
     */
    @Transactional
    public ApprovalDocumentEntity proposeChange(PermissionPrincipal actor, String companyId,
            String title, LocalDate businessDate) {
        permissions.check(actor, ApprovalPermissions.RULES_WRITE,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(businessDate)
                        .description("proposing a 취업규칙 change")
                        .build()).orThrow();

        ApprovalDocumentEntity document = new ApprovalDocumentEntity(
                UUID.randomUUID().toString(), companyId, DOCUMENT_TYPE,
                Texts.hasText(title) ? title : "취업규칙 개정", actor.accountId());
        // The drafter's unit, resolved the same way an ordinary draft resolves
        // it. Leaving it null would look harmless and would not be: the line
        // template would fall through to the company default, every
        // DRAFTER_UNIT role expression would resolve to nobody, and every
        // permission target for the document would name no unit.
        document.setDrafterOrgUnitId(
                drafts.contextFor(actor, companyId, businessDate).drafterOrgUnitId());
        return documents.save(document);
    }

    /** The version in force on a date. What an employee is actually bound by. */
    @Transactional(readOnly = true)
    public Optional<EmploymentRules> effectiveOn(PermissionPrincipal actor, String companyId,
            LocalDate on) {
        permissions.check(actor, ApprovalPermissions.RULES_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(on)
                        .description("취업규칙 effective on " + on)
                        .build()).orThrow();
        return store.findEffectiveOn(companyId, on);
    }

    /**
     * Section-level diff of a version against the one before it.
     *
     * <p>Empty for the first version — there is nothing to compare a first
     * edition with, and rendering every section as ADDED would drown the reader
     * in a change list on the one occasion nothing has changed.
     */
    @Transactional(readOnly = true)
    public List<EmploymentRules.SectionDiff> diffAgainstPrevious(PermissionPrincipal actor,
            String companyId, int version) {
        permissions.check(actor, ApprovalPermissions.RULES_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(LocalDate.now())
                        .description("취업규칙 v" + version + " diff")
                        .build()).orThrow();

        EmploymentRules current = null;
        EmploymentRules previous = null;
        for (EmploymentRules candidate : store.findAllVersions(companyId)) {
            if (candidate.version() == version) {
                current = candidate;
            } else if (candidate.version() < version
                    && (previous == null || candidate.version() > previous.version())) {
                previous = candidate;
            }
        }
        if (current == null) {
            throw new IllegalArgumentException(
                    "no version " + version + " of 취업규칙 exists for company " + companyId);
        }
        return previous == null
                ? Immutables.<EmploymentRules.SectionDiff>listOf()
                : current.diffAgainst(previous);
    }

    /**
     * Records that an employee has read a version.
     *
     * <p>Per employee <em>per version</em>. Acknowledging the 2024 rules says
     * nothing about the 2026 ones, and a receipt that carried over would be
     * worthless as evidence precisely when it was needed.
     */
    @Transactional
    public void acknowledge(PermissionPrincipal actor, String rulesId, String employeeId,
            LocalDate acknowledgedOn) {
        if (!employeeId.equals(actor.employeeId())) {
            throw new IllegalArgumentException(
                    "본인만 확인 처리를 할 수 있습니다. (An acknowledgement is a personal statement that "
                            + "you have read the rules. Nobody may record one on your behalf.)");
        }
        store.acknowledge(rulesId, employeeId, acknowledgedOn);
    }

    /** Who has acknowledged a version, for the HR follow-up list. */
    @Transactional(readOnly = true)
    public List<String> acknowledgedBy(PermissionPrincipal actor, String companyId,
            String rulesId) {
        permissions.check(actor, ApprovalPermissions.RULES_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(LocalDate.now())
                        .description("취업규칙 acknowledgements")
                        .build()).orThrow();
        return store.acknowledgedBy(rulesId);
    }

    // ------------------------------------------------------------------

    /**
     * The digest of a set of sections, for submitting a proposal.
     *
     * <p>Pass this as the body digest when submitting the proposal document, so
     * that {@link #publish} can prove the text being enacted is the text that
     * was approved. Ordered, and both languages, because reordering the articles
     * or rewriting only the English is still a change to the rules.
     */
    public static String textDigest(List<EmploymentRules.Section> sections) {
        List<String> parts = new ArrayList<String>();
        for (EmploymentRules.Section section : sections) {
            parts.add(section.number());
            parts.add(section.headingKo());
            parts.add(section.headingEn());
            parts.add(section.bodyKo());
            parts.add(section.bodyEn());
        }
        return DocumentSnapshot.ofParts(parts);
    }

    /**
     * The text being enacted must be the text that was approved.
     *
     * <p>Without this, the 대표's signature authorises a <em>document id</em>
     * rather than a set of words: anyone holding the id could enact different
     * sections under it, and the approval would still look valid. Re-deriving
     * the submitted digest from the sections in hand and comparing it to the one
     * frozen at submission is what makes the signature mean the words.
     *
     * <p>Skipped only when the proposal was submitted without a body digest at
     * all — an older document, or one whose text lives somewhere this service
     * cannot see. That case is worth being explicit about rather than silently
     * accepting: it is the one place where the guarantee thins.
     */
    private void requireApprovedText(ApprovalDocumentEntity approval,
            List<EmploymentRules.Section> sections) {

        String frozen = approval.submittedSnapshotHash();
        if (Texts.isBlank(frozen)) {
            return;
        }
        String expected = DocumentSnapshot.of(approval, textDigest(sections));
        if (!expected.equals(frozen)) {
            throw new EmploymentRules.RepresentativeApprovalRequiredException(
                    "결재된 내용과 시행하려는 내용이 다릅니다. (The sections being enacted are not the ones "
                            + "the 대표 approved: the digest recorded at submission was " + frozen
                            + " and this text hashes to " + expected + ". Submit the amended text "
                            + "for approval rather than enacting it under an approval given for "
                            + "something else.)");
        }
    }

    private ApprovalDocumentEntity requireApproval(String companyId, String approvalDocumentId) {
        Optional<ApprovalDocumentEntity> found = documents.findById(approvalDocumentId);
        if (!found.isPresent()) {
            throw new EmploymentRules.RepresentativeApprovalRequiredException(
                    "결재 문서 " + approvalDocumentId + " 을(를) 찾을 수 없습니다. (No approval document "
                            + approvalDocumentId + " exists, so nothing authorises this change.)");
        }
        ApprovalDocumentEntity approval = found.get();
        if (!approval.companyId().equals(companyId)) {
            throw new EmploymentRules.RepresentativeApprovalRequiredException(
                    "approval document " + approvalDocumentId + " belongs to company "
                            + approval.companyId() + ", not " + companyId
                            + ". One company's 대표 cannot enact another company's rules.");
        }
        if (!DOCUMENT_TYPE.equals(approval.documentType())) {
            // Without this, an approved expense claim would be a valid licence
            // to rewrite the employment rules.
            throw new EmploymentRules.RepresentativeApprovalRequiredException(
                    "approval document " + approvalDocumentId + " is a \""
                            + approval.documentType() + "\", not a \"" + DOCUMENT_TYPE + "\". "
                            + "The 대표 approved something else.");
        }
        return approval;
    }

    /**
     * The distinct representatives who actually signed.
     *
     * <p>Read from the trail rather than from the line, and filtered against who
     * held a representative rank on the approval date. 반려, 보류 and 참조 are not
     * signatures; 대결 is attributed to the acting person, who is not a 대표 —
     * which is exactly why a 취업규칙 change cannot be waved through by a stand-in.
     */
    private List<String> representativeSignatories(ApprovalDocumentEntity approval,
            String companyId, LocalDate approvedOn) {

        List<ApprovalStepEntity> stepRows =
                steps.findByDocumentIdOrderByPositionAsc(approval.id());
        if (stepRows.isEmpty()) {
            return Immutables.<String>listOf();
        }
        List<String> stepIds = new ArrayList<String>();
        for (ApprovalStepEntity step : stepRows) {
            stepIds.add(step.id());
        }
        // The snapshot, not today's org chart: a signature counts only if the
        // signer was one of the people this document was actually routed to.
        List<ApprovalStepApproverEntity> snapshot = approvers.findByStepIdIn(stepIds);
        Set<String> snapshotted = new LinkedHashSet<String>();
        for (ApprovalStepApproverEntity approver : snapshot) {
            snapshotted.add(approver.accountId());
        }

        Set<String> representativeAccounts = new LinkedHashSet<String>();
        for (ResolvedApprover representative : directory.representatives(companyId, approvedOn)) {
            representativeAccounts.add(representative.accountId());
        }

        List<String> signatories = new ArrayList<String>();
        for (ApprovalActionEntity action :
                actions.findByStepIdInOrderByActedAtBusinessDateAscActedAtOffsetSecondsAsc(
                        stepIds)) {
            boolean signature = action.action() == ApprovalAction.APPROVE
                    || action.action() == ApprovalAction.DELEGATED_FINAL;
            if (!signature) {
                continue;
            }
            String accountId = action.actorAccountId();
            if (!snapshotted.contains(accountId) || !representativeAccounts.contains(accountId)) {
                continue;
            }
            if (!signatories.contains(accountId)) {
                signatories.add(accountId);
            }
        }
        return signatories;
    }
}
