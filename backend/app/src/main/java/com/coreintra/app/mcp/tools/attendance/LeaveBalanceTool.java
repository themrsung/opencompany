package com.coreintra.app.mcp.tools.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.app.mcp.tools.approval.ApprovalToolSupport;
import com.coreintra.attendance.service.AttendancePermissions;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * How many 연차 days somebody has left, on a date.
 *
 * <p>Two hazards are spelled out in the summary because a model will otherwise
 * fall into both. The balance is a decimal — half-days and quarter-days are
 * ordinary in Korean practice — so it must not be rounded to an integer when
 * reported. And the balance is not a reservation: two people can be told the
 * same eleven days are available a second apart, and only the 결재 that approves
 * a 휴가 request actually spends them.
 */
@Component
public class LeaveBalanceTool implements McpTool {

    private final LeaveService leave;
    private final ObjectMapper json;

    public LeaveBalanceTool(LeaveService leave, ObjectMapper json) {
        this.leave = leave;
        this.json = json;
    }

    @Override
    public String name() {
        return "attendance_read_leave_balance";
    }

    @Override
    public String summary() {
        return "Read an employee's 연차 (annual leave) balance under one leave policy, as of "
                + "a date. Reads only — no leave is booked, granted or spent by calling this. "
                + "A past date gives the balance as it stood then: grants that had not yet "
                + "vested are excluded and days that had already lapsed are gone.\n\n"
                + "balanceDays is an EXACT DECIMAL STRING, not a number. Half-days and "
                + "quarter-days are ordinary, so \"11.25\" means eleven and a quarter days; "
                + "do not parse it into a float and do not round it when reporting it to "
                + "somebody.\n\n"
                + "This is a balance, not a reservation. Leave is spent when a 휴가 request "
                + "is approved through 결재, and a colleague booking the same days a moment "
                + "later changes the answer. Do not tell anybody their leave is secured on "
                + "the strength of this call.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return AttendancePermissions.LEAVE_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ApprovalToolSupport.schema(json);
        ApprovalToolSupport.text(schema, "companyId", "Which company the employee is in.");
        ApprovalToolSupport.text(schema, "employeeId",
                "Whose balance. Not an account id — use org_employee_lookup to turn a name "
                        + "into one.");
        ApprovalToolSupport.text(schema, "policyId",
                "Which leave policy the days come out of. It decides the bookable unit and "
                        + "the expiry, so it cannot be inferred.");
        ApprovalToolSupport.text(schema, "asOf",
                "The date to answer as of, YYYY-MM-DD. Defaults to today.");
        ApprovalToolSupport.required(schema, "companyId", "employeeId", "policyId");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String companyId = ApprovalToolSupport.requiredText(arguments, "companyId");
                String employeeId = ApprovalToolSupport.requiredText(arguments, "employeeId");
                String policyId = ApprovalToolSupport.requiredText(arguments, "policyId");
                String asOf = ApprovalToolSupport.optionalText(arguments, "asOf");
                LocalDate on = asOf == null ? LocalDate.now() : LocalDate.parse(asOf);

                BigDecimal balance =
                        leave.balanceOn(principal, companyId, employeeId, policyId, on);

                ObjectNode payload = json.createObjectNode();
                payload.put("employeeId", employeeId);
                payload.put("policyId", policyId);
                payload.put("asOf", on.toString());
                payload.put("balanceDays", ApiWire.decimal(balance));
                payload.put("note", "An exact decimal string. Not a reservation: leave is "
                        + "spent when a 휴가 request is approved through 결재.");
                return McpToolResult.json(render(payload));
            }
        });
    }

    private String render(ObjectNode payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render the balance", e);
        }
    }
}
