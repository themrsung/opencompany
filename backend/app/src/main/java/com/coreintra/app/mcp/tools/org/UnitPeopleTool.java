package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EmployeeService;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.PositionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Who is in a unit on a given date.
 *
 * <h2>Two permissions, one answer</h2>
 *
 * <p>The roster comes from {@code hr.position:read} on the unit. Putting names
 * against it needs {@code hr.employee:read} on each person, and the two do not
 * always coincide — a team lead may see that their unit has six positions
 * without being allowed to read the personnel record behind every one.
 *
 * <p>So each name is fetched individually and a refusal is recorded as
 * {@code "nameVisible": false} rather than failing the call. That is the same
 * shape {@code EmployeeService.list} already uses when it filters a list row by
 * row: the caller gets everything they are entitled to and is told, precisely,
 * where the answer stops. Failing the whole call instead would make the tool
 * useless to exactly the people who need it most.
 */
@Component
public class UnitPeopleTool implements McpTool {

    private final PositionService positions;
    private final EmployeeService employees;
    private final ObjectMapper json;

    public UnitPeopleTool(PositionService positions, EmployeeService employees, ObjectMapper json) {
        this.positions = positions;
        this.employees = employees;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_unit_people";
    }

    @Override
    public String summary() {
        return "List the live positions in one org unit as of a business date — the unit's "
                + "roster. Each entry carries the person's id and, where the calling account may "
                + "read the personnel record, their name; nameVisible=false means the position "
                + "is visible but the person behind it is not.";
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
        OrgToolSupport.text(schema, "orgUnitId", "The unit to list.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "orgUnitId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String orgUnitId = OrgToolSupport.requiredText(arguments, "orgUnitId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        ObjectNode payload = json.createObjectNode();
        payload.put("orgUnitId", orgUnitId);
        payload.put("asOf", on.toString());
        ArrayNode people = payload.putArray("people");

        for (Position position : positions.liveInUnit(principal, orgUnitId, on)) {
            ObjectNode entry = people.addObject();
            entry.put("positionId", position.id());
            entry.put("employeeId", position.employeeId());
            entry.put("rankId", position.rankId());
            entry.put("primary", position.isPrimary());
            entry.put("effectiveFrom", OrgToolSupport.text(position.effectiveFrom()));
            entry.put("effectiveTo", OrgToolSupport.text(position.effectiveTo()));
            nameInto(entry, principal, position.employeeId(), on);
        }
        return McpToolResult.json(payload.toString());
    }

    private void nameInto(ObjectNode entry, PermissionPrincipal principal, String employeeId,
            LocalDate on) {
        try {
            Employee employee = employees.read(principal, employeeId, on);
            entry.put("nameVisible", true);
            entry.put("nameKo", employee.nameKo());
            entry.put("nameEn", employee.nameEn());
            entry.put("employeeNumber", employee.employeeNumber());
        } catch (PermissionDeniedException e) {
            // Caught for this one row only, and never for the roster call above:
            // a denial there is the answer to the question that was asked and
            // must reach the caller.
            entry.put("nameVisible", false);
        }
    }
}
