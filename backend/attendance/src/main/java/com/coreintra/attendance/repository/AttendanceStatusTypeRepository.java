package com.coreintra.attendance.repository;

import com.coreintra.attendance.entity.AttendanceStatusType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceStatusTypeRepository extends JpaRepository<AttendanceStatusType, String> {

    List<AttendanceStatusType> findByCompanyIdAndActiveTrueOrderBySortOrderAsc(String companyId);

    Optional<AttendanceStatusType> findByCompanyIdAndCode(String companyId, String code);
}
