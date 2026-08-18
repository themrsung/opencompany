package com.coreintra.runtime.testing;

import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import com.coreintra.runtime.support.TemporaryMasterRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory grants. Note the absence of a delete, as in the interface itself. */
public final class InMemoryTemporaryMasterGrants implements TemporaryMasterRepository {

    private final Map<String, TemporaryMasterGrantRow> byId =
            new LinkedHashMap<String, TemporaryMasterGrantRow>();

    public TemporaryMasterGrantRow stored(String id) {
        return byId.get(id);
    }

    @Override
    public TemporaryMasterGrantRow save(TemporaryMasterGrantRow row) {
        byId.put(row.id(), row);
        return row;
    }

    @Override
    public Optional<TemporaryMasterGrantRow> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<TemporaryMasterGrantRow> findByAccountId(String accountId) {
        for (TemporaryMasterGrantRow row : byId.values()) {
            if (row.accountId().equals(accountId)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<TemporaryMasterGrantRow> findByCompanyIdAndRevokedAtIsNullAndExpiresAtAfter(
            String companyId, OffsetDateTime now) {
        List<TemporaryMasterGrantRow> live = new ArrayList<TemporaryMasterGrantRow>();
        for (TemporaryMasterGrantRow row : byId.values()) {
            if (row.companyId().equals(companyId) && row.revokedAt() == null
                    && row.expiresAt().isAfter(now)) {
                live.add(row);
            }
        }
        return live;
    }

    @Override
    public List<TemporaryMasterGrantRow> findByCompanyIdOrderByIssuedAtDesc(String companyId) {
        return forCompany(companyId);
    }

    @Override
    public List<TemporaryMasterGrantRow> findByCompanyIdAndSessionReportIdIsNull(String companyId) {
        List<TemporaryMasterGrantRow> unreported = new ArrayList<TemporaryMasterGrantRow>();
        for (TemporaryMasterGrantRow row : forCompany(companyId)) {
            if (row.sessionReportId() == null) {
                unreported.add(row);
            }
        }
        return unreported;
    }

    private List<TemporaryMasterGrantRow> forCompany(String companyId) {
        List<TemporaryMasterGrantRow> found = new ArrayList<TemporaryMasterGrantRow>();
        for (TemporaryMasterGrantRow row : byId.values()) {
            if (row.companyId().equals(companyId)) {
                found.add(row);
            }
        }
        return found;
    }
}
