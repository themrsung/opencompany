package com.coreintra.core.service;

import com.coreintra.core.org.OrgUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Every unit of a company, retired ones included.
 *
 * <p>{@code OrgUnitRepository} answers the questions a running system asks - the
 * active tree, and a subtree by path - which is right for reads but wrong for
 * the questions this layer has to answer before it writes: is this code already
 * taken (the unique constraint counts retired rows too), and where does the rest
 * of the subtree sit, since a move has to rewrite every path beneath it.
 *
 * <p>The whole company is read at once on purpose. A move is the one operation
 * that touches an unbounded number of rows, and doing it from a single ordered
 * snapshot is what makes "parents before children" a property of the data rather
 * than of the loop.
 */
public interface OrgUnitCatalogRepository extends Repository<OrgUnit, String> {

    <S extends OrgUnit> S save(S unit);

    <S extends OrgUnit> List<S> saveAll(Iterable<S> units);

    Optional<OrgUnit> findById(String id);

    /** Ordered by materialised path, so callers get parents before children. */
    List<OrgUnit> findByCompanyIdOrderByPathAsc(String companyId);
}
