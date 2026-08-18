package com.coreintra.app.mcp.tools.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.org.OrgApiFixture;
import com.coreintra.app.mcp.McpProtocol;
import com.coreintra.app.support.DatabaseTestSupport;
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
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The org tools, against the real protocol handler and a real database.
 *
 * <p>Two properties matter more than the rest. First, a read-scoped token cannot
 * see, call or learn of the existence of a write tool — hiding is what stops a
 * model burning its turns on something it can never be allowed to do. Second, a
 * tool is a second client of the same service and not a second way into the
 * data: same account, same grants, same evaluator, and a denial arrives as a
 * tool error the model can act on rather than as a broken server.
 *
 * <h2>Why most of this drives {@link McpProtocol} rather than HTTP</h2>
 *
 * <p>Scopes reach the protocol from an API key, and issuing one is broken today
 * in the auth module: {@code ApiKeyService} mints its <em>public</em> prefix
 * with {@code SecretHasher.randomToken(6)}, and that method refuses anything
 * under 128 bits. Until that is fixed no scoped credential can be created, so
 * these tests hand the protocol the principal and scopes the controller would
 * have handed it — the same method, with one HTTP hop left out. One test does
 * go over HTTP, to prove the endpoint and the filter are wired.
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
    @Autowired private McpProtocol protocol;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> READ_ONLY = Immutables.setOf("mcp:read");
    private static final Set<String> READ_WRITE = Immutables.setOf("mcp:read", "mcp:write");

    private OrgApiFixture fixture;
    private String companyId;
    private String employeeId;
    private String serviceAccountId;
    private String someoneElsesAccountId;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();
        companyId = fixture.company("acme", "에이스전자");
        employeeId = fixture.employee(companyId, "0001", "김민준");

        someoneElsesAccountId = fixture.account("minjun", "김민준", employeeId, false);
        serviceAccountId = fixture.serviceAccount("reporter", "보고서 봇");
        // A service account holds no position, so account-attached grants are
        // the only authority it can have - which is what makes it enumerable.
        fixture.grant(GrantSource.USER_ACCOUNT, serviceAccountId, "hr.employee:read",
                PermissionScope.ALL, true);
    }

    @Test
    @DisplayName("a read-scoped token does not see the write tools at all")
    void writeToolsAreInvisibleWithoutTheScope() {
        List<String> names = toolNames(READ_ONLY);

        assertThat(names)
                .as("the read side the brief asks for, at minimum")
                .contains("org_employee_lookup", "org_unit_people", "permission_explain_decision");
        assertThat(names)
                .as("hidden, not merely refused: a model that can see a tool keeps trying it")
                .doesNotContain("org_employee_create", "org_position_assign");
    }

    @Test
    @DisplayName("calling a write tool without the scope answers as though it did not exist")
    void callingAHiddenToolLooksLikeAMissingTool() {
        JsonNode response = handle(READ_ONLY, call("org_position_assign",
                "{\"employeeId\":\"" + employeeId + "\",\"orgUnitId\":\"x\",\"rankId\":\"y\","
                        + "\"effectiveFrom\":\"2026-08-18\"}"));

        assertThat(response.path("error").path("code").asInt())
                .as("the same answer as a genuinely unknown tool, so the write set cannot be "
                        + "enumerated by probing")
                .isEqualTo(-32601);
        assertThat(response.path("error").path("message").asText()).contains("No such tool");
    }

    @Test
    @DisplayName("the write tools appear once the token carries mcp:write")
    void writeToolsAppearWithTheScope() {
        assertThat(toolNames(READ_WRITE))
                .contains("org_employee_create", "org_position_assign");
    }

    @Test
    @DisplayName("every visible tool states its permission and whether it writes")
    void descriptionsCarryThePermissionAndTheWriteFlag() {
        JsonNode tools = handle(READ_ONLY, request("tools/list")).path("result").path("tools");

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
        JsonNode result = handle(READ_ONLY, call("org_employee_lookup",
                "{\"employeeId\":\"" + employeeId + "\"}")).path("result");

        assertThat(result.path("isError").asBoolean(false)).isFalse();

        JsonNode payload = JSON.readTree(result.path("content").get(0).path("text").asText());
        assertThat(payload.path("employees")).hasSize(1);
        assertThat(payload.path("employees").get(0).path("nameKo").asText()).isEqualTo("김민준");
    }

    @Test
    @DisplayName("searching by name finds the person a model was actually given")
    void searchFindsByName() throws Exception {
        JsonNode payload = JSON.readTree(handle(READ_ONLY, call("org_employee_lookup",
                "{\"companyId\":\"" + companyId + "\",\"query\":\"민준\"}"))
                .path("result").path("content").get(0).path("text").asText());

        assertThat(payload.path("employees")).hasSize(1);
        assertThat(payload.path("employees").get(0).path("employeeId").asText())
                .isEqualTo(employeeId);
    }

    @Test
    @DisplayName("a denial is a tool error the model can act on, not a protocol failure")
    void denialIsAToolError() {
        // Someone else's account, and the service account was never granted
        // admin.permission:read. Inspecting one's own authority needs no
        // permission, so asking about itself would prove nothing.
        JsonNode response = handle(READ_ONLY, call("permission_explain_decision",
                "{\"accountId\":\"" + someoneElsesAccountId + "\",\"permission\":\"hr.employee:read\","
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
    void missingArgumentIsRecoverable() {
        JsonNode result = handle(READ_ONLY, call("org_unit_people", "{}")).path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("_meta").path("code").asText()).isEqualTo("invalid_argument");
        assertThat(result.path("content").get(0).path("text").asText()).contains("orgUnitId");
    }

    @Test
    @DisplayName("the endpoint is wired: a signed-in caller can list tools over HTTP")
    void toolsListOverHttp() throws Exception {
        String employeeIdForCaller = fixture.employee(companyId, "0002", "관리자");
        String accountId = fixture.account("admin", "관리자", employeeIdForCaller, false);
        Cookie session = fixture.sessionCookie(accountId);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("tools/list"))
                .cookie(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode tools = JSON.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("result").path("tools");
        assertThat(tools.size()).isGreaterThan(0);

        List<String> names = new ArrayList<String>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        assertThat(names)
                .as("a session carries no mcp:write scope, so the write tools stay hidden")
                .doesNotContain("org_employee_create");
    }

    @Test
    @DisplayName("an unauthenticated MCP call is refused; there is no anonymous access")
    void mcpRequiresACaller() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("tools/list"))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private List<String> toolNames(Set<String> scopes) {
        JsonNode tools = handle(scopes, request("tools/list")).path("result").path("tools");
        List<String> names = new ArrayList<String>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    /** The exact call {@code McpController} makes, minus the HTTP hop. */
    private JsonNode handle(Set<String> scopes, String body) {
        PermissionPrincipal principal =
                PermissionPrincipal.serviceAccount(serviceAccountId, "보고서 봇", scopes);
        try {
            return protocol.handle(principal, principal.apiKeyScopes(), JSON.readTree(body));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the test's own JSON is malformed", e);
        }
    }

    private static String request(String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}";
    }
}
