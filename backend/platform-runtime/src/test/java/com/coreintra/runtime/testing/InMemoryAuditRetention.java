package com.coreintra.runtime.testing;

import com.coreintra.runtime.audit.AuditRetentionPolicyRepository;
import com.coreintra.runtime.audit.AuditRetentionPolicyRow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory retention policies. The upward-only rule lives in the row, not here. */
public final class InMemoryAuditRetention implements AuditRetentionPolicyRepository {

    private final Map<String, AuditRetentionPolicyRow> byCompany =
            new LinkedHashMap<String, AuditRetentionPolicyRow>();

    @Override
    public AuditRetentionPolicyRow save(AuditRetentionPolicyRow row) {
        byCompany.put(row.companyId(), row);
        return row;
    }

    @Override
    public Optional<AuditRetentionPolicyRow> findById(String companyId) {
        return Optional.ofNullable(byCompany.get(companyId));
    }
}
