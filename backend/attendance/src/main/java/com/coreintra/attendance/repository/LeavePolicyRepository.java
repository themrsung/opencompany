package com.coreintra.attendance.repository;

import com.coreintra.attendance.entity.LeavePolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, String> {

    /** Usually one policy; sometimes 연차 plus 병가. */
    List<LeavePolicy> findByCompanyIdAndActiveTrueOrderByCodeAsc(String companyId);

    /**
     * By stable code, e.g. {@code ANNUAL}.
     *
     * <p>Not filtered on {@code active}: a request booked against a policy that
     * has since been retired must still resolve, or an approval already in
     * flight would fail with "no such policy" the day HR tidies up.
     */
    Optional<LeavePolicy> findByCompanyIdAndCode(String companyId, String code);
}
