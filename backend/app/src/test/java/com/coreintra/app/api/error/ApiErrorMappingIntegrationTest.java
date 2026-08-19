package com.coreintra.app.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.org.OrgApiFixture;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.SessionService;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.JobFunctionRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The status a failure arrives as, which is the part callers actually branch on.
 *
 * <h2>Why this needs a test of its own</h2>
 *
 * <p>{@code RecordNotFoundException} extends {@code IllegalArgumentException},
 * and the handler for the latter maps to 400. For a missing row to arrive as a
 * 404, Spring has to pick the <em>more specific</em> handler — which it does,
 * but as an implicit property of the framework rather than anything visible in
 * the code. Delete the specific handler, or reorder something, and every
 * missing row starts telling the caller their payload is wrong. Nothing else in
 * the suite would notice.
 *
 * <p>The general case is asserted alongside it, because a test that only pins
 * the specific mapping would still pass if someone "fixed" it by widening the
 * 404 handler to every {@code IllegalArgumentException}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorMappingIntegrationTest {

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
    private Cookie session;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();
        companyId = fixture.company("acme", "에이스전자");
        String employeeId = fixture.employee(companyId, "0001", "김민준");
        String accountId = fixture.account("minjun", "김민준", employeeId, false);
        fixture.grantAccountAdministration(accountId);
        session = fixture.sessionCookie(accountId);
    }

    @Test
    @DisplayName("a missing row is 404, not the 400 its parent exception would give")
    void missingRowIsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/employees/no-such-employee")
                        .cookie(session)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("RecordNotFoundException extends IllegalArgumentException. If the specific "
                        + "handler is lost, this becomes a 400 and sends the caller looking for a "
                        + "mistake in a payload they did not send.")
                .isEqualTo(404);

        JsonNode problem = body(result);
        assertThat(problem.path("code").asText()).isEqualTo("not_found");
        assertThat(problem.path("detail").asText())
                .as("and it names what was not found, rather than saying only that something was not")
                .contains("no-such-employee");
    }

    @Test
    @DisplayName("an ordinary bad argument is still 400")
    void badArgumentIsStillBadRequest() throws Exception {
        // A corrupt cursor. Cursors.decode refuses rather than quietly restarting
        // from the first page, because silently serving page one for a corrupt
        // cursor makes an infinite loop look like a working client.
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/companies")
                        .param("cursor", "not-a-cursor!!")
                        .cookie(session)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the 404 mapping must be for missing rows only; widening it to every "
                        + "IllegalArgumentException would hide real client errors as 404s")
                .isEqualTo(400);
        assertThat(body(result).path("code").asText()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("every problem response is problem+json, whichever handler produced it")
    void problemsAreProblemJson() throws Exception {
        MvcResult notFound = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/employees/no-such-employee")
                        .cookie(session)).andReturn();
        MvcResult unauthenticated = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/companies")).andReturn();

        for (MvcResult result : new MvcResult[] {notFound, unauthenticated}) {
            assertThat(result.getResponse().getContentType())
                    .as("a client that parses one failure shape must be able to parse them all")
                    .startsWith("application/problem+json");
        }
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
