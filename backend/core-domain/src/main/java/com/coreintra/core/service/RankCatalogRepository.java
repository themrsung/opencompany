package com.coreintra.core.service;

import com.coreintra.core.org.Rank;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * The seniority ladder of one company, retired rungs included.
 *
 * <p>{@link #saveAll} is here for the reorder: renumbering the ladder is one
 * decision about the whole ladder, so it is written as one call rather than a
 * loop of saves that a reader could mistake for something interruptible.
 */
public interface RankCatalogRepository extends Repository<Rank, String> {

    <S extends Rank> S save(S rank);

    <S extends Rank> List<S> saveAll(Iterable<S> ranks);

    Optional<Rank> findById(String id);

    /** Most senior first, which is the order the ladder is shown and reordered in. */
    List<Rank> findByCompanyIdOrderBySeniorityDesc(String companyId);
}
