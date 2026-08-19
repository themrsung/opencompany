package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EffectivePermissionsService;
import com.coreintra.core.service.OrgPermissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Everything one person's account can do, as of a date.
 *
 * <p>The list view beside {@code permission_explain_decision}'s single answer.
 * It exists as its own tool because the two questions have different shapes: a
 * model asked "what is 김민준 allowed to do" would otherwise probe the decision
 * tool once per permission it could think of, which is slow, incomplete and
 * wrong in a way nobody notices — the permissions it did not think of do not
 * appear.
 *
 * <p>Keyed by employee, because that is what a person asking has. The org state
 * the grants were resolved against comes back with them, so the answer can be
 * read as "these grants, because of these positions on this date".
 */
@Component
public class EffectivePermissionsTool implements McpTool {

    private final EffectivePermissionsService permissions;
    private final ObjectMapper json;

    public EffectivePermissionsTool(EffectivePermissionsService permissions, ObjectMapper json) {
        this.permissions = permissions;
        this.json = json;
    }

    @Override
    public String name() {
        return "permission_effective_list";
    }

    @Override
    public String summary() {
        return "List every grant reaching one person's account as of a business date, together "
                + "with the units, companies, ranks and 직무 those grants came through. Denies "
                + "are included and marked: a DENY out-ranks every ALLOW within its own scope, "
                + "so a permission can appear in this list and still be refused.";
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
        OrgToolSupport.text(schema, "employeeId", "The person whose account to describe.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "employeeId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String employeeId = OrgToolSupport.requiredText(arguments, "employeeId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        EffectivePermissions effective = permissions.explainEmployee(principal, employeeId, on);

        ObjectNode payload = json.createObjectNode();
        payload.put("employeeId", employeeId);
        payload.put("accountId", effective.principal().accountId());
        payload.put("asOf", on.toString());

        ObjectNode orgState = payload.putObject("orgState");
        strings(orgState.putArray("companyIds"), effective.orgState().companyIds());
        strings(orgState.putArray("orgUnitIds"), effective.orgState().orgUnitIds());
        strings(orgState.putArray("rankIds"), effective.orgState().rankIds());
        strings(orgState.putArray("jobFunctionIds"), effective.orgState().jobFunctionIds());

        ArrayNode grants = payload.putArray("grants");
        for (PermissionGrant grant : effective.grants()) {
            ObjectNode entry = grants.addObject();
            entry.put("permission", String.valueOf(grant.key()));
            entry.put("scope", grant.scope().name());
            entry.put("source", grant.source().name());
            entry.put("sourceId", grant.sourceId());
            entry.put("sourceLabel", grant.sourceLabel());
            entry.put("effect", grant.isAllow() ? "ALLOW" : "DENY");
        }
        if (effective.isEmpty()) {
            payload.put("note", "No grants reach this account on this date. The system is "
                    + "deny-by-default, so that means it can do nothing at all.");
        }
        return McpToolResult.json(payload.toString());
    }

    private static void strings(ArrayNode target, java.util.Set<String> values) {
        for (String value : values) {
            target.add(value);
        }
    }
}
