package com.coreintra.app.mcp.tools.org;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.org.Company;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.CompanyService;
import com.coreintra.core.service.OrgPermissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The legal entities in this installation.
 *
 * <p>The entry point for everything else: almost every other tool wants a
 * {@code companyId}, and an installation with a 본사 and two 자회사 gives a
 * model three plausible answers to a question that was asked about one of them.
 * Listing them with their kind and parent lets it choose rather than guess.
 */
@Component
public class CompanyListTool implements McpTool {

    private final CompanyService companies;
    private final ObjectMapper json;

    public CompanyListTool(CompanyService companies, ObjectMapper json) {
        this.companies = companies;
        this.json = json;
    }

    @Override
    public String name() {
        return "org_company_list";
    }

    @Override
    public String summary() {
        return "List the legal entities the calling account may see — 본사, 지사 and 자회사 — "
                + "with their codes, kinds and parents. Start here when a question names a "
                + "company rather than an id.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return OrgPermissions.COMPANY_READ;
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = OrgToolSupport.schema(json);
        OrgToolSupport.businessDate(schema);
        return schema;
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        LocalDate on = OrgToolSupport.businessDate(arguments);

        ObjectNode payload = json.createObjectNode();
        payload.put("asOf", on.toString());
        ArrayNode entries = payload.putArray("companies");

        for (Company company : companies.list(principal, on)) {
            ObjectNode entry = entries.addObject();
            entry.put("companyId", company.id());
            entry.put("code", company.code());
            entry.put("nameKo", company.nameKo());
            entry.put("nameEn", company.nameEn());
            entry.put("kind", company.kind() == null ? null : company.kind().name());
            entry.put("parentCompanyId", company.parentCompanyId());
            entry.put("baseCurrencyCode", company.baseCurrencyCode());
            entry.put("active", company.isActive());
        }
        return McpToolResult.json(payload.toString());
    }
}
