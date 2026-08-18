package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.OrgUnitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The 조직도 of one company, as it stood on a date.
 *
 * <p>Flat and path-ordered rather than nested, which is the same shape the REST
 * endpoint returns and for the same reason: a parent always precedes its
 * children, so the list can be read straight through, and a truncated answer is
 * a valid prefix rather than a broken tree.
 *
 * <p>This is the tool that turns a name a person used — "영업1팀" — into the id
 * every other tool wants, so its answer carries codes and both names rather than
 * ids alone.
 */
@Component
public class OrgTreeTool implements McpTool {

    private final OrgUnitService units;
    private final ObjectMapper json;

    public OrgTreeTool(OrgUnitService units, ObjectMapper json) {
        this.units = units;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_unit_tree";
    }

    @Override
    public String summary() {
        return "The org chart of one company as of a business date, flat and in depth-first "
                + "order with each unit's materialised path. Use it to turn a unit name into the "
                + "id other tools need. Units the calling account may not read are absent.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.UNIT_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.text(schema, "companyId", "The company whose tree to read.");
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
        ArrayNode nodes = payload.putArray("units");

        for (OrgUnit unit : units.tree(principal, companyId, on)) {
            ObjectNode entry = nodes.addObject();
            entry.put("orgUnitId", unit.id());
            entry.put("parentId", unit.parentId());
            entry.put("code", unit.code());
            entry.put("nameKo", unit.nameKo());
            entry.put("nameEn", unit.nameEn());
            entry.put("path", unit.path());
            entry.put("depth", unit.depth());
            entry.put("active", unit.isActive());
        }
        return McpToolResult.json(payload.toString());
    }
}
