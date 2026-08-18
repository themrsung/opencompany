package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApprovalDocumentDetail;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.service.ApprovalDocumentService;
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
 * 상신 — put a draft into its approval line.
 *
 * <p>The moment that matters here is the snapshot. Submission resolves every
 * role expression on the line to actual people and freezes them onto the
 * document, so the set of people who may sign it is decided now and does not
 * follow a later reorganisation. It also makes the document immutable: after
 * this the drafter cannot edit it, and can only recall it while no one has
 * approved.
 *
 * <p>That is why the summary is explicit that this is not "save". A model that
 * submits a half-written 지출결의서 has sent it to somebody's inbox with the
 * drafter's name on it.
 */
@Component
public class ApprovalSubmitTool implements McpTool {

    private final ApprovalDocumentService documents;
    private final ObjectMapper json;

    public ApprovalSubmitTool(ApprovalDocumentService documents, ObjectMapper json) {
        this.documents = documents;
        this.json = json;
    }

    @Override
    public String name() {
        return "approval_submit_document";
    }

    @Override
    public String summary() {
        return "SUBMITS (상신) a draft approval document into its approval line, sending it "
                + "to the first approver's inbox under the drafter's name. This is not a "
                + "save: after it the document is IMMUTABLE and cannot be edited, and the "
                + "drafter can only withdraw it (회수) while nobody has approved yet.\n\n"
                + "Submission resolves the line's role expressions — 'the 부장 of the "
                + "drafter's unit', 'the 대표' — to specific people as the organisation stands "
                + "on the document's business date, and freezes them onto the document. Who "
                + "may sign it is settled at this moment and a later reorganisation will not "
                + "change it. If a required step resolves to nobody the submission is refused "
                + "rather than quietly skipping a signature.\n\n"
                + "Only submit a document that is finished and that the drafter intends to "
                + "send. Read it with approval_read_document first and check that its state "
                + "is DRAFTING and its title and amount are what were intended.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return ApprovalPermissions.DOCUMENT_WRITE;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ApprovalToolSupport.schema(json);
        ApprovalToolSupport.text(schema, "documentId",
                "The draft to submit. Its state must be DRAFTING.");
        ApprovalToolSupport.instant(schema, "submittedAt", "When the document is submitted");
        ApprovalToolSupport.text(schema, "bodyDigest",
                "Optional digest of the document body from the documents module, folded into "
                        + "the snapshot hash every later signature is taken against. Omit for "
                        + "a document that is only header fields; do not invent a value.");
        ApprovalToolSupport.required(schema, "documentId", "submittedAt");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String documentId = ApprovalToolSupport.requiredText(arguments, "documentId");
                BusinessInstant submittedAt =
                        ApprovalToolSupport.requiredInstant(arguments, "submittedAt");
                String bodyDigest = ApprovalToolSupport.optionalText(arguments, "bodyDigest");

                ApprovalDocumentDetail after = ApprovalDocumentDetail.from(
                        documents.submit(principal, documentId, submittedAt, bodyDigest),
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
