package com.coreintra.app.api.approval;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The 결재선 서식 a document type gets, and what an amount would add to it.
 *
 * <h2>Roles, not people</h2>
 *
 * <p>A template names role expressions — "the 부장 of the drafter's unit",
 * "anyone with {@code finance.expense:approve}", "the 대표". They are resolved
 * to people once, at submission, and snapshotted, so this view shows the shape
 * of the line rather than the names on it. Showing names here would be a
 * prediction, and a reorg between the preview and the submission would make it a
 * wrong one.
 *
 * <h2>Why the threshold explanation is part of the response</h2>
 *
 * <p>"Expenses over 5,000,000원 add a 이사 step" is a rule the drafter needs
 * before they submit, not after. {@link #getEffectiveSteps()} is what this
 * amount actually produces and {@link #getThresholdExplanation()} says why in
 * words, so a drafter who is surprised by a 이사 in their line has the reason in
 * the same response.
 */
@Schema(name = "ApprovalLineTemplate",
        description = "The 결재선 template that applies to a document type, with the steps "
                + "a given amount would produce.")
public class ApprovalLineTemplateView {

    /** One step of a template, before its role is resolved to anybody. */
    @Schema(name = "ApprovalLineTemplateStep")
    public static class StepView {
        private final int position;
        private final String kind;
        private final String role;
        private final String roleSelector;
        private final String roleValue;
        private final String roleDomain;
        private final boolean optional;

        StepView(ApprovalLineTemplate.TemplateStep step) {
            this.position = step.position();
            this.kind = step.kind().name();
            this.role = step.role().toString();
            this.roleSelector = step.role().selector().name();
            this.roleValue = step.role().value();
            this.roleDomain = step.role().domain() == null ? null : step.role().domain().name();
            this.optional = step.isOptional();
        }

        public int getPosition() {
            return position;
        }

        @Schema(description = "DRAFT, REVIEW, CONCURRENCE, APPROVE or CC.")
        public String getKind() {
            return kind;
        }

        @Schema(description = "The role expression as written in the template.",
                example = "rank:bujang@DRAFTER_UNIT")
        public String getRole() {
            return role;
        }

        @Schema(description = "RANK, JOB_FUNCTION, PERMISSION, REPRESENTATIVE or ACCOUNT.")
        public String getRoleSelector() {
            return roleSelector;
        }

        public String getRoleValue() {
            return roleValue;
        }

        @Schema(description = "How far the role is searched from the drafter.")
        public String getRoleDomain() {
            return roleDomain;
        }

        @Schema(description = "An optional step is dropped when it resolves to nobody; a "
                + "required one refuses the submission instead.")
        public boolean isOptional() {
            return optional;
        }
    }

    /** An amount threshold that adds steps. */
    @Schema(name = "ApprovalLineThresholdRule")
    public static class ThresholdView {
        private final String minimumAmount;
        private final String rationale;
        private final boolean applies;
        private final List<StepView> additionalSteps;

        ThresholdView(ApprovalLineTemplate.ThresholdRule rule, BigDecimal amount) {
            this.minimumAmount = ApiWire.decimal(rule.minimumAmount());
            this.rationale = rule.rationale();
            this.applies = amount != null && rule.appliesTo(amount);
            List<StepView> steps = new ArrayList<StepView>();
            for (ApprovalLineTemplate.TemplateStep step : rule.additionalSteps()) {
                steps.add(new StepView(step));
            }
            this.additionalSteps = Immutables.copyOf(steps);
        }

        @Schema(description = "An exact decimal string.", example = "5000000")
        public String getMinimumAmount() {
            return minimumAmount;
        }

        @Schema(description = "Why the rule exists, in the client's own words.")
        public String getRationale() {
            return rationale;
        }

        @Schema(description = "True when the amount asked about crosses this threshold.")
        public boolean isApplies() {
            return applies;
        }

        public List<StepView> getAdditionalSteps() {
            return additionalSteps;
        }
    }

    private final String id;
    private final String documentType;
    private final String orgUnitId;
    private final String amount;
    private final List<StepView> baseSteps;
    private final List<ThresholdView> thresholds;
    private final List<StepView> effectiveSteps;
    private final List<String> thresholdExplanation;

    private ApprovalLineTemplateView(ApprovalLineTemplate template, BigDecimal amount) {
        this.id = template.id();
        this.documentType = template.documentType();
        this.orgUnitId = template.orgUnitId();
        this.amount = ApiWire.decimal(amount);
        this.baseSteps = steps(template.baseSteps());
        List<ThresholdView> rules = new ArrayList<ThresholdView>();
        for (ApprovalLineTemplate.ThresholdRule rule : template.thresholdRules()) {
            rules.add(new ThresholdView(rule, amount));
        }
        this.thresholds = Immutables.copyOf(rules);
        this.effectiveSteps = steps(template.stepsFor(amount));
        this.thresholdExplanation = Immutables.copyOf(template.explainFor(amount));
    }

    static ApprovalLineTemplateView from(ApprovalLineTemplate template, BigDecimal amount) {
        return new ApprovalLineTemplateView(template, amount);
    }

    private static List<StepView> steps(List<ApprovalLineTemplate.TemplateStep> source) {
        List<StepView> views = new ArrayList<StepView>();
        for (ApprovalLineTemplate.TemplateStep step : source) {
            views.add(new StepView(step));
        }
        return Immutables.copyOf(views);
    }

    public String getId() {
        return id;
    }

    public String getDocumentType() {
        return documentType;
    }

    @Schema(description = "The unit this template is specific to, or null for the company "
            + "default. The most specific template on the drafter's unit chain wins.")
    public String getOrgUnitId() {
        return orgUnitId;
    }

    @Schema(description = "The amount the effective steps were computed for, echoed back as "
            + "an exact decimal string. Null when none was given.")
    public String getAmount() {
        return amount;
    }

    @Schema(description = "The steps before any amount threshold is applied.")
    public List<StepView> getBaseSteps() {
        return baseSteps;
    }

    public List<ThresholdView> getThresholds() {
        return thresholds;
    }

    @Schema(description = "Base steps plus whatever the amount adds, in position order. "
            + "This is the shape the document will be routed through.")
    public List<StepView> getEffectiveSteps() {
        return effectiveSteps;
    }

    @Schema(description = "Why the effective steps are what they are, in prose.")
    public List<String> getThresholdExplanation() {
        return thresholdExplanation;
    }
}
