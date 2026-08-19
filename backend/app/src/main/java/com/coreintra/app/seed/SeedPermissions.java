package com.coreintra.app.seed;

import com.coreintra.core.permission.PermissionKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every permission the demo installation hands out, in one list.
 *
 * <p>The operator holds the union of everything below at {@code ALL} scope, and it has to:
 * {@code PermissionGrantService} refuses to hand out a key the caller does not itself hold,
 * so a grant the seed makes for a rank is only possible if the operator already has it. Deriving
 * the operator's keys from the same table that describes the ranks keeps that true by
 * construction instead of by luck.
 *
 * <p>Nothing here is a wildcard. {@code PermissionGrantService} rejects wildcard keys outright -
 * "grant the concrete resource trees instead, so the effective-permissions explainer stays
 * readable" - and a seed that wrote {@code hr.*:*} rows around the back of that rule would
 * produce a demo whose explainer says nothing useful about why anybody can do anything.
 */
final class SeedPermissions {

    private SeedPermissions() {
    }

    /** What the seed itself needs in order to build an organisation from nothing. */
    static final String[] OPERATOR = {
        "company.settings:read", "company.settings:update", "admin.company:create",
        "hr.orgUnit:read", "hr.orgUnit:create", "hr.orgUnit:update", "hr.orgUnit:move",
        "hr.rank:read", "hr.rank:create", "hr.rank:update", "hr.rank:reorder",
        "hr.jobFunction:read", "hr.jobFunction:create", "hr.jobFunction:update",
        "hr.employee:read", "hr.employee:create", "hr.employee:update",
        "hr.position:read", "hr.position:assign", "hr.position:close",
        "hr.attendance:read", "hr.attendance:write", "hr.attendance.status:write",
        "hr.leave:read", "hr.leave:write", "hr.rules:read", "hr.rules:write",
        "admin.account:read", "admin.account:create", "admin.account:update",
        "admin.master:update", "admin.permission:read", "admin.permission:grant",
        "admin.permission:revoke",
        "approval.document:read", "approval.document:write",
        "accounting.book:read", "accounting.book:manage",
        "accounting.account:read", "accounting.account:create", "accounting.account:update",
        "accounting.entry:read", "accounting.entry:post",
        "accounting.batch:read", "accounting.batch:create",
        "accounting.report:read",
        "accounting.currency:read", "accounting.currency:manage",
        "accounting.client:read", "accounting.client:manage",
    };

    /**
     * Everyone stamps their own attendance and files their own documents. The attendance keys
     * are {@code SELF}-scoped, which reaches an attendance target because the target names the
     * employee it is about; the approval keys are {@code ORG_UNIT_SUBTREE}-scoped, because an
     * approval target names the drafter's unit and a person's own unit is inside their own
     * subtree.
     */
    static final String[] EVERY_RANK_SELF = {
        "hr.attendance:read", "hr.attendance:write",
    };

    static final String[] EVERY_RANK_SUBTREE = {
        "approval.document:read", "approval.document:write",
    };

    /** 부장: the team's attendance and the team's people, not the company's. */
    static final String[] BUJANG_SUBTREE = {
        "hr.attendance:read", "hr.employee:read", "hr.position:read", "accounting.report:read",
    };

    /** 이사: the same, one level of the tree wider by virtue of where they sit. */
    static final String[] ISA_SUBTREE = {
        "hr.attendance:read", "hr.employee:read", "hr.position:read",
    };

    /**
     * 과장 and above may read the ledger reports company-wide. This is the grant that makes
     * the promotion visible: 김민준 could not open a report on the 31st of July and can on the
     * 1st of August, without anybody having touched his permissions.
     */
    static final String[] GWAJANG_COMPANY = {
        "accounting.report:read",
    };

    /** 대표: company-wide reading, and the authority to sign anything. */
    static final String[] DAEPYO_COMPANY = {
        "approval.document:read", "approval.document:write",
        "hr.employee:read", "hr.attendance:read", "hr.position:read",
        "accounting.report:read", "company.settings:read",
    };

    /** 인사 직무: the people module, wherever in the company the person sits. */
    static final String[] HR_FUNCTION_COMPANY = {
        "hr.employee:read", "hr.employee:create", "hr.employee:update",
        "hr.position:read", "hr.position:assign",
        "hr.orgUnit:read", "hr.rank:read", "hr.jobFunction:read",
        "hr.attendance:read", "hr.attendance:write", "hr.attendance.status:write",
        "hr.leave:read", "hr.leave:write",
        "hr.rules:read", "hr.rules:write",
        "approval.document:read", "approval.document:write",
    };

    /** 회계 직무: the books. */
    static final String[] ACCOUNTING_FUNCTION_COMPANY = {
        "accounting.book:read", "accounting.book:manage",
        "accounting.account:read", "accounting.account:create", "accounting.account:update",
        "accounting.entry:read", "accounting.entry:post",
        "accounting.batch:read", "accounting.batch:create",
        "accounting.report:read",
        "accounting.currency:read", "accounting.currency:manage",
        "accounting.client:read", "accounting.client:manage",
        "approval.document:read", "approval.document:write",
    };

    /**
     * The demo's one explicit deny: 해외영업팀 cannot read ledger reports even though their
     * 부장 rank allows it. Deny beats allow within its scope, and an installation with no deny
     * in it never shows an administrator what the explainer looks like when one bites.
     */
    static final String[] OVERSEAS_SALES_DENY = {
        "accounting.report:read",
    };

    static List<PermissionKey> keys(String[] wire) {
        List<PermissionKey> keys = new ArrayList<PermissionKey>(wire.length);
        for (String text : wire) {
            keys.add(PermissionKey.parse(text));
        }
        return keys;
    }

    /**
     * The operator's keys, checked against everything the seed later hands out. A key granted
     * to a rank but missing from {@link #OPERATOR} would fail at run time inside
     * {@code PermissionGrantService}; finding it here says which key and why.
     */
    static List<PermissionKey> operatorKeys() {
        Set<String> operator = new LinkedHashSet<String>();
        for (String text : OPERATOR) {
            operator.add(text);
        }
        String[][] handedOut = {
            EVERY_RANK_SELF, EVERY_RANK_SUBTREE, BUJANG_SUBTREE, ISA_SUBTREE, GWAJANG_COMPANY,
            DAEPYO_COMPANY, HR_FUNCTION_COMPANY, ACCOUNTING_FUNCTION_COMPANY, OVERSEAS_SALES_DENY,
        };
        for (String[] bundle : handedOut) {
            for (String text : bundle) {
                if (!operator.contains(text)) {
                    throw new IllegalStateException("the seed grants \"" + text
                            + "\" but the operator does not hold it; add it to SeedPermissions.OPERATOR");
                }
            }
        }
        List<PermissionKey> keys = new ArrayList<PermissionKey>(operator.size());
        for (String text : operator) {
            keys.add(PermissionKey.parse(text));
        }
        return keys;
    }
}
