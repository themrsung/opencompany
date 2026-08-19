package com.coreintra.approval.repository;

import com.coreintra.approval.entity.EmploymentRulesAcknowledgementEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentRulesAcknowledgementRepository
        extends JpaRepository<EmploymentRulesAcknowledgementEntity,
                EmploymentRulesAcknowledgementEntity.Key> {

    /** Who has read a version. The employer's evidence that it was circulated. */
    List<EmploymentRulesAcknowledgementEntity> findByRulesIdOrderByEmployeeIdAsc(String rulesId);

    /** The other direction: which versions one employee has signed off. */
    List<EmploymentRulesAcknowledgementEntity> findByEmployeeId(String employeeId);
}
