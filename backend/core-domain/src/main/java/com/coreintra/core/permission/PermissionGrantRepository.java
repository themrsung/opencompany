package com.coreintra.core.permission;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrantRow, String> {

    /**
     * Every grant hanging off any of the given sources, in one statement.
     *
     * <p>One query rather than four: a permission check is on the hottest path
     * in the system, and four round trips per check is four times the latency
     * for no benefit.
     */
    @Query("select g from PermissionGrantRow g "
            + "where (g.source = com.coreintra.core.permission.GrantSource.RANK and g.sourceId in :rankIds) "
            + "   or (g.source = com.coreintra.core.permission.GrantSource.JOB_FUNCTION and g.sourceId in :functionIds) "
            + "   or (g.source = com.coreintra.core.permission.GrantSource.ORG_UNIT and g.sourceId in :unitIds) "
            + "   or (g.source = com.coreintra.core.permission.GrantSource.USER_ACCOUNT and g.sourceId = :accountId) "
            + "   or (g.source = com.coreintra.core.permission.GrantSource.TEMPORARY_MASTER_CAPABILITY and g.sourceId = :accountId)")
    List<PermissionGrantRow> findReaching(
            @Param("rankIds") Collection<String> rankIds,
            @Param("functionIds") Collection<String> functionIds,
            @Param("unitIds") Collection<String> unitIds,
            @Param("accountId") String accountId);

    List<PermissionGrantRow> findBySourceAndSourceId(GrantSource source, String sourceId);
}
