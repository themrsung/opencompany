package com.coreintra.runtime.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads and one write.
 *
 * <p>This deliberately extends {@link Repository}, the empty marker, rather
 * than {@code JpaRepository}. {@code JpaRepository} would inherit
 * {@code delete}, {@code deleteAll} and {@code deleteById} for free, and §12
 * says no account can remove a trail - master included. A trigger in the
 * database refuses the SQL, but an inherited {@code deleteAll()} would still
 * compile, still be callable from any service in the tree, and would fail at
 * runtime as an error rather than at review as an argument. The method is
 * absent instead.
 *
 * <p>Every finder is scoped by company. A support engineer reading an audit log
 * is the situation §8 exists for; a cross-tenant query here would be the first
 * thing to abuse.
 */
public interface AuditLogRepository extends Repository<AuditLogRow, String> {

    AuditLogRow save(AuditLogRow row);

    Optional<AuditLogRow> findById(String id);

    Page<AuditLogRow> findByCompanyIdOrderByCreatedAtDesc(String companyId, Pageable pageable);

    List<AuditLogRow> findByCompanyIdAndResourceAndResourceIdOrderByCreatedAtDesc(
            String companyId, String resource, String resourceId);

    /** The support-session report: every action taken under one grant. */
    List<AuditLogRow> findByTemporaryMasterGrantIdOrderByCreatedAtAsc(String grantId);

    List<AuditLogRow> findByCompanyIdAndOccurredAtBusinessDateBetweenOrderByCreatedAtDesc(
            String companyId, LocalDate from, LocalDate to);

    long countByCompanyId(String companyId);
}
