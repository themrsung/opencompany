package com.coreintra.app.mcp.tools.attendance;

import com.coreintra.app.api.attendance.WhoIsInView;
import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.app.mcp.tools.approval.ApprovalToolSupport;
import com.coreintra.attendance.service.AttendancePermissions;
import com.coreintra.attendance.service.WhoIsInEntry;
import com.coreintra.attendance.service.WhosInService;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * Who is in today — the board, for a team or a whole company.
 *
 * <p>The two things a model must not get wrong about this answer are stated in
 * the summary rather than left to inference: 비공개 means a colleague's status is
 * private and not that they are absent, and a null status means nothing was
 * recorded and not that the person was off. Both mistakes produce a confident,
 * wrong sentence about a named human being.
 */
@Component
public class WhosInTool implements McpTool {

    private final WhosInService board;
    private final ObjectMapper json;

    public WhosInTool(WhosInService board, ObjectMapper json) {
        this.board = board;
        this.json = json;
    }

    @Override
    public String name() {
        return "attendance_read_whos_in";
    }

    @Override
    public String summary() {
        return "Read the who's-in board for a business date: who is working, remote, in the "
                + "field, away, on leave or gone home, and whether each spell is still open. "
                + "Reads only — nothing is recorded or changed by calling this. Give "
                + "orgUnitId for one team or omit it for the whole company; membership is "
                + "resolved as of the date asked about, so a past date shows that day's "
                + "team.\n\n"
                + "Two things this answer does NOT mean. An entry with visible=false and the "
                + "label 비공개 is somebody whose status the calling account may not see — it "
                + "does not mean they are absent, and it must not be reported as such. An "
                + "entry with a null statusCode is somebody who recorded nothing that day, "
                + "which is also not the same as being off.\n\n"
                + "Times are business instants (YYYY-MM-DDT[-]HH:MM:SS.mmm, no timezone). An "
                + "end time past 24:00 — 27:00 means 03:00 the following morning — belongs to "
                + "the business day the shift began on and is correct, not a data error.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return AttendancePermissions.ATTENDANCE_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ApprovalToolSupport.schema(json);
        ApprovalToolSupport.text(schema, "companyId", "Which company's board.");
        ApprovalToolSupport.text(schema, "orgUnitId",
                "One team. Omit for the whole company.");
        ApprovalToolSupport.businessDate(schema);
        ApprovalToolSupport.required(schema, "companyId");
        return schema;
    }

    @Override
    public McpToolResult call(final PermissionPrincipal principal, final JsonNode arguments) {
        return ApprovalToolSupport.refusable(new Callable<McpToolResult>() {
            @Override
            public McpToolResult call() {
                String companyId = ApprovalToolSupport.requiredText(arguments, "companyId");
                String orgUnitId = ApprovalToolSupport.optionalText(arguments, "orgUnitId");
                LocalDate on = ApprovalToolSupport.businessDate(arguments);

                List<WhoIsInEntry> entries = Texts.isBlank(orgUnitId)
                        ? board.forCompany(principal, companyId, on)
                        : board.forUnit(principal, companyId, orgUnitId, on);

                ObjectNode payload = json.createObjectNode();
                payload.put("companyId", companyId);
                payload.put("orgUnitId", orgUnitId);
                payload.put("businessDate", on.toString());
                ArrayNode rows = payload.putArray("entries");
                for (WhoIsInEntry entry : entries) {
                    rows.add(json.valueToTree(WhoIsInView.from(entry)));
                }
                return McpToolResult.json(render(payload));
            }
        });
    }

    private String render(ObjectNode payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render the board", e);
        }
    }
}
