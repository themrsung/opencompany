package com.coreintra.runtime.ratelimit;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** Policies are retired, not deleted, so "what applied last Tuesday" stays answerable. */
public interface RateLimitPolicyRepository extends Repository<RateLimitPolicyRow, String> {

    RateLimitPolicyRow save(RateLimitPolicyRow row);

    Optional<RateLimitPolicyRow> findById(String id);

    Optional<RateLimitPolicyRow> findBySubjectKindAndSubjectIdAndLimitKeyAndRetiredAtIsNull(
            String subjectKind, String subjectId, String limitKey);

    Optional<RateLimitPolicyRow> findBySubjectKindAndSubjectIdIsNullAndLimitKeyAndRetiredAtIsNull(
            String subjectKind, String limitKey);

    List<RateLimitPolicyRow> findByRetiredAtIsNull();
}
