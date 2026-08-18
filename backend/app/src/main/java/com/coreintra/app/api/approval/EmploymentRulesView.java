package com.coreintra.app.api.approval;

import com.coreintra.app.api.http.ETags;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 취업규칙 — one version of the employment rules, with the signatures that
 * enacted it.
 *
 * <h2>The provenance is part of the document</h2>
 *
 * <p>{@link #getApprovalDocumentId()}, {@link #getApprovedUnderMode()} and
 * {@link #getApprovingRepresentativeIds()} are not metadata. §4 makes 대표자
 * 결재 a domain invariant with no administrative override, and a version that
 * could not say which representatives signed it, under which representation
 * mode, would be a claim rather than a record. An employee reading the rules
 * they are bound by can see who bound them.
 *
 * <p>The ETag is the version number, which is the one place in this API where a
 * genuine monotonic counter already exists.
 */
@Schema(name = "EmploymentRules", description = "One version of a company's 취업규칙.")
public class EmploymentRulesView {

    /** One 조 of the rules, in both languages. */
    @Schema(name = "EmploymentRulesSection")
    public static class SectionView {
        private final String number;
        private final String headingKo;
        private final String headingEn;
        private final String bodyKo;
        private final String bodyEn;

        SectionView(EmploymentRules.Section section) {
            this.number = section.number();
            this.headingKo = section.headingKo();
            this.headingEn = section.headingEn();
            this.bodyKo = section.bodyKo();
            this.bodyEn = section.bodyEn();
        }

        @Schema(example = "제12조")
        public String getNumber() {
            return number;
        }

        public String getHeadingKo() {
            return headingKo;
        }

        public String getHeadingEn() {
            return headingEn;
        }

        public String getBodyKo() {
            return bodyKo;
        }

        public String getBodyEn() {
            return bodyEn;
        }
    }

    private final String id;
    private final String companyId;
    private final int version;
    private final String effectiveFrom;
    private final String approvalDocumentId;
    private final String approvedUnderMode;
    private final List<String> approvingRepresentativeIds;
    private final List<SectionView> sections;
    private final String etag;

    private EmploymentRulesView(EmploymentRules rules) {
        this.id = rules.id();
        this.companyId = rules.companyId();
        this.version = rules.version();
        this.effectiveFrom = rules.effectiveFrom().toString();
        this.approvalDocumentId = rules.approvalDocumentId();
        this.approvedUnderMode = rules.approvedUnderMode() == null
                ? null : rules.approvedUnderMode().toString();
        this.approvingRepresentativeIds = Immutables.copyOf(rules.approvingRepresentativeIds());
        List<SectionView> views = new ArrayList<SectionView>();
        for (EmploymentRules.Section section : rules.sections()) {
            views.add(new SectionView(section));
        }
        this.sections = Immutables.copyOf(views);
        this.etag = tagOf(rules);
    }

    public static EmploymentRulesView from(EmploymentRules rules) {
        return new EmploymentRulesView(rules);
    }

    /** The version number is a real counter, so the tag needs nothing derived. */
    public static String tagOf(EmploymentRules rules) {
        return ETags.of(rules.id(), rules.version());
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    @Schema(description = "Increases by one per enacted version, never reused.")
    public int getVersion() {
        return version;
    }

    @Schema(description = "The date this version took effect, YYYY-MM-DD. Employees see the "
            + "version that was effective on the date being asked about, not the newest one.")
    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    @Schema(description = "The 결재 document that enacted this version. Never null: there "
            + "is no path to a version without one.")
    public String getApprovalDocumentId() {
        return approvalDocumentId;
    }

    @Schema(description = "The representation mode in force when it was enacted, e.g. "
            + "공동대표 (2 of 3).")
    public String getApprovedUnderMode() {
        return approvedUnderMode;
    }

    @Schema(description = "The representatives who actually signed. Under 공동대표 there are "
            + "at least as many as the quorum required.")
    public List<String> getApprovingRepresentativeIds() {
        return approvingRepresentativeIds;
    }

    @Schema(description = "Ordered, and in both languages: reordering the articles or "
            + "rewriting only the English is still a change to the rules.")
    public List<SectionView> getSections() {
        return sections;
    }

    public String getEtag() {
        return etag;
    }
}
