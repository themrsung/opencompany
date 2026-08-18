package com.coreintra.app.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The accounting REST surface, end to end, against a real PostgreSQL.
 *
 * <p>Four claims are held here that cannot be held anywhere else, because each is about what
 * actually crosses the wire rather than about what a service returns:
 *
 * <ul>
 *   <li>every amount is a JSON <em>string</em>, at every level of every response;</li>
 *   <li>an idempotency key posts once, and the same key with a different body is refused;</li>
 *   <li>reports exclude drafts, voids and closing batches on the way out, not just in a unit test;</li>
 *   <li>a caller without {@code accounting.entry:post} is refused, and the refusal names it.</li>
 * </ul>
 *
 * <p>The permission evaluator is the real one over real grant rows, so "refused" here means
 * refused by the same machinery an administrator's grant flows through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountingApiTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    /**
     * A principal the test can set, in place of the session-cookie one.
     *
     * <p>Standing up a real sign-in for every case would test authentication, which has its own
     * suite. What is under test here is authorisation and serialisation, so the caller is supplied
     * and the grants behind it are real rows read by the real evaluator.
     */
    @TestConfiguration
    static class SuppliedPrincipal {

        static PermissionPrincipal caller;

        @Bean
        @Primary
        public CurrentPrincipal suppliedCurrentPrincipal() {
            return new CurrentPrincipal() {
                @Override
                public PermissionPrincipal require() {
                    if (caller == null) {
                        throw new UnauthenticatedException();
                    }
                    return caller;
                }
            };
        }
    }

    /**
     * Fresh accounts per test.
     *
     * <p>A grant is unique on (source, sourceId, resource, action, scope, allow), so reusing one
     * account across tests would make the second {@code @BeforeEach} collide. Fresh ids also mean
     * one test cannot leave a grant behind that changes what the next one is allowed to do.
     */
    private String accountant;
    private String reader;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private PermissionGrantRepository grants;

    private String companyId;
    private String bookId;

    @BeforeEach
    void seedACompanyAndABook() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Ids are VARCHAR(36) UUID strings; the code is short and unique per company.
        companyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO company (id, code, name_ko, kind) VALUES (?, ?, ?, 'HEAD_OFFICE')",
                companyId, "API" + companyId.substring(0, 8), "회계 API 테스트");
        accountant = UUID.randomUUID().toString();
        reader = UUID.randomUUID().toString();
        insertAccount(jdbc, accountant);
        insertAccount(jdbc, reader);

        // The accountant may do everything in the module; the reader may do everything except
        // post, by an explicit deny — which is how a real installation says "everything but".
        grant(accountant, PermissionKey.of("accounting.*", "*"), true);
        grant(reader, PermissionKey.of("accounting.*", "*"), true);
        grant(reader, PermissionKey.of("accounting.entry", "post"), false);

        as(accountant);
        bookId = idOf(perform(post("/api/v1/accounting/books")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + companyId + "\",\"name\":\"API book "
                        + UUID.randomUUID() + "\",\"baseCurrency\":{\"code\":\"KRW\","
                        + "\"nameKo\":\"원\",\"nameEn\":\"Won\",\"symbol\":\"₩\","
                        + "\"displayDecimals\":0}}"), 201));

        openAccount("1100", null, "현금", "CASH", "OPERATING");
        openAccount("1200", null, "매출채권", "RECEIVABLE", "OPERATING");
        openAccount("3900", null, "이익잉여금", null, "FINANCING");
        openAccount("4100", null, "매출", null, "OPERATING");
        openAccount("5100", null, "급여", null, "OPERATING");
    }

    private void insertAccount(JdbcTemplate jdbc, String accountId) {
        jdbc.update("INSERT INTO user_account (id, username, display_name, kind) "
                + "VALUES (?, ?, ?, 'SERVICE_ACCOUNT') ON CONFLICT (id) DO NOTHING",
                accountId, accountId, accountId);
    }

    private void grant(String accountId, PermissionKey key, boolean allow) {
        grants.save(new PermissionGrantRow(UUID.randomUUID().toString(),
                GrantSource.USER_ACCOUNT, accountId, key, PermissionScope.ALL, allow));
    }

    private static void as(String accountId) {
        SuppliedPrincipal.caller = PermissionPrincipal.user(accountId, accountId, null);
    }

    private void openAccount(String id, String parentId, String nameKo, String category,
            String classification) throws Exception {
        perform(post("/api/v1/accounting/books/" + bookId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"parentId\":"
                        + (parentId == null ? "null" : "\"" + parentId + "\"")
                        + ",\"nameKo\":\"" + nameKo + "\""
                        + (category == null ? "" : ",\"category\":\"" + category + "\"")
                        + (classification == null ? ""
                                : ",\"classification\":\"" + classification + "\"")
                        + "}"), 201);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private JsonNode perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("unexpected status; body was: %s", body)
                .isEqualTo(expectedStatus);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private static String idOf(JsonNode node) {
        return node.path("id").asText();
    }

    private String postEntry(String key, String date, String amount, int expectedStatus)
            throws Exception {
        JsonNode response = perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(saleBody(date, amount)), expectedStatus);
        return idOf(response);
    }

    private static String saleBody(String date, String amount) {
        return "{\"postedAt\":\"" + date + "T09:00:00.000\",\"description\":\"외상 매출\","
                + "\"postings\":["
                + "{\"accountId\":\"1200\",\"side\":\"debit\",\"amount\":\"" + amount + "\"},"
                + "{\"accountId\":\"4100\",\"side\":\"credit\",\"amount\":\"" + amount + "\"}]}";
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("money on the wire")
    class MoneyIsText {

        /**
         * Field names that carry a quantity of something. A new one is added to this list when a
         * report grows a figure, and the test then insists it is a string.
         */
        private final List<String> moneyFields = new ArrayList<String>(java.util.Arrays.asList(
                "amount", "baseAmount", "signedBaseAmount", "rate", "totalDebits", "totalCredits",
                "debit", "credit", "net", "imbalance", "assets", "liabilities", "equity",
                "unclosedNetIncome", "income", "expense", "netIncome", "openingCash",
                "closingCash", "netMovement", "discrepancy", "openingEquity", "ownerMovements",
                "closingAdjustments", "transferredToEquityByClosing", "closingEquity", "total",
                "outstanding", "unappliedCredits", "totalUnappliedCredits", "unassigned",
                "capitalised", "recognised", "unexpired", "scheduledTotal", "totalUnexpired",
                "totalScheduled", "unscheduled", "carryingAmount", "residualValue"));

        @Test
        @DisplayName("ACCEPTANCE: an amount is a string in the JSON, at every level of every "
                + "response")
        void everyAmountIsAString() throws Exception {
            as(accountant);
            postEntry("money-1-" + UUID.randomUUID(), "2026-03-15", "333333.33333333333333", 201);
            perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", "money-2-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"postedAt\":\"2026-03-16T09:00:00.000\",\"description\":\"수금\","
                            + "\"postings\":["
                            + "{\"accountId\":\"1100\",\"side\":\"debit\",\"amount\":\"1,000\"},"
                            + "{\"accountId\":\"1200\",\"side\":\"credit\",\"amount\":\"1000\"}]}"),
                    201);

            String base = "/api/v1/accounting/books/" + bookId;
            check(perform(get(base + "/entries"), 200), "the journal");
            check(perform(get(base + "/accounts"), 200), "the chart");
            check(perform(get(base + "/reports/trial-balance?asOf=2026-12-31"), 200),
                    "the trial balance");
            check(perform(get(base + "/reports/balance-sheet?asOf=2026-12-31"), 200),
                    "the balance sheet");
            check(perform(get(base + "/reports/income-statement?from=2026-01-01&to=2026-12-31"),
                    200), "the income statement");
            check(perform(get(base + "/reports/cash-flow?from=2026-01-01&to=2026-12-31"), 200),
                    "the cash-flow statement");
            check(perform(get(base + "/reports/equity-statement?from=2026-01-01&to=2026-12-31"),
                    200), "the equity statement");
            check(perform(get(base + "/reports/receivables-ageing?asOf=2026-12-31"), 200),
                    "the ageing report");
            check(perform(get(base + "/reports/prepaid-schedules?asOf=2026-12-31"), 200),
                    "the prepaid schedules");
            check(perform(post(base + "/amortisation/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"debitAccountId\":\"5100\",\"creditAccountId\":\"1100\","
                            + "\"baseAmount\":\"1000000\",\"startMonth\":\"2026-01\","
                            + "\"months\":3,\"roundingDigits\":2,\"remainderTo\":\"END\","
                            + "\"postingDay\":28,\"offsetSeconds\":32400,"
                            + "\"description\":\"보험료 상각\"}"), 200), "the amortisation preview");
        }

        @Test
        @DisplayName("the full precision survives the round trip, undamaged")
        void precisionSurvives() throws Exception {
            as(accountant);
            String entryId = postEntry("precision-" + UUID.randomUUID(), "2026-03-15",
                    "333333.33333333333333", 201);

            JsonNode entry = perform(get("/api/v1/accounting/entries/" + entryId), 200);
            JsonNode amount = entry.path("postings").get(0).path("amount");
            assertThat(amount.isTextual())
                    .as("a JSON number here becomes a double in the browser and loses four digits")
                    .isTrue();
            assertThat(amount.asText()).isEqualTo("333333.33333333333333");
        }

        @Test
        @DisplayName("thousands separators are accepted and 1e3, .5 and 1. are refused")
        void inputFormat() throws Exception {
            as(accountant);
            postEntry("sep-" + UUID.randomUUID(), "2026-03-15", "1,400,000.25", 201);

            for (String bad : new String[] {"1e3", ".5", "1."}) {
                JsonNode problem = perform(
                        post("/api/v1/accounting/books/" + bookId + "/entries")
                                .header("Idempotency-Key", "bad-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(saleBody("2026-03-15", bad)), 400);
                assertThat(problem.path("code").asText())
                        .as("%s must be refused rather than interpreted", bad)
                        .isEqualTo("bad_request");
            }
        }

        /**
         * Walks the whole tree.
         *
         * <p>Two rules, and the second is the one that catches the mistake nobody sees in review:
         * a field with a money-ish name must be textual, <em>and</em> no node anywhere may be a
         * non-integral number. The second covers a nested type nobody thought to add to the list,
         * because the only way a {@link java.math.BigDecimal} reaches JSON unprotected is as a
         * decimal number.
         */
        private void check(JsonNode node, String what) {
            walk(node, "$", what);
        }

        private void walk(JsonNode node, String path, String what) {
            if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
                throw new AssertionError("in " + what + ", " + path + " is a JSON number ("
                        + node.asText() + "). Amounts cross the wire as exact decimal strings; a "
                        + "JSON number is a double in most parsers. See ADR 0004.");
            }
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode value = field.getValue();
                    if (moneyFields.contains(field.getKey()) && !value.isNull()) {
                        assertThat(value.isTextual())
                                .as("in %s, %s.%s must be a string, was %s", what, path,
                                        field.getKey(), value.getNodeType())
                                .isTrue();
                    }
                    walk(value, path + '.' + field.getKey(), what);
                }
                return;
            }
            if (node.isArray()) {
                for (int index = 0; index < node.size(); index++) {
                    walk(node.get(index), path + '[' + index + ']', what);
                }
            }
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("ACCEPTANCE: the same key twice creates one entry")
        void sameKeySameBodyPostsOnce() throws Exception {
            as(accountant);
            String key = "once-" + UUID.randomUUID();

            String first = postEntry(key, "2026-03-15", "500000", 201);
            String second = postEntry(key, "2026-03-15", "500000", 201);

            assertThat(second)
                    .as("a retry must be answered with what the first call did, not a new entry")
                    .isEqualTo(first);
            JsonNode journal = perform(
                    get("/api/v1/accounting/books/" + bookId + "/entries"), 200);
            assertThat(journal.path("items").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("ACCEPTANCE: the same key with a different body is refused, loudly")
        void sameKeyDifferentBodyIsRefused() throws Exception {
            as(accountant);
            String key = "clash-" + UUID.randomUUID();
            postEntry(key, "2026-03-15", "500000", 201);

            JsonNode problem = perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(saleBody("2026-03-15", "900000")), 409);

            assertThat(problem.path("code").asText()).isEqualTo("idempotency_key_reused");
            assertThat(problem.path("extensions").path("idempotencyKey").asText()).isEqualTo(key);
            JsonNode journal = perform(
                    get("/api/v1/accounting/books/" + bookId + "/entries"), 200);
            assertThat(journal.path("items").size())
                    .as("and the second request did not post anything")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a POST that creates money without a key is refused rather than run")
        void keyIsRequired() throws Exception {
            as(accountant);
            JsonNode problem = perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(saleBody("2026-03-15", "500000")), 400);
            assertThat(problem.path("detail").asText()).contains("Idempotency-Key");
        }
    }

    @Nested
    @DisplayName("reports over the wire")
    class Reports {

        @Test
        @DisplayName("ACCEPTANCE: a report excludes voided and draft entries, and excludes "
                + "closing batches from the income statement")
        void exclusions() throws Exception {
            as(accountant);
            postEntry("sale-" + UUID.randomUUID(), "2026-03-15", "1000000", 201);
            perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", "wages-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"postedAt\":\"2026-04-15T09:00:00.000\",\"description\":\"급여\","
                            + "\"postings\":["
                            + "{\"accountId\":\"5100\",\"side\":\"debit\",\"amount\":\"300000\"},"
                            + "{\"accountId\":\"1100\",\"side\":\"credit\","
                            + "\"amount\":\"300000\"}]}"), 201);

            String statement = "/api/v1/accounting/books/" + bookId
                    + "/reports/income-statement?from=2026-01-01&to=2026-12-31";
            assertThat(perform(get(statement), 200).path("netIncome").asText())
                    .isEqualTo("700000");

            // A draft contributes nothing.
            perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", "draft-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"postedAt\":\"2026-05-15T09:00:00.000\",\"description\":\"작성중\","
                            + "\"draft\":true,\"postings\":["
                            + "{\"accountId\":\"1200\",\"side\":\"debit\",\"amount\":\"888888\"},"
                            + "{\"accountId\":\"4100\",\"side\":\"credit\","
                            + "\"amount\":\"888888\"}]}"), 201);
            assertThat(perform(get(statement), 200).path("netIncome").asText())
                    .as("a draft is a proposal, not a figure")
                    .isEqualTo("700000");

            // A voided entry contributes nothing either.
            String mistake = postEntry("dupe-" + UUID.randomUUID(), "2026-06-15", "50000", 201);
            String etag = mockMvc.perform(get("/api/v1/accounting/entries/" + mistake))
                    .andReturn().getResponse().getHeader("ETag");
            perform(post("/api/v1/accounting/entries/" + mistake + "/void")
                    .header("If-Match", etag)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"중복 입력\"}"), 200);
            assertThat(perform(get(statement), 200).path("netIncome").asText())
                    .as("a voided entry is off every report")
                    .isEqualTo("700000");

            // And the closing batch does not unearn the year.
            perform(post("/api/v1/accounting/books/" + bookId + "/batches")
                    .header("Idempotency-Key", "close-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"CLOSING\",\"label\":\"2026 결산\",\"entries\":[{"
                            + "\"postedAt\":\"2026-12-31T09:00:00.000\",\"description\":\"손익 대체\","
                            + "\"postings\":["
                            + "{\"accountId\":\"4100\",\"side\":\"debit\",\"amount\":\"1000000\"},"
                            + "{\"accountId\":\"5100\",\"side\":\"credit\",\"amount\":\"300000\"},"
                            + "{\"accountId\":\"3900\",\"side\":\"credit\","
                            + "\"amount\":\"700000\"}]}]}"), 201);

            assertThat(perform(get(statement), 200).path("netIncome").asText())
                    .as("the year earned this, and closing it does not unearn it")
                    .isEqualTo("700000");
        }

        @Test
        @DisplayName("the balanced flag is reported, with the imbalance beside it")
        void balancedFlagIsReported() throws Exception {
            as(accountant);
            postEntry("bal-" + UUID.randomUUID(), "2026-03-15", "1000000", 201);

            JsonNode trial = perform(get("/api/v1/accounting/books/" + bookId
                    + "/reports/trial-balance?asOf=2026-12-31"), 200);
            assertThat(trial.path("balanced").asBoolean()).isTrue();
            assertThat(trial.path("imbalance").asText())
                    .as("the alarm is reported as a figure, not implied by its absence")
                    .isEqualTo("0");

            JsonNode sheet = perform(get("/api/v1/accounting/books/" + bookId
                    + "/reports/balance-sheet?asOf=2026-12-31"), 200);
            assertThat(sheet.path("balanced").asBoolean()).isTrue();
            assertThat(sheet.path("unclosedNetIncome").asText()).isEqualTo("1000000");
            assertThat(sheet.path("imbalance").asText()).isEqualTo("0");
        }

        @Test
        @DisplayName("an unbalanced entry is a 422 carrying the exact difference, "
                + "and nothing is written")
        void unbalancedIsRefusedWithTheDifference() throws Exception {
            as(accountant);
            JsonNode problem = perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", "unbal-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"postedAt\":\"2026-03-15T09:00:00.000\",\"description\":\"틀린 전표\","
                            + "\"postings\":["
                            + "{\"accountId\":\"1200\",\"side\":\"debit\",\"amount\":\"1000000\"},"
                            + "{\"accountId\":\"4100\",\"side\":\"credit\","
                            + "\"amount\":\"999999\"}]}"), 422);

            assertThat(problem.path("code").asText()).isEqualTo("entry_unbalanced");
            assertThat(problem.path("extensions").path("difference").asText())
                    .as("the figure the bookkeeper needs, as a string like every other amount")
                    .isEqualTo("1");
            assertThat(perform(get("/api/v1/accounting/books/" + bookId + "/entries"), 200)
                    .path("items").size()).isZero();
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("ACCEPTANCE: an entry posted by an account without accounting.entry:post is "
                + "refused, and the refusal names the permission")
        void postingIsRefusedByName() throws Exception {
            as(reader);

            JsonNode problem = perform(post("/api/v1/accounting/books/" + bookId + "/entries")
                    .header("Idempotency-Key", "denied-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(saleBody("2026-03-15", "1000000")), 403);

            assertThat(problem.path("code").asText()).isEqualTo("permission_denied");
            assertThat(problem.path("extensions").path("requiredPermission").asText())
                    .as("a bare 403 leaves an administrator guessing which grant to make")
                    .isEqualTo("accounting.entry:post");

            as(accountant);
            assertThat(perform(get("/api/v1/accounting/books/" + bookId + "/entries"), 200)
                    .path("items").size())
                    .as("and nothing was written")
                    .isZero();
        }

        @Test
        @DisplayName("the same account may still read what it is entitled to read")
        void readsStillWork() throws Exception {
            as(accountant);
            postEntry("read-" + UUID.randomUUID(), "2026-03-15", "1000000", 201);

            as(reader);
            assertThat(perform(get("/api/v1/accounting/books/" + bookId
                    + "/reports/trial-balance?asOf=2026-12-31"), 200)
                    .path("totalDebits").asText())
                    .as("the deny is on posting, and it reaches nothing else")
                    .isEqualTo("1000000");
        }

        @Test
        @DisplayName("an anonymous request is 401, not 403")
        void anonymousIsUnauthenticated() throws Exception {
            SuppliedPrincipal.caller = null;
            perform(get("/api/v1/accounting/books/" + bookId
                    + "/reports/trial-balance?asOf=2026-12-31"), 401);
        }
    }

    @Nested
    @DisplayName("concurrency control")
    class Concurrency {

        @Test
        @DisplayName("a mutation without If-Match is 428, and a stale one is 412")
        void ifMatchIsEnforced() throws Exception {
            as(accountant);
            String entryId = postEntry("etag-" + UUID.randomUUID(), "2026-03-15", "1000000", 201);

            perform(post("/api/v1/accounting/entries/" + entryId + "/void")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"취소\"}"), 428);

            perform(post("/api/v1/accounting/entries/" + entryId + "/void")
                    .header("If-Match", "\"99-nonsense\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"취소\"}"), 412);

            String etag = mockMvc.perform(get("/api/v1/accounting/entries/" + entryId))
                    .andReturn().getResponse().getHeader("ETag");
            JsonNode voided = perform(post("/api/v1/accounting/entries/" + entryId + "/void")
                    .header("If-Match", etag)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"취소\"}"), 200);
            assertThat(voided.path("status").asText()).isEqualTo("VOID");
            assertThat(voided.path("revisions").get(0).path("actorAccountId").asText())
                    .as("the trail names the account the evaluator judged")
                    .isEqualTo(accountant);
        }
    }

    @Nested
    @DisplayName("pagination")
    class Pagination {

        @Test
        @DisplayName("a cursor walks the journal without skipping or repeating an entry")
        void cursorWalksEverything() throws Exception {
            as(accountant);
            for (int day = 1; day <= 5; day++) {
                postEntry("page-" + day + "-" + UUID.randomUUID(),
                        String.format("2026-03-%02d", day), "1000", 201);
            }

            List<String> seen = new ArrayList<String>();
            String cursor = null;
            for (int guard = 0; guard < 10; guard++) {
                String url = "/api/v1/accounting/books/" + bookId + "/entries?limit=2"
                        + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode page = perform(get(url), 200);
                for (JsonNode item : page.path("items")) {
                    seen.add(item.path("id").asText());
                }
                // The installation serialises with non_null inclusion, so the end of the
                // collection is an absent nextCursor rather than a null one. Both mean the same
                // thing and a client must treat them the same, or it walks the list for ever.
                if (!page.hasNonNull("nextCursor")) {
                    break;
                }
                cursor = page.get("nextCursor").asText();
            }

            assertThat(seen).as("five entries, each exactly once").hasSize(5);
            assertThat(new java.util.HashSet<String>(seen)).hasSize(5);
        }
    }
}
