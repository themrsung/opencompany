package com.coreintra.app.api.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coreintra.app.api.approval.support.ApiTestWorld;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.compat.Immutables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 취업규칙 over the API, and the one invariant that must survive every route
 * into it.
 *
 * <p>§4: any create, amend or repeal requires 대표자 결재 under the company's
 * representation mode, and no admin, no master account and no API path may
 * bypass it. The interesting tests here are therefore the failures — an
 * enactment with no approval, and the same attempt made by a master account,
 * which is the credential a determined operator would reach for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestWorld.class)
class EmploymentRulesApiIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApiTestWorld world;
    private Cookie hrManager;
    private Cookie master;
    private Cookie firstRep;
    private Cookie secondRep;

    private static final List<EmploymentRules.Section> SECTIONS = Immutables.listOf(
            new EmploymentRules.Section("제12조", "근로시간", "Working hours",
                    "1주간의 소정근로시간은 40시간으로 합니다.",
                    "Contractual working hours are 40 per week."),
            new EmploymentRules.Section("제31조", "연차유급휴가", "Annual paid leave",
                    "연차유급휴가는 관계 법령에 따라 부여합니다.",
                    "Annual paid leave is granted in accordance with the applicable statute."));

    @BeforeEach
    void seed() {
        world.reset();
        world.registerRepresentativeOnlyTemplate(EmploymentRulesService.DOCUMENT_TYPE);
        // 공동대표: both 대표이사s must sign, which is what makes "one signature
        // is not enough" a meaningful thing to assert.
        world.useRepresentation(RepresentationMode.jointAll(2));

        hrManager = world.sessionFor(world.drafterAccountId);
        master = world.sessionFor(world.masterAccountId);
        firstRep = world.sessionFor(world.firstRepAccountId);
        secondRep = world.sessionFor(world.secondRepAccountId);
    }

    @Test
    @DisplayName("cannot be enacted through the API without 대표자 결재, and holding "
            + "hr.rules:write does not help")
    void enactmentWithoutApprovalIsRefused() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/employment-rules")
                .cookie(hrManager)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(null)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode problem = read(result);
        assertThat(problem.path("code").asText()).isEqualTo("representative_approval_required");
        assertThat(problem.path("detail").asText()).contains("대표자 결재가 필요합니다");

        assertThat(effectiveOn("2026-09-01").getResponse().getStatus())
                .as("nothing was enacted")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("cannot be enacted by a master account either — the master credential is "
            + "not a way round a domain invariant")
    void masterAccountCannotEnactEither() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/employment-rules")
                .cookie(master)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(null)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a master account is an administrative capability, not representative "
                        + "authority")
                .isEqualTo(403);
        assertThat(read(result).path("code").asText())
                .isEqualTo("representative_approval_required");
        assertThat(read(result).path("detail").asText())
                .contains("a master account does not have one either");

        assertThat(effectiveOn("2026-09-01").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("cannot be enacted under an approval document the representatives have not "
            + "finished signing")
    void halfSignedApprovalIsNotEnough() throws Exception {
        String documentId = proposeAndSubmit();
        approve(documentId, firstRep);

        // One of two 대표이사s. Under 공동대표 the document is PARTIALLY_APPROVED,
        // and enacting on the strength of it would be enacting on half a quorum.
        MvcResult result = mockMvc.perform(post("/api/v1/employment-rules")
                .cookie(master)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(documentId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(result).path("code").asText())
                .isEqualTo("representative_approval_required");
    }

    @Test
    @DisplayName("is enacted once both 대표이사s have signed, and records who signed it under "
            + "which mode")
    void enactedUnderAFullQuorum() throws Exception {
        String documentId = proposeAndSubmit();
        approve(documentId, firstRep);
        approve(documentId, secondRep);

        MvcResult result = mockMvc.perform(post("/api/v1/employment-rules")
                .cookie(hrManager)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(documentId)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("publish failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        JsonNode published = read(result);
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(published.path("approvalDocumentId").asText()).isEqualTo(documentId);
        assertThat(published.path("approvedUnderMode").asText()).contains("공동대표");
        assertThat(ids(published.path("approvingRepresentativeIds")))
                .as("both signatures are on the record, not just the last one")
                .containsExactlyInAnyOrder(world.firstRepAccountId, world.secondRepAccountId);
        assertThat(published.path("sections")).hasSize(2);
    }

    @Test
    @DisplayName("an employee sees the version that was in force on the date they ask about")
    void effectiveDating() throws Exception {
        enactVersionOne();

        assertThat(effectiveOn("2026-08-31").getResponse().getStatus())
                .as("the day before it took effect, there is nothing in force")
                .isEqualTo(404);

        JsonNode inForce = json.readTree(
                effectiveOn("2026-09-01").getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(inForce.path("version").asInt()).isEqualTo(1);
        assertThat(inForce.path("effectiveFrom").asText()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("an acknowledgement is personal: nobody may record one on another person's "
            + "behalf")
    void acknowledgementsArePersonal() throws Exception {
        String rulesId = enactVersionOne();

        MvcResult onBehalf = mockMvc.perform(
                post("/api/v1/employment-rules/{id}/acknowledgements", rulesId)
                        .cookie(master)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"" + world.drafterEmployeeId + "\","
                                + "\"acknowledgedOn\":\"2026-09-02\"}"))
                .andReturn();
        assertThat(onBehalf.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(onBehalf).path("detail").asText()).contains("본인만 확인 처리를 할 수 있습니다");

        MvcResult own = mockMvc.perform(
                post("/api/v1/employment-rules/{id}/acknowledgements", rulesId)
                        .cookie(hrManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"" + world.drafterEmployeeId + "\","
                                + "\"acknowledgedOn\":\"2026-09-02\"}"))
                .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(204);

        JsonNode receipts = json.readTree(mockMvc.perform(
                get("/api/v1/employment-rules/{id}/acknowledgements", rulesId)
                        .cookie(hrManager)
                        .param("companyId", world.companyId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(ids(receipts.path("employeeIds")))
                .containsExactly(world.drafterEmployeeId);
    }

    @Test
    @DisplayName("the diff of the first version is empty rather than every article marked as "
            + "added")
    void firstVersionHasNoDiff() throws Exception {
        enactVersionOne();

        JsonNode diff = json.readTree(mockMvc.perform(
                get("/api/v1/employment-rules/{version}/diff", 1)
                        .cookie(hrManager)
                        .param("companyId", world.companyId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(diff).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String enactVersionOne() throws Exception {
        String documentId = proposeAndSubmit();
        approve(documentId, firstRep);
        approve(documentId, secondRep);
        MvcResult result = mockMvc.perform(post("/api/v1/employment-rules")
                .cookie(hrManager)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(documentId)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("publish failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return read(result).path("id").asText();
    }

    private String proposeAndSubmit() throws Exception {
        MvcResult proposal = mockMvc.perform(post("/api/v1/employment-rules/proposals")
                .cookie(hrManager)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\","
                        + "\"title\":\"취업규칙 개정(안)\",\"businessDate\":\"2026-08-30\"}"))
                .andReturn();
        assertThat(proposal.getResponse().getStatus())
                .as("propose failed: %s", proposal.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        String documentId = read(proposal).path("id").asText();

        // The digest ties the approval to this exact text. Without it the
        // representatives would be signing a title and the body could change
        // afterwards.
        MvcResult submitted = mockMvc.perform(
                post("/api/v1/approvals/{id}/submit", documentId)
                        .cookie(hrManager)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode()
                                .put("submittedAt", "2026-08-30T10:00:00.000")
                                .put("bodyDigest",
                                        EmploymentRulesService.textDigest(SECTIONS)))))
                .andReturn();
        assertThat(submitted.getResponse().getStatus())
                .as("submit failed: %s", submitted.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return documentId;
    }

    private void approve(String documentId, Cookie who) throws Exception {
        JsonNode detail = json.readTree(mockMvc.perform(
                get("/api/v1/approvals/{id}", documentId).cookie(who))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String stepId = null;
        for (JsonNode step : detail.path("steps")) {
            if ("PENDING".equals(step.path("state").asText())) {
                stepId = step.path("id").asText();
            }
        }
        assertThat(stepId).as("no pending step on %s", detail.path("steps")).isNotNull();

        MvcResult result = mockMvc.perform(post(
                "/api/v1/approvals/{id}/steps/{step}/approve", documentId, stepId)
                .cookie(who)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actedAt\":\"2026-08-30T11:00:00.000\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("approve failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
    }

    private MvcResult effectiveOn(String date) throws Exception {
        return mockMvc.perform(get("/api/v1/employment-rules")
                .cookie(hrManager)
                .param("companyId", world.companyId)
                .param("on", date))
                .andReturn();
    }

    private String publishBody(String approvalDocumentId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("companyId", world.companyId);
        body.put("effectiveFrom", "2026-09-01");
        body.put("approvalDocumentId", approvalDocumentId);
        ArrayNode sections = body.putArray("sections");
        for (EmploymentRules.Section section : SECTIONS) {
            ObjectNode node = sections.addObject();
            node.put("number", section.number());
            node.put("headingKo", section.headingKo());
            node.put("headingEn", section.headingEn());
            node.put("bodyKo", section.bodyKo());
            node.put("bodyEn", section.bodyEn());
        }
        return json.writeValueAsString(body);
    }

    private static List<String> ids(JsonNode array) {
        java.util.List<String> found = new java.util.ArrayList<String>();
        for (JsonNode node : array) {
            found.add(node.asText());
        }
        return found;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
