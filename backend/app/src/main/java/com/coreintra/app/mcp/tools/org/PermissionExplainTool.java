package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionDecision;
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
 * Why can this account do this — or why not.
 *
 * <p>The explainer the brief calls non-negotiable, as a tool. A denial is a
 * successful answer here and comes back as {@code allowed: false} with the grant
 * chain, not as an error: the question an administrator brings to this tool is
 * almost always "why <em>can't</em> they", and a tool that refused to explain
 * refusals would be silent exactly when it is needed.
 *
 * <p>The target is built from the subject's own position on the business date
 * rather than from arguments the caller assembles, so the commonest way to get a
 * confident wrong answer — naming the unit the person is in today when asking
 * about a document from March — is not expressible.
 */
@Component
public class PermissionExplainTool implements McpTool {

    private final EffectivePermissionsService permissions;
    private final ObjectMapper json;

    public PermissionExplainTool(EffectivePermissionsService permissions, ObjectMapper json) {
        this.permissions = permissions;
        this.json = json;
    }

    @Override
    public String name() {
        return "permission_explain_decision";
    }

    @Override
    public String summary() {
        return "Explain whether one account may do one thing, with the grant chain behind the "
                + "answer. Name the subject with employeeId (the target is then resolved from "
                + "that person's position on the date) or with companyId and optionally "
                + "orgUnitId. A denial is a normal answer, not an error.";
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
        OrgToolSupport.text(schema, "accountId", "The account whose authority is in question.");
        OrgToolSupport.text(schema, "permission", "resource:action, e.g. hr.employee:read.");
        OrgToolSupport.text(schema, "employeeId", "The person the action would be about.");
        OrgToolSupport.text(schema, "companyId", "Use instead of employeeId, with orgUnitId.");
        OrgToolSupport.text(schema, "orgUnitId", "Narrows a companyId target to one unit.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "accountId", "permission");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String accountId = OrgToolSupport.requiredText(arguments, "accountId");
        PermissionKey key = PermissionKey.parse(OrgToolSupport.requiredText(arguments, "permission"));
        String employeeId = OrgToolSupport.optionalText(arguments, "employeeId");
        String companyId = OrgToolSupport.optionalText(arguments, "companyId");
        String orgUnitId = OrgToolSupport.optionalText(arguments, "orgUnitId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        PermissionDecision decision;
        if (employeeId != null) {
            decision = permissions.explainDecisionAboutEmployee(principal, accountId, key,
                    employeeId, on);
        } else if (companyId != null) {
            decision = permissions.explainDecisionAboutUnit(principal, accountId, key, companyId,
                    orgUnitId, on);
        } else {
            throw new IllegalArgumentException(
                    "name the subject: either 'employeeId', or 'companyId' with an optional "
                            + "'orgUnitId'. Without one, there is no domain object to decide "
                            + "about, and every decision here is made on the object.");
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("accountId", accountId);
        payload.put("permission", String.valueOf(key));
        payload.put("asOf", on.toString());
        payload.put("allowed", decision.isAllowed());
        payload.put("summary", decision.summary());
        if (decision.decidingGrant() != null) {
            grantInto(payload.putObject("decidingGrant"), decision.decidingGrant());
        }
        ArrayNode considered = payload.putArray("considerations");
        for (PermissionDecision.Consideration consideration : decision.considerations()) {
            ObjectNode entry = considered.addObject();
            grantInto(entry.putObject("grant"), consideration.grant());
            entry.put("applied", consideration.applied());
            entry.put("reason", consideration.reason());
        }
        return McpToolResult.json(payload.toString());
    }

    private static void grantInto(ObjectNode node,
            com.coreintra.core.permission.PermissionGrant grant) {
        node.put("permission", String.valueOf(grant.key()));
        node.put("scope", grant.scope().name());
        node.put("source", grant.source().name());
        node.put("sourceId", grant.sourceId());
        node.put("sourceLabel", grant.sourceLabel());
        node.put("effect", grant.isAllow() ? "ALLOW" : "DENY");
    }
}
