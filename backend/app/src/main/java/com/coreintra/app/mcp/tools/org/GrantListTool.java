package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.PermissionGrantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * What is attached to one rank, 직무, unit or account.
 *
 * <p>The other half of the explainer. {@code permission_effective_list} answers
 * "what can this person do"; this one answers "what does holding 부장 give
 * anybody", which is the question asked when a grant needs changing rather than
 * understanding — and the two have very different blast radii.
 */
@Component
public class GrantListTool implements McpTool {

    private final PermissionGrantService grants;
    private final ObjectMapper json;

    public GrantListTool(PermissionGrantService grants, ObjectMapper json) {
        this.grants = grants;
        this.json = json;
    }

    @Override
    public String name() {
        return "permission_grant_list";
    }

    @Override
    public String summary() {
        return "List the live grants attached to one source — a rank, a 직무, an org unit or a "
                + "single account. Revoked grants are not shown. A DENY here beats every ALLOW "
                + "within its own scope, wherever that ALLOW came from.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.PERMISSION_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "source",
                "RANK, JOB_FUNCTION, ORG_UNIT or USER_ACCOUNT.");
        OrgToolSupport.text(schema, "sourceId", "The id of that rank, 직무, unit or account.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "source", "sourceId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        GrantSource source = source(OrgToolSupport.requiredText(arguments, "source"));
        String sourceId = OrgToolSupport.requiredText(arguments, "sourceId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        ObjectNode payload = json.createObjectNode();
        payload.put("source", source.name());
        payload.put("sourceId", sourceId);
        payload.put("asOf", on.toString());
        ArrayNode entries = payload.putArray("grants");

        for (PermissionGrant grant : grants.list(principal, source, sourceId, on)) {
            ObjectNode entry = entries.addObject();
            entry.put("permission", String.valueOf(grant.key()));
            entry.put("scope", grant.scope().name());
            entry.put("sourceLabel", grant.sourceLabel());
            entry.put("effect", grant.isAllow() ? "ALLOW" : "DENY");
        }
        return McpToolResult.json(payload.toString());
    }

    private static GrantSource source(String value) {
        try {
            return GrantSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'source' must be RANK, JOB_FUNCTION, ORG_UNIT or "
                    + "USER_ACCOUNT; got '" + value + "'");
        }
    }
}
