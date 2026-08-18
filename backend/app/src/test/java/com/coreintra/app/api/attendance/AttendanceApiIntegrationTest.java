package com.coreintra.app.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coreintra.app.api.approval.support.ApiTestWorld;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 근태 over HTTP: custom statuses, spells that cross midnight, the who's-in
 * board, and leave balances.
 *
 * <p>The 27:00 cases carry the weight. A shift that begins at 18:00 on the 30th
 * and ends at 03:00 on the 31st is one spell on the 30th, and every layer
 * between the request and the row has an opportunity to helpfully normalise it
 * onto the 31st — Jackson, the DTO, the generated column, the response. These
 * tests walk the value through all of them and back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestWorld.class)
class AttendanceApiIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private LeaveService leave;
    @Autowired private ApiTestWorld world;
    private Cookie person;
    private Cookie colleague;

    @BeforeEach
    void seed() {
        world.reset();
        person = world.sessionFor(world.drafterAccountId);
        colleague = world.sessionFor(world.approverAccountId);
    }

    // ------------------------------------------------------------------
    // Status types
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("status types")
    class StatusTypes {

        @Test
        @DisplayName("a client-defined status is as real as a built-in one")
        void customStatusesAreFirstClass() throws Exception {
            String statusId = defineStatus("TRAINING", "교육", true, true);

            JsonNode listed = list();
            JsonNode created = rowWithCode(listed, "TRAINING");
            assertThat(created.path("id").asText()).isEqualTo(statusId);
            assertThat(created.path("labelKo").asText()).isEqualTo("교육");
            assertThat(created.path("builtIn").asBoolean())
                    .as("defined by the client, not shipped")
                    .isFalse();
            assertThat(created.path("behaviour").path("countsAsWorking").asBoolean()).isTrue();
            assertThat(created.path("behaviour").path("visibleToPeers").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a status that deducts leave without requiring approval is refused at "
                + "definition time")
        void leaveDeductingStatusesMustBeApproved() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/attendance/status-types")
                    .cookie(person)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyId\":\"" + world.companyId + "\",\"code\":\"SNEAKY\","
                            + "\"labelKo\":\"몰래휴가\",\"deductsLeaveBalance\":true,"
                            + "\"requiresApproval\":false}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("it would spend somebody's balance with nobody having agreed to it")
                    .isEqualTo(400);
            assertThat(read(result).path("detail").asText())
                    .contains("must also require approval");
        }

        @Test
        @DisplayName("relabelling needs If-Match, and a stale tag is refused")
        void relabelIsGuarded() throws Exception {
            String statusId = defineStatus("FIELD", "외근", true, false);
            String tag = rowWithCode(list(), "FIELD").path("etag").asText();

            MvcResult missing = mockMvc.perform(relabel(statusId, null, "현장"))
                    .andReturn();
            assertThat(missing.getResponse().getStatus()).isEqualTo(428);

            assertThat(mockMvc.perform(relabel(statusId, tag, "현장")).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult stale = mockMvc.perform(relabel(statusId, tag, "출장")).andReturn();
            assertThat(stale.getResponse().getStatus()).isEqualTo(412);
            assertThat(rowWithCode(list(), "FIELD").path("labelKo").asText())
                    .as("the stale write did not land")
                    .isEqualTo("현장");
        }
    }

    // ------------------------------------------------------------------
    // Records across midnight
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a shift that crosses midnight")
    class NightShift {

        @Test
        @DisplayName("ends at 27:00 on the business day it began, and is not moved to the "
                + "next calendar date")
        void endsAtTwentySeven() throws Exception {
            defineStatus("WORKING", "근무", true, false);

            JsonNode started = startStatus("WORKING", "2026-08-30T18:00:00.000");
            assertThat(started.path("businessDate").asText()).isEqualTo("2026-08-30");
            assertThat(started.path("open").asBoolean()).isTrue();

            JsonNode ended = endStatus("2026-08-30T27:00:00.000");
            assertThat(ended.path("endedAt").asText())
                    .as("03:00 the next morning belongs to the shift that began the evening "
                            + "before")
                    .isEqualTo("2026-08-30T27:00:00.000");
            assertThat(ended.path("businessDate").asText()).isEqualTo("2026-08-30");
            assertThat(ended.path("durationSeconds").asLong())
                    .as("nine hours, not minus fifteen")
                    .isEqualTo(9 * 3600L);

            // Re-read, so this is what was stored rather than what was echoed.
            JsonNode day = dayOf("2026-08-30");
            JsonNode row = day.path("items").get(0);
            assertThat(row.path("endedAt").asText()).isEqualTo("2026-08-30T27:00:00.000");

            assertThat(dayOf("2026-08-31").path("items"))
                    .as("the shift belongs to one business day and appears on exactly one")
                    .isEmpty();
        }

        @Test
        @DisplayName("a trailing Z in the request is rejected loudly and nothing is recorded")
        void zuluIsRefused() throws Exception {
            defineStatus("WORKING", "근무", true, false);

            MvcResult result = mockMvc.perform(post("/api/v1/attendance/records")
                    .cookie(person)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyId\":\"" + world.companyId + "\",\"employeeId\":\""
                            + world.drafterEmployeeId + "\",\"statusCode\":\"WORKING\","
                            + "\"at\":\"2026-08-30T18:00:00.000Z\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode problem = read(result);
            assertThat(problem.path("code").asText()).isEqualTo("invalid_business_instant");
            assertThat(problem.path("extensions").path("input").asText())
                    .isEqualTo("2026-08-30T18:00:00.000Z");
            assertThat(dayOf("2026-08-30").path("items")).isEmpty();
        }

        @Test
        @DisplayName("a pre-shift briefing before midnight keeps a negative offset")
        void negativeOffsetsSurvive() throws Exception {
            defineStatus("WORKING", "근무", true, false);

            JsonNode started = startStatus("WORKING", "2026-08-30T-02:00:00.000");
            assertThat(started.path("startedAt").asText())
                    .as("22:00 on the 29th, belonging to the 30th's business day")
                    .isEqualTo("2026-08-30T-02:00:00.000");
            assertThat(started.path("businessDate").asText()).isEqualTo("2026-08-30");
        }
    }

    // ------------------------------------------------------------------
    // The board
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the who's-in board")
    class Board {

        @Test
        @DisplayName("is filtered by org unit and shows people who recorded nothing")
        void filteredByUnit() throws Exception {
            defineStatus("REMOTE", "재택", true, true);
            startStatus("REMOTE", "2026-08-30T09:00:00.000");

            JsonNode finance = board(world.financeUnitId, "2026-08-30");
            assertThat(employeeIds(finance))
                    .as("김민준, 박부장 and 정참조 hold positions in 회계팀")
                    .contains(world.drafterEmployeeId, world.approverEmployeeId,
                            world.observerEmployeeId)
                    .doesNotContain(world.firstRepEmployeeId);

            JsonNode mine = rowFor(finance, world.drafterEmployeeId);
            assertThat(mine.path("statusCode").asText()).isEqualTo("REMOTE");
            assertThat(mine.path("current").asBoolean())
                    .as("the spell is still open — 재택, not 재택했음")
                    .isTrue();

            JsonNode theirs = rowFor(finance, world.approverEmployeeId);
            assertThat(theirs.hasNonNull("statusCode"))
                    .as("somebody who recorded nothing is shown with no status, not omitted "
                            + "from the board")
                    .isFalse();
        }

        @Test
        @DisplayName("hides a private status behind 비공개 rather than dropping the person")
        void privateStatusesStayOnTheBoard() throws Exception {
            defineStatus("THERAPY", "상담", false, false);
            startStatus("THERAPY", "2026-08-30T09:00:00.000");

            JsonNode asColleague = json.readTree(mockMvc.perform(get("/api/v1/whos-in")
                    .cookie(colleague)
                    .param("companyId", world.companyId)
                    .param("orgUnitId", world.financeUnitId)
                    .param("businessDate", "2026-08-30"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            JsonNode row = rowFor(asColleague, world.drafterEmployeeId);
            assertThat(row.path("visible").asBoolean()).isFalse();
            assertThat(row.path("labelKo").asText()).isEqualTo("비공개");
            assertThat(row.hasNonNull("statusCode"))
                    .as("the code would give the status away")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Leave
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("연차")
    class Leave {

        @Test
        @DisplayName("a balance crosses the wire as an exact decimal string, quarter-days "
                + "and all")
        void balancesAreDecimalStrings() throws Exception {
            PermissionPrincipal actor = PermissionPrincipal.user(
                    world.drafterAccountId, "김민준", world.drafterEmployeeId);
            leave.grantEntitlement(actor, world.companyId, world.drafterEmployeeId,
                    world.leavePolicyId, LocalDate.of(2020, 3, 1),
                    BusinessInstant.of(LocalDate.of(2026, 1, 1), 9 * 3600),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            JsonNode balance = json.readTree(mockMvc.perform(get("/api/v1/leave/balance")
                    .cookie(person)
                    .param("companyId", world.companyId)
                    .param("employeeId", world.drafterEmployeeId)
                    .param("policyId", world.leavePolicyId)
                    .param("asOf", "2026-06-30"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            assertThat(balance.path("balanceDays").isTextual())
                    .as("a JSON number would round 0.25 into somebody's afternoon")
                    .isTrue();
            assertThat(new java.math.BigDecimal(balance.path("balanceDays").asText()))
                    .isGreaterThan(java.math.BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a quote rounds up to the bookable unit and says it did")
        void quotesRoundUp() throws Exception {
            PermissionPrincipal actor = PermissionPrincipal.user(
                    world.drafterAccountId, "김민준", world.drafterEmployeeId);
            leave.grantEntitlement(actor, world.companyId, world.drafterEmployeeId,
                    world.leavePolicyId, LocalDate.of(2020, 3, 1),
                    BusinessInstant.of(LocalDate.of(2026, 1, 1), 9 * 3600),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            JsonNode quote = json.readTree(mockMvc.perform(get("/api/v1/leave/quote")
                    .cookie(person)
                    .param("companyId", world.companyId)
                    .param("employeeId", world.drafterEmployeeId)
                    .param("policyId", world.leavePolicyId)
                    .param("requestedDays", "0.3")
                    .param("asOf", "2026-06-30"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            assertThat(quote.path("requestedDays").asText()).isEqualTo("0.3");
            assertThat(new java.math.BigDecimal(quote.path("bookableDays").asText()))
                    .as("rounded up: booking less leave than is taken puts the difference "
                            + "nowhere")
                    .isGreaterThanOrEqualTo(new java.math.BigDecimal("0.3"));
            assertThat(quote.path("affordable").asBoolean()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String defineStatus(String code, String labelKo, boolean countsAsWorking,
            boolean visibleToPeers) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/attendance/status-types")
                .cookie(person)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\",\"code\":\"" + code + "\","
                        + "\"labelKo\":\"" + labelKo + "\",\"countsAsWorking\":"
                        + countsAsWorking + ",\"visibleToPeers\":" + visibleToPeers + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("define failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return read(result).path("id").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder relabel(
            String statusId, String ifMatch, String labelKo) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                patch("/api/v1/attendance/status-types/{id}/labels", statusId)
                        .cookie(person)
                        .param("companyId", world.companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labelKo\":\"" + labelKo + "\"}");
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private JsonNode list() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/attendance/status-types")
                .cookie(person)
                .param("companyId", world.companyId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode startStatus(String code, String at) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/attendance/records")
                .cookie(person)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\",\"employeeId\":\""
                        + world.drafterEmployeeId + "\",\"statusCode\":\"" + code + "\","
                        + "\"at\":\"" + at + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("start failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return read(result);
    }

    private JsonNode endStatus(String at) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/attendance/records/end")
                .cookie(person)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\",\"employeeId\":\""
                        + world.drafterEmployeeId + "\",\"at\":\"" + at + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("end failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode dayOf(String businessDate) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/attendance/records")
                .cookie(person)
                .param("companyId", world.companyId)
                .param("employeeId", world.drafterEmployeeId)
                .param("businessDate", businessDate))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode board(String orgUnitId, String businessDate) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/whos-in")
                .cookie(person)
                .param("companyId", world.companyId)
                .param("orgUnitId", orgUnitId)
                .param("businessDate", businessDate))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode rowWithCode(JsonNode page, String code) {
        for (JsonNode row : page.path("items")) {
            if (code.equals(row.path("code").asText())) {
                return row;
            }
        }
        throw new AssertionError("no status " + code + " in " + page.path("items"));
    }

    private static JsonNode rowFor(JsonNode page, String employeeId) {
        for (JsonNode row : page.path("items")) {
            if (employeeId.equals(row.path("employeeId").asText())) {
                return row;
            }
        }
        throw new AssertionError("no board row for " + employeeId + " in " + page.path("items"));
    }

    private static java.util.List<String> employeeIds(JsonNode page) {
        java.util.List<String> found = new java.util.ArrayList<String>();
        for (JsonNode row : page.path("items")) {
            found.add(row.path("employeeId").asText());
        }
        return found;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
