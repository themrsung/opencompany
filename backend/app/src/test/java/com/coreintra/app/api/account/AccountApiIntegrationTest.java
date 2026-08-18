package com.coreintra.app.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.api.org.OrgApiFixture;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.AuthenticationService;
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
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The credential surface: enrolment, recovery codes, API keys, master status.
 *
 * <p>Everything here is about secrets that exist for exactly one response, and
 * about the one refusal that keeps an installation reachable. The tests are
 * written to fail if a later change makes any of those secrets fetchable twice —
 * which is the change somebody will be tempted to make the first time a user
 * closes the tab too early.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiIntegrationTest {

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
    @Autowired private AuthenticationService authentication;

    private static final ObjectMapper JSON = new ObjectMapper();

    private OrgApiFixture fixture;
    private String accountId;
    private Cookie session;

    @BeforeEach
    void seed() {
        fixture = new OrgApiFixture(dataSource, companies, orgUnits, ranks, jobFunctions,
                employees, accounts, positions, grants, sessions);
        fixture.reset();
        String companyId = fixture.company("acme", "에이스전자");
        String employeeId = fixture.employee(companyId, "0001", "김민준");
        accountId = fixture.account("minjun", "김민준", employeeId, true);
        fixture.grantAccountAdministration(accountId);
        session = fixture.sessionCookie(accountId);
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("the recovery codes come back once and are never returned again")
        void recoveryCodesAreReturnedOnce() throws Exception {
            MvcResult begun = post("/api/v1/account/enrolment", null);
            assertThat(begun.getResponse().getStatus()).isEqualTo(200);

            JsonNode enrolled = body(begun);
            List<String> issued = strings(enrolled.path("recoveryCodes"));
            assertThat(issued).as("an enrolment with no recovery codes is a lockout waiting to "
                    + "happen").isNotEmpty();
            assertThat(enrolled.path("otpauthUri").asText()).startsWith("otpauth://");

            MvcResult status = get("/api/v1/account/enrolment");
            String statusBody = status.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body(status).has("recoveryCodes"))
                    .as("the status endpoint must never carry the codes")
                    .isFalse();
            for (String code : issued) {
                assertThat(statusBody)
                        .as("no code may appear anywhere in a later response")
                        .doesNotContain(code);
            }
            assertThat(body(status).path("remainingRecoveryCodes").asLong())
                    .isEqualTo(issued.size());
        }

        @Test
        @DisplayName("regenerating issues a different set and kills the old one")
        void regenerationInvalidatesTheOldCodes() throws Exception {
            List<String> first = strings(body(post("/api/v1/account/enrolment", null))
                    .path("recoveryCodes"));

            List<String> second = strings(body(post("/api/v1/account/enrolment/recovery-codes",
                    null)).path("recoveryCodes"));

            assertThat(second).isNotEmpty().doesNotContainAnyElementsOf(first);
            assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() {
                    authentication.authenticateWithRecoveryCode("minjun", first.get(0),
                            "127.0.0.1");
                }
            })
                    .as("a code from the replaced set must not sign anybody in")
                    .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("a wrong confirmation code is an answer, not an error")
        void wrongConfirmationCodeAnswersFalse() throws Exception {
            post("/api/v1/account/enrolment", null);

            MvcResult result = post("/api/v1/account/enrolment/confirmation",
                    "{\"code\":\"000000\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).path("confirmed").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("API keys")
    class ApiKeys {

        @Test
        @DisplayName("the plaintext is returned once at issue and is not recoverable afterwards")
        void plaintextIsReturnedOnce() throws Exception {
            MvcResult issued = post("/api/v1/account/api-keys",
                    "{\"name\":\"보고서 봇\",\"scopes\":[\"mcp:read\"]}");
            assertThat(issued.getResponse().getStatus()).isEqualTo(200);

            JsonNode key = body(issued);
            String plaintext = key.path("plaintext").asText();
            assertThat(plaintext).startsWith("ci_");
            String keyId = key.path("key").path("id").asText();

            MvcResult listed = get("/api/v1/account/api-keys");
            String listedBody = listed.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(listedBody)
                    .as("only a salted hash is stored, so no later call can produce this")
                    .doesNotContain(plaintext);
            assertThat(listedBody)
                    .as("the prefix stays visible so the key can be recognised and revoked")
                    .contains(key.path("key").path("keyPrefix").asText());

            JsonNode page = body(listed);
            assertThat(page.path("items")).hasSize(1);
            assertThat(page.path("items").get(0).has("plaintext")).isFalse();
            assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(keyId);
        }

        @Test
        @DisplayName("a revoked key leaves the live list")
        void revokedKeyDisappears() throws Exception {
            String keyId = body(post("/api/v1/account/api-keys", "{\"name\":\"임시 키\"}"))
                    .path("key").path("id").asText();

            MvcResult revoked = mockMvc.perform(
                    MockMvcRequestBuilders.delete("/api/v1/account/api-keys/" + keyId)
                            .cookie(session)).andReturn();

            assertThat(revoked.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(get("/api/v1/account/api-keys")).path("items")).isEmpty();
        }

        @Test
        @DisplayName("a key belonging to another account cannot be revoked through this one")
        void revokingSomebodyElsesKeyIsRefused() throws Exception {
            String otherId = fixture.serviceAccount("robot", "로봇");
            MvcResult issuedForOther = post(
                    "/api/v1/account/api-keys?accountId=" + otherId, "{\"name\":\"남의 키\"}");
            String otherKeyId = body(issuedForOther).path("key").path("id").asText();

            MvcResult result = mockMvc.perform(
                    MockMvcRequestBuilders.delete("/api/v1/account/api-keys/" + otherKeyId)
                            .cookie(session)).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("the key id alone must not be enough; it has to be on the named account")
                    .isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("master accounts")
    class Masters {

        @Test
        @DisplayName("demoting the last master is refused with a message that says what to do")
        void lastMasterCannotBeDemoted() throws Exception {
            assertThat(body(get("/api/v1/account/masters")).path("activeMasterCount").asLong())
                    .isEqualTo(1);

            MvcResult result = mockMvc.perform(
                    MockMvcRequestBuilders.delete("/api/v1/account/masters/" + accountId)
                            .cookie(session)).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("409: the caller has the authority, the installation cannot allow it")
                    .isEqualTo(409);
            JsonNode problem = body(result);
            assertThat(problem.path("code").asText()).isEqualTo("last_master");
            assertThat(problem.path("detail").asText())
                    .as("bilingual, and it names the way out rather than only the refusal")
                    .contains("마지막 마스터")
                    .contains("Promote another account");
            assertThat(body(get("/api/v1/account/masters")).path("activeMasterCount").asLong())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("demotion succeeds once a second master exists")
        void demotionSucceedsWithASpareMaster() throws Exception {
            String spare = fixture.account("seoyeon", "이서연", null, false);

            MvcResult promoted = post("/api/v1/account/masters",
                    "{\"accountId\":\"" + spare + "\"}");
            assertThat(promoted.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(get("/api/v1/account/masters")).path("activeMasterCount").asLong())
                    .isEqualTo(2);

            MvcResult demoted = mockMvc.perform(
                    MockMvcRequestBuilders.delete("/api/v1/account/masters/" + accountId)
                            .cookie(session)).andReturn();

            assertThat(demoted.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(get("/api/v1/account/masters")).path("activeMasterCount").asLong())
                    .isEqualTo(1);
        }
    }

    private MvcResult get(String path) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(path)
                .cookie(session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result;
    }

    private MvcResult post(String path, String json) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.post(path)
                        .cookie(session);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<String>();
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
