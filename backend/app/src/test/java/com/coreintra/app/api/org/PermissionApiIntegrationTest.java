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
import java.time.LocalDate;
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
 * The grant book and the explainer, over HTTP.
 *
 * <p>The round trip that matters is grant → list → revoke, because it is the one
 * that cannot be done at all unless the API can name a grant after making it.
 * {@code PermissionGrant} carries no id, so the id in these responses is
 * recovered by {@code GrantIdentities}; if that pairing ever breaks, revoking
 * becomes impossible and this test is what says so.
 *
 * <p>The escalation gate is tested too, because it is the property that stops
 * the grant endpoints being a privilege-escalation endpoint: handing out a
 * permission you do not hold is refused, and the refusal names the permission
 * you were short of rather than saying "you may not grant".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionApiIntegrationTest {

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
    private String rankId;
    private String subjectEmployeeId;
    private Cookie session;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();

        String companyId = fixture.company("acme", "에이스전자");
        String unitId = fixture.unit(companyId, "finance", "회계팀", null);
        rankId = fixture.rank(companyId, "bujang", "부장", 50);

        subjectEmployeeId = fixture.employee(companyId, "0002", "이서연");
        fixture.account("seoyeon", "이서연", subjectEmployeeId, false);
        fixture.position(subjectEmployeeId, unitId, rankId, LocalDate.of(2026, 1, 1));

        String adminEmployeeId = fixture.employee(companyId, "0001", "관리자");
        String adminAccountId = fixture.account("admin", "관리자", adminEmployeeId, false);
        fixture.grant(GrantSource.USER_ACCOUNT, adminAccountId, "admin.permission:read",
                PermissionScope.ALL, true);
        fixture.grant(GrantSource.USER_ACCOUNT, adminAccountId, "admin.permission:grant",
                PermissionScope.ALL, true);
        fixture.grant(GrantSource.USER_ACCOUNT, adminAccountId, "admin.permission:revoke",
                PermissionScope.ALL, true);
        // The escalation gate: an administrator can only hand out what they hold.
        fixture.grant(GrantSource.USER_ACCOUNT, adminAccountId, "hr.employee:read",
                PermissionScope.ALL, true);
        session = fixture.sessionCookie(adminAccountId);
    }

    @Test
    @DisplayName("a grant can be made, listed with its id, and revoked again")
    void grantListRevokeRoundTrip() throws Exception {
        MvcResult granted = post("/api/v1/permissions/grants",
                "{\"source\":\"RANK\",\"sourceId\":\"" + rankId + "\","
                        + "\"permission\":\"hr.employee:read\",\"scope\":\"ORG_UNIT_SUBTREE\","
                        + "\"allow\":true,\"reason\":\"부장은 팀원 인사기록을 봅니다\"}");
        assertThat(granted.getResponse().getStatus()).isEqualTo(200);

        String grantId = body(granted).path("id").asText();
        assertThat(grantId)
                .as("without an id the grant could never be taken back")
                .isNotEmpty();

        JsonNode listed = body(get("/api/v1/permissions/grants?source=RANK&sourceId=" + rankId));
        assertThat(listed.path("items")).hasSize(1);
        assertThat(listed.path("items").get(0).path("id").asText()).isEqualTo(grantId);
        assertThat(listed.path("items").get(0).path("effect").asText()).isEqualTo("ALLOW");
        assertThat(listed.path("items").get(0).path("sourceLabel").asText())
                .as("the label is what an administrator recognises; the id is not")
                .contains("부장");

        MvcResult revoked = post("/api/v1/permissions/grants/" + grantId + "/revocation",
                "{\"reason\":\"조직 개편으로 회수\"}");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(204);

        assertThat(body(get("/api/v1/permissions/grants?source=RANK&sourceId=" + rankId))
                .path("items"))
                .as("revoked grants are kept for the audit trail and not shown as live")
                .isEmpty();
    }

    @Test
    @DisplayName("granting a permission the caller does not hold is refused, naming that permission")
    void escalationIsRefused() throws Exception {
        MvcResult result = post("/api/v1/permissions/grants",
                "{\"source\":\"RANK\",\"sourceId\":\"" + rankId + "\","
                        + "\"permission\":\"accounting.entry:post\",\"scope\":\"COMPANY\","
                        + "\"allow\":true,\"reason\":\"시도\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result).path("extensions").path("requiredPermission").asText())
                .as("the answer is 'you do not hold this', not 'you may not grant'")
                .isEqualTo("accounting.entry:post");
    }

    @Test
    @DisplayName("a wildcard cannot be granted through the API")
    void wildcardGrantIsRefused() throws Exception {
        MvcResult result = post("/api/v1/permissions/grants",
                "{\"source\":\"RANK\",\"sourceId\":\"" + rankId + "\","
                        + "\"permission\":\"hr.*:read\",\"scope\":\"COMPANY\","
                        + "\"allow\":true,\"reason\":\"시도\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).path("detail").asText()).contains("wildcard");
    }

    @Test
    @DisplayName("the effective set for a person shows the grants and the positions behind them")
    void effectivePermissionsForAnEmployee() throws Exception {
        post("/api/v1/permissions/grants",
                "{\"source\":\"RANK\",\"sourceId\":\"" + rankId + "\","
                        + "\"permission\":\"hr.employee:read\",\"scope\":\"ORG_UNIT_SUBTREE\","
                        + "\"allow\":true,\"reason\":\"부장 권한\"}");

        JsonNode effective = body(get("/api/v1/permissions/effective?employeeId="
                + subjectEmployeeId + "&asOf=2026-08-18"));

        assertThat(effective.path("accountId").asText()).isNotEmpty();
        assertThat(effective.path("asOf").asText()).isEqualTo("2026-08-18");
        assertThat(effective.path("rankIds").size())
                .as("the org state is returned with the grants, so the answer can be read as "
                        + "'these grants, because of these positions'")
                .isEqualTo(1);
        assertThat(effective.path("grants").get(0).path("permission").asText())
                .isEqualTo("hr.employee:read");
    }

    @Test
    @DisplayName("the decision explainer answers 200 for a denial, with the chain")
    void decisionExplainerAnswersDenials() throws Exception {
        JsonNode explained = body(get("/api/v1/permissions/decision/about-employee"
                + "?accountId=" + accountOf() + "&permission=accounting.entry:post"
                + "&employeeId=" + subjectEmployeeId + "&asOf=2026-08-18"));

        assertThat(explained.path("allowed").asBoolean())
                .as("nothing granted it, and deny-by-default is the whole model")
                .isFalse();
        assertThat(explained.path("summary").asText()).isNotEmpty();
        assertThat(explained.path("decidingGrant").isMissingNode())
                .as("null deciding grant means nothing applied at all")
                .isTrue();
    }

    /** The subject's account id, found through the effective-permissions answer. */
    private String accountOf() throws Exception {
        return body(get("/api/v1/permissions/effective?employeeId=" + subjectEmployeeId))
                .path("accountId").asText();
    }

    private MvcResult get(String path) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(path)
                .cookie(session)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("GET %s", path)
                .isEqualTo(200);
        return result;
    }

    private MvcResult post(String path, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .cookie(session)).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
