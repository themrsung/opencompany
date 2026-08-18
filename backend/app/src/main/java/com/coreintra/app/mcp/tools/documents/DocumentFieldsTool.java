package com.coreintra.app.mcp.tools.documents;

import com.coreintra.app.api.documents.DocumentAccess;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Read a document's typed field values.
 *
 * <h2>Why a model gets the fields and not the file</h2>
 *
 * <p>The field values are the queryable projection of the docx, and they are
 * what a question like "how much was that 지출결의서 for?" actually needs. Handing
 * a model the document instead would mean handing it a ZIP it cannot read, or
 * converting one on a request thread, and neither answers the question.
 *
 * <p>Money comes back as an exact decimal string with its currency code beside
 * it. A model that reads {@code 1400000.25} as a JSON number and writes it back
 * rounded has silently changed a figure on a document, which is the failure the
 * whole money rule exists to prevent.
 */
@Component
public class DocumentFieldsTool implements McpTool {

    private final DocumentService documents;
    private final TemplateService templates;
    private final DocumentAccess access;
    private final ObjectMapper json;

    public DocumentFieldsTool(DocumentService documents, TemplateService templates,
            DocumentAccess access, ObjectMapper json) {
        this.documents = documents;
        this.templates = templates;
        this.access = access;
        this.json = json;
    }

    @Override
    public String name() {
        return "documents_read_fields";
    }

    @Override
    public String summary() {
        return "Read the typed field values of one document version: the amounts, dates, "
                + "employee references and text that were filled into its content controls. "
                + "Defaults to the current version. Amounts are exact decimal strings with a "
                + "currency code and must be kept as strings — never parsed into a number. "
                + "Returns no document bytes.";
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
        DocumentToolSupport.text(schema, "documentId", "The document to read.");
        DocumentToolSupport.integer(schema, "versionNo",
                "Which version. Defaults to the current one.");
        DocumentToolSupport.businessDate(schema);
        DocumentToolSupport.required(schema, "documentId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = DocumentToolSupport.businessDate(arguments);
        String documentId = DocumentToolSupport.requiredText(arguments, "documentId");

        Optional<DocumentEntity> found = access.document(principal, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "MCP documents_read_fields");
        if (!found.isPresent()) {
            return McpToolResult.failure("not_found", "No document has the id " + documentId);
        }
        DocumentEntity document = found.get();

        Integer requested = DocumentToolSupport.optionalInteger(arguments, "versionNo");
        Integer versionNo = requested == null ? document.currentVersionNo() : requested;
        if (versionNo == null) {
            return McpToolResult.failure("no_version",
                    "That document has no version yet, so it has no field values.");
        }
        if (!documents.version(documentId, versionNo.intValue()).isPresent()) {
            return McpToolResult.failure("not_found",
                    "Document " + documentId + " has no version " + versionNo);
        }

        DocumentFieldSchema schema = null;
        if (document.templateId() != null && document.templateVersionNo() != null) {
            schema = templates.schemaOf(document.templateId(),
                    document.templateVersionNo().intValue());
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("documentId", documentId);
        payload.put("title", document.title());
        payload.put("versionNo", versionNo);
        payload.put("asOf", on.toString());
        ArrayNode fields = payload.putArray("fields");

        List<DocumentFieldValueEntity> values =
                documents.fieldValuesOf(documentId, versionNo.intValue());
        for (DocumentFieldValueEntity value : values) {
            append(fields, value, schema == null ? null : schema.field(value.fieldId()));
        }

        List<String> missing = documents.missingRequiredFields(documentId, versionNo.intValue());
        ArrayNode missingRequired = payload.putArray("missingRequired");
        for (String tag : missing) {
            missingRequired.add(tag);
        }
        if (!missing.isEmpty()) {
            payload.put("note", "Required fields are still empty, so this document cannot be "
                    + "submitted for 결재 as it stands.");
        }
        return McpToolResult.json(payload.toString());
    }

    private static void append(ArrayNode target, DocumentFieldValueEntity value,
            FieldDefinition definition) {
        ObjectNode node = target.addObject();
        node.put("fieldId", value.fieldId());
        node.put("type", value.fieldType() == null ? null : value.fieldType().name());
        node.put("labelKo", definition == null ? null : definition.labelKo());
        node.put("labelEn", definition == null ? null : definition.labelEn());
        node.put("text", value.valueText());
        // Strings, deliberately: see the class note on money.
        node.put("number", value.valueNumber() == null
                ? null : value.valueNumber().toPlainString());
        node.put("amount", value.valueAmount() == null
                ? null : value.valueAmount().toPlainString());
        node.put("currencyCode", value.valueCurrencyCode());
        node.put("date", value.valueDate() == null ? null : value.valueDate().toString());
        node.put("instant", value.valueInstant() == null
                ? null : value.valueInstant().toWireString());
        node.put("refId", value.valueRefId());
        node.put("attachmentSha256", value.valueBlobSha256());
    }
}
