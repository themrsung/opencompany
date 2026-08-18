package com.coreintra.app.mcp.tools.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.api.documents.DocumentApiSupport;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolRegistry;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.TemplateBody;
import com.coreintra.documents.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The document tools an MCP client sees.
 *
 * <p>Exercised as tools rather than over JSON-RPC: the protocol layer has its own
 * test, and what matters here is that these particular capabilities check the
 * same permission the REST path checks, that the one which queues work is
 * separated as a write tool, and that none of them can put a document's bytes
 * into a model's context.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentToolsTest {

    private static final Set<String> READ_ONLY = Immutables.setOf();
    private static final Set<String> WRITE = Immutables.setOf(McpToolRegistry.WRITE_SCOPE);

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private McpToolRegistry registry;
    @Autowired private DocumentFindTool find;
    @Autowired private DocumentFieldsTool fields;
    @Autowired private DocumentExportTool export;
    @Autowired private FontListTool fonts;
    @Autowired private TemplateListTool templates;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PermissionGrantRepository grants;
    @Autowired private DocumentService documents;
    @Autowired private TemplateService templateService;

    private String companyId;
    private String accountId;
    private PermissionPrincipal caller;
    private DocumentEntity document;

    @BeforeEach
    void seed() {
        companyId = UUID.randomUUID().toString();
        companies.save(new Company(companyId, "acme-" + companyId.substring(0, 8), "에이스전자",
                Company.CompanyKind.HEAD_OFFICE));

        accountId = UUID.randomUUID().toString();
        accounts.save(new UserAccount(accountId, "mcp-" + accountId.substring(0, 8), "MCP 클라이언트",
                UserAccount.AccountKind.SERVICE_ACCOUNT));
        grant(accountId, DocumentPermissions.DOCUMENT_READ);
        grant(accountId, DocumentPermissions.DOCUMENT_EXPORT);
        grant(accountId, DocumentPermissions.TEMPLATE_READ);
        grant(accountId, DocumentPermissions.FONT_READ);

        caller = PermissionPrincipal.serviceAccount(accountId, "MCP 클라이언트", WRITE);
        document = documents.createFromUpload(companyId, "보고서", "8월 이사회 안건",
                "# 8월 이사회\n\n매출 1,400,000\n".getBytes(com.coreintra.compat.Texts.UTF_8),
                DocumentFormat.MDV, accountId,
                BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0));
    }


    @AfterEach
    void removeWhatThisTestPutIn() {
        DocumentApiSupport.cleanUp(dataSource, companyId);
    }

    @Test
    @DisplayName("a read-scoped token is not shown the tool that queues a conversion")
    void theExportToolNeedsTheWriteScope() {
        List<String> readable = names(registry.visibleTo(READ_ONLY));
        List<String> writable = names(registry.visibleTo(WRITE));

        assertThat(readable)
                .contains("documents_find", "documents_read_fields", "documents_list_templates",
                        "documents_list_fonts")
                .doesNotContain("documents_export");
        assertThat(writable).contains("documents_export");

        assertThat(registry.find("documents_export", READ_ONLY))
                .as("hidden and refused, so a model does not burn its turns retrying something "
                        + "it can never be allowed to do")
                .isNull();
    }

    @Test
    @DisplayName("every document tool states the permission it needs and whether it writes")
    void descriptionsCarryTheTwoFacts() {
        List<McpTool> tools = registry.visibleTo(WRITE);
        for (McpTool tool : tools) {
            if (!tool.name().startsWith("documents_")) {
                continue;
            }
            assertThat(tool.describe())
                    .as("%s must say what it needs and whether it writes", tool.name())
                    .contains("Requires permission: documents.")
                    .contains("Writes:");
            assertThat(tool.requiredPermission().isWildcard()).isFalse();
        }
        assertThat(export.writes())
                .as("queueing a conversion occupies a worker even though no row changes")
                .isTrue();
        assertThat(find.writes()).isFalse();
        assertThat(fields.writes()).isFalse();
    }

    @Test
    @DisplayName("finding a document answers with metadata and never with the file")
    void findReturnsMetadataOnly() throws Exception {
        McpToolResult result = find.call(caller, arguments("{\"companyId\":\"" + companyId
                + "\",\"query\":\"이사회\"}"));

        assertThat(result.isError()).isFalse();
        JsonNode payload = json.readTree(result.textBlocks().get(0));
        assertThat(payload.path("documents").size()).isEqualTo(1);
        assertThat(payload.path("documents").get(0).path("documentId").asText())
                .isEqualTo(document.id());
        assertThat(result.textBlocks().get(0))
                .as("no bytes, in any encoding: a document in a transcript is governed by no "
                        + "permission check afterwards")
                .doesNotContain("매출");
    }

    @Test
    @DisplayName("reading fields keeps money as an exact decimal string, with its label")
    void fieldsKeepMoneyExact() throws Exception {
        DocumentEntity expense = draftLeaveRequest("1400000.25");

        McpToolResult result = fields.call(caller,
                arguments("{\"documentId\":\"" + expense.id() + "\"}"));

        assertThat(result.isError()).isFalse();
        JsonNode payload = json.readTree(result.textBlocks().get(0));
        assertThat(payload.path("documentId").asText()).isEqualTo(expense.id());

        JsonNode amount = fieldNamed(payload.path("fields"), DocumentApiSupport.AMOUNT);
        assertThat(amount.path("amount").isTextual())
                .as("a JSON number is an IEEE 754 double in the client that reads it, and a "
                        + "model that reads one back rounded has changed a figure on a document")
                .isTrue();
        assertThat(amount.path("amount").asText()).isEqualTo("1400000.25");
        assertThat(amount.path("labelKo").asText()).isEqualTo("금액");
        assertThat(amount.path("type").asText()).isEqualTo("MONEY");
    }

    @Test
    @DisplayName("exporting through a tool returns a job to poll, not a document")
    void exportReturnsAJobAndNoBytes() throws Exception {
        McpToolResult result = export.call(caller, arguments("{\"documentId\":\"" + document.id()
                + "\",\"format\":\"PDF\"}"));

        assertThat(result.isError()).isFalse();
        String body = result.textBlocks().get(0);
        JsonNode payload = json.readTree(body);
        assertThat(payload.path("status").asText()).isEqualTo("queued");
        assertThat(payload.path("jobId").asText()).isNotEmpty();
        assertThat(payload.path("pollUrl").asText())
                .isEqualTo("/api/v1/documents/" + document.id() + "/export/jobs/"
                        + payload.path("jobId").asText());
        assertThat(payload.has("content")).isFalse();
        assertThat(payload.has("bytes")).isFalse();
        assertThat(body.length())
                .as("a tool result that carried a PDF would be a document nobody can revoke")
                .isLessThan(4_000);
    }

    @Test
    @DisplayName("a tool refuses exactly as the endpoint would, through the same evaluator")
    void toolsCheckTheSamePermission() {
        String strangerId = UUID.randomUUID().toString();
        accounts.save(new UserAccount(strangerId, "stranger-" + strangerId.substring(0, 8),
                "권한 없는 계정", UserAccount.AccountKind.SERVICE_ACCOUNT));
        final PermissionPrincipal stranger =
                PermissionPrincipal.serviceAccount(strangerId, "권한 없는 계정", WRITE);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() throws Throwable {
                fields.call(stranger, arguments("{\"documentId\":\"" + document.id() + "\"}"));
            }
        })
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("documents.document");
    }

    @Test
    @DisplayName("listing templates and fonts answers for the caller's company only")
    void listingToolsAreCompanyScoped() throws Exception {
        McpToolResult templateResult = templates.call(caller,
                arguments("{\"companyId\":\"" + companyId + "\"}"));
        assertThat(templateResult.isError()).isFalse();
        assertThat(json.readTree(templateResult.textBlocks().get(0)).path("companyId").asText())
                .isEqualTo(companyId);

        McpToolResult fontResult = fonts.call(caller, arguments("{\"companyId\":\"" + companyId
                + "\",\"resolve\":[\"함초롬바탕\"]}"));
        assertThat(fontResult.isError()).isFalse();
        JsonNode payload = json.readTree(fontResult.textBlocks().get(0));
        assertThat(payload.path("resolutions").size()).isEqualTo(1);
        assertThat(payload.path("resolutions").get(0).path("requestedFamily").asText())
                .isEqualTo("함초롬바탕");
        assertThat(payload.path("resolutions").get(0).path("reason").asText())
                .as("a missing family is explained, never silently swapped")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every tool's input schema is closed, so a misspelled argument fails loudly")
    void schemasAreClosed() {
        McpTool[] tools = new McpTool[] {find, fields, export, templates, fonts};
        for (int i = 0; i < tools.length; i++) {
            assertThat(tools[i].inputSchema().path("additionalProperties").asBoolean(true))
                    .as("%s", tools[i].name())
                    .isFalse();
        }
    }

    /** A template-pinned document, so the field values are extracted and typed. */
    private DocumentEntity draftLeaveRequest(String amount) {
        String templateId = templateService.create(companyId, DocumentApiSupport.code("expense"),
                "지출결의서", "지출결의서", "Expense", accountId).id();
        templateService.publishVersion(templateId, DocumentApiSupport.leaveRequestSchema(),
                Immutables.listOf(new TemplateBody("ko", DocumentFormat.DOCX,
                        DocumentApiSupport.leaveRequestDocx(
                                DocumentApiSupport.filledValues(amount)), "expense.docx")),
                accountId, BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0));
        return documents.createFromTemplate(companyId, templateId, 1, "ko", "8월 지출결의서",
                accountId, BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 30, 0), "KRW");
    }

    private static JsonNode fieldNamed(JsonNode fields, String fieldId) {
        for (int i = 0; i < fields.size(); i++) {
            if (fieldId.equals(fields.get(i).path("fieldId").asText())) {
                return fields.get(i);
            }
        }
        throw new AssertionError("no field " + fieldId + " in " + fields);
    }

    private void grant(String accountId, PermissionKey key) {
        grants.save(new PermissionGrantRow(UUID.randomUUID().toString(), GrantSource.USER_ACCOUNT,
                accountId, key, PermissionScope.ALL, true));
    }

    private JsonNode arguments(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("bad test arguments: " + body, e);
        }
    }

    private static List<String> names(List<McpTool> tools) {
        List<String> names = new java.util.ArrayList<String>();
        for (McpTool tool : tools) {
            names.add(tool.name());
        }
        return names;
    }
}
