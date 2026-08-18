package com.coreintra.core.permission;

import java.util.List;

/**
 * Every grant reaching a principal, from all four sources, resolved as of the
 * target's business date.
 *
 * <p>Implementations return grants unfiltered and unordered; deciding which
 * apply is the evaluator's job alone. Splitting that judgement across a query
 * and an evaluator is how two subtly different permission systems come to
 * exist in one codebase.
 */
public interface GrantDirectory {

    List<PermissionGrant> grantsFor(PermissionPrincipal principal, PrincipalOrgState orgState);
}
