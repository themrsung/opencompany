package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import com.coreintra.approval.service.ApprovalLineTemplateWriteService.StepDefinition;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.service.OrgPermissions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Writing 결재선 서식, which until now could only be done with SQL. */
class ApprovalLineTemplateWriteServiceTest {

    private static final String COMPANY = "co-1";
    private static final String ADMIN = "acc-admin";
    private static final String TYPE = "EXPENSE";
    private static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    private final ApprovalTestWorld world = new ApprovalTestWorld();
    private final Lines lines = new Lines();
    private final TemplateSteps steps = new TemplateSteps();
    private ApprovalLineTemplateWriteService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalLineTemplateWriteService(lines, steps, world.permissions);
        world.grants.grant(ADMIN, OrgPermissions.COMPANY_UPDATE, PermissionScope.ALL);
    }

    private static PermissionPrincipal admin() {
        return PermissionPrincipal.user(ADMIN, "김서연", null);
    }

    private static List<StepDefinition> twoSteps() {
        List<StepDefinition> defined = new ArrayList<StepDefinition>();
        defined.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        defined.add(StepDefinition.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.representative()));
        return defined;
    }

    @Test
    @DisplayName("a company default line is written with its steps in order")
    void writesTheLine() {
        ApprovalLineTemplateEntity template = service.define(admin(), COMPANY, TYPE, null,
                "지출결의서 기본 결재선", "Expense default", twoSteps(), DAY);

        assertThat(template.orgUnitId())
                .as("null unit is the company-wide default every unit falls back to")
                .isNull();
        assertThat(template.isActive()).isTrue();
        assertThat(template.nameEn()).isEqualTo("Expense default");

        List<ApprovalTemplateStepEntity> written = service.stepsOf(template.id());
        assertThat(written).hasSize(2);
        assertThat(written.get(0).kind()).isEqualTo(ApprovalStepKind.REVIEW);
        assertThat(written.get(0).roleExpression())
                .as("a role expression, never a person")
                .isEqualTo(RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)
                        .toString());
        assertThat(written.get(1).isBaseStep()).isTrue();
    }

    @Test
    @DisplayName("a threshold step is stored with its amount and its reason for the drafter")
    void writesAThresholdStep() {
        List<StepDefinition> defined = twoSteps();
        defined.add(StepDefinition.above(new BigDecimal("5000000"), 3, ApprovalStepKind.APPROVE,
                RoleExpression.representative(), "5,000,000원을 넘는 지출은 대표 승인을 받습니다."));

        ApprovalLineTemplateEntity template = service.define(admin(), COMPANY, TYPE, null,
                "지출결의서", null, defined, DAY);

        ApprovalTemplateStepEntity threshold = service.stepsOf(template.id()).get(2);
        assertThat(threshold.isBaseStep()).isFalse();
        assertThat(threshold.minimumAmount()).isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(threshold.rationale()).contains("5,000,000");
    }

    @Test
    @DisplayName("redefining a slot retires the previous template rather than editing it")
    void supersedesTheOldTemplate() {
        ApprovalLineTemplateEntity first = service.define(admin(), COMPANY, TYPE, null, "1차", null,
                twoSteps(), DAY);
        ApprovalLineTemplateEntity second = service.define(admin(), COMPANY, TYPE, null, "2차",
                null, twoSteps(), DAY);

        assertThat(lines.rows.get(first.id()).isActive())
                .as("documents approved last year name this template; it stays readable")
                .isFalse();
        assertThat(lines.findByCompanyIdAndDocumentTypeAndActiveTrue(COMPANY, TYPE))
                .extracting("id")
                .containsExactly(second.id());
    }

    @Test
    @DisplayName("a unit-specific line does not disturb the company default")
    void unitLineIsItsOwnSlot() {
        ApprovalLineTemplateEntity companyWide = service.define(admin(), COMPANY, TYPE, null,
                "회사 기본", null, twoSteps(), DAY);
        service.define(admin(), COMPANY, TYPE, "unit-dev", "개발본부", null, twoSteps(), DAY);

        assertThat(lines.rows.get(companyWide.id()).isActive()).isTrue();
        assertThat(lines.findByCompanyIdAndDocumentTypeAndActiveTrue(COMPANY, TYPE)).hasSize(2);
    }

    @Test
    @DisplayName("a line with no steps is refused: it would be an approval nobody has to give")
    void refusesAnEmptyLine() {
        assertThatThrownBy(() -> service.define(admin(), COMPANY, TYPE, null, "빈 결재선", null,
                Immutables.<StepDefinition>listOf(), DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단계");
        assertThat(lines.rows).isEmpty();
    }

    @Test
    @DisplayName("a line of nothing but 참조 is refused: nobody would ever have to act")
    void refusesALineThatOnlyNotifies() {
        List<StepDefinition> onlyCc = new ArrayList<StepDefinition>();
        onlyCc.add(StepDefinition.base(1, ApprovalStepKind.CC,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY)));

        assertThatThrownBy(() -> service.define(admin(), COMPANY, TYPE, null, "참조만", null, onlyCc,
                DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("참조");
    }

    @Test
    @DisplayName("two steps at the same position are refused, because a tie is not an order")
    void refusesDuplicatePositions() {
        List<StepDefinition> clashing = new ArrayList<StepDefinition>();
        clashing.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        clashing.add(StepDefinition.base(1, ApprovalStepKind.APPROVE,
                RoleExpression.representative()));

        assertThatThrownBy(() -> service.define(admin(), COMPANY, TYPE, null, "충돌", null,
                clashing, DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position 1");
    }

    @Test
    @DisplayName("a threshold at zero is refused: it applies to everything, which is a base step")
    void refusesAThresholdOfZero() {
        List<StepDefinition> defined = twoSteps();
        defined.add(StepDefinition.above(BigDecimal.ZERO, 3, ApprovalStepKind.APPROVE,
                RoleExpression.representative(), "always"));

        assertThatThrownBy(() -> service.define(admin(), COMPANY, TYPE, null, "0원", null, defined,
                DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a caller without company.settings:update cannot decide who signs what")
    void refusesWithoutThePermission() {
        PermissionPrincipal stranger = PermissionPrincipal.user("acc-stranger", "이준호", null);

        assertThatThrownBy(() -> service.define(stranger, COMPANY, TYPE, null, "무단", null,
                twoSteps(), DAY))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(lines.rows).isEmpty();
    }

    @Test
    @DisplayName("retiring is idempotent and never deletes")
    void retires() {
        ApprovalLineTemplateEntity template = service.define(admin(), COMPANY, TYPE, null, "기본",
                null, twoSteps(), DAY);

        service.retire(admin(), template.id(), DAY);
        service.retire(admin(), template.id(), DAY);

        assertThat(lines.rows).hasSize(1);
        assertThat(lines.rows.get(template.id()).isActive()).isFalse();
    }

    static final class Lines extends InMemoryRepository<ApprovalLineTemplateEntity, String>
            implements ApprovalLineTemplateRepository {

        @Override
        String idOf(ApprovalLineTemplateEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalLineTemplateEntity> findByCompanyIdAndDocumentTypeAndActiveTrue(
                String companyId, String documentType) {
            List<ApprovalLineTemplateEntity> found = new ArrayList<ApprovalLineTemplateEntity>();
            for (ApprovalLineTemplateEntity row : rows.values()) {
                if (row.companyId().equals(companyId) && row.documentType().equals(documentType)
                        && row.isActive()) {
                    found.add(row);
                }
            }
            return found;
        }
    }

    static final class TemplateSteps extends InMemoryRepository<ApprovalTemplateStepEntity, String>
            implements ApprovalTemplateStepRepository {

        @Override
        String idOf(ApprovalTemplateStepEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalTemplateStepEntity> findByTemplateIdInOrderByPositionAsc(
                Collection<String> templateIds) {
            List<ApprovalTemplateStepEntity> found = new ArrayList<ApprovalTemplateStepEntity>();
            for (ApprovalTemplateStepEntity row : rows.values()) {
                if (templateIds.contains(row.templateId())) {
                    found.add(row);
                }
            }
            return byPosition(found);
        }

        @Override
        public List<ApprovalTemplateStepEntity> findByTemplateIdOrderByPositionAsc(
                String templateId) {
            return findByTemplateIdInOrderByPositionAsc(Immutables.listOf(templateId));
        }

        private static List<ApprovalTemplateStepEntity> byPosition(
                List<ApprovalTemplateStepEntity> found) {
            Collections.sort(found, new Comparator<ApprovalTemplateStepEntity>() {
                @Override
                public int compare(ApprovalTemplateStepEntity left,
                        ApprovalTemplateStepEntity right) {
                    return Integer.compare(left.position(), right.position());
                }
            });
            return found;
        }
    }
}
