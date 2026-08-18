package com.coreintra.core.permission;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full picture of what one principal may do, as of a business date.
 *
 * <p>Backs the effective-permissions explainer. Grants are grouped by key and
 * each retains its source, so the UI can render "왜 이 사용자는 이 작업을 할 수
 * 있습니까?" as a chain rather than a yes/no.
 */
public final class EffectivePermissions implements Serializable {

    private static final long serialVersionUID = 1L;

    private final PermissionPrincipal principal;
    private final LocalDate asOfBusinessDate;
    private final PrincipalOrgState orgState;
    private final List<PermissionGrant> grants;

    public EffectivePermissions(PermissionPrincipal principal, LocalDate asOfBusinessDate,
            PrincipalOrgState orgState, List<PermissionGrant> grants) {
        this.principal = principal;
        this.asOfBusinessDate = asOfBusinessDate;
        this.orgState = orgState;
        List<PermissionGrant> sorted = new ArrayList<PermissionGrant>(grants);
        Collections.sort(sorted, new Comparator<PermissionGrant>() {
            @Override
            public int compare(PermissionGrant left, PermissionGrant right) {
                int byKey = left.key().compareTo(right.key());
                if (byKey != 0) {
                    return byKey;
                }
                // Denies first within a key: they are what an admin needs to see.
                if (left.isDeny() != right.isDeny()) {
                    return left.isDeny() ? -1 : 1;
                }
                return right.scope().compareTo(left.scope());
            }
        });
        this.grants = Immutables.copyOf(sorted);
    }

    public PermissionPrincipal principal() {
        return principal;
    }

    public LocalDate asOfBusinessDate() {
        return asOfBusinessDate;
    }

    /** The org position the grants were resolved against. */
    public PrincipalOrgState orgState() {
        return orgState;
    }

    public List<PermissionGrant> grants() {
        return grants;
    }

    /** Grants grouped by permission, insertion-ordered for stable rendering. */
    public Map<PermissionKey, List<PermissionGrant>> byKey() {
        Map<PermissionKey, List<PermissionGrant>> grouped =
                new LinkedHashMap<PermissionKey, List<PermissionGrant>>();
        for (PermissionGrant grant : grants) {
            List<PermissionGrant> bucket = grouped.get(grant.key());
            if (bucket == null) {
                bucket = new ArrayList<PermissionGrant>();
                grouped.put(grant.key(), bucket);
            }
            bucket.add(grant);
        }
        return grouped;
    }

    public boolean isEmpty() {
        return grants.isEmpty();
    }
}
