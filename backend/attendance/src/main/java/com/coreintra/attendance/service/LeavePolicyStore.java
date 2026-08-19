package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import java.util.List;
import java.util.Optional;

/**
 * Reads leave policies.
 *
 * <p>A port because {@code leave_policy} and {@code leave_tenure_increment} (V5)
 * have no JPA entities yet. The rows exist and V6 seeds the Korean 연차 default
 * into them; nothing in Java reads them.
 *
 * <h2>Policy is configuration, not statute in code</h2>
 *
 * <p>Every number that decides an entitlement — monthly accrual, annual grant,
 * the tenure ladder, the carry-over limit and its expiry, the bookable unit —
 * comes from a row through this interface. The Korean 연차 default is seeded and
 * editable, and its statutory reference lives in the migration's comment rather
 * than in any code path, because statute changes, clients are routinely more
 * generous than the minimum, and an overseas subsidiary runs another scheme
 * entirely.
 */
public interface LeavePolicyStore {

    /** Every active policy in a company. Usually one; sometimes 연차 plus 병가. */
    List<LeaveAccrualPolicy> findActive(String companyId);

    Optional<LeaveAccrualPolicy> findById(String policyId);

    /** By its stable code, e.g. {@code ANNUAL}. */
    Optional<LeaveAccrualPolicy> findByCode(String companyId, String code);
}
