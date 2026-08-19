package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApprovalDocumentDetail;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.service.ApprovalActionService;
import com.coreintra.approval.service.ApprovalPermissions;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * 반려 — send a document back to whoever drafted it.
 *
 * <p>The mandatory reason is the whole design. A document returned with no
 * explanation is re-submitted unchanged and the loop repeats, which is how an
 * approval queue becomes a war of attrition. The tool refuses a blank reason
 * before the service is called, and says what a good one contains, because a
 * model given the chance to omit it will.
 */
@Component
public class ApprovalReturnTool implements McpTool {

    private final ApprovalActionService actions;
    private final ObjectMapper json;

    public ApprovalReturnTool(ApprovalActionService actions, ObjectMapper json) {
        this.actions = actions;
        this.json = json;
    }

    @Override
    public String name() {
        return "approval_return_to_drafter";
    }

    @Override
    public String summary() {
        return "RETURNS (반려) an approval document to its drafter, rejecting it at this "
                + "step. The document stops moving forward, the drafter has to correct and "
                + "re-submit it, and the rejection is written to an immutable trail that "
                + "cannot be removed. This is a refusal, not a request for information.\n\n"
                + "A reason is mandatory and a blank one is refused. Write what is wrong and "
                + "what the drafter must change — the reason is the only thing they get, and "
                + "a document returned without one comes back unchanged.\n\n"
                + "Call approval_read_document first and act only on a step whose state is "
                + "PENDING and whose approvers include the calling account. What authorises "
                + "this is not a permission grant: the approval line was resolved to specific "
                + "people at submission and frozen, and being one of that step's approvers is "
                + "the authorisation.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return ApprovalPermissions.DOCUMENT_READ;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ApprovalToolSupport.schema(json);
        ApprovalToolSupport.text(schema, "documentId",
                "The document to send back. Read it first with approval_read_document.");
        ApprovalToolSupport.text(schema, "stepId",
                "The PENDING step the calling account is an approver of.");
        ApprovalToolSupport.instant(schema, "actedAt", "When the document is returned");
        ApprovalToolSupport.text(schema, "reason",
                "Mandatory. What is wrong and what to change, in the language the drafter "
                        + "works in. Not a restatement of the fact that it was returned.");
        ApprovalToolSupport.required(schema, "documentId", "stepId", "actedAt", "reason");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String documentId = ApprovalToolSupport.requiredText(arguments, "documentId");
                String stepId = ApprovalToolSupport.requiredText(arguments, "stepId");
                BusinessInstant actedAt =
                        ApprovalToolSupport.requiredInstant(arguments, "actedAt");
                String reason = ApprovalToolSupport.optionalText(arguments, "reason");
                if (Texts.isBlank(reason)) {
                    // Refused here as well as in the domain, so that the model is
                    // told what to write rather than only that something was
                    // missing.
                    return McpToolResult.failure("reason_required",
                            "반려에는 사유가 반드시 필요합니다. (A 반려 needs a reason. Say what is "
                                    + "wrong with the document and what the drafter should "
                                    + "change; without it they will re-submit it unchanged "
                                    + "and the same approver will have to read it again.)");
                }

                ApprovalDocumentDetail after = ApprovalDocumentDetail.from(
                        actions.returnToDrafter(principal, documentId, stepId, actedAt, reason),
                        principal.accountId());
                return McpToolResult.json(render(after));
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
