package com.coreintra.app.mcp.tools.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.org.OrgApiFixture;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.ApiKeyService;
import com.coreintra.auth.service.SessionService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.JobFunctionRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The org tools over the real MCP endpoint, with a real scoped API key.
 *
 * <p>Two properties matter more than the rest and both are tested end to end
 * rather than against a mock registry. First, a read-scoped token cannot see,
 * call or learn of the existence of a write tool — hiding is what stops a model
 * burning its turns on something it can never be allowed to do. Second, a tool
 * is a second client of the same service and not a second way into the data: the
 * same account, the same grants, the same evaluator, and a denial comes back as
 * a tool error the model can act on rather than as a broken server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgToolsIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @Autowired private CompanyRepository companies;
    @Autowired private OrgUnitRepository orgUnits;
    @Autowired private RankRepository ranks;
    @Autowired private JobFunctionRepository jobFunctions;
    @Autowired private EmployeeRepository employees;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PositionRepository positions;
    @Autowired private PermissionGrantRepository grants;
    @Autowired private SessionService sessions;
    @Autowired private ApiKeyService apiKeys;

    private static final ObjectMapper JSON = new ObjectMapper();

    private OrgApiFixture fixture;
    private String companyId;
    private String employeeId;
    private String serviceAccountId;
    private String readOnlyKey;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();
        companyId = fixture.company("acme", "에이스전자");
        employeeId = fixture.employee(companyId, "0001", "김민준");

        serviceAccountId = fixture.serviceAccount("reporter", "보고서 봇");
        // A service account holds no position, so account-attached grants are
        // the only authority it can have - which is what makes it enumerable.
        fixture.grant(GrantSource.USER_ACCOUNT, serviceAccountId, "hr.employee:read",
                PermissionScope.ALL, true);
        readOnlyKey = issueKey(Immutables.setOf("mcp:read"));
    }

    @Test
    @DisplayName("a read-scoped token does not see the write tools at all")
    void writeToolsAreInvisibleWithoutTheScope() throws Exception {
        List<String> names = toolNames(readOnlyKey);

        assertThat(names)
                .as("the read side the brief asks for, at minimum")
                .contains("org_employee_lookup", "org_unit_people", "permission_explain_decision");
        assertThat(names)
                .as("hidden, not merely refused: a model that can see a tool keeps trying it")
                .doesNotContain("org_employee_create", "org_position_assign");
    }

    @Test
    @DisplayName("calling a write tool without the scope answers as though it did not exist")
    void callingAHiddenToolLooksLikeAMissingTool() throws Exception {
        JsonNode response = rpc(readOnlyKey, call("org_position_assign",
                "{\"employeeId\":\"" + employeeId + "\",\"orgUnitId\":\"x\",\"rankId\":\"y\","
                        + "\"effectiveFrom\":\"2026-08-18\"}"));

        assertThat(response.path("error").path("code").asInt())
                .as("the same answer as a genuinely unknown tool, so the write set cannot be "
                        + "enumerated by probing")
                .isEqualTo(-32601);
        assertThat(response.path("error").path("message").asText()).contains("No such tool");
    }

    @Test
    @DisplayName("the write tools appear once the key carries mcp:write")
    void writeToolsAppearWithTheScope() throws Exception {
        String writeKey = issueKey(Immutables.setOf("mcp:read", "mcp:write"));

        assertThat(toolNames(writeKey))
                .contains("org_employee_create", "org_position_assign");
    }

    @Test
    @DisplayName("every tool states its permission and whether it writes, without repeating itself")
    void descriptionsCarryThePermissionAndTheWriteFlag() throws Exception {
        JsonNode tools = rpc(readOnlyKey, request("tools/list")).path("result").path("tools");

        assertThat(tools.size()).isGreaterThan(0);
        for (JsonNode tool : tools) {
            String description = tool.path("description").asText();
            assertThat(description)
                    .as("%s must state the permission it checks", tool.path("name").asText())
                    .contains("Requires permission: ");
            assertThat(description)
                    .as("%s must state that it does not write", tool.path("name").asText())
                    .contains("Writes: no");
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("a read tool answers from the same service the REST endpoint uses")
    void readToolAnswersThroughTheService() throws Exception {
        JsonNode response = rpc(readOnlyKey, call("org_employee_lookup",
                "{\"employeeId\":\"" + employeeId + "\"}"));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean(false)).isFalse();

        JsonNode payload = JSON.readTree(result.path("content").get(0).path("text").asText());
        assertThat(payload.path("employees")).hasSize(1);
        assertThat(payload.path("employees").get(0).path("nameKo").asText()).isEqualTo("김민준");
    }

    @Test
    @DisplayName("searching by name finds the person a model was actually given")
    void searchFindsByName() throws Exception {
        JsonNode payload = JSON.readTree(rpc(readOnlyKey, call("org_employee_lookup",
                "{\"companyId\":\"" + companyId + "\",\"query\":\"민준\"}"))
                .path("result").path("content").get(0).path("text").asText());

        assertThat(payload.path("employees")).hasSize(1);
        assertThat(payload.path("employees").get(0).path("employeeId").asText())
                .isEqualTo(employeeId);
    }

    @Test
    @DisplayName("a denial is a tool error the model can act on, not a protocol failure")
    void denialIsAToolError() throws Exception {
        // The service account was never granted admin.permission:read.
        JsonNode response = rpc(readOnlyKey, call("permission_explain_decision",
                "{\"accountId\":\"" + serviceAccountId + "\",\"permission\":\"hr.employee:read\","
                        + "\"employeeId\":\"" + employeeId + "\"}"));

        assertThat(response.has("error"))
                .as("the server is fine; this one call was refused")
                .isFalse();
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("_meta").path("code").asText()).isEqualTo("permission_denied");
        assertThat(result.path("content").get(0).path("text").asText())
                .as("the missing permission is named, exactly as the REST 403 names it")
                .contains("admin.permission:read");
    }

    @Test
    @DisplayName("a missing required argument is an error result, not a 500")
    void missingArgumentIsRecoverable() throws Exception {
        JsonNode result = rpc(readOnlyKey, call("org_unit_people", "{}")).path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("_meta").path("code").asText()).isEqualTo("invalid_argument");
        assertThat(result.path("content").get(0).path("text").asText()).contains("orgUnitId");
    }

    private String issueKey(java.util.Set<String> scopes) {
        return apiKeys.issue(serviceAccountId, "test key " + OrgApiFixture.id(), scopes,
                serviceAccountId, null).plaintext();
    }

    private List<String> toolNames(String key) throws Exception {
        JsonNode tools = rpc(key, request("tools/list")).path("result").path("tools");
        List<String> names = new ArrayList<String>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static String request(String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}";
    }

    private JsonNode rpc(String key, String body) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
