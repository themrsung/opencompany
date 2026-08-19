package com.coreintra.runtime.testing;

import com.coreintra.runtime.audit.AuditLogRepository;
import com.coreintra.runtime.audit.AuditLogRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An in-memory audit log for unit tests.
 *
 * <p>This module cannot reach {@code DatabaseTestSupport}, which lives in
 * {@code app}'s test sources, so the services are exercised against fakes of
 * their own repositories. That is not a compromise here: the invariants under
 * test - a denial is still recorded, a session trail is complete - are about
 * what the service decides to write, not about SQL. The SQL half is held by the
 * trigger, and by the integration tests in {@code app}.
 */
public final class InMemoryAuditLog implements AuditLogRepository {

    private final List<AuditLogRow> rows = new ArrayList<AuditLogRow>();

    public List<AuditLogRow> rows() {
        return rows;
    }

    @Override
    public AuditLogRow save(AuditLogRow row) {
        rows.add(row);
        return row;
    }

    @Override
    public Optional<AuditLogRow> findById(String id) {
        for (AuditLogRow row : rows) {
            if (row.id().equals(id)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    @Override
    public Page<AuditLogRow> findByCompanyIdOrderByCreatedAtDesc(String companyId, Pageable pageable) {
        return new PageImpl<AuditLogRow>(forCompany(companyId));
    }

    @Override
    public List<AuditLogRow> findByCompanyIdAndResourceAndResourceIdOrderByCreatedAtDesc(
            String companyId, String resource, String resourceId) {
        List<AuditLogRow> found = new ArrayList<AuditLogRow>();
        for (AuditLogRow row : forCompany(companyId)) {
            if (row.resource().equals(resource)
                    && (resourceId == null ? row.resourceId() == null
                        : resourceId.equals(row.resourceId()))) {
                found.add(row);
            }
        }
        return found;
    }

    @Override
    public List<AuditLogRow> findByTemporaryMasterGrantIdOrderByCreatedAtAsc(String grantId) {
        List<AuditLogRow> found = new ArrayList<AuditLogRow>();
        for (AuditLogRow row : rows) {
            if (grantId.equals(row.temporaryMasterGrantId())) {
                found.add(row);
            }
        }
        return found;
    }

    @Override
    public List<AuditLogRow> findByCompanyIdAndOccurredAtBusinessDateBetweenOrderByCreatedAtDesc(
            String companyId, LocalDate from, LocalDate to) {
        List<AuditLogRow> found = new ArrayList<AuditLogRow>();
        for (AuditLogRow row : forCompany(companyId)) {
            LocalDate on = row.occurredAt().businessDate();
            if (!on.isBefore(from) && !on.isAfter(to)) {
                found.add(row);
            }
        }
        return found;
    }

    @Override
    public long countByCompanyId(String companyId) {
        return forCompany(companyId).size();
    }

    private List<AuditLogRow> forCompany(String companyId) {
        List<AuditLogRow> found = new ArrayList<AuditLogRow>();
        for (AuditLogRow row : rows) {
            if (row.companyId().equals(companyId)) {
                found.add(row);
            }
        }
        return found;
    }
}
