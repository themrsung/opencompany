package com.coreintra.approval.entity;

import com.coreintra.approval.domain.ApprovalStepKind;
import java.math.BigDecimal;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One step of a 결재선 template — either part of the base line or contributed by
 * an amount threshold.
 *
 * <p>Both live in one table, distinguished by {@link #minimumAmount()}: null is
 * a base step, a value is a step that appears only once the document's amount
 * reaches it. Keeping them together is what makes reading a template one query,
 * and it keeps the two kinds of step structurally identical — which they are,
 * since a threshold does not change what a signature means, only whether it is
 * required.
 *
 * <p>The amount is {@link BigDecimal} and the comparison is inclusive: a rule at
 * 5,000,000 applies at exactly 5,000,000. Exclusive bounds produce the classic
 * off-by-one where the round number everyone tests with falls through the gap.
 */
@Entity
@Table(name = "approval_template_step")
public class ApprovalTemplateStepEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ApprovalStepKind kind;

    /** A role expression, never a person. See {@code RoleExpression}. */
    @Column(name = "role_expression", nullable = false, length = 300)
    private String roleExpression;

    /** A step that resolves to nobody is dropped rather than failing submission. */
    @Column(name = "optional_step", nullable = false)
    private boolean optionalStep;

    /** Null on the base line. Set on a step contributed by a threshold rule. */
    @Column(name = "minimum_amount", precision = 38, scale = 10)
    private BigDecimal minimumAmount;

    /** Shown to the drafter, so an extra signature does not look arbitrary. */
    @Column(name = "rationale")
    private String rationale;

    protected ApprovalTemplateStepEntity() {
    }

    public ApprovalTemplateStepEntity(String id, String templateId, int position,
            ApprovalStepKind kind, String roleExpression, boolean optionalStep,
            BigDecimal minimumAmount, String rationale) {
        this.id = id;
        this.templateId = templateId;
        this.position = position;
        this.kind = kind;
        this.roleExpression = roleExpression;
        this.optionalStep = optionalStep;
        this.minimumAmount = minimumAmount;
        this.rationale = rationale;
    }

    /** True when this step belongs to the base line rather than to a threshold. */
    public boolean isBaseStep() {
        return minimumAmount == null;
    }

    public String id() {
        return id;
    }

    public String templateId() {
        return templateId;
    }

    public int position() {
        return position;
    }

    public ApprovalStepKind kind() {
        return kind;
    }

    public String roleExpression() {
        return roleExpression;
    }

    public boolean isOptionalStep() {
        return optionalStep;
    }

    public BigDecimal minimumAmount() {
        return minimumAmount;
    }

    public String rationale() {
        return rationale;
    }
}
