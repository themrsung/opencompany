package com.coreintra.app.mcp.tools.documents;

import com.coreintra.app.api.documents.DocumentAccess;
import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * List the templates a company can draft from, and what fields they take.
 *
 * <p>The field manifest is included because it is the half a model needs to be
 * useful: "which template do I use for a 휴가신청서 and what does it want filled
 * in?" is one question, and answering it in two round trips means the second one
 * often does not happen.
 */
@Component
public class TemplateListTool implements McpTool {

    private final TemplateService templates;
    private final DocumentAccess access;
    private final ObjectMapper json;

    public TemplateListTool(TemplateService templates, DocumentAccess access, ObjectMapper json) {
        this.templates = templates;
        this.access = access;
        this.json = json;
    }

    @Override
    public String name() {
        return "documents_list_templates";
    }

    @Override
    public String summary() {
        return "List a company's document templates with their codes, names and current "
                + "published version. Set includeFields to also return each template's field "
                + "manifest — the typed fields a document drafted from it will ask for. A "
                + "document is pinned to the template version it was created from and does not "
                + "follow later ones.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return DocumentPermissions.TEMPLATE_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = DocumentToolSupport.schema(json);
        DocumentToolSupport.text(schema, "companyId", "Whose templates to list.");
        DocumentToolSupport.text(schema, "documentType", "Narrow to one type. Optional.");
        ObjectNode includeFields = ((ObjectNode) schema.get("properties"))
                .putObject("includeFields");
        includeFields.put("type", "boolean");
        includeFields.put("description",
                "Include each template's field manifest. Defaults to false.");
        DocumentToolSupport.businessDate(schema);
        DocumentToolSupport.required(schema, "companyId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = DocumentToolSupport.businessDate(arguments);
        String companyId = DocumentToolSupport.requiredText(arguments, "companyId");
        String documentType = DocumentToolSupport.optionalText(arguments, "documentType");
        boolean includeFields = arguments != null
                && arguments.path("includeFields").asBoolean(false);

        access.company(principal, companyId, DocumentPermissions.TEMPLATE_READ, on,
                "MCP documents_list_templates");

        List<DocumentTemplateEntity> rows = Texts.isBlank(documentType)
                ? templates.list(companyId)
                : templates.listFor(companyId, documentType);

        ObjectNode payload = json.createObjectNode();
        payload.put("companyId", companyId);
        payload.put("asOf", on.toString());
        ArrayNode out = payload.putArray("templates");
        for (DocumentTemplateEntity template : rows) {
            ObjectNode node = out.addObject();
            node.put("templateId", template.id());
            node.put("code", template.code());
            node.put("documentType", template.documentType());
            node.put("nameKo", template.nameKo());
            node.put("nameEn", template.nameEn());
            node.put("currentVersionNo", template.currentVersionNo());
            node.put("builtIn", template.isBuiltIn());
            node.put("active", template.isActive());
            if (includeFields && template.currentVersionNo() != null) {
                appendFields(node, template);
            }
        }
        return McpToolResult.json(payload.toString());
    }

    private void appendFields(ObjectNode node, DocumentTemplateEntity template) {
        DocumentFieldSchema schema = templates.schemaOf(template.id(),
                template.currentVersionNo().intValue());
        ArrayNode fields = node.putArray("fields");
        for (FieldDefinition field : schema.fields()) {
            ObjectNode entry = fields.addObject();
            entry.put("tag", field.tag());
            entry.put("type", field.type().name());
            entry.put("labelKo", field.labelKo());
            entry.put("labelEn", field.labelEn());
            entry.put("required", field.isRequired());
        }
    }
}
