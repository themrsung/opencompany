package com.coreintra.core.org.repository;

import com.coreintra.core.org.OrgUnit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, String> {

    List<OrgUnit> findByCompanyIdAndActiveTrue(String companyId);

    /**
     * The unit and everything beneath it, in one statement.
     *
     * <p>Uses the materialised path so a subtree read is a prefix scan on an
     * indexed column rather than a recursive walk. The path is slash-terminated,
     * so this cannot half-match a sibling with a shared code prefix.
     */
    @Query("select u from OrgUnit u where u.path like concat(:path, '%')")
    List<OrgUnit> findSubtree(@Param("path") String path);
}
