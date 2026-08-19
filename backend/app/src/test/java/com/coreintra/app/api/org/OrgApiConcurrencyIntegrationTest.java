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
import javax.servlet.http.Cookie;
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
 * Two people editing the same row, and what the second one is told.
 *
 * <p>The failure this prevents is silent and expensive: both open 회계팀, both
 * rename it, and the second save overwrites the first with nobody informed. The
 * three outcomes tested here are the whole contract — no {@code If-Match} is
 * refused outright, a stale one is refused with the current tag attached so the
 * client can re-derive its change in one round trip, and a current one goes
 * through and yields a new tag.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgApiConcurrencyIntegrationTest {

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

        String employeeId = fixture.employee(companyId, "0001", "관리자");
        String accountId = fixture.account("admin", "관리자", employeeId, false);
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "company.settings:read",
                PermissionScope.ALL, true);
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "company.settings:update",
                PermissionScope.ALL, true);
        session = fixture.sessionCookie(accountId);
    }

    @Test
    @DisplayName("a mutation without If-Match is refused before anything is written")
    void missingIfMatchIsRefused() throws Exception {
        MvcResult result = rename(null, "이름바꾼회사");

        assertThat(result.getResponse().getStatus())
                .as("428: the client has not implemented concurrency control at all")
                .isEqualTo(428);
        assertThat(body(result).path("code").asText()).isEqualTo("precondition_required");
        assertThat(currentName()).isEqualTo("에이스전자");
    }

    @Test
    @DisplayName("a stale If-Match is refused with 412 carrying the current tag")
    void staleIfMatchIsRefusedWithTheCurrentTag() throws Exception {
        String original = read().getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult firstWriter = rename(original, "첫번째수정");
        assertThat(firstWriter.getResponse().getStatus()).isEqualTo(200);
        String afterFirstWrite = firstWriter.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(afterFirstWrite)
                .as("the tag has to move, or the second writer would be waved through")
                .isNotEqualTo(original);

        MvcResult secondWriter = rename(original, "두번째수정");

        assertThat(secondWriter.getResponse().getStatus()).isEqualTo(412);
        JsonNode problem = body(secondWriter);
        assertThat(problem.path("code").asText()).isEqualTo("precondition_failed");
        assertThat(problem.path("extensions").path("currentEtag").asText())
                .as("handing back the current tag turns the retry into one round trip")
                .isEqualTo(afterFirstWrite);
        assertThat(secondWriter.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo(afterFirstWrite);
        assertThat(currentName())
                .as("the first writer's change survived; that is the point of all this")
                .isEqualTo("첫번째수정");
    }

    @Test
    @DisplayName("the tag from a read is accepted by the write that follows it")
    void freshIfMatchIsAccepted() throws Exception {
        String tag = read().getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult result = rename(tag, "정상수정");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).path("nameKo").asText()).isEqualTo("정상수정");
    }

    @Test
    @DisplayName("If-Match: * is accepted, because it means 'I only care that it exists'")
    void wildcardIfMatchIsAccepted() throws Exception {
        MvcResult result = rename("*", "와일드카드수정");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(currentName()).isEqualTo("와일드카드수정");
    }

    private MvcResult read() throws Exception {
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/org/companies/" + companyId)
                        .cookie(session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result;
    }

    private MvcResult rename(String ifMatch, String nameKo) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.patch("/api/v1/org/companies/" + companyId + "/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameKo\":\"" + nameKo + "\"}")
                        .cookie(session);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String currentName() throws Exception {
        return body(read()).path("nameKo").asText();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
