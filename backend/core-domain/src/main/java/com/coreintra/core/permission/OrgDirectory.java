package com.coreintra.core.permission;

import java.time.LocalDate;

/**
 * The org chart, queried as of a business date.
 *
 * <p>Kept as an interface so the evaluator can be tested against an in-memory
 * org without a database, and so the production implementation can cache
 * aggressively — a permission check happens on every request and must not walk
 * the tree row by row.
 */
public interface OrgDirectory {

    /**
     * Where this account stood on {@code asOf}.
     *
     * @return never null; use {@link PrincipalOrgState#none} for an account with
     *         no position
     */
    PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf);

    /**
     * True when {@code candidateDescendantId} is {@code ancestorId} itself or
     * lies beneath it in the tree as it stood on {@code asOf}.
     *
     * <p>Reflexive on purpose: {@link PermissionScope#ORG_UNIT_SUBTREE} covers
     * the unit itself as well as its children, which is what "subtree" means to
     * the person granting it.
     */
    boolean isInSubtree(String ancestorId, String candidateDescendantId, LocalDate asOf);
}
