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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Find a person, by id or by name.
 *
 * <p>The search half is not a convenience. A model is given "김민준", never a
 * UUID, and without a way to turn one into the other every other tool is
 * unreachable — it would have to guess ids, which it will happily do. Searching
 * goes through {@code EmployeeService.list}, so every candidate is filtered by
 * the evaluator before the match runs: a caller who cannot read the 인사팀 does
 * not learn that somebody there matches their query.
 */
@Component
public class EmployeeLookupTool implements McpTool {

    private final EmployeeService employees;
    private final ObjectMapper json;

    public EmployeeLookupTool(EmployeeService employees, ObjectMapper json) {
        this.employees = employees;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_employee_lookup";
    }

    @Override
    public String summary() {
        return "Find a person in the organisation. Give employeeId for an exact record, or "
                + "companyId with query to search by Korean name, English name or employee "
                + "number. Results are limited to the people the calling account may read, so a "
                + "name that is absent may exist and be out of reach rather than not exist.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.EMPLOYEE_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "employeeId", "The exact person to read. Omit to search.");
        OrgToolSupport.text(schema, "companyId", "Which company to search. Required with query.");
        OrgToolSupport.text(schema, "query", "Part of a name or employee number, case-insensitive.");
        OrgToolSupport.flag(schema, "includeLeavers",
                "Include people who have left. Defaults to false.");
        OrgToolSupport.businessDate(schema);
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = OrgToolSupport.businessDate(arguments);
        String employeeId = OrgToolSupport.optionalText(arguments, "employeeId");

        ObjectNode payload = json.createObjectNode();
        payload.put("asOf", on.toString());
        ArrayNode found = payload.putArray("employees");

        if (employeeId != null) {
            append(found, employees.read(principal, employeeId, on));
            return McpToolResult.json(payload.toString());
        }

        String companyId = OrgToolSupport.requiredText(arguments, "companyId");
        String query = OrgToolSupport.requiredText(arguments, "query").toLowerCase();
        boolean includeLeavers = OrgToolSupport.flag(arguments, "includeLeavers", false);

        List<Employee> candidates = employees.list(principal, companyId, includeLeavers, on);
        for (Employee employee : candidates) {
            if (matches(employee, query)) {
                append(found, employee);
            }
        }
        if (found.size() == 0) {
            payload.put("note", "Nobody matched. The query may be wrong, or the people it would "
                    + "have matched may be outside what this account may read.");
        }
        return McpToolResult.json(payload.toString());
    }

    private static boolean matches(Employee employee, String query) {
        return contains(employee.nameKo(), query)
                || contains(employee.nameEn(), query)
                || contains(employee.employeeNumber(), query);
    }

    private static boolean contains(String field, String query) {
        return field != null && field.toLowerCase().contains(query);
    }

    private static void append(ArrayNode target, Employee employee) {
        ObjectNode node = target.addObject();
        node.put("employeeId", employee.id());
        node.put("companyId", employee.companyId());
        node.put("employeeNumber", employee.employeeNumber());
        node.put("nameKo", employee.nameKo());
        node.put("nameEn", employee.nameEn());
        node.put("email", employee.email());
        node.put("hiredOn", OrgToolSupport.text(employee.hiredOn()));
        node.put("terminatedOn", OrgToolSupport.text(employee.terminatedOn()));
    }
}
