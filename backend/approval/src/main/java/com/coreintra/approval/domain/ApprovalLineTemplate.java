package com.coreintra.approval.domain;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The 결재선 template for a document type in an org unit, with amount thresholds.
 *
 * <p>A 지출결의서 for 300,000원 and one for 30,000,000원 are the same document
 * type and need different signatures. Rather than making the drafter choose the
 * line — which is asking the person spending the money how much scrutiny they
 * need — the template carries threshold rules and the line is derived from the
 * document's own amount.
 *
 * <h2>Amounts are BigDecimal, and thresholds are inclusive-from</h2>
 *
 * <p>A rule with {@code minimumAmount} 5,000,000 applies at exactly 5,000,000,
 * not above it. Exclusive bounds here produce the classic off-by-one where the
 * round number everyone tests with falls through the gap.
 */
public final class ApprovalLineTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** One step in a template: a role expression rather than a person. */
    public static final class TemplateStep implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int position;
        private final ApprovalStepKind kind;
        private final RoleExpression role;
        private final boolean optional;

        public TemplateStep(int position, ApprovalStepKind kind, RoleExpression role,
                boolean optional) {
            this.position = position;
            this.kind = kind;
            this.role = role;
            this.optional = optional;
        }

        public int position() {
            return position;
        }

        public ApprovalStepKind kind() {
            return kind;
        }

        public RoleExpression role() {
            return role;
        }

        /**
         * When true, a step whose role resolves to nobody is dropped instead of
         * failing the submission.
         *
         * <p>For a genuinely optional reviewer — a 법무 concurrence in a company
         * that has no legal team. A required step that resolves to nobody must
         * still fail loudly, because the alternative is a document that silently
         * skipped a signature.
         */
        public boolean isOptional() {
            return optional;
        }
    }

    /**
     * Extra steps that apply above an amount.
     *
     * <p>Additive: a 30,000,000원 expense gets the base line plus every rule
     * whose threshold it crosses, so thresholds stack rather than replacing one
     * another. Replacement semantics would mean a higher threshold could
     * accidentally remove a signature a lower one required.
     */
    public static final class ThresholdRule implements Serializable {
        private static final long serialVersionUID = 1L;

        private final BigDecimal minimumAmount;
        private final List<TemplateStep> additionalSteps;
        private final String rationale;

        public ThresholdRule(BigDecimal minimumAmount, List<TemplateStep> additionalSteps,
                String rationale) {
            if (minimumAmount == null) {
                throw new NullPointerException("minimumAmount");
            }
            this.minimumAmount = minimumAmount;
            this.additionalSteps = Immutables.copyOf(additionalSteps);
            this.rationale = rationale;
        }

        /** Inclusive. A rule at 5,000,000 applies to exactly 5,000,000. */
        public BigDecimal minimumAmount() {
            return minimumAmount;
        }

        public List<TemplateStep> additionalSteps() {
            return additionalSteps;
        }

        /** Shown to the drafter so the extra step does not look arbitrary. */
        public String rationale() {
            return rationale;
        }

        /** Numeric comparison, so 5000000 and 5000000.00 behave identically. */
        public boolean appliesTo(BigDecimal amount) {
            return amount != null && amount.compareTo(minimumAmount) >= 0;
        }
    }

    private final String id;
    private final String documentType;
    private final String orgUnitId;
    private final List<TemplateStep> baseSteps;
    private final List<ThresholdRule> thresholdRules;

    public ApprovalLineTemplate(String id, String documentType, String orgUnitId,
            List<TemplateStep> baseSteps, List<ThresholdRule> thresholdRules) {
        this.id = id;
        this.documentType = documentType;
        this.orgUnitId = orgUnitId;
        this.baseSteps = Immutables.copyOf(baseSteps);

        List<ThresholdRule> sorted = new ArrayList<ThresholdRule>(thresholdRules);
        Collections.sort(sorted, new Comparator<ThresholdRule>() {
            @Override
            public int compare(ThresholdRule left, ThresholdRule right) {
                return left.minimumAmount().compareTo(right.minimumAmount());
            }
        });
        this.thresholdRules = Immutables.copyOf(sorted);
    }

    public String id() {
        return id;
    }

    public String documentType() {
        return documentType;
    }

    /** The unit this template applies to. Null means the company default. */
    public String orgUnitId() {
        return orgUnitId;
    }

    public List<TemplateStep> baseSteps() {
        return baseSteps;
    }

    public List<ThresholdRule> thresholdRules() {
        return thresholdRules;
    }

    /**
     * The steps for a document of this amount: the base line plus every
     * threshold it crosses, in position order.
     *
     * @param amount the document's amount, or null for a document with none
     */
    public List<TemplateStep> stepsFor(BigDecimal amount) {
        List<TemplateStep> resolved = new ArrayList<TemplateStep>(baseSteps);
        for (ThresholdRule rule : thresholdRules) {
            if (rule.appliesTo(amount)) {
                resolved.addAll(rule.additionalSteps());
            }
        }
        Collections.sort(resolved, new Comparator<TemplateStep>() {
            @Override
            public int compare(TemplateStep left, TemplateStep right) {
                return Integer.compare(left.position(), right.position());
            }
        });
        return Immutables.copyOf(resolved);
    }

    /** Why this document's line looks the way it does, for the drafter to read. */
    public List<String> explainFor(BigDecimal amount) {
        List<String> reasons = new ArrayList<String>();
        for (ThresholdRule rule : thresholdRules) {
            if (rule.appliesTo(amount)) {
                reasons.add(rule.rationale());
            }
        }
        return Immutables.copyOf(reasons);
    }
}
