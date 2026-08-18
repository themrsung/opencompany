package com.coreintra.app.mcp.tools.documents;

import com.coreintra.app.api.documents.DocumentAccess;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Find a document, by id or by title.
 *
 * <p>The search half is not a convenience. A model is given "지난달 지출결의서",
 * never a UUID, and without a way to turn one into the other every other document
 * tool is unreachable — it would have to guess ids, which it will happily do.
 * Candidates come from {@code DocumentAccess.list}, so every one is filtered by
 * the evaluator before the match runs: a caller who cannot read another company's
 * documents does not learn that one of them matches.
 *
 * <p>No bytes. This answers with metadata, and the download route is a REST
 * endpoint with its own {@code documents.document:export} check.
 */
@Component
public class DocumentFindTool implements McpTool {

    private final DocumentAccess access;
    private final ObjectMapper json;

    public DocumentFindTool(DocumentAccess access, ObjectMapper json) {
        this.access = access;
        this.json = json;
    }

    @Override
    public String name() {
        return "documents_find";
    }

    @Override
    public String summary() {
        return "Find documents in a company. Give documentId for one exact record, or companyId "
                + "with query to search by title, optionally narrowed by documentType. Returns "
                + "metadata only — never the file — and only the documents the calling account "
                + "may read, so a title that is absent may exist and be out of reach rather than "
                + "not exist.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return DocumentPermissions.DOCUMENT_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = DocumentToolSupport.schema(json);
        DocumentToolSupport.text(schema, "documentId", "The exact document to read. Omit to search.");
        DocumentToolSupport.text(schema, "companyId", "Which company to search. Required with query.");
        DocumentToolSupport.text(schema, "query", "Part of a title, case-insensitive.");
        DocumentToolSupport.text(schema, "documentType",
                "Narrow to one type, e.g. 지출결의서. Optional.");
        DocumentToolSupport.businessDate(schema);
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = DocumentToolSupport.businessDate(arguments);
        String documentId = DocumentToolSupport.optionalText(arguments, "documentId");

        ObjectNode payload = json.createObjectNode();
        payload.put("asOf", on.toString());
        ArrayNode found = payload.putArray("documents");

        if (documentId != null) {
            Optional<DocumentEntity> document = access.document(principal, documentId,
                    DocumentPermissions.DOCUMENT_READ, on, "MCP documents_find");
            if (!document.isPresent()) {
                return McpToolResult.failure("not_found", "No document has the id " + documentId);
            }
            append(found, document.get());
            return McpToolResult.json(payload.toString());
        }

        String companyId = DocumentToolSupport.requiredText(arguments, "companyId");
        String query = DocumentToolSupport.requiredText(arguments, "query")
                .toLowerCase(Locale.ROOT);
        String documentType = DocumentToolSupport.optionalText(arguments, "documentType");

        List<DocumentEntity> candidates = access.list(principal, companyId, documentType, on);
        for (DocumentEntity candidate : candidates) {
            if (candidate.title() != null
                    && candidate.title().toLowerCase(Locale.ROOT).contains(query)) {
                append(found, candidate);
            }
        }
        if (found.size() == 0) {
            payload.put("note", "Nothing matched. The query may be wrong, or the documents it "
                    + "would have matched may be outside what this account may read.");
        }
        return McpToolResult.json(payload.toString());
    }

    private static void append(ArrayNode target, DocumentEntity document) {
        ObjectNode node = target.addObject();
        node.put("documentId", document.id());
        node.put("companyId", document.companyId());
        node.put("documentType", document.documentType());
        node.put("title", document.title());
        node.put("currentVersionNo", document.currentVersionNo());
        node.put("templateId", document.templateId());
        node.put("templateVersionNo", document.templateVersionNo());
        node.put("retired", document.isRetired());
    }
}
