package com.coreintra.app.mcp.tools.documents;

import com.coreintra.app.api.documents.DocumentAccess;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.api.documents.ExportFormats;
import com.coreintra.app.api.documents.ExportRequests;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.service.ConversionJobService;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.RenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ask for an export, and get back a job to poll.
 *
 * <h2>Why this is a write tool</h2>
 *
 * <p>It queues work. A conversion occupies a LibreOffice worker for seconds and
 * a few hundred megabytes, and a read-scoped assistant that could start one
 * could exhaust the pool without ever changing a row. §10's rule is that write
 * tools need explicit opt-in on the token, and "starts a job" is a write by that
 * standard even though no document changes — which is why {@link #writes()} is
 * true and a read-scoped token cannot see this tool at all.
 *
 * <p>Where nothing needs converting — a DOCX exported as DOCX, or an export the
 * archive already holds — it answers immediately and queues nothing. It is still
 * a write tool: whether a call writes must not depend on the argument, or the
 * scope check becomes a coin toss.
 *
 * <h2>It never returns bytes</h2>
 *
 * <p>The answer is a URL and a job id. A model has no use for a PDF as base64,
 * and putting one in a tool result would put a document into a transcript that
 * no permission check governs afterwards. The download route applies
 * {@code documents.document:export} to whoever follows the link, which is the
 * same check this tool made.
 */
@Component
public class DocumentExportTool implements McpTool {

    private final DocumentService documents;
    private final RenderService renders;
    private final ConversionJobService jobs;
    private final DocumentAccess access;
    private final ObjectMapper json;

    public DocumentExportTool(DocumentService documents, RenderService renders,
            ConversionJobService jobs, DocumentAccess access, ObjectMapper json) {
        this.documents = documents;
        this.renders = renders;
        this.jobs = jobs;
        this.access = access;
        this.json = json;
    }

    @Override
    public String name() {
        return "documents_export";
    }

    @Override
    public String summary() {
        return "Ask for a document to be exported to PDF, DOCX, DOC, HWP, HWPX, HTML or MDV. "
                + "Queues a conversion job unless the bytes already exist, and answers with a "
                + "status, a job id to poll and a download URL — never the file itself. DOC is "
                + "legacy and lossy and the answer says so. Polling is a GET on the returned "
                + "pollUrl.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return DocumentPermissions.DOCUMENT_EXPORT;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = DocumentToolSupport.schema(json);
        DocumentToolSupport.text(schema, "documentId", "The document to export.");
        DocumentToolSupport.text(schema, "format",
                "PDF, DOCX, DOC, HWP, HWPX, HTML or MDV.");
        DocumentToolSupport.integer(schema, "versionNo",
                "Which version. Defaults to the current one.");
        DocumentToolSupport.text(schema, "locale",
                "Which language body to render. Defaults to ko.");
        DocumentToolSupport.businessDate(schema);
        DocumentToolSupport.required(schema, "documentId", "format");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = DocumentToolSupport.businessDate(arguments);
        String documentId = DocumentToolSupport.requiredText(arguments, "documentId");
        RenderFormat target = ExportFormats.renderFormat(
                DocumentToolSupport.requiredText(arguments, "format"));
        String locale = DocumentToolSupport.optionalText(arguments, "locale");

        Optional<DocumentEntity> found = access.document(principal, documentId,
                DocumentPermissions.DOCUMENT_EXPORT, on, "MCP documents_export");
        if (!found.isPresent()) {
            return McpToolResult.failure("not_found", "No document has the id " + documentId);
        }
        DocumentEntity document = found.get();

        Integer requested = DocumentToolSupport.optionalInteger(arguments, "versionNo");
        Integer versionNo = requested == null ? document.currentVersionNo() : requested;
        if (versionNo == null) {
            return McpToolResult.failure("no_version",
                    "That document has no version yet, so there is nothing to export.");
        }
        Optional<DocumentVersionEntity> versionRow =
                documents.version(documentId, versionNo.intValue());
        if (!versionRow.isPresent()) {
            return McpToolResult.failure("not_found",
                    "Document " + documentId + " has no version " + versionNo);
        }
        DocumentVersionEntity version = versionRow.get();

        ObjectNode payload = json.createObjectNode();
        payload.put("documentId", documentId);
        payload.put("versionNo", versionNo);
        payload.put("format", target.name());
        payload.put("legacy", ExportFormats.isLegacy(target));
        if (ExportFormats.isLegacy(target)) {
            payload.put("legacyNote", ExportFormats.legacyNoteEn(target));
        }

        if (ExportFormats.isNativeDownload(version.format(), target)) {
            payload.put("status", "ready");
            payload.put("mode", "immediate");
            payload.put("contentUrl",
                    ExportRequests.nativeContentUrl(documentId, versionNo.intValue()));
            payload.put("note", "No conversion was needed: the stored bytes already are this "
                    + "format.");
            return McpToolResult.json(payload.toString());
        }

        DocumentRenderEntity archived = archivedRender(documentId, versionNo.intValue(), target);
        if (archived != null) {
            payload.put("status", "ready");
            payload.put("mode", "immediate");
            payload.put("renderId", archived.id());
            payload.put("rendererVersion", archived.rendererVersion());
            payload.put("contentUrl", ExportRequests.exportContentUrl(documentId,
                    versionNo.intValue(), target));
            payload.put("note", "Served from the archive, which is authoritative: a re-render "
                    + "could only differ from what was approved.");
            return McpToolResult.json(payload.toString());
        }

        ConversionJobEntity job = jobs.enqueue(document.companyId(), documentId,
                versionNo.intValue(), version.blobSha256(), version.format(), target,
                ExportRequests.fingerprint(document, version, target, locale),
                principal.accountId());

        payload.put("status", jobStatus(job));
        payload.put("mode", "asynchronous");
        payload.put("jobId", job.id());
        payload.put("jobState", job.state().name());
        payload.put("attempt", job.attemptCount());
        payload.put("maxAttempts", job.maxAttempts());
        payload.put("pollUrl", ExportRequests.pollUrl(documentId, job.id()));
        if (job.errorCode() != null) {
            payload.put("lastErrorCode", job.errorCode());
            payload.put("lastErrorDetail", job.errorDetail());
        }
        if (job.state() == ConversionJobEntity.State.ABANDONED) {
            payload.put("note", "This conversion has already used all its attempts and stopped. "
                    + "Nothing partial was written, so there is no file. The conversion worker "
                    + "needs to be checked and the job requeued by an operator.");
        }
        return McpToolResult.json(payload.toString());
    }

    private static String jobStatus(ConversionJobEntity job) {
        return job.state() == ConversionJobEntity.State.ABANDONED ? "failed" : "queued";
    }

    private DocumentRenderEntity archivedRender(String documentId, int versionNo,
            RenderFormat target) {
        DocumentRenderEntity earliest = null;
        List<DocumentRenderEntity> archived = renders.rendersOf(documentId, versionNo);
        for (DocumentRenderEntity render : archived) {
            if (render.format() != target) {
                continue;
            }
            // Earliest wins: after a renderer upgrade the newest archive is
            // precisely the one nobody approved.
            if (earliest == null || (render.renderedAt() != null && earliest.renderedAt() != null
                    && render.renderedAt().isBefore(earliest.renderedAt()))) {
                earliest = render;
            }
        }
        return earliest;
    }
}
