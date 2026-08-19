package com.coreintra.runtime.support;

import org.springframework.data.repository.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Grants, and the switch.
 *
 * <p>No delete, deliberately, as with the audit log: a session that ended must
 * remain readable, because "there was no support session" and "the record of it
 * is gone" must not look the same to a client reading their own history.
 */
public interface TemporaryMasterRepository extends Repository<TemporaryMasterGrantRow, String> {

    TemporaryMasterGrantRow save(TemporaryMasterGrantRow row);

    Optional<TemporaryMasterGrantRow> findById(String id);

    Optional<TemporaryMasterGrantRow> findByAccountId(String accountId);

    /**
     * Sessions that have not been revoked and have not expired yet.
     *
     * <p>The banner asks this on every page load, which is why it is a query
     * rather than a scan with filtering in Java.
     */
    List<TemporaryMasterGrantRow> findByCompanyIdAndRevokedAtIsNullAndExpiresAtAfter(
            String companyId, OffsetDateTime now);

    List<TemporaryMasterGrantRow> findByCompanyIdOrderByIssuedAtDesc(String companyId);

    /** Sessions whose report has not been produced yet. */
    List<TemporaryMasterGrantRow> findByCompanyIdAndSessionReportIdIsNull(String companyId);
}
