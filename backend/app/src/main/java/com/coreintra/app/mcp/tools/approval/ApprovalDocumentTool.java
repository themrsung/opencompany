package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApprovalDocumentDetail;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalPermissions;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * Read one approval document: its header, its resolved 결재선, and its whole
 * trail.
 *
 * <p>The trail is the point. A model asked "why has this not been approved?"
 * needs to see that 박부장 held it on the 12th with a reason, or that the
 * document is at a 공동대표 step with one of two signatures — not a state name it
 * has to guess at. Everything the 결재 screen shows is here, in one call.
 */
@Component
public class ApprovalDocumentTool implements McpTool {

    private final ApprovalDocumentService documents;
    private final ObjectMapper json;

    public ApprovalDocumentTool(ApprovalDocumentService documents, ObjectMapper json) {
        this.documents = documents;
        this.json = json;
    }

    @Override
    public String name() {
        return "approval_read_document";
    }

    @Override
    public String summary() {
        return "Read one 결재 document with its resolved approval line and its full immutable "
                + "trail: every action, who took it, when in business time, and why. Reads "
                + "only — nothing is approved or changed by calling this. Use it before any "
                + "write tool, because it is what says which step is pending, which step id "
                + "to act on, and whether the calling account is one of the people the "
                + "document was routed to. Times come back as business instants "
                + "(YYYY-MM-DDT[-]HH:MM:SS.mmm, no timezone) and amounts as exact decimal "
                + "strings; do not parse either into a number or a UTC timestamp.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return ApprovalPermissions.DOCUMENT_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ApprovalToolSupport.schema(json);
        ApprovalToolSupport.text(schema, "documentId",
                "The document to read. Get it from approval_read_inbox.");
        ApprovalToolSupport.required(schema, "documentId");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String documentId = ApprovalToolSupport.requiredText(arguments, "documentId");
                ApprovalDocumentDetail detail = ApprovalDocumentDetail.from(
                        documents.read(principal, documentId), principal.accountId());
                return McpToolResult.json(render(detail));
            }
        });
    }

    private String render(ApprovalDocumentDetail detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render the document", e);
        }
    }
}
