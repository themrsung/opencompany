package com.coreintra.app.api.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.SessionService;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Who gets in, and what a refusal tells them.
 *
 * <p>Three outcomes, and the difference between the first two is the whole
 * point: no credential is 401 and the client should refresh and retry, a
 * credential without the grant is 403 and no amount of retrying will help. A
 * system that answered 403 for both would have every expired session look like
 * a permissions incident.
 *
 * <p>The 403 has to name the missing permission. Without that, an administrator
 * holding a support ticket has to guess which of thirty keys was wanted, and the
 * explainer they would use to find out is itself behind a permission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgApiSecurityIntegrationTest {

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private OrgApiFixture fixture;
    private String companyId;
    private String accountId;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();
        companyId = fixture.company("acme", "에이스전자");
        String employeeId = fixture.employee(companyId, "0001", "김민준");
        accountId = fixture.account("minjun", "김민준", employeeId, false);
    }

    @Test
    @DisplayName("a request with no credential is refused with 401, not 403")
    void unauthenticatedIs401() throws Exception {
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/companies")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);

        JsonNode problem = body(result);
        assertThat(problem.path("code").asText())
                .as("the client branches on the code, never on the title")
                .isEqualTo("unauthenticated");
        assertThat(result.getResponse().getContentType())
                .as("RFC 7807, so a generic error handler recognises it")
                .startsWith("application/problem+json");
    }

    @Test
    @DisplayName("a signed-in caller without the grant is refused with 403 naming the permission")
    void unauthorisedIs403NamingThePermission() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/org/companies")
                .header(HttpHeaders.AUTHORIZATION, fixture.bearer(accountId))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);

        JsonNode problem = body(result);
        assertThat(problem.path("code").asText()).isEqualTo("permission_denied");
        assertThat(problem.path("extensions").path("requiredPermission").asText())
                .as("naming the key is what turns a support ticket into a grant")
                .isEqualTo("company.settings:read");
    }

    @Test
    @DisplayName("the same request succeeds once the grant is attached to the account")
    void grantedCallerSucceeds() throws Exception {
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "company.settings:read",
                PermissionScope.ALL, true);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/org/companies")
                .header(HttpHeaders.AUTHORIZATION, fixture.bearer(accountId))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).path("items")).hasSize(1);
    }

    @Test
    @DisplayName("an explicit deny beats the allow, and the denial still names the permission")
    void explicitDenyWins() throws Exception {
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "company.settings:read",
                PermissionScope.ALL, true);
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "company.settings:read",
                PermissionScope.ALL, false);

        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/companies/" + companyId)
                        .header(HttpHeaders.AUTHORIZATION, fixture.bearer(accountId))).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("no count of allows out-votes a deny within its scope")
                .isEqualTo(403);
        assertThat(body(result).path("extensions").path("requiredPermission").asText())
                .isEqualTo("company.settings:read");
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
