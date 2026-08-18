package com.coreintra.app.mcp.tools.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.api.approval.support.ApiTestWorld;
import com.coreintra.app.mcp.McpToolRegistry;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.approval.service.DraftRequest;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The 결재 tools as an MCP client sees them.
 *
 * <p>Two properties matter more than the payloads. A read-scoped token must not
 * see that {@code approval_approve_step} exists, because a model that can see a
 * tool will keep trying it. And a domain refusal must come back as a tool error
 * the model can act on rather than as a protocol error that says the server is
 * broken — the difference between "I may not do that, try something else" and "I
 * should give up".
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ApiTestWorld.class)
class ApprovalMcpToolsTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    private static final Set<String> READ_ONLY = Immutables.setOf("mcp:read");
    private static final Set<String> READ_WRITE = Immutables.setOf("mcp:read", "mcp:write");

    @Autowired private McpToolRegistry registry;
    @Autowired private ObjectMapper json;
    @Autowired private ApprovalDocumentService documents;

    @Autowired private ApprovalInboxTool inboxTool;
    @Autowired private ApprovalDocumentTool documentTool;
    @Autowired private ApprovalApproveTool approveTool;
    @Autowired private ApprovalReturnTool returnTool;
    @Autowired private ApprovalSubmitTool submitTool;
    @Autowired private ApiTestWorld world;
    private PermissionPrincipal drafter;
    private PermissionPrincipal approver;

    @BeforeEach
    void seed() {
        world.reset();
        world.registerExpenseTemplate();

        drafter = PermissionPrincipal.user(
                world.drafterAccountId, "김민준", world.drafterEmployeeId);
        approver = PermissionPrincipal.user(
                world.approverAccountId, "박부장", world.approverEmployeeId);
    }

    @Test
    @DisplayName("a read-scoped token cannot see the tools that decide approvals")
    void writeToolsAreInvisibleWithoutTheScope() {
        List<String> readable = names(READ_ONLY);
        assertThat(readable)
                .contains("approval_read_inbox", "approval_read_document")
                .doesNotContain("approval_approve_step", "approval_return_to_drafter",
                        "approval_submit_document");

        assertThat(names(READ_WRITE))
                .contains("approval_approve_step", "approval_return_to_drafter",
                        "approval_submit_document");

        assertThat(registry.find("approval_approve_step", READ_ONLY))
                .as("and it is refused as though it did not exist, so the write tools cannot "
                        + "be enumerated by probing")
                .isNull();
    }

    @Test
    @DisplayName("every write tool says in its description that it writes, and which "
            + "permission it needs")
    void descriptionsStateTheTwoRequiredFacts() {
        assertThat(approveTool.describe())
                .contains("Writes: yes")
                .contains("Requires permission: approval.document:read");
        assertThat(inboxTool.describe()).contains("Writes: no");

        assertThat(approveTool.summary())
                .as("a model must not be able to read this as 'acknowledge'")
                .contains("CANNOT BE UNDONE")
                .contains("This is a real approval and not an acknowledgement");
    }

    @Test
    @DisplayName("the inbox tool answers with the same payload the REST inbox returns")
    void inboxToolMatchesTheRestShape() throws Exception {
        String documentId = submittedDocument("8월 출장비", "120000");

        JsonNode payload = call(inboxTool.call(approver,
                arguments("companyId", world.companyId)));

        assertThat(payload.path("accountId").asText()).isEqualTo(world.approverAccountId);
        assertThat(payload.path("counts").path("awaiting").asInt()).isEqualTo(1);
        assertThat(payload.path("awaitingMe").get(0).path("id").asText())
                .isEqualTo(documentId);
        assertThat(payload.path("awaitingMe").get(0).path("amount").isTextual())
                .as("amounts stay exact decimal strings on this door too")
                .isTrue();
    }

    @Test
    @DisplayName("the document tool returns the trail with business instants intact")
    void documentToolKeepsBusinessTime() throws Exception {
        String documentId = submittedDocument("야근 식대", "30000");
        String stepId = pendingStepId(documentId);

        approveTool.call(approver, arguments(
                "documentId", documentId,
                "stepId", stepId,
                "actedAt", "2026-08-30T27:00:00.000"));

        JsonNode detail = call(documentTool.call(drafter,
                arguments("documentId", documentId)));
        assertThat(detail.path("trail").get(0).path("actedAt").asText())
                .isEqualTo("2026-08-30T27:00:00.000");
        assertThat(detail.path("document").path("state").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("returning with no reason is a tool error naming what to write, not a "
            + "protocol failure")
    void returnWithoutReasonIsARefusal() throws Exception {
        String documentId = submittedDocument("사유 없음", "10000");
        String stepId = pendingStepId(documentId);

        McpToolResult result = returnTool.call(approver, arguments(
                "documentId", documentId,
                "stepId", stepId,
                "actedAt", "2026-08-30T14:00:00.000",
                "reason", "   "));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo("reason_required");
        assertThat(result.textBlocks().get(0))
                .contains("사유")
                .contains("what the drafter should change");

        assertThat(documents.read(approver, documentId).state().name())
                .as("nothing was written")
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("a domain refusal — 회수 after the first approval — comes back as a tool "
            + "error rather than propagating as a server fault")
    void domainRefusalsAreToolErrors() throws Exception {
        String documentId = submittedDocument("이미 결재됨", "10000");
        approveTool.call(approver, arguments(
                "documentId", documentId,
                "stepId", pendingStepId(documentId),
                "actedAt", "2026-08-30T15:00:00.000"));

        // Submitting an already-submitted document is the reachable equivalent:
        // a state conflict, not a bug.
        McpToolResult result = submitTool.call(drafter, arguments(
                "documentId", documentId,
                "submittedAt", "2026-08-30T16:00:00.000"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo("document_state_conflict");
        assertThat(result.textBlocks().get(0)).contains("이미 상신된 문서입니다");
    }

    @Test
    @DisplayName("a schema is closed, so a misspelled argument fails rather than being "
            + "silently ignored")
    void schemasAreClosed() {
        assertThat(approveTool.inputSchema().path("additionalProperties").asBoolean()).isFalse();
        assertThat(names(approveTool.inputSchema().path("required")))
                .containsExactly("documentId", "stepId", "actedAt");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String submittedDocument(String title, String amount) {
        ApprovalDocumentEntity drafted = documents.draft(drafter, new DraftRequest(
                world.companyId, ApiTestWorld.DOCUMENT_TYPE, title, new BigDecimal(amount),
                "KRW", LocalDate.of(2026, 8, 30)));
        documents.submit(drafter, drafted.id(),
                BusinessInstant.of(LocalDate.of(2026, 8, 30), 9 * 3600), null);
        return drafted.id();
    }

    private String pendingStepId(String documentId) {
        ApprovalDocumentView view = documents.read(approver, documentId);
        for (com.coreintra.approval.domain.ApprovalStep step : view.line().pendingSteps()) {
            if (step.mayAct(world.approverAccountId)) {
                return step.id();
            }
        }
        throw new AssertionError("nothing pending on 박부장");
    }

    private ObjectNode arguments(String... pairs) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }

    private JsonNode call(McpToolResult result) throws Exception {
        assertThat(result.isError())
                .as("tool failed: %s", result.textBlocks())
                .isFalse();
        return json.readTree(result.textBlocks().get(0));
    }

    private List<String> names(Set<String> scopes) {
        List<String> found = new ArrayList<String>();
        for (com.coreintra.app.mcp.McpTool tool : registry.visibleTo(scopes)) {
            found.add(tool.name());
        }
        return found;
    }

    private static List<String> names(JsonNode array) {
        List<String> found = new ArrayList<String>();
        for (JsonNode node : array) {
            found.add(node.asText());
        }
        return found;
    }
}
