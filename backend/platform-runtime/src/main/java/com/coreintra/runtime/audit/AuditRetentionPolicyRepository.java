package com.coreintra.runtime.audit;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * The retention policy, per company.
 *
 * <p>Same reasoning as {@link AuditLogRepository}: the marker interface, and no
 * delete. Removing a company's retention policy row would let the next write
 * install a shorter one.
 */
public interface AuditRetentionPolicyRepository extends Repository<AuditRetentionPolicyRow, String> {

    AuditRetentionPolicyRow save(AuditRetentionPolicyRow row);

    Optional<AuditRetentionPolicyRow> findById(String companyId);
}
