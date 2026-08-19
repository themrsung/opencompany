package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.RankService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The 직급 ladder of one company.
 *
 * <p>Worth its own tool because a model asked to place somebody at 대리 has to
 * discover that 대리 exists here, is called that here, and sits where it sits.
 * None of it is hardcoded: a client may run a three-rung ladder with English
 * labels, and a tool that assumed the familiar seven would quietly assign people
 * to a rank that does not exist.
 */
@Component
public class RankListTool implements McpTool {

    private final RankService ranks;
    private final ObjectMapper json;

    public RankListTool(RankService ranks, ObjectMapper json) {
        this.ranks = ranks;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_rank_list";
    }

    @Override
    public String summary() {
        return "The company's 직급 ladder, most senior first, with the seniority number each "
                + "rung sits at. Labels and ordering are client data — compare the seniority "
                + "number, never the label.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.RANK_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "companyId", "The company whose ladder to read.");
        OrgToolSupport.businessDate(schema);
        OrgToolSupport.required(schema, "companyId");
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        String companyId = OrgToolSupport.requiredText(arguments, "companyId");
        LocalDate on = OrgToolSupport.businessDate(arguments);

        ObjectNode payload = json.createObjectNode();
        payload.put("companyId", companyId);
        payload.put("asOf", on.toString());
        ArrayNode entries = payload.putArray("ranks");

        for (Rank rank : ranks.list(principal, companyId, on)) {
            ObjectNode entry = entries.addObject();
            entry.put("rankId", rank.id());
            entry.put("code", rank.code());
            entry.put("labelKo", rank.labelKo());
            entry.put("labelEn", rank.labelEn());
            entry.put("seniority", rank.seniority());
            entry.put("representative", rank.isRepresentative());
            entry.put("active", rank.isActive());
        }
        return McpToolResult.json(payload.toString());
    }
}
