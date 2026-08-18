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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Place someone in a unit at a rank, from a date.
 *
 * <p>The most consequential write in this package, and the clearest case for
 * {@code mcp:write} being a visibility rule rather than a checkbox. A position
 * is what grants reach through: putting somebody in the 재무팀 at 부장 hands them
 * every permission attached to that unit and that rank, immediately. Nobody
 * should be able to do that through a token that was scoped for reading, and
 * with the write scope absent this tool is not listed at all.
 *
 * <p>The permission is checked by {@code PositionService} against the unit as it
 * stands on the business date, so authority over 영업본부 does not become
 * authority over a team that moved out of it last month.
 */
@Component
public class PositionAssignTool implements McpTool {

    private final PositionService positions;
    private final ObjectMapper json;

    public PositionAssignTool(PositionService positions, ObjectMapper json) {
        this.positions = positions;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_position_assign";
    }

    @Override
    public String summary() {
        return "Assign a person to an org unit at a rank from a date, optionally with 직무. "
                + "This is how permissions reach somebody: they immediately hold every grant "
                + "attached to that unit, that rank and those 직무. Marking it primary closes "
                + "any other primary assignment from the same day. Ends nothing else — use the "
                + "REST closure endpoint to end an assignment.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.POSITION_ASSIGN;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "employeeId", "Who is being placed.");
        OrgToolSupport.text(schema, "orgUnitId", "Which unit. Use org_unit_tree to find it.");
        OrgToolSupport.text(schema, "rankId", "Which 직급. Use org_rank_list to find it.");
        OrgToolSupport.textArray(schema, "jobFunctionIds", "직무 ids, optional.");
        OrgToolSupport.text(schema, "effectiveFrom", "First day of the assignment, YYYY-MM-DD.");
        OrgToolSupport.flag(schema, "primary",
                "Whether this is the assignment that answers 'which team are they on'.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "employeeId", "orgUnitId", "rankId", "effectiveFrom");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String employeeId = OrgToolSupport.requiredText(arguments, "employeeId");
        String orgUnitId = OrgToolSupport.requiredText(arguments, "orgUnitId");
        String rankId = OrgToolSupport.requiredText(arguments, "rankId");
        LocalDate effectiveFrom = date(OrgToolSupport.requiredText(arguments, "effectiveFrom"));
        LocalDate on = OrgToolSupport.businessDate(arguments);

        Position assigned = positions.assign(principal, employeeId, orgUnitId, rankId,
                OrgToolSupport.textList(arguments, "jobFunctionIds"), effectiveFrom,
                OrgToolSupport.flag(arguments, "primary", false), on);

        ObjectNode payload = json.createObjectNode();
        payload.put("positionId", assigned.id());
        payload.put("employeeId", assigned.employeeId());
        payload.put("orgUnitId", assigned.orgUnitId());
        payload.put("rankId", assigned.rankId());
        payload.put("primary", assigned.isPrimary());
        payload.put("effectiveFrom", OrgToolSupport.text(assigned.effectiveFrom()));
        payload.put("effectiveTo", OrgToolSupport.text(assigned.effectiveTo()));
        payload.put("note", "Grants attached to this unit, rank and 직무 now reach this person "
                + "from " + assigned.effectiveFrom() + ". Use permission_effective_list to see "
                + "exactly what changed.");
        return McpToolResult.json(payload.toString());
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'effectiveFrom' must be YYYY-MM-DD; got '" + value + "'");
        }
    }
}
