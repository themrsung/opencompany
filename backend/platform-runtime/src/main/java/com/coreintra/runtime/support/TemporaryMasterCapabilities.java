package com.coreintra.runtime.support;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;

import java.util.Set;

/**
 * The rules about what a support session may be granted, restated at the
 * persistence boundary.
 *
 * <p><b>This duplicates {@code com.coreintra.auth.support.TemporaryMasterGrant}
 * on purpose.</b> That class is the domain: it decides whether a session may
 * exist at all. It lives in {@code auth}, and this module may not depend on
 * {@code auth} (nor {@code auth} on this, which would be the cycle). Two paths
 * reach this table - the issuance flow in {@code auth}, and any future import or
 * repair tool - and a row that arrived by the second path with a wildcard in it
 * would be indistinguishable from one the domain approved.
 *
 * <p>So the list appears three times: in the domain, here, and as a CHECK
 * constraint in {@code V9__platform_runtime.sql}. That is not redundancy to be
 * cleaned up later. Each layer refuses on its own, and the test in this module
 * asserts the two Java copies still agree in content. If someone adds a
 * capability to the never-grantable list in {@code auth} and not here, the
 * database still refuses the write.
 */
public final class TemporaryMasterCapabilities {

    /**
     * Capabilities that may never be granted to a support session, whatever
     * anyone ticks. Each would let the session escape its own boundaries:
     * grant itself more, make itself permanent, mint another session, or remove
     * the record of what it did.
     */
    public static final Set<String> NEVER_GRANTABLE = Immutables.setOf(
            "admin.permission:grant",
            "admin.permission:revoke",
            "admin.master:create",
            "admin.master:update",
            "admin.master:delete",
            "admin.temporaryMaster:issue",
            "hr.employmentRules:amend",
            "hr.employmentRules:create",
            "hr.employmentRules:repeal",
            "company.representation:update",
            "admin.audit:disable",
            "admin.audit:delete");

    private TemporaryMasterCapabilities() {
    }

    /**
     * @return the capability, trimmed
     * @throws IllegalArgumentException for a blank value, a wildcard, or
     *         anything on the never-grantable list
     */
    public static String validate(String capability) {
        if (Texts.isBlank(capability)) {
            throw new IllegalArgumentException("a capability cannot be blank");
        }
        String trimmed = Texts.strip(capability);
        if (trimmed.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "\"" + trimmed + "\" is a wildcard. Capabilities must be ticked "
                            + "individually so the issuer sees exactly what each one exposes; "
                            + "there is no grant-all.");
        }
        if (NEVER_GRANTABLE.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "\"" + trimmed + "\" can never be granted to a temporary master. It would "
                            + "let the support session grant itself more access, make itself "
                            + "permanent, issue another session, or remove the record of what "
                            + "it did.");
        }
        return trimmed;
    }
}
