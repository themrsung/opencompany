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
import java.util.ArrayList;
import java.util.List;
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
 * The property cursor pagination exists for: a walk sees every row once.
 *
 * <p>The interesting case is the one that breaks offset pagination, so it is the
 * one the main test performs — somebody inserts a row while the walk is in
 * progress. With offsets, a row inserted before the cursor pushes an unread row
 * from position 4 to position 5 and page two starts at what is now position 5,
 * so that row is never returned and nothing anywhere reports a problem. On an
 * approval inbox that is a document nobody sees.
 *
 * <p>A cursor anchored to (employee number, id) cannot do that. A row inserted
 * behind the cursor is not shown — the walk has already passed that point, which
 * is honest and visible — and every row ahead of it is returned exactly once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgApiPagingIntegrationTest {

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

        String callerEmployeeId = fixture.employee(companyId, "0000", "관리자");
        String accountId = fixture.account("admin", "관리자", callerEmployeeId, false);
        fixture.grant(GrantSource.USER_ACCOUNT, accountId, "hr.employee:read",
                PermissionScope.ALL, true);
        session = fixture.sessionCookie(accountId);
    }

    @Test
    @DisplayName("a cursor walk returns every row exactly once, even when a row is inserted mid-walk")
    void walkSeesEveryRowOnce() throws Exception {
        List<String> expected = new ArrayList<String>();
        expected.add("0000");
        for (int i = 1; i <= 6; i++) {
            String number = String.format("%04d", Integer.valueOf(i));
            fixture.employee(companyId, number, "사원" + i);
            expected.add(number);
        }

        List<String> seen = new ArrayList<String>();
        String cursor = null;
        int pages = 0;
        boolean inserted = false;

        do {
            JsonNode page = page(cursor, 3);
            for (JsonNode item : page.path("items")) {
                seen.add(item.path("employeeNumber").asText());
            }
            pages++;

            if (!inserted) {
                // Two writes between page one and page two: one that lands in
                // territory the walk has already covered, and one ahead of it.
                fixture.employee(companyId, "0000-a", "끼어든사람");
                fixture.employee(companyId, "9999", "늦게온사람");
                inserted = true;
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null && pages < 20);

        assertThat(pages)
                .as("three rows a page over seven rows plus one inserted ahead of the cursor")
                .isGreaterThan(2);
        assertThat(seen)
                .as("no row is served twice, which is the duplicate half of the offset bug")
                .doesNotHaveDuplicates();
        assertThat(seen)
                .as("no pre-existing row is skipped, which is the half that loses documents")
                .containsAll(expected);
        assertThat(seen)
                .as("a row inserted ahead of the cursor is picked up by the same walk")
                .contains("9999");
        assertThat(seen)
                .as("a row inserted behind the cursor is not re-served; the walk has passed it")
                .doesNotContain("0000-a");
    }

    @Test
    @DisplayName("the last page is signalled by a null cursor, never by a short page")
    void endOfCollectionIsTheNullCursor() throws Exception {
        for (int i = 1; i <= 3; i++) {
            fixture.employee(companyId, String.format("%04d", Integer.valueOf(i)), "사원" + i);
        }

        JsonNode first = page(null, 4);
        assertThat(first.path("items")).hasSize(4);
        assertThat(first.path("nextCursor").isNull())
                .as("exactly four rows and a limit of four: there is nothing after them")
                .isTrue();
    }

    @Test
    @DisplayName("a page size beyond the cap is clamped rather than honoured")
    void pageSizeIsCapped() throws Exception {
        for (int i = 1; i <= 5; i++) {
            fixture.employee(companyId, String.format("%04d", Integer.valueOf(i)), "사원" + i);
        }

        JsonNode page = page(null, 1000000);
        assertThat(page.path("items").size())
                .as("?limit=1000000 is a denial of service anyone with a session could trigger")
                .isLessThanOrEqualTo(Pages.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("a corrupt cursor is a 400, not a silent restart from page one")
    void corruptCursorIsRefused() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/org/employees")
                .param("companyId", companyId)
                .param("cursor", "not-a-cursor-at-all")
                .cookie(session)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("quietly serving page one for a corrupt cursor makes an infinite loop look "
                        + "like a working client")
                .isEqualTo(400);
    }

    private JsonNode page(String cursor, int limit) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/org/employees")
                .param("companyId", companyId)
                .param("limit", Integer.toString(limit))
                .param("cursor", cursor == null ? "" : cursor)
                .cookie(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
