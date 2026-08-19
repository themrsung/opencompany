package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApprovalDocumentDetail;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.service.ApprovalActionService;
import com.coreintra.approval.service.ApprovalPermissions;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * 승인 — sign an approval step as the calling account.
 *
 * <h2>The most consequential thing in this system a model can reach</h2>
 *
 * <p>Everything else a tool can do here is either a read or a draft. This one
 * puts a person's name on a decision: it spends money, grants leave, and in the
 * 대표 case can be one half of the quorum that changes a company's 취업규칙. It
 * is appended to an immutable trail against the digest of the document as it
 * stands, and there is no undo — a signature given in error is corrected by
 * somebody else's 반려, which stays on the record too.
 *
 * <p>So the summary is written to be impossible to misread as "acknowledge" or
 * "mark as seen", and it tells the model to read the document first. A model
 * that approves the wrong 지출결의서 because the description sounded procedural
 * is a failure of this string, not of the model.
 *
 * <h2>What actually authorises it</h2>
 *
 * <p>Not a grant. The 결재선 was resolved to people at submission and
 * snapshotted, and being in that snapshot for this step is the authorisation
 * (see {@code ApprovalPermissions}). {@link #requiredPermission()} has to name
 * one concrete key, and names the read permission, which is the least a caller
 * needs and which the domain checks for anybody who is not on the line. The
 * summary says plainly that the grant is not the gate, because a model that
 * believed otherwise would reason wrongly about who can sign what.
 */
@Component
public class ApprovalApproveTool implements McpTool {

    private final ApprovalActionService actions;
    private final ObjectMapper json;

    public ApprovalApproveTool(ApprovalActionService actions, ObjectMapper json) {
        this.actions = actions;
        this.json = json;
    }

    @Override
    public String name() {
        return "approval_approve_step";
    }

    @Override
    public String summary() {
        return "APPROVES (승인) an approval step, signing the document as the calling "
                + "account. This is a real approval and not an acknowledgement: it advances "
                + "the document towards being enacted, and an approved document releases "
                + "money, grants leave, or changes company rules according to what it is. "
                + "It is written to an immutable trail against the document's current digest "
                + "and CANNOT BE UNDONE — the only remedy afterwards is for another approver "
                + "to 반려 (return) it, which also stays on the record. Once any step has "
                + "been approved the drafter can no longer recall the document.\n\n"
                + "Call approval_read_document first and act only on a step whose state is "
                + "PENDING and whose approvers include the calling account. Do not approve on "
                + "somebody's behalf: acting for an absent approver is a different action "
                + "(대결) with its own record, and this tool will attribute the signature to "
                + "the caller.\n\n"
                + "What authorises this is not a permission grant. The approval line was "
                + "resolved to specific people when the document was submitted and frozen "
                + "there; being one of that step's approvers is the authorisation, and no "
                + "grant can add the caller to a step they were not routed to.\n\n"
                + "Under 공동대표 (joint representation) one signature does not finish the "
                + "document: it becomes PARTIALLY_APPROVED until the quorum of distinct "
                + "representatives is met, and calling this again as the same account will "
                + "not complete it.";
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
                "The document to sign. Read it first with approval_read_document.");
        ApprovalToolSupport.text(schema, "stepId",
                "The step to sign, from the document's steps. Must be the PENDING step that "
                        + "lists the calling account among its approvers.");
        ApprovalToolSupport.instant(schema, "actedAt", "When the approval is given");
        ApprovalToolSupport.text(schema, "comment",
                "Optional note, recorded on the trail exactly as written.");
        ApprovalToolSupport.required(schema, "documentId", "stepId", "actedAt");
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
                String comment = ApprovalToolSupport.optionalText(arguments, "comment");

                ApprovalDocumentDetail after = ApprovalDocumentDetail.from(
                        actions.approve(principal, documentId, stepId, actedAt, comment),
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
