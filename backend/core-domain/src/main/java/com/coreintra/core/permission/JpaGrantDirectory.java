package com.coreintra.core.permission;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.repository.JobFunctionRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.RankRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads every grant that reaches a principal, from all four sources, in one query.
 *
 * <p>Source labels are resolved here and carried on the grant, because the
 * explainer is useless if it can only say {@code RANK(9f3c-...)}. An admin
 * needs to read the chain, not decode it.
 */
@Component
public class JpaGrantDirectory implements GrantDirectory {

    /**
     * Postgres rejects {@code IN ()}. Passing a sentinel that matches no row is
     * simpler and more predictable than branching the query four ways.
     */
    private static final String NO_MATCH = " -none-";

    private final PermissionGrantRepository grants;
    private final RankRepository ranks;
    private final JobFunctionRepository jobFunctions;
    private final OrgUnitRepository orgUnits;

    public JpaGrantDirectory(PermissionGrantRepository grants, RankRepository ranks,
            JobFunctionRepository jobFunctions, OrgUnitRepository orgUnits) {
        this.grants = grants;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.orgUnits = orgUnits;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionGrant> grantsFor(PermissionPrincipal principal, PrincipalOrgState orgState) {
        List<PermissionGrantRow> rows = grants.findReaching(
                nonEmpty(orgState.rankIds()),
                nonEmpty(orgState.jobFunctionIds()),
                nonEmpty(orgState.orgUnitIds()),
                principal.accountId());

        Map<String, String> labels = resolveLabels(rows);
        List<PermissionGrant> resolved = new ArrayList<PermissionGrant>(rows.size());
        for (PermissionGrantRow row : rows) {
            resolved.add(row.toDomain(labels.get(labelKey(row.source(), row.sourceId()))));
        }
        return resolved;
    }

    private static Collection<String> nonEmpty(Set<String> ids) {
        return ids.isEmpty() ? Immutables.listOf(NO_MATCH) : new ArrayList<String>(ids);
    }

    /** One lookup per source, not one per grant. */
    private Map<String, String> resolveLabels(List<PermissionGrantRow> rows) {
        Map<String, String> labels = new HashMap<String, String>();
        for (PermissionGrantRow row : rows) {
            String key = labelKey(row.source(), row.sourceId());
            if (labels.containsKey(key)) {
                continue;
            }
            labels.put(key, lookupLabel(row));
        }
        return labels;
    }

    private String lookupLabel(PermissionGrantRow row) {
        switch (row.source()) {
            case RANK: {
                Optional<Rank> rank = ranks.findById(row.sourceId());
                return rank.isPresent() ? rank.get().labelKo() : row.sourceId();
            }
            case JOB_FUNCTION: {
                Optional<JobFunction> function = jobFunctions.findById(row.sourceId());
                return function.isPresent() ? function.get().labelKo() : row.sourceId();
            }
            case ORG_UNIT: {
                Optional<OrgUnit> unit = orgUnits.findById(row.sourceId());
                return unit.isPresent() ? unit.get().nameKo() : row.sourceId();
            }
            case USER_ACCOUNT:
                return "granted directly to this account";
            case TEMPORARY_MASTER_CAPABILITY:
                return "temporary master capability";
            default:
                return row.sourceId();
        }
    }

    private static String labelKey(GrantSource source, String sourceId) {
        return source + "/" + sourceId;
    }
}
