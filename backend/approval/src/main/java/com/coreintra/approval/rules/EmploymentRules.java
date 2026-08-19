package com.coreintra.approval.rules;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 취업규칙 — the employment rules, versioned, and changeable only by 대표자 결재.
 *
 * <h2>The invariant</h2>
 *
 * <p>Creating, amending or repealing employment rules requires representative
 * approval under the company's active representation mode. <b>No admin, no
 * master account, and no API path may bypass this.</b> It is not a permission
 * check that a sufficiently privileged caller can satisfy — it is a domain
 * invariant enforced here, in the only constructor that can produce an
 * effective version.
 *
 * <p>That distinction matters. A permission check asks "may this caller do
 * this?", and the answer for a master account is usually yes. This asks "has
 * the 대표 approved it?", and for a master account the answer is still no.
 *
 * <p>Under 공동대표 the quorum applies here exactly as it does anywhere else:
 * a document that is merely {@link ApprovalState#PARTIALLY_APPROVED} has not
 * satisfied it.
 */
public final class EmploymentRules implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Attempted change without valid representative approval. */
    public static class RepresentativeApprovalRequiredException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public RepresentativeApprovalRequiredException(String message) {
            super(message);
        }
    }

    /** One numbered section, in both languages. */
    public static final class Section implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String number;
        private final String headingKo;
        private final String headingEn;
        private final String bodyKo;
        private final String bodyEn;

        public Section(String number, String headingKo, String headingEn, String bodyKo,
                String bodyEn) {
            if (Texts.isBlank(number)) {
                throw new IllegalArgumentException("a section needs a number, e.g. 제12조");
            }
            if (Texts.isBlank(bodyKo)) {
                // Korean is authoritative: these rules are filed with and read
                // by Korean authorities and Korean employees.
                throw new IllegalArgumentException(
                        "section " + number + " needs Korean text; the Korean version is the "
                                + "authoritative one");
            }
            this.number = number;
            this.headingKo = headingKo;
            this.headingEn = headingEn;
            this.bodyKo = bodyKo;
            this.bodyEn = bodyEn;
        }

        public String number() {
            return number;
        }

        public String headingKo() {
            return headingKo;
        }

        public String headingEn() {
            return headingEn;
        }

        public String bodyKo() {
            return bodyKo;
        }

        public String bodyEn() {
            return bodyEn;
        }

        /** Content equality, ignoring the number. Used by the diff. */
        boolean sameContentAs(Section other) {
            return equalOrBothNull(headingKo, other.headingKo)
                    && equalOrBothNull(headingEn, other.headingEn)
                    && equalOrBothNull(bodyKo, other.bodyKo)
                    && equalOrBothNull(bodyEn, other.bodyEn);
        }

        private static boolean equalOrBothNull(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }
    }

    /** What changed between two versions, section by section. */
    public static final class SectionDiff implements Serializable {
        private static final long serialVersionUID = 1L;

        public enum ChangeKind {
            ADDED, REMOVED, AMENDED, UNCHANGED
        }

        private final String sectionNumber;
        private final ChangeKind kind;
        private final Section before;
        private final Section after;

        SectionDiff(String sectionNumber, ChangeKind kind, Section before, Section after) {
            this.sectionNumber = sectionNumber;
            this.kind = kind;
            this.before = before;
            this.after = after;
        }

        public String sectionNumber() {
            return sectionNumber;
        }

        public ChangeKind kind() {
            return kind;
        }

        public Section before() {
            return before;
        }

        public Section after() {
            return after;
        }
    }

    private final String id;
    private final String companyId;
    private final int version;
    private final LocalDate effectiveFrom;
    private final List<Section> sections;
    private final String approvalDocumentId;
    private final RepresentationMode approvedUnderMode;
    private final List<String> approvingRepresentativeIds;

    private EmploymentRules(String id, String companyId, int version, LocalDate effectiveFrom,
            List<Section> sections, String approvalDocumentId,
            RepresentationMode approvedUnderMode, List<String> approvingRepresentativeIds) {
        this.id = id;
        this.companyId = companyId;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
        this.sections = Immutables.copyOf(sections);
        this.approvalDocumentId = approvalDocumentId;
        this.approvedUnderMode = approvedUnderMode;
        this.approvingRepresentativeIds = Immutables.copyOf(approvingRepresentativeIds);
    }

    /**
     * The only way to produce an effective version.
     *
     * <p>There is deliberately no other constructor, no setter, and no
     * package-private back door. A caller who wants to publish employment rules
     * must present an approval document that reached {@link ApprovalState#APPROVED}
     * with the required number of distinct representatives — which is what makes
     * "no admin, no master, no API path may bypass" true by construction rather
     * than by policy.
     *
     * @param approvalState        the state the approval document actually reached
     * @param approvingRepresentativeIds the distinct representatives who approved
     * @throws RepresentativeApprovalRequiredException if the approval is missing,
     *         incomplete, or satisfied by too few representatives
     */
    public static EmploymentRules publish(String id, String companyId, int version,
            LocalDate effectiveFrom, List<Section> sections, String approvalDocumentId,
            ApprovalState approvalState, RepresentationMode mode,
            List<String> approvingRepresentativeIds) {

        if (mode == null) {
            throw new RepresentativeApprovalRequiredException(
                    "the company's representation mode must be known before employment rules can "
                            + "be published; it decides how many representatives must approve");
        }
        if (Texts.isBlank(approvalDocumentId)) {
            throw new RepresentativeApprovalRequiredException(
                    "취업규칙의 제정·변경·폐지는 대표자 결재가 필요합니다. "
                            + "(Employment rules cannot be created, amended or repealed without an "
                            + "approval document. There is no administrative override, and a "
                            + "master account does not have one either.)");
        }
        if (approvalState != ApprovalState.APPROVED) {
            throw new RepresentativeApprovalRequiredException(
                    "the approval document for these employment rules is " + approvalState
                            + ", not APPROVED. Under " + mode + " it is not yet effective."
                            + (approvalState == ApprovalState.PARTIALLY_APPROVED
                                    ? " A joint-representation quorum that is partially met is not met."
                                    : ""));
        }

        List<String> distinct = new ArrayList<String>();
        if (approvingRepresentativeIds != null) {
            for (String representative : approvingRepresentativeIds) {
                if (representative != null && !distinct.contains(representative)) {
                    distinct.add(representative);
                }
            }
        }
        if (!mode.isSatisfiedBy(distinct.size())) {
            throw new RepresentativeApprovalRequiredException(
                    "these employment rules were approved by " + distinct.size()
                            + " representative(s), but " + mode + " requires "
                            + mode.requiredApprovals()
                            + ". Distinct representatives are required; one person approving "
                            + "twice does not satisfy a quorum.");
        }
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("employment rules need at least one section");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "employment rules need an effective date; employees must be able to see which "
                            + "version applied on any given day");
        }
        return new EmploymentRules(id, companyId, version, effectiveFrom, sections,
                approvalDocumentId, mode, distinct);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public int version() {
        return version;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public List<Section> sections() {
        return sections;
    }

    /** The 결재 document that authorised this version. Never null. */
    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    /** The mode in force when it was approved, not the mode in force now. */
    public RepresentationMode approvedUnderMode() {
        return approvedUnderMode;
    }

    public List<String> approvingRepresentativeIds() {
        return approvingRepresentativeIds;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFrom);
    }

    /**
     * Section-level diff against the prior version.
     *
     * <p>Ordered by section number as it appears in the newer version, so a
     * reviewer reads the diff in the order the document reads. Sections removed
     * entirely are appended, because they have no position in the new document
     * and dropping them from the diff would hide a repeal.
     */
    public List<SectionDiff> diffAgainst(EmploymentRules previous) {
        List<SectionDiff> diffs = new ArrayList<SectionDiff>();
        Map<String, Section> before = new LinkedHashMap<String, Section>();
        if (previous != null) {
            for (Section section : previous.sections) {
                before.put(section.number(), section);
            }
        }

        for (Section current : sections) {
            Section prior = before.remove(current.number());
            if (prior == null) {
                diffs.add(new SectionDiff(current.number(), SectionDiff.ChangeKind.ADDED,
                        null, current));
            } else if (prior.sameContentAs(current)) {
                diffs.add(new SectionDiff(current.number(), SectionDiff.ChangeKind.UNCHANGED,
                        prior, current));
            } else {
                diffs.add(new SectionDiff(current.number(), SectionDiff.ChangeKind.AMENDED,
                        prior, current));
            }
        }
        for (Section removed : before.values()) {
            diffs.add(new SectionDiff(removed.number(), SectionDiff.ChangeKind.REMOVED,
                    removed, null));
        }
        return Immutables.copyOf(diffs);
    }

    /** Only the sections that actually changed, for the review summary. */
    public List<SectionDiff> substantiveChangesAgainst(EmploymentRules previous) {
        List<SectionDiff> changes = new ArrayList<SectionDiff>();
        for (SectionDiff diff : diffAgainst(previous)) {
            if (diff.kind() != SectionDiff.ChangeKind.UNCHANGED) {
                changes.add(diff);
            }
        }
        return Immutables.copyOf(changes);
    }
}
