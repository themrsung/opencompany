package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which template a document uses: most specific wins, measured up the org tree.
 */
class ApprovalLineTemplateServiceTest {

    private static final String EXPENSE = "EXPENSE_CLAIM";

    private ApprovalTestWorld world;
    private ApprovalLineTemplateService service;
    private OrgUnit division;
    private OrgUnit team;
    private OrgUnit squad;

    @BeforeEach
    void setUp() {
        world = new ApprovalTestWorld();
        division = world.unit("HQ", null);
        team = world.unit("DEV", division);
        squad = world.unit("PLATFORM", team);
        service = world.templateService();
    }

    private ApprovalLineTemplate template(String id, String orgUnitId) {
        List<ApprovalLineTemplate.TemplateStep> steps =
                new ArrayList<ApprovalLineTemplate.TemplateStep>();
        steps.add(new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.APPROVE,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));
        ApprovalLineTemplate created = new ApprovalLineTemplate(id, EXPENSE, orgUnitId, steps,
                Immutables.<ApprovalLineTemplate.ThresholdRule>listOf());
        world.templates.all.add(created);
        return created;
    }

    @Test
    @DisplayName("a template on the drafter's own unit beats the company default")
    void ownUnitBeatsDefault() {
        template("company-default", null);
        template("dev-team", team.id());

        assertThat(service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, team.id()).id())
                .isEqualTo("dev-team");
    }

    @Test
    @DisplayName("a unit with no template of its own inherits its parent's")
    void inheritsFromTheParent() {
        template("company-default", null);
        template("dev-team", team.id());

        assertThat(service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, squad.id()).id())
                .as("a company that configures one template on 본부 expects the teams under it "
                        + "to inherit, not to fall through to the company default")
                .isEqualTo("dev-team");
    }

    @Test
    @DisplayName("the nearest ancestor wins, not the highest")
    void nearestAncestorWins() {
        template("hq", division.id());
        template("dev-team", team.id());

        assertThat(service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, squad.id()).id())
                .isEqualTo("dev-team");
    }

    @Test
    @DisplayName("with nothing above it, the company default applies")
    void fallsBackToTheDefault() {
        template("company-default", null);

        assertThat(service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, squad.id()).id())
                .isEqualTo("company-default");
    }

    @Test
    @DisplayName("a drafter with no unit still gets the company default")
    void noUnitStillResolves() {
        template("company-default", null);

        assertThat(service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, null).id())
                .isEqualTo("company-default");
    }

    @Test
    @DisplayName("no template at all is refused, naming the fix rather than routing nothing")
    void noTemplateRefused() {
        assertThatThrownBy(() -> service.resolve(ApprovalTestWorld.COMPANY, EXPENSE, team.id()))
                .isInstanceOf(ApprovalLineTemplateService.NoTemplateException.class)
                .hasMessageContaining("Register one");

        assertThat(service.find(ApprovalTestWorld.COMPANY, EXPENSE, team.id()))
                .as("find() answers the same question without throwing, for 'can this be "
                        + "submitted?' checks")
                .isEmpty();
    }

    @Test
    @DisplayName("templates for another document type are not considered")
    void otherDocumentTypesIgnored() {
        List<ApprovalLineTemplate.TemplateStep> steps =
                new ArrayList<ApprovalLineTemplate.TemplateStep>();
        steps.add(new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.APPROVE,
                RoleExpression.representative(), false));
        world.templates.all.add(new ApprovalLineTemplate("leave", "LEAVE_REQUEST", team.id(),
                steps, Immutables.<ApprovalLineTemplate.ThresholdRule>listOf()));

        assertThat(service.find(ApprovalTestWorld.COMPANY, EXPENSE, team.id())).isEmpty();
    }
}
