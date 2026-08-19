package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.service.OrgPermissions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes 결재선 templates. Nothing did, before this.
 *
 * <p>{@link ApprovalLineTemplateService} picks the template a document should
 * use and {@code JpaApprovalLineTemplateStore} reads the rows, but
 * {@code approval_line_template} had no writer at all, so on a fresh
 * installation every submission failed with "register a company default first"
 * and there was no way to register one. This is that way.
 *
 * <h2>Superseding, not editing</h2>
 *
 * <p>Defining a template for a slot that already has one retires the old row
 * rather than mutating it, because approved documents name the template that
 * routed them and that trail has to stay readable. A slot is
 * ({@code companyId}, {@code documentType}, {@code orgUnitId}), with a null unit
 * meaning the company-wide default.
 *
 * <h2>What is refused</h2>
 *
 * <p>A template with no step, and a template whose every step is a 참조, are
 * both refused: a line nobody has to act on is an approval nobody has to give,
 * and it would let a document reach {@code APPROVED} without a signature. Two
 * steps at the same position within the same line are refused too — the order
 * of a 결재선 is the thing it exists to record, and a tie is not an order.
 *
 * <h2>Why {@code company.settings:update}</h2>
 *
 * <p>See {@link CompanyRepresentationService}: configuring approval routing is
 * a company setting, and a new permission key would be one no installation
 * could grant.
 */
@Service
public class ApprovalLineTemplateWriteService {

    /**
     * One step as the caller describes it.
     *
     * <p>A {@code minimumAmount} makes it a threshold step: it appears only once
     * the document's amount reaches that figure, inclusive. Null makes it part
     * of the base line. The amount is {@link BigDecimal} because it decides who
     * has to sign, and a rounding error in that decision is a signature that
     * should have been required and was not.
     */
    public static final class StepDefinition {

        private final int position;
        private final ApprovalStepKind kind;
        private final RoleExpression role;
        private final boolean optional;
        private final BigDecimal minimumAmount;
        private final String rationale;

        public StepDefinition(int position, ApprovalStepKind kind, RoleExpression role,
                boolean optional, BigDecimal minimumAmount, String rationale) {
            this.position = position;
            this.kind = kind;
            this.role = role;
            this.optional = optional;
            this.minimumAmount = minimumAmount;
            this.rationale = rationale;
        }

        /** A step of the base line: always required, whatever the amount. */
        public static StepDefinition base(int position, ApprovalStepKind kind, RoleExpression role) {
            return new StepDefinition(position, kind, role, false, null, null);
        }

        /** A step contributed by an amount threshold, with the reason shown to the drafter. */
        public static StepDefinition above(BigDecimal minimumAmount, int position,
                ApprovalStepKind kind, RoleExpression role, String rationale) {
            return new StepDefinition(position, kind, role, false, minimumAmount, rationale);
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

        public boolean isOptional() {
            return optional;
        }

        public BigDecimal minimumAmount() {
            return minimumAmount;
        }

        public String rationale() {
            return rationale;
        }
    }

    private final ApprovalLineTemplateRepository templates;
    private final ApprovalTemplateStepRepository steps;
    private final PermissionEvaluator permissions;

    public ApprovalLineTemplateWriteService(ApprovalLineTemplateRepository templates,
            ApprovalTemplateStepRepository steps, PermissionEvaluator permissions) {
        if (templates == null) {
            throw new NullPointerException("templates");
        }
        if (steps == null) {
            throw new NullPointerException("steps");
        }
        if (permissions == null) {
            throw new NullPointerException("permissions");
        }
        this.templates = templates;
        this.steps = steps;
        this.permissions = permissions;
    }

    /**
     * Defines the 결재선 for a document type, superseding whatever occupied the
     * same slot.
     *
     * @param orgUnitId null for the company-wide default — the line every unit
     *        that has not chosen its own falls back to
     * @return the new template row
     */
    @Transactional
    public ApprovalLineTemplateEntity define(PermissionPrincipal caller, String companyId,
            String documentType, String orgUnitId, String nameKo, String nameEn,
            List<StepDefinition> definitions, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
        String company = required(companyId, "companyId");
        String type = required(documentType, "documentType");
        String label = required(nameKo, "nameKo");
        String unit = Texts.isBlank(orgUnitId) ? null : Texts.strip(orgUnitId);

        permissions.check(caller, OrgPermissions.COMPANY_UPDATE,
                PermissionTarget.builder()
                        .companyId(company)
                        .orgUnitId(unit)
                        .asOfBusinessDate(businessDate)
                        .description("defining the 결재선 for " + type)
                        .build()).orThrow();

        List<StepDefinition> ordered = validated(definitions, type);

        for (ApprovalLineTemplateEntity existing
                : templates.findByCompanyIdAndDocumentTypeAndActiveTrue(company, type)) {
            if (sameSlot(existing.orgUnitId(), unit)) {
                existing.retire();
                templates.save(existing);
            }
        }

        String templateId = UUID.randomUUID().toString();
        ApprovalLineTemplateEntity template =
                new ApprovalLineTemplateEntity(templateId, company, type, unit, label);
        template.rename(label, Texts.isBlank(nameEn) ? null : Texts.strip(nameEn));
        templates.save(template);

        for (StepDefinition step : ordered) {
            steps.save(new ApprovalTemplateStepEntity(UUID.randomUUID().toString(), templateId,
                    step.position(), step.kind(), step.role().toString(), step.isOptional(),
                    step.minimumAmount(),
                    Texts.isBlank(step.rationale()) ? null : Texts.strip(step.rationale())));
        }
        return template;
    }

    /**
     * Retires a template.
     *
     * <p>Never a delete: {@code approval_document.template_id} points at it, and
     * a document whose line came from nowhere cannot be explained to whoever
     * signed it. Retiring twice is a no-op rather than an error.
     */
    @Transactional
    public void retire(PermissionPrincipal caller, String templateId, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        Optional<ApprovalLineTemplateEntity> found =
                templates.findById(required(templateId, "templateId"));
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such approval line template: " + templateId);
        }
        ApprovalLineTemplateEntity template = found.get();
        permissions.check(caller, OrgPermissions.COMPANY_UPDATE,
                PermissionTarget.builder()
                        .companyId(template.companyId())
                        .orgUnitId(template.orgUnitId())
                        .asOfBusinessDate(businessDate)
                        .description("retiring the 결재선 for " + template.documentType())
                        .build()).orThrow();
        if (template.isActive()) {
            template.retire();
            templates.save(template);
        }
    }

    /** The steps of one template, in order. For an editor that has to show what is there. */
    @Transactional(readOnly = true)
    public List<ApprovalTemplateStepEntity> stepsOf(String templateId) {
        return steps.findByTemplateIdOrderByPositionAsc(required(templateId, "templateId"));
    }

    private static List<StepDefinition> validated(List<StepDefinition> definitions, String type) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "\"" + type + "\" 결재선에는 단계가 하나 이상 있어야 합니다. (A 결재선 with no "
                            + "steps is an approval nobody has to give: a document routed by it "
                            + "would reach APPROVED without a signature.)");
        }

        boolean anyoneActs = false;
        // Positions collide within a line, not across lines: the base line and
        // each threshold are separate sequences, and a threshold step at
        // position 2 is meant to sit beside the base step at position 2.
        Map<String, Set<Integer>> positionsByLine = new LinkedHashMap<String, Set<Integer>>();
        List<StepDefinition> ordered = new ArrayList<StepDefinition>(definitions.size());

        for (StepDefinition step : definitions) {
            if (step == null) {
                throw new IllegalArgumentException("a null step was passed for " + type);
            }
            if (step.kind() == null) {
                throw new IllegalArgumentException("every step needs a kind");
            }
            if (step.role() == null) {
                throw new IllegalArgumentException(
                        "every step needs a role expression; a template naming a person would "
                                + "route to them after they left");
            }
            if (step.position() < 1) {
                throw new IllegalArgumentException(
                        "step positions start at 1, got " + step.position());
            }
            if (step.minimumAmount() != null
                    && step.minimumAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "금액 기준이 " + step.minimumAmount().toPlainString()
                                + " 이하이면 모든 문서에 적용되므로 기본 결재선 단계로 등록해 주십시오. "
                                + "(A threshold at or below zero applies to every document, "
                                + "which is a base step wearing a threshold's clothes.)");
            }
            String line = step.minimumAmount() == null
                    ? "" : step.minimumAmount().stripTrailingZeros().toPlainString();
            Set<Integer> taken = positionsByLine.get(line);
            if (taken == null) {
                taken = new LinkedHashSet<Integer>();
                positionsByLine.put(line, taken);
            }
            if (!taken.add(Integer.valueOf(step.position()))) {
                throw new IllegalArgumentException(
                        "같은 결재선에 " + step.position() + "번 단계가 두 번 있습니다. "
                                + "(Two steps share position " + step.position() + " in the same "
                                + "line. The order is what a 결재선 records, and a tie is not an "
                                + "order.)");
            }
            anyoneActs = anyoneActs || step.kind().requiresAction();
            ordered.add(step);
        }

        if (!anyoneActs) {
            throw new IllegalArgumentException(
                    "\"" + type + "\" 결재선에 결재·검토·합의 단계가 없습니다. 참조만으로는 결재가 "
                            + "완결되지 않습니다. (Every step in this line is a 참조, which never "
                            + "blocks. A document routed by it would be approved by nobody.)");
        }
        return Immutables.copyOf(ordered);
    }

    private static boolean sameSlot(String existingUnitId, String unitId) {
        return existingUnitId == null ? unitId == null : existingUnitId.equals(unitId);
    }

    private static String required(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return Texts.strip(value);
    }
}
