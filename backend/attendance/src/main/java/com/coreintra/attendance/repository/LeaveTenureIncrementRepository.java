package com.coreintra.attendance.repository;

import com.coreintra.attendance.entity.LeaveTenureIncrement;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTenureIncrementRepository extends JpaRepository<LeaveTenureIncrement, String> {

    List<LeaveTenureIncrement> findByPolicyIdOrderByAfterCompletedYearsAsc(String policyId);

    /** Every ladder for a set of policies, so listing a company's policies is two queries. */
    List<LeaveTenureIncrement> findByPolicyIdInOrderByAfterCompletedYearsAsc(
            Collection<String> policyIds);
}
