package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.PositionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * One person's assignment history.
 *
 * <p>The tool to reach for before answering anything about the past. "Was 김민준
 * allowed to approve this in March" depends on where they sat in March, and this
 * is where that is written down: closed intervals are kept, not overwritten, so
 * the answer is a fact rather than an inference from where they sit now.
 */
@Component
public class PositionHistoryTool implements McpTool {

    private final PositionService positions;
    private final ObjectMapper json;

    public PositionHistoryTool(PositionService positions, ObjectMapper json) {
        this.positions = positions;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_position_history";
    }

    @Override
    public String summary() {
        return "Every assignment one person has held, in order, including closed ones. "
                + "effectiveFrom is inclusive and effectiveTo is exclusive; a null effectiveTo "
                + "means the assignment is still open.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.POSITION_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "employeeId", "Whose history to read.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "employeeId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String employeeId = OrgToolSupport.requiredText(arguments, "employeeId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        ObjectNode payload = json.createObjectNode();
        payload.put("employeeId", employeeId);
        payload.put("asOf", on.toString());
        ArrayNode entries = payload.putArray("positions");

        for (Position position : positions.history(principal, employeeId, on)) {
            ObjectNode entry = entries.addObject();
            entry.put("positionId", position.id());
            entry.put("orgUnitId", position.orgUnitId());
            entry.put("rankId", position.rankId());
            entry.put("primary", position.isPrimary());
            entry.put("effectiveFrom", OrgToolSupport.text(position.effectiveFrom()));
            entry.put("effectiveTo", OrgToolSupport.text(position.effectiveTo()));
        }
        return McpToolResult.json(payload.toString());
    }
}
