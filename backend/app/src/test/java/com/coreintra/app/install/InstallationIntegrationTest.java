package com.coreintra.app.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.UserAccountService;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.service.CompanyService;
import com.coreintra.core.service.PermissionGrantService;
import com.coreintra.runtime.audit.AuditActorKind;
import com.coreintra.runtime.audit.AuditLogRow;
import com.coreintra.runtime.audit.AuditLogService;
import com.coreintra.runtime.audit.AuditOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Opening a fresh box, and finding the door locked afterwards.
 *
 * <p>Against real PostgreSQL, because most of what makes the installer safe is
 * in the database: the primary key that decides a race between two installers,
 * the foreign keys that make a half-installed box impossible, the trigger that
 * refuses to let the installation row be removed, and the function that lays
 * the factory catalogue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstallationIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private InstallationService installation;
    @Autowired private CompanyDefaults defaults;
    @Autowired private UserAccountService userAccounts;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PermissionGrantService permissionGrants;
    @Autowired private CompanyService companies;
    @Autowired private AuthenticationService authentication;
    @Autowired private AuditLogService audit;

    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheBox() {
        EmptyInstallation.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // Installing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("installing an empty box creates the first company and master, and shows the credentials once")
    void installsAnEmptyBox() throws Exception {
        assertThat(installation.isAvailable()).isTrue();

        JsonNode installed = install(request("HANBIT", "daepyo"), 201);

        assertThat(installed.path("companyCode").asText()).isEqualTo("HANBIT");
        assertThat(installed.path("masterUsername").asText()).isEqualTo("daepyo");
        assertThat(installed.path("otpauthUri").asText()).startsWith("otpauth://totp/");
        assertThat(installed.path("recoveryCodes")).hasSize(10);
        assertThat(installed.path("bootstrapPermissions"))
                .as("one wildcard row per top-level resource tree, installation-wide")
                .hasSize(7);
        for (JsonNode permission : installed.path("bootstrapPermissions")) {
            assertThat(permission.asText()).endsWith("@ALL");
        }

        String companyId = installed.path("companyId").asText();
        UserAccount master = accounts.findByUsername("daepyo").orElse(null);
        assertThat(master).isNotNull();
        assertThat(master.isMaster()).isTrue();
        assertThat(master.employeeId())
                .as("nobody has been hired yet; the first master is not an employee")
                .isNull();

        assertThat(count("select count(*) from company")).isEqualTo(1);
        assertThat(count("select count(*) from installation")).isEqualTo(1);
        assertThat(count("select count(*) from company_representation where company_id = '"
                + companyId + "'")).isEqualTo(1);
        assertThat(count("select count(*) from approval_line_template where company_id = '"
                + companyId + "' and active")).isEqualTo(2);
    }

    @Test
    @DisplayName("the secret in the response signs the master in, and no copy of it is kept")
    void theCredentialsWorkAndAreNotStored() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);
        String secretBase32 = installed.path("secretBase32").asText();
        String firstRecoveryCode = installed.path("recoveryCodes").get(0).asText();

        TotpGenerator totp = new TotpGenerator();
        byte[] secret = Base32.decode(secretBase32.replace(" ", ""));
        String code = totp.generateAt(secret, System.currentTimeMillis() / 1000L);

        UserAccount signedIn = authentication.authenticate("daepyo", code, "127.0.0.1");
        assertThat(signedIn.isMaster()).isTrue();

        assertThat(count("select count(*) from totp_credential where secret_encrypted like '%"
                + secretBase32.replace(" ", "") + "%'"))
                .as("the secret is stored encrypted; the plaintext exists only in the response")
                .isZero();
        assertThat(count("select count(*) from recovery_code where code_hash like '%"
                + firstRecoveryCode + "%'"))
                .as("recovery codes are stored as salted hashes and can never be read back")
                .isZero();
    }

    @Test
    @DisplayName("the installation is the first row in the audit log, and it has no actor")
    void theInstallationIsAudited() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);

        List<AuditLogRow> trail = audit.trailFor(installed.path("companyId").asText(),
                "installation", InstallationRow.ID);
        assertThat(trail).hasSize(1);
        AuditLogRow event = trail.get(0);
        assertThat(event.actorKind()).isEqualTo(AuditActorKind.ANONYMOUS);
        assertThat(event.actorAccountId())
                .as("an installation has no actor, and that is still an event")
                .isNull();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.ALLOWED);
        assertThat(event.capability()).isEqualTo("admin.installation:open");
        assertThat(count("select count(*) from audit_log")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Closing the door
    // ------------------------------------------------------------------

    @Test
    @DisplayName("once the box is open the endpoint is gone, and says so")
    void theEndpointIsUnavailableAfterwards() throws Exception {
        install(request("HANBIT", "daepyo"), 201);

        assertThat(installation.isAvailable()).isFalse();

        MvcResult refused = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/install"))
                .andReturn();
        assertThat(read(refused).path("available").asBoolean()).isFalse();

        JsonNode problem = install(request("SECOND", "intruder"), 410);
        assertThat(problem.path("code").asText()).isEqualTo("already_installed");
        assertThat(problem.path("detail").asText()).contains("already been opened");
    }

    @Test
    @DisplayName("a second call cannot create a second master, whatever it asks for")
    void aSecondCallCannotCreateASecondMaster() throws Exception {
        install(request("HANBIT", "daepyo"), 201);

        install(request("SECOND", "intruder"), 410);

        assertThat(accounts.findByUsername("intruder")).isEmpty();
        assertThat(accounts.countByMasterTrueAndActiveTrue()).isEqualTo(1L);
        assertThat(count("select count(*) from company")).isEqualTo(1);
        assertThat(count("select count(*) from installation")).isEqualTo(1);
    }

    @Test
    @DisplayName("a box that is only nearly empty is refused: a stray company is somebody's data")
    void refusesANearlyEmptyBox() throws Exception {
        // The failure this guards: a first attempt that created a company and
        // then died. Whoever reaches the endpoint next is a stranger, and the
        // company is somebody else's.
        jdbc.update("insert into company (id, code, name_ko, kind, active, created_at) "
                + "values (?, 'LEFTOVER', '남은 회사', 'HEAD_OFFICE', true, now())",
                UUID.randomUUID().toString());

        assertThat(installation.isAvailable()).isFalse();
        JsonNode problem = install(request("HANBIT", "daepyo"), 409);

        assertThat(problem.path("code").asText()).isEqualTo("installation_not_empty");
        assertThat(accounts.count())
                .as("no account, and above all no master, on a box holding data we did not create")
                .isZero();
        assertThat(count("select count(*) from permission_grant")).isZero();
    }

    @Test
    @DisplayName("the installation row cannot be deleted, so the door cannot be propped open again")
    void theInstallationRowIsWrittenOnce() throws Exception {
        install(request("HANBIT", "daepyo"), 201);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                jdbc.update("delete from installation");
            }
        }).hasMessageContaining("written once");
    }

    @Test
    @DisplayName("the database, not the count, is what decides a race between two installers")
    void theInstallationRowIsSingular() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);
        final String masterId = installed.path("masterAccountId").asText();
        final String companyId = installed.path("companyId").asText();

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                jdbc.update("insert into installation (id, master_account_id, company_id) "
                        + "values ('installation', ?, ?)", masterId, companyId);
            }
        })
                .as("the loser of a race blocks here and then fails, taking its own writes with it")
                .hasMessageContaining("installation_pkey");

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                jdbc.update("insert into installation (id, master_account_id, company_id) "
                        + "values ('installation-2', ?, ?)", masterId, companyId);
            }
        })
                .as("and choosing a different id does not help, which is what the CHECK is for")
                .hasMessageContaining("installation_is_singular");
    }

    // ------------------------------------------------------------------
    // What the master can do next
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the master the installer created can grant a permission and create an account")
    void theMasterCanOpenTheSystemUp() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);
        String companyId = installed.path("companyId").asText();
        PermissionPrincipal master = master(installed);

        String bujangId = jdbc.queryForObject(
                "select id from rank where company_id = ? and code = 'BUJANG'", String.class,
                companyId);
        PermissionGrant granted = permissionGrants.grant(master, GrantSource.RANK, bujangId,
                PermissionKey.parse("hr.employee:read"), PermissionScope.ORG_UNIT_SUBTREE, true,
                "부장은 소속 부서원을 조회합니다.", TODAY);
        assertThat(granted.key().toString()).isEqualTo("hr.employee:read");

        UserAccount created = userAccounts.create(master, "hr.admin", "인사 담당",
                UserAccount.AccountKind.USER, null, TODAY);
        assertThat(created.id()).isNotBlank();
        assertThat(created.isMaster())
                .as("creating an account never confers master; that is a separate decision")
                .isFalse();
        assertThat(accounts.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("the master cannot re-grant its own wildcards, so they never spread")
    void theWildcardsCannotSpread() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);
        final PermissionPrincipal master = master(installed);
        final String bujangId = jdbc.queryForObject(
                "select id from rank where company_id = ? and code = 'BUJANG'", String.class,
                installed.path("companyId").asText());

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                permissionGrants.grant(master, GrantSource.RANK, bujangId,
                        PermissionKey.of("hr.*", "*"), PermissionScope.COMPANY, true,
                        "everything, please", TODAY);
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Later companies
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a company created later gets exactly the defaults the first one got")
    void aLaterCompanyGetsTheSameDefaults() throws Exception {
        JsonNode installed = install(request("HANBIT", "daepyo"), 201);
        String first = installed.path("companyId").asText();
        PermissionPrincipal master = master(installed);

        Company second = companies.create(master, "HANBIT2", "한빛물류 주식회사",
                Company.CompanyKind.SUBSIDIARY, first, TODAY);
        assertThat(codes("rank", second.id()))
                .as("a company created after the migration ran starts empty; that is the wall "
                        + "a client hits when they add their second entity")
                .isEmpty();

        int written = defaults.applyTo(master, second.id(), TODAY);
        assertThat(written).isPositive();

        assertThat(codes("rank", second.id())).isEqualTo(codes("rank", first));
        assertThat(codes("job_function", second.id())).isEqualTo(codes("job_function", first));
        assertThat(codes("attendance_status_type", second.id()))
                .isEqualTo(codes("attendance_status_type", first));
        assertThat(codes("leave_policy", second.id())).isEqualTo(codes("leave_policy", first));
        assertThat(count("select count(*) from leave_tenure_increment i "
                + "join leave_policy p on p.id = i.policy_id where p.company_id = '"
                + second.id() + "'")).isEqualTo(4);

        assertThat(defaults.applyTo(master, second.id(), TODAY))
                .as("asking twice writes nothing and destroys nothing the client has edited")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Representation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("installing as 공동대표 with a quorum of one is refused with the reason, and nothing is left behind")
    void refusesJointRepresentationWithAQuorumOfOne() throws Exception {
        Map<String, Object> body = request("HANBIT", "daepyo");
        body.put("representationMode", "JOINT");
        body.put("requiredApprovals", Integer.valueOf(1));
        body.put("designatedRepresentatives", Integer.valueOf(3));

        JsonNode problem = install(body, 400);

        assertThat(problem.path("detail").asText())
                .as("the service refuses first, so the client gets the reason rather than "
                        + "representation_quorum_sane")
                .contains("각자대표");
        assertThat(installation.isAvailable())
                .as("the whole installation is one transaction: a refusal half way through "
                        + "leaves the box installable rather than half open")
                .isTrue();
        assertThat(accounts.count()).isZero();
        assertThat(count("select count(*) from company")).isZero();
    }

    @Test
    @DisplayName("installing as 공동대표 with a real quorum records it for the approval module to read")
    void installsJointRepresentation() throws Exception {
        Map<String, Object> body = request("HANBIT", "daepyo");
        body.put("representationMode", "JOINT");
        body.put("requiredApprovals", Integer.valueOf(2));
        body.put("designatedRepresentatives", Integer.valueOf(3));

        JsonNode installed = install(body, 201);

        String companyId = installed.path("companyId").asText();
        assertThat(jdbc.queryForObject(
                "select mode from company_representation where company_id = ?", String.class,
                companyId)).isEqualTo("JOINT");
        assertThat(count("select count(*) from company_representation where company_id = '"
                + companyId + "' and required_approvals = 2")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> request(String companyCode, String username) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("companyCode", companyCode);
        body.put("companyNameKo", "한빛산업 주식회사");
        body.put("companyNameEn", "Hanbit Industries");
        body.put("businessRegistrationNumber", "220-81-45678");
        body.put("baseCurrencyCode", "KRW");
        body.put("establishedOn", "2018-03-02");
        body.put("masterUsername", username);
        body.put("masterDisplayName", "김서연");
        return body;
    }

    private JsonNode install(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expectedStatus);
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static PermissionPrincipal master(JsonNode installed) {
        return PermissionPrincipal.master(installed.path("masterAccountId").asText(), "김서연",
                null);
    }

    private List<String> codes(String table, String companyId) {
        return jdbc.queryForList("select code from " + table
                + " where company_id = ? order by code", String.class, companyId);
    }

    private int count(String sql) {
        Integer counted = jdbc.queryForObject(sql, Integer.class);
        return counted == null ? 0 : counted.intValue();
    }
}
