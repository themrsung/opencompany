package com.coreintra.app.mcp.tools.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolRegistry;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The MCP tools: the same services, the same evaluator, the same strings.
 *
 * <p>What is worth testing here is precisely what is different about the MCP door — the write
 * tool's visibility, the descriptions a model reads, and the fact that a tool refuses for the same
 * reason and with the same words a controller does. The reports themselves are tested where they
 * are computed.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountingMcpToolsTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    private static final java.util.Set<String> READ_ONLY = Immutables.setOf("mcp:read");
    private static final java.util.Set<String> MAY_WRITE =
            Immutables.setOf("mcp:read", "mcp:write");

    @Autowired private McpToolRegistry registry;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private PermissionGrantRepository grants;
    @Autowired private com.coreintra.accounting.service.BookService books;
    @Autowired private com.coreintra.accounting.service.ChartOfAccountsService chart;

    private PermissionPrincipal accountant;
    private PermissionPrincipal reader;
    private String bookId;

    @BeforeEach
    void seed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Ids are VARCHAR(36) UUID strings, so nothing here is prefixed into overflowing one.
        String companyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO company (id, code, name_ko, kind) VALUES (?, ?, ?, 'HEAD_OFFICE')",
                companyId, "MCP" + companyId.substring(0, 8), "MCP 회계 테스트");

        String accountantId = UUID.randomUUID().toString();
        String readerId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO user_account (id, username, display_name, kind) "
                + "VALUES (?, ?, ?, 'SERVICE_ACCOUNT')", accountantId, accountantId, "회계");
        jdbc.update("INSERT INTO user_account (id, username, display_name, kind) "
                + "VALUES (?, ?, ?, 'SERVICE_ACCOUNT')", readerId, readerId, "열람");

        allow(accountantId, PermissionKey.of("accounting.*", "*"), true);
        allow(readerId, PermissionKey.of("accounting.*", "*"), true);
        allow(readerId, PermissionKey.of("accounting.entry", "post"), false);

        accountant = PermissionPrincipal.serviceAccount(accountantId, "회계", MAY_WRITE);
        reader = PermissionPrincipal.serviceAccount(readerId, "열람", MAY_WRITE);

        java.time.LocalDate day = java.time.LocalDate.of(2026, 1, 2);
        bookId = books.openBook(accountant, companyId, "MCP book " + UUID.randomUUID(),
                com.coreintra.accounting.domain.Currency.krw(), day).id();
        chart.openAccount(accountant, bookId, "1200", null, "매출채권", "Receivables", null, false,
                day);
        chart.openAccount(accountant, bookId, "4100", null, "매출", "Revenue", null, false, day);
    }

    private void allow(String accountId, PermissionKey key, boolean allow) {
        grants.save(new PermissionGrantRow(UUID.randomUUID().toString(), GrantSource.USER_ACCOUNT,
                accountId, key, PermissionScope.ALL, allow));
    }

    private McpTool tool(String name, java.util.Set<String> scopes) {
        McpTool found = registry.find(name, scopes);
        assertThat(found).as("%s is not visible to %s", name, scopes).isNotNull();
        return found;
    }

    private JsonNode callAndRead(McpTool tool, PermissionPrincipal principal, String arguments)
            throws Exception {
        McpToolResult result = tool.call(principal, json.readTree(arguments));
        assertThat(result.isError()).as("tool reported an error: %s", result.textBlocks())
                .isFalse();
        return json.readTree(result.textBlocks().get(0));
    }

    @Test
    @DisplayName("the reports and the chart are read tools, visible without mcp:write")
    void readToolsAreVisibleToAReadScopedToken() {
        List<McpTool> visible = registry.visibleTo(READ_ONLY);
        assertThat(visible).extracting(McpTool::name)
                .contains("accounting_chart_of_accounts", "accounting_trial_balance",
                        "accounting_balance_sheet", "accounting_income_statement",
                        "accounting_cash_flow", "accounting_equity_statement",
                        "accounting_receivables_ageing", "accounting_prepaid_schedules");
        for (McpTool visibleTool : visible) {
            if (visibleTool.name().startsWith("accounting_")) {
                assertThat(visibleTool.writes())
                        .as("%s is visible to a read-scoped token, so it must not write",
                                visibleTool.name())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("posting an entry is a write tool: invisible without mcp:write, and refused "
            + "as though it did not exist")
    void theWriteToolNeedsExplicitOptIn() {
        assertThat(registry.visibleTo(READ_ONLY)).extracting(McpTool::name)
                .as("a model that can see a tool will keep trying it")
                .doesNotContain("accounting_post_entry");
        assertThat(registry.find("accounting_post_entry", READ_ONLY))
                .as("and the direct call finds nothing, so the write tools cannot be enumerated")
                .isNull();
        assertThat(registry.find("accounting_post_entry", MAY_WRITE)).isNotNull();
    }

    @Test
    @DisplayName("every tool states the permission it needs and whether it writes")
    void descriptionsCarryTheTwoFacts() {
        for (McpTool listed : registry.visibleTo(MAY_WRITE)) {
            if (!listed.name().startsWith("accounting_")) {
                continue;
            }
            assertThat(listed.describe())
                    .contains("Requires permission: " + listed.requiredPermission())
                    .contains("Writes: ");
            assertThat(listed.requiredPermission().resource()).startsWith("accounting.");
            assertThat(listed.requiredPermission().isWildcard())
                    .as("a tool checks one concrete permission, so the explainer can say why")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a report tool returns exact decimal strings, never JSON numbers")
    void amountsAreStrings() throws Exception {
        callAndRead(tool("accounting_post_entry", MAY_WRITE), accountant,
                entryArguments("mcp-money-" + UUID.randomUUID(), "333333.33333333333333"));

        JsonNode trial = callAndRead(tool("accounting_trial_balance", READ_ONLY), accountant,
                "{\"book\":\"" + bookId + "\",\"asOf\":\"2026-12-31\"}");

        assertThat(trial.path("totalDebits").isTextual()).isTrue();
        assertThat(trial.path("totalDebits").asText()).isEqualTo("333333.33333333333333");
        assertNoDecimalNumbers(trial, "$");

        JsonNode sheet = callAndRead(tool("accounting_balance_sheet", READ_ONLY), accountant,
                "{\"book\":\"" + bookId + "\",\"asOf\":\"2026-12-31\"}");
        assertNoDecimalNumbers(sheet, "$");
        assertThat(sheet.path("balanced").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the write tool posts once per idempotency key")
    void theWriteToolIsIdempotent() throws Exception {
        McpTool post = tool("accounting_post_entry", MAY_WRITE);
        String key = "mcp-once-" + UUID.randomUUID();

        JsonNode first = callAndRead(post, accountant, entryArguments(key, "1000000"));
        JsonNode second = callAndRead(post, accountant, entryArguments(key, "1000000"));

        assertThat(second.path("id").asText())
                .as("a model that retries a call it is unsure about must not post twice")
                .isEqualTo(first.path("id").asText());
    }

    @Test
    @DisplayName("the write tool refuses an account without accounting.entry:post, by name")
    void theWriteToolRefusesByName() {
        McpTool post = tool("accounting_post_entry", MAY_WRITE);

        assertThatThrownBy(() -> post.call(reader,
                json.readTree(entryArguments("mcp-denied-" + UUID.randomUUID(), "1000000"))))
                .isInstanceOf(PermissionDeniedException.class)
                .satisfies(thrown -> assertThat(
                        ((PermissionDeniedException) thrown).requiredPermission().toString())
                        .isEqualTo("accounting.entry:post"));
    }

    @Test
    @DisplayName("an unbalanced entry is refused with the exact difference, and nothing is written")
    void theWriteToolWillNotPlug() throws Exception {
        McpTool post = tool("accounting_post_entry", MAY_WRITE);

        assertThatThrownBy(() -> post.call(accountant, json.readTree(
                "{\"book\":\"" + bookId + "\",\"idempotencyKey\":\"mcp-unbal-" + UUID.randomUUID()
                        + "\",\"postedAt\":\"2026-03-15T09:00:00.000\",\"description\":\"틀린 전표\","
                        + "\"postings\":["
                        + "{\"accountId\":\"1200\",\"side\":\"debit\",\"amount\":\"1000000\"},"
                        + "{\"accountId\":\"4100\",\"side\":\"credit\",\"amount\":\"999999\"}]}")))
                .isInstanceOf(com.coreintra.accounting.domain.Entry.UnbalancedEntryException.class)
                .hasMessageContaining("차변과 대변이 일치하지 않습니다");

        JsonNode trial = callAndRead(tool("accounting_trial_balance", READ_ONLY), accountant,
                "{\"book\":\"" + bookId + "\",\"asOf\":\"2026-12-31\"}");
        assertThat(trial.path("totalDebits").asText()).isEqualTo("0");
    }

    @Test
    @DisplayName("the chart tool reports what each account inherits as well as what it sets")
    void chartToolShowsInheritance() throws Exception {
        JsonNode accounts = callAndRead(tool("accounting_chart_of_accounts", READ_ONLY),
                accountant, "{\"book\":\"" + bookId + "\"}");

        assertThat(accounts.isArray()).isTrue();
        assertThat(accounts.size()).isEqualTo(2);
        assertThat(accounts.get(0).path("type").asText()).isEqualTo("ASSET");
        assertThat(accounts.get(0).path("postable").asBoolean()).isTrue();
    }

    private String entryArguments(String key, String amount) {
        return "{\"book\":\"" + bookId + "\",\"idempotencyKey\":\"" + key + "\","
                + "\"postedAt\":\"2026-03-15T09:00:00.000\",\"description\":\"외상 매출\","
                + "\"postings\":["
                + "{\"accountId\":\"1200\",\"side\":\"debit\",\"amount\":\"" + amount + "\"},"
                + "{\"accountId\":\"4100\",\"side\":\"credit\",\"amount\":\"" + amount + "\"}]}";
    }

    /**
     * No node anywhere may be a non-integral number.
     *
     * <p>The tightest net available: the only way a {@code BigDecimal} reaches JSON unprotected is
     * as a decimal number, and integers — a display-decimal count, a revision number — are
     * quantities of nothing that can lose precision.
     */
    private static void assertNoDecimalNumbers(JsonNode node, String path) {
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            throw new AssertionError(path + " is a JSON number (" + node.asText()
                    + "). Amounts are exact decimal strings; see ADR 0004.");
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                assertNoDecimalNumbers(field.getValue(), path + '.' + field.getKey());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                assertNoDecimalNumbers(node.get(index), path + '[' + index + ']');
            }
        }
    }
}
