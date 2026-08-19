package com.coreintra.core.org.repository;

import com.coreintra.core.org.Rank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RankRepository extends JpaRepository<Rank, String> {
    List<Rank> findByCompanyIdOrderBySeniorityDesc(String companyId);
    List<Rank> findByCompanyIdAndRepresentativeTrueAndActiveTrue(String companyId);
}
