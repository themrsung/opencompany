package com.coreintra.approval.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaApprovalLineTemplateStoreTest {

    private final FakeTemplates templates = new FakeTemplates();
    private final FakeSteps steps = new FakeSteps();
    private final JpaApprovalLineTemplateStore store =
            new JpaApprovalLineTemplateStore(templates, steps);

    @Test
    @DisplayName("rows with no minimum amount are the base line")
    void baseStepsAreTheRowsWithoutAnAmount() {
        templates.save(new ApprovalLineTemplateEntity("t1", "acme", "EXPENSE", null, "지출결의서"));
        steps.save(step("s1", "t1", 1, ApprovalStepKind.DRAFT, "rank:사원@DRAFTER_UNIT", null, null));
        steps.save(step("s2", "t1", 2, ApprovalStepKind.APPROVE, "rank:부장@DRAFTER_UNIT", null, null));

        List<ApprovalLineTemplate> found = store.findActive("acme", "EXPENSE");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).baseSteps()).hasSize(2);
        assertThat(found.get(0).thresholdRules()).isEmpty();
        assertThat(found.get(0).baseSteps().get(1).role().toString())
                .isEqualTo("rank:부장@DRAFTER_UNIT");
    }

    @Test
    @DisplayName("amount-bearing rows regroup into the threshold rule they came from")
    void thresholdStepsRegroupByAmountAndRationale() {
        templates.save(new ApprovalLineTemplateEntity("t1", "acme", "EXPENSE", null, "지출결의서"));
        steps.save(step("s1", "t1", 1, ApprovalStepKind.APPROVE, "rank:부장@DRAFTER_UNIT", null, null));
        steps.save(step("s2", "t1", 2, ApprovalStepKind.APPROVE, "rank:이사@COMPANY",
                new BigDecimal("5000000"), "5,000,000원 이상"));
        steps.save(step("s3", "t1", 3, ApprovalStepKind.CONCURRENCE, "jobFunction:회계@COMPANY",
                new BigDecimal("5000000"), "5,000,000원 이상"));
        steps.save(step("s4", "t1", 4, ApprovalStepKind.APPROVE, "representative@COMPANY",
                new BigDecimal("30000000"), "3,000만원 이상"));

        ApprovalLineTemplate template = store.findActive("acme", "EXPENSE").get(0);

        assertThat(template.thresholdRules()).hasSize(2);
        // Two rows sharing an amount and a reason are one rule with two steps,
        // not two rules the drafter would be shown the same reason for twice.
        assertThat(template.thresholdRules().get(0).additionalSteps()).hasSize(2);
        assertThat(template.thresholdRules().get(1).additionalSteps()).hasSize(1);
        assertThat(template.stepsFor(new BigDecimal("30000000"))).hasSize(4);
        assertThat(template.stepsFor(new BigDecimal("1000000"))).hasSize(1);
    }

    @Test
    @DisplayName("the same amount with a different reason is a different rule")
    void sameAmountDifferentReasonStaysTwoRules() {
        templates.save(new ApprovalLineTemplateEntity("t1", "acme", "EXPENSE", null, "지출결의서"));
        steps.save(step("s1", "t1", 1, ApprovalStepKind.APPROVE, "rank:이사@COMPANY",
                new BigDecimal("5000000"), "금액 기준"));
        steps.save(step("s2", "t1", 2, ApprovalStepKind.CONCURRENCE, "jobFunction:법무@COMPANY",
                new BigDecimal("5000000"), "법무 검토 기준"));

        ApprovalLineTemplate template = store.findActive("acme", "EXPENSE").get(0);

        assertThat(template.explainFor(new BigDecimal("5000000")))
                .containsExactlyInAnyOrder("금액 기준", "법무 검토 기준");
    }

    @Test
    @DisplayName("5,000,000 exactly crosses a 5,000,000 threshold")
    void thresholdsAreInclusive() {
        templates.save(new ApprovalLineTemplateEntity("t1", "acme", "EXPENSE", null, "지출결의서"));
        steps.save(step("s1", "t1", 1, ApprovalStepKind.APPROVE, "rank:부장@DRAFTER_UNIT", null, null));
        steps.save(step("s2", "t1", 2, ApprovalStepKind.APPROVE, "rank:이사@COMPANY",
                new BigDecimal("5000000.00"), "5,000,000원 이상"));

        ApprovalLineTemplate template = store.findActive("acme", "EXPENSE").get(0);

        assertThat(template.stepsFor(new BigDecimal("5000000"))).hasSize(2);
        assertThat(template.stepsFor(new BigDecimal("4999999.99"))).hasSize(1);
    }

    @Test
    @DisplayName("the company default and a unit's template both come back, unranked")
    void specificityIsNotDecidedHere() {
        // Picking the most specific one walks the org tree, which this adapter
        // deliberately knows nothing about. Returning both is the contract.
        templates.save(new ApprovalLineTemplateEntity("default", "acme", "LEAVE_REQUEST", null,
                "회사 기본"));
        templates.save(new ApprovalLineTemplateEntity("team", "acme", "LEAVE_REQUEST", "unit-1",
                "개발팀"));
        steps.save(step("s1", "default", 1, ApprovalStepKind.APPROVE, "rank:부장@DRAFTER_UNIT",
                null, null));
        steps.save(step("s2", "team", 1, ApprovalStepKind.APPROVE, "rank:팀장@DRAFTER_UNIT",
                null, null));

        List<ApprovalLineTemplate> found = store.findActive("acme", "LEAVE_REQUEST");

        assertThat(found).hasSize(2);
        assertThat(found.get(0).orgUnitId()).isNull();
        assertThat(found.get(1).orgUnitId()).isEqualTo("unit-1");
    }

    @Test
    @DisplayName("a template with no steps yet is returned rather than skipped")
    void emptyTemplateStillLoads() {
        // Half-configured is a state a client will be in for as long as it takes
        // them to add the steps, and it must not look like "no template exists".
        templates.save(new ApprovalLineTemplateEntity("t1", "acme", "EXPENSE", null, "지출결의서"));

        List<ApprovalLineTemplate> found = store.findActive("acme", "EXPENSE");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).baseSteps()).isEmpty();
    }

    @Test
    @DisplayName("nothing configured is an empty list, not a failure")
    void nothingConfigured() {
        assertThat(store.findActive("acme", "EXPENSE")).isEmpty();
    }

    private static ApprovalTemplateStepEntity step(String id, String templateId, int position,
            ApprovalStepKind kind, String role, BigDecimal minimumAmount, String rationale) {
        return new ApprovalTemplateStepEntity(id, templateId, position, kind, role, false,
                minimumAmount, rationale);
    }

    /** Sanity: the role expressions above are the ones the domain parses. */
    @Test
    @DisplayName("role expressions round-trip through the column they are stored in")
    void roleExpressionsRoundTrip() {
        assertThat(RoleExpression.parse("representative@COMPANY").toString())
                .isEqualTo("representative@COMPANY");
    }

    private static final class FakeTemplates extends FakeRepository<ApprovalLineTemplateEntity, String>
            implements ApprovalLineTemplateRepository {
        @Override
        String idOf(ApprovalLineTemplateEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalLineTemplateEntity> findByCompanyIdAndDocumentTypeAndActiveTrue(
                String companyId, String documentType) {
            List<ApprovalLineTemplateEntity> found = new ArrayList<ApprovalLineTemplateEntity>();
            for (ApprovalLineTemplateEntity row : all()) {
                if (row.companyId().equals(companyId) && row.documentType().equals(documentType)
                        && row.isActive()) {
                    found.add(row);
                }
            }
            return found;
        }
    }

    private static final class FakeSteps extends FakeRepository<ApprovalTemplateStepEntity, String>
            implements ApprovalTemplateStepRepository {
        @Override
        String idOf(ApprovalTemplateStepEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalTemplateStepEntity> findByTemplateIdInOrderByPositionAsc(
                Collection<String> templateIds) {
            List<ApprovalTemplateStepEntity> found = new ArrayList<ApprovalTemplateStepEntity>();
            for (ApprovalTemplateStepEntity row : all()) {
                if (templateIds.contains(row.templateId())) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<ApprovalTemplateStepEntity> findByTemplateIdOrderByPositionAsc(
                String templateId) {
            List<ApprovalTemplateStepEntity> found = new ArrayList<ApprovalTemplateStepEntity>();
            for (ApprovalTemplateStepEntity row : all()) {
                if (row.templateId().equals(templateId)) {
                    found.add(row);
                }
            }
            return found;
        }
    }
}
