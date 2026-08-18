package com.coreintra.app.mcp.tools.documents;

import com.coreintra.app.api.documents.DocumentPermissions;
import com.coreintra.app.api.fonts.FontAccess;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.service.FontStoreService;
import com.coreintra.documents.service.InstalledFont;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Iterator;
import org.springframework.stereotype.Component;

/**
 * List the fonts an installation actually has.
 *
 * <p>Worth a tool of its own because the commonest support question about an
 * export is "why does this PDF look wrong?", and the answer is usually a font
 * that is not installed. This reports what is there, what each face covers, and
 * — when families are named — what they would resolve to, so the diagnosis does
 * not require guessing.
 *
 * <p>Metadata only. The bytes are served to the browser by a content-addressed
 * route with its own check; a model has no use for a TTF.
 */
@Component
public class FontListTool implements McpTool {

    private final FontStoreService fonts;
    private final FontAccess access;
    private final ObjectMapper json;

    public FontListTool(FontStoreService fonts, FontAccess access, ObjectMapper json) {
        this.fonts = fonts;
        this.access = access;
        this.json = json;
    }

    @Override
    public String name() {
        return "documents_list_fonts";
    }

    @Override
    public String summary() {
        return "List the fonts installed for a company: family, style, script coverage, source, "
                + "who uploaded each one and whether it is enabled. Name families in resolve[] to "
                + "also see what each would resolve to — a substituted or unresolved family is "
                + "why an export looks wrong. Returns no font files.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return DocumentPermissions.FONT_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = DocumentToolSupport.schema(json);
        DocumentToolSupport.text(schema, "companyId", "Whose font store to list.");
        ObjectNode resolve = ((ObjectNode) schema.get("properties")).putObject("resolve");
        resolve.put("type", "array");
        resolve.putObject("items").put("type", "string");
        resolve.put("description", "Font families to resolve against the store. Optional.");
        DocumentToolSupport.text(schema, "script",
                "ISO 15924 script code the text is in, e.g. Hang or Latn. Optional.");
        DocumentToolSupport.businessDate(schema);
        DocumentToolSupport.required(schema, "companyId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = DocumentToolSupport.businessDate(arguments);
        String companyId = DocumentToolSupport.requiredText(arguments, "companyId");
        access.company(principal, companyId, DocumentPermissions.FONT_READ, on,
                "MCP documents_list_fonts");

        ObjectNode payload = json.createObjectNode();
        payload.put("companyId", companyId);
        ArrayNode installed = payload.putArray("fonts");
        for (InstalledFont font : fonts.installedFor(companyId)) {
            FontRecord record = font.record();
            ObjectNode node = installed.addObject();
            node.put("fontId", font.id());
            node.put("family", font.family());
            node.put("style", font.style());
            node.put("fileFormat", record.fileFormat());
            node.put("source", String.valueOf(record.source()));
            node.put("embeddingPermission", String.valueOf(record.embeddingPermission()));
            node.put("uploadedByAccountId", record.uploadedByAccountId());
            node.put("enabled", font.isEnabled());
            ArrayNode scripts = node.putArray("scriptCoverage");
            for (Iterator<String> it = record.scriptCoverage().iterator(); it.hasNext();) {
                scripts.add(it.next());
            }
        }

        JsonNode wanted = arguments == null ? null : arguments.get("resolve");
        if (wanted != null && wanted.isArray() && wanted.size() > 0) {
            String script = DocumentToolSupport.optionalText(arguments, "script");
            com.coreintra.documents.font.FontResolver resolver = fonts.resolverFor(companyId);
            ArrayNode resolutions = payload.putArray("resolutions");
            for (int i = 0; i < wanted.size(); i++) {
                com.coreintra.documents.font.FontResolver.Resolution resolution =
                        resolver.resolve(wanted.get(i).asText(), script);
                ObjectNode node = resolutions.addObject();
                node.put("requestedFamily", resolution.requestedFamily());
                node.put("resolvedFamily", resolution.resolvedFamily());
                node.put("substituted", resolution.isSubstituted());
                node.put("unresolved", resolution.isUnresolved());
                node.put("reason", resolution.reason());
            }
        }
        return McpToolResult.json(payload.toString());
    }
}
