package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.Employee;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EmployeeService;
import com.coreintra.core.service.OrgPermissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Add a person. A write tool, and one that genuinely needs the scope.
 *
 * <p>This creates a row in the personnel table. It is not reversible from here —
 * there is no delete anywhere in this system, so a person added in error is
 * corrected by terminating them, which leaves both facts in the record. That is
 * exactly the kind of action {@code mcp:write} exists to gate, and it is why a
 * read-scoped token cannot see this tool at all rather than seeing it and
 * failing: a model that can see a tool will keep trying it.
 *
 * <p>It creates the person and nothing else. Where they sit and at what rank is
 * a position, with its own permission and its own tool, because being allowed to
 * add somebody to the payroll is not the same as being allowed to put them in
 * the 재무팀.
 */
@Component
public class EmployeeCreateTool implements McpTool {

    private final EmployeeService employees;
    private final ObjectMapper json;

    public EmployeeCreateTool(EmployeeService employees, ObjectMapper json) {
        this.employees = employees;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_employee_create";
    }

    @Override
    public String summary() {
        return "Add a person to a company's personnel records. Creates the person only — use "
                + "org_position_assign afterwards to place them in a unit at a rank. Nothing in "
                + "this system is deleted, so a mistake here is corrected by recording a "
                + "termination, not by removing the row.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.EMPLOYEE_CREATE;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "companyId", "Which legal entity employs them.");
        OrgToolSupport.text(schema, "nameKo", "Korean name, as payroll knows it.");
        OrgToolSupport.text(schema, "nameEn", "English name, optional.");
        OrgToolSupport.text(schema, "employeeNumber", "Payroll number, optional but usual.");
        OrgToolSupport.text(schema, "email", "Work address, optional.");
        OrgToolSupport.text(schema, "hiredOn", "First day, YYYY-MM-DD.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "companyId", "nameKo");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String companyId = OrgToolSupport.requiredText(arguments, "companyId");
        String nameKo = OrgToolSupport.requiredText(arguments, "nameKo");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        Employee created = employees.create(principal, companyId,
                OrgToolSupport.optionalText(arguments, "employeeNumber"), nameKo,
                OrgToolSupport.optionalText(arguments, "nameEn"),
                OrgToolSupport.optionalText(arguments, "email"),
                date(OrgToolSupport.optionalText(arguments, "hiredOn"), "hiredOn"), on);

        ObjectNode payload = json.createObjectNode();
        payload.put("employeeId", created.id());
        payload.put("companyId", created.companyId());
        payload.put("employeeNumber", created.employeeNumber());
        payload.put("nameKo", created.nameKo());
        payload.put("nameEn", created.nameEn());
        payload.put("hiredOn", OrgToolSupport.text(created.hiredOn()));
        payload.put("note", "Created. They hold no position yet, so no permission reaches them "
                + "through a rank, a 직무 or a unit.");
        return McpToolResult.json(payload.toString());
    }

    private static LocalDate date(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be YYYY-MM-DD; got '" + value + "'");
        }
    }
}
