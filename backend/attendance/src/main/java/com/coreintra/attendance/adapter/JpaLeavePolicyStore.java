package com.coreintra.attendance.adapter;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.entity.LeavePolicy;
import com.coreintra.attendance.entity.LeaveTenureIncrement;
import com.coreintra.attendance.repository.LeavePolicyRepository;
import com.coreintra.attendance.repository.LeaveTenureIncrementRepository;
import com.coreintra.attendance.service.LeavePolicyStore;
import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads accrual policies out of {@code leave_policy} and
 * {@code leave_tenure_increment}.
 *
 * <p>A mapping and nothing else. Every entitlement number is a column, and none
 * of the arithmetic that uses them happens here: {@code LeaveAccrualPolicy}
 * computes entitlement, carry-over and rounding, and it is tested without a
 * database precisely so that changing a client's ladder is a data edit rather
 * than a release.
 */
@Component
public class JpaLeavePolicyStore implements LeavePolicyStore {

    private final LeavePolicyRepository policies;
    private final LeaveTenureIncrementRepository increments;

    public JpaLeavePolicyStore(LeavePolicyRepository policies,
            LeaveTenureIncrementRepository increments) {
        this.policies = policies;
        this.increments = increments;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveAccrualPolicy> findActive(String companyId) {
        List<LeavePolicy> rows = policies.findByCompanyIdAndActiveTrueOrderByCodeAsc(companyId);
        if (rows.isEmpty()) {
            return Immutables.listOf();
        }
        List<String> ids = new ArrayList<String>();
        for (LeavePolicy row : rows) {
            ids.add(row.id());
        }
        Map<String, List<LeaveTenureIncrement>> ladders = new LinkedHashMap<String,
                List<LeaveTenureIncrement>>();
        for (LeaveTenureIncrement rung
                : increments.findByPolicyIdInOrderByAfterCompletedYearsAsc(ids)) {
            List<LeaveTenureIncrement> ladder = ladders.get(rung.policyId());
            if (ladder == null) {
                ladder = new ArrayList<LeaveTenureIncrement>();
                ladders.put(rung.policyId(), ladder);
            }
            ladder.add(rung);
        }

        List<LeaveAccrualPolicy> assembled = new ArrayList<LeaveAccrualPolicy>();
        for (LeavePolicy row : rows) {
            assembled.add(assemble(row, ladders.get(row.id())));
        }
        return Immutables.copyOf(assembled);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LeaveAccrualPolicy> findById(String policyId) {
        Optional<LeavePolicy> row = policies.findById(policyId);
        if (!row.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(assemble(row.get(),
                increments.findByPolicyIdOrderByAfterCompletedYearsAsc(policyId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LeaveAccrualPolicy> findByCode(String companyId, String code) {
        Optional<LeavePolicy> row = policies.findByCompanyIdAndCode(companyId, code);
        if (!row.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(assemble(row.get(),
                increments.findByPolicyIdOrderByAfterCompletedYearsAsc(row.get().id())));
    }

    private LeaveAccrualPolicy assemble(LeavePolicy row, List<LeaveTenureIncrement> ladder) {
        LeaveAccrualPolicy.Builder builder = LeaveAccrualPolicy.builder(row.id())
                .name(row.nameKo(), row.nameEn())
                .monthlyAccrualDays(row.monthlyAccrualDays())
                .annualGrantDays(row.annualGrantDays())
                .annualGrantAfterCompletedYears(row.annualGrantAfterYears())
                .maximumDays(row.maximumDays())
                .carryOverLimitDays(row.carryOverLimitDays())
                .carryOverExpiryMonths(row.carryOverExpiryMonths())
                .minimumBookableUnitDays(row.minimumBookableUnitDays());
        if (ladder != null) {
            for (LeaveTenureIncrement rung : ladder) {
                builder.tenureIncrement(rung.afterCompletedYears(), rung.additionalDays());
            }
        }
        return builder.build();
    }
}
