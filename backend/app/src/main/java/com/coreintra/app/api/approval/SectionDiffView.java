package com.coreintra.app.api.approval;

import com.coreintra.approval.rules.EmploymentRules;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One article's worth of change between two versions of 취업규칙.
 *
 * <p>§4 asks for a section-level diff rather than a text diff, and the reason is
 * the audience. A 대표이사 approving an amendment needs to see "제12조 amended,
 * 제31조 added" and then read those two articles; a character-level diff of a
 * legal document is unreadable on the one occasion it matters most.
 *
 * <p>{@code UNCHANGED} entries are included so that the client can render the
 * whole document with changes marked in place, rather than a change list floating
 * free of the text it changes.
 */
@Schema(name = "EmploymentRulesSectionDiff",
        description = "How one article differs from the previous version.")
public class SectionDiffView {

    private final String sectionNumber;
    private final String kind;
    private final EmploymentRulesView.SectionView before;
    private final EmploymentRulesView.SectionView after;

    private SectionDiffView(EmploymentRules.SectionDiff diff) {
        this.sectionNumber = diff.sectionNumber();
        this.kind = diff.kind().name();
        this.before = diff.before() == null
                ? null : new EmploymentRulesView.SectionView(diff.before());
        this.after = diff.after() == null
                ? null : new EmploymentRulesView.SectionView(diff.after());
    }

    public static SectionDiffView from(EmploymentRules.SectionDiff diff) {
        return new SectionDiffView(diff);
    }

    @Schema(example = "제12조")
    public String getSectionNumber() {
        return sectionNumber;
    }

    @Schema(description = "ADDED, REMOVED, AMENDED or UNCHANGED.", example = "AMENDED")
    public String getKind() {
        return kind;
    }

    @Schema(description = "Null when the article is new.")
    public EmploymentRulesView.SectionView getBefore() {
        return before;
    }

    @Schema(description = "Null when the article was repealed.")
    public EmploymentRulesView.SectionView getAfter() {
        return after;
    }
}
