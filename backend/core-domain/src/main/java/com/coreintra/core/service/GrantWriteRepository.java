package com.coreintra.core.service;

import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * The grant book, from the writing side.
 *
 * <p>{@code PermissionGrantRepository} reads every grant reaching a principal in
 * one statement, which is the hot path. Granting is the cold path and asks a
 * different question: what is already attached to this one rank / 직무 / unit /
 * account, so that {@code permission_grant_unique} is not discovered by a
 * constraint violation, and so the explainer never shows the same grant twice.
 *
 * <p>There is no {@code delete} here, deliberately. Revoking a grant sets
 * {@code revoked_at} and saves; nothing in this module removes a row. The
 * evaluator filters revoked grants out, the explainer keeps showing them, and
 * uniqueness applies to live grants only so a revoked permission can be granted
 * again (see {@code V11__permission_grant_revocation.sql}).
 */
public interface GrantWriteRepository extends Repository<PermissionGrantRow, String> {

    <S extends PermissionGrantRow> S save(S row);

    Optional<PermissionGrantRow> findById(String id);

    List<PermissionGrantRow> findBySourceAndSourceId(GrantSource source, String sourceId);
}
