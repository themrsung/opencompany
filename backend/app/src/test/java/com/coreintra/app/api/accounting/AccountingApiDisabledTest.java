package com.coreintra.app.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.app.mcp.McpToolRegistry;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ACCEPTANCE (the API half): with the module off, its endpoints and tools are absent — not
 * present and refusing.
 *
 * <p>{@code AccountingDisabledBootTest} already holds the other half: the application boots and
 * every other feature works. This one is about the surface, and the distinction matters. A
 * controller that existed and returned 403 would still appear in the OpenAPI document, in the
 * generated TypeScript client and in the MCP tool listing, so a client who never bought accounting
 * would be able to see the shape of what they had not bought — and, worse, the endpoints would be
 * wired to services that are not there, which is a context that refuses to start.
 *
 * <p>§9 says the tables stay, and they do: the flag removes beans, endpoints and UI, never data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "coreintra.accounting.enabled=false")
class AccountingApiDisabledTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    /** Authenticated, so that a 404 cannot be mistaken for a 401. */
    @TestConfiguration
    static class SuppliedPrincipal {

        @Bean
        @Primary
        public CurrentPrincipal suppliedCurrentPrincipal() {
            return new CurrentPrincipal() {
                @Override
                public PermissionPrincipal require() {
                    return PermissionPrincipal.serviceAccount("disabled-test", "disabled-test",
                            com.coreintra.compat.Immutables.setOf("mcp:write"));
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext context;
    @Autowired private McpToolRegistry tools;

    @Test
    @DisplayName("every accounting endpoint is absent, not merely refusing")
    void endpointsAreGone() throws Exception {
        String[] paths = {
            "/api/v1/accounting/books?companyId=any",
            "/api/v1/accounting/books/any/accounts",
            "/api/v1/accounting/books/any/entries",
            "/api/v1/accounting/books/any/batches",
            "/api/v1/accounting/books/any/currencies",
            "/api/v1/accounting/books/any/clients",
            "/api/v1/accounting/books/any/reports/trial-balance",
            "/api/v1/accounting/books/any/reports/balance-sheet",
            "/api/v1/accounting/books/any/reports/income-statement?from=2026-01-01&to=2026-12-31",
            "/api/v1/accounting/books/any/reports/cash-flow?from=2026-01-01&to=2026-12-31",
            "/api/v1/accounting/books/any/reports/equity-statement?from=2026-01-01&to=2026-12-31",
            "/api/v1/accounting/books/any/reports/receivables-ageing",
            "/api/v1/accounting/books/any/reports/prepaid-schedules",
        };
        for (String path : paths) {
            assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s should not be routed at all with accounting off", path)
                    .isEqualTo(404);
        }
        assertThat(mockMvc.perform(post("/api/v1/accounting/books/any/amortisation/preview")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("no accounting controller, tool or helper is a bean")
    void beansAreGone() {
        assertThat(context.getBeanNamesForType(AccountingBooksController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChartOfAccountsController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JournalController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AccountingReportsController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AmortizationController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AccountingIdempotency.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AccountingApiAdvice.class)).isEmpty();
    }

    @Test
    @DisplayName("no accounting MCP tool is visible, even to a token scoped for writes")
    void toolsAreGone() throws Exception {
        assertThat(tools.visibleTo(com.coreintra.compat.Immutables.setOf("mcp:write")))
                .as("a tool that is listed and then fails is worse than one that is not listed")
                .noneMatch(tool -> tool.name().startsWith("accounting_"));

        MvcResult result = mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andReturn();
        JsonNode listing = json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode tool : listing.path("result").path("tools")) {
            assertThat(tool.path("name").asText()).doesNotStartWith("accounting_");
        }

        MvcResult refused = mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"accounting_post_entry\",\"arguments\":{}}}")).andReturn();
        JsonNode answer = json.readTree(
                refused.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(answer.path("error").path("message").asText())
                .as("and calling one directly finds nothing either")
                .contains("No such tool");
    }

    @Test
    @DisplayName("the OpenAPI document does not describe a module that is not there")
    void theContractIsQuietToo() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
        JsonNode document = json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        for (java.util.Iterator<String> paths = document.path("paths").fieldNames();
                paths.hasNext();) {
            assertThat(paths.next())
                    .as("the committed contract is generated from the running application, so a "
                            + "phantom endpoint here would become a phantom method on the client")
                    .doesNotStartWith("/api/v1/accounting");
        }
    }
}
