package com.coreintra.attendance.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.entity.LeavePolicy;
import com.coreintra.attendance.entity.LeaveTenureIncrement;
import com.coreintra.attendance.repository.LeavePolicyRepository;
import com.coreintra.attendance.repository.LeaveTenureIncrementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaLeavePolicyStoreTest {

    private final FakePolicies policies = new FakePolicies();
    private final FakeIncrements increments = new FakeIncrements();
    private final JpaLeavePolicyStore store = new JpaLeavePolicyStore(policies, increments);

    @Test
    @DisplayName("the tenure ladder comes back attached to its policy")
    void ladderIsAssembledOntoThePolicy() {
        policies.save(annual());
        increments.save(new LeaveTenureIncrement("i1", "pol-1", 3, BigDecimal.ONE));
        increments.save(new LeaveTenureIncrement("i2", "pol-1", 5, new BigDecimal("2")));

        Optional<LeaveAccrualPolicy> found = store.findById("pol-1");

        assertThat(found).isPresent();
        assertThat(found.get().tenureIncrements()).hasSize(2);
        assertThat(found.get().tenureIncrements().get(1).additionalDays())
                .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("every entitlement number comes from the row, not from the code")
    void entitlementIsComputedFromTheRow() {
        // The point of the port: change the row and the entitlement changes,
        // with nothing recompiled. A client more generous than the statute is
        // the normal case, not an exception to handle.
        LeavePolicy generous = annual();
        generous.setAnnualGrantDays(new BigDecimal("20"));
        policies.save(generous);

        LeaveAccrualPolicy policy = store.findById("pol-1").get();

        assertThat(policy.entitlementFor(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 18)))
                .isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("a policy is found by the stable code a document names it with")
    void findsByCode() {
        policies.save(annual());

        assertThat(store.findByCode("acme", "ANNUAL")).isPresent();
        assertThat(store.findByCode("acme", "SICK")).isEmpty();
    }

    @Test
    @DisplayName("a retired policy is still findable by code, so an in-flight approval survives")
    void retiredPoliciesStayResolvableByCode() {
        LeavePolicy retired = annual();
        retired.retire();
        policies.save(retired);

        assertThat(store.findActive("acme")).isEmpty();
        assertThat(store.findByCode("acme", "ANNUAL"))
                .as("a request already in 결재 must not fail the day HR tidies up")
                .isPresent();
    }

    @Test
    @DisplayName("the half-day unit survives, so 0.5 stays bookable")
    void bookableUnitIsCarried() {
        policies.save(annual());

        LeaveAccrualPolicy policy = store.findById("pol-1").get();

        assertThat(policy.isBookable(new BigDecimal("0.5"))).isTrue();
        assertThat(policy.isBookable(new BigDecimal("0.3"))).isFalse();
    }

    private static LeavePolicy annual() {
        LeavePolicy policy = new LeavePolicy("pol-1", "acme", "ANNUAL", "연차유급휴가 (기본)");
        policy.setMonthlyAccrualDays(BigDecimal.ONE);
        policy.setAnnualGrantDays(new BigDecimal("15"));
        policy.setAnnualGrantAfterYears(1);
        policy.setMaximumDays(new BigDecimal("25"));
        policy.setCarryOverExpiryMonths(12);
        policy.setMinimumBookableUnitDays(new BigDecimal("0.5"));
        return policy;
    }

    private static final class FakePolicies extends FakeRepository<LeavePolicy, String>
            implements LeavePolicyRepository {
        @Override
        String idOf(LeavePolicy entity) {
            return entity.id();
        }

        @Override
        public List<LeavePolicy> findByCompanyIdAndActiveTrueOrderByCodeAsc(String companyId) {
            List<LeavePolicy> found = new ArrayList<LeavePolicy>();
            for (LeavePolicy row : all()) {
                if (row.companyId().equals(companyId) && row.isActive()) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public Optional<LeavePolicy> findByCompanyIdAndCode(String companyId, String code) {
            for (LeavePolicy row : all()) {
                if (row.companyId().equals(companyId) && row.code().equals(code)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }
    }

    private static final class FakeIncrements
            extends FakeRepository<LeaveTenureIncrement, String>
            implements LeaveTenureIncrementRepository {
        @Override
        String idOf(LeaveTenureIncrement entity) {
            return entity.id();
        }

        @Override
        public List<LeaveTenureIncrement> findByPolicyIdOrderByAfterCompletedYearsAsc(
                String policyId) {
            List<LeaveTenureIncrement> found = new ArrayList<LeaveTenureIncrement>();
            for (LeaveTenureIncrement row : all()) {
                if (row.policyId().equals(policyId)) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<LeaveTenureIncrement> findByPolicyIdInOrderByAfterCompletedYearsAsc(
                Collection<String> policyIds) {
            List<LeaveTenureIncrement> found = new ArrayList<LeaveTenureIncrement>();
            for (LeaveTenureIncrement row : all()) {
                if (policyIds.contains(row.policyId())) {
                    found.add(row);
                }
            }
            return found;
        }
    }
}
