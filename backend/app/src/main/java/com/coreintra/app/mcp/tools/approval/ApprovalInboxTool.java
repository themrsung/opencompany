package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.api.approval.ApprovalInboxResponse;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.service.ApprovalInboxService;
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
 * Read one person's 결재함.
 *
 * <p>Answers with exactly the payload {@code GET /api/v1/approvals/inbox}
 * returns — the same DTO, serialised by the same mapper. That is not laziness:
 * ADR 0009 says REST and MCP are two doors onto one surface, and the cheapest
 * way for them to drift is for each to build its own shape of the same answer.
 */
@Component
public class ApprovalInboxTool implements McpTool {

    private final ApprovalInboxService inbox;
    private final ObjectMapper json;

    public ApprovalInboxTool(ApprovalInboxService inbox, ObjectMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    @Override
    public String name() {
        return "approval_read_inbox";
    }

    @Override
    public String summary() {
        return "Read a person's 결재함 (approval inbox): the documents waiting on them, the "
                + "documents they drafted, the documents they are copied on, and the counts. "
                + "Reads only — nothing is approved, submitted or changed by calling this. "
                + "Defaults to the calling account; give accountId to read somebody else's, "
                + "which is a sensitive read and is permission checked. A document that is "
                + "absent may exist and be out of reach rather than not exist.";
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
        ApprovalToolSupport.text(schema, "companyId",
                "Which company's inbox. Required: a grant scoped to a company or a unit needs "
                        + "something to reach when the inbox is not your own.");
        ApprovalToolSupport.text(schema, "accountId",
                "Whose inbox. Defaults to the calling account.");
        ApprovalToolSupport.required(schema, "companyId");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String companyId = ApprovalToolSupport.requiredText(arguments, "companyId");
                String accountId = ApprovalToolSupport.optionalText(arguments, "accountId");
                String subject = accountId == null ? principal.accountId() : accountId;

                ApprovalInboxResponse response = ApprovalInboxResponse.from(
                        inbox.load(principal, companyId, subject, ApiWire.DEFAULT_PAGE_SIZE),
                        ApiWire.DEFAULT_PAGE_SIZE);
                return McpToolResult.json(render(response));
            }
        });
    }

    private String render(ApprovalInboxResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render the inbox", e);
        }
    }
}
