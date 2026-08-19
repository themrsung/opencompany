package com.coreintra.approval.service;

import com.coreintra.approval.rules.EmploymentRules;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for 취업규칙 versions and their acknowledgement receipts.
 *
 * <p>A port because there is <b>no {@code employment_rules} table</b>. V4 and V5
 * do not create one and neither does any later migration; the domain type exists
 * and is fully tested, but nothing stores it. Writing that migration was not in
 * this layer's remit, so the shape it needs is stated here instead — see the
 * report accompanying this work.
 *
 * <h2>Versioned supersession, never update</h2>
 *
 * <p>An amendment is a new version with a later {@code effectiveFrom}, not an
 * edit of the previous one. Employees must be able to see the text that applied
 * on any given date — including a date in the past, during a dispute — and an
 * in-place update destroys exactly that. A repeal is likewise a new version.
 *
 * <p>Acknowledgements are per employee <em>per version</em> for the same reason:
 * having read the 2024 rules says nothing about having read the 2026 ones.
 */
public interface EmploymentRulesStore {

    /** Every version for a company, oldest first. */
    List<EmploymentRules> findAllVersions(String companyId);

    /**
     * The version in force on a date.
     *
     * <p>The latest version whose {@code effectiveFrom} is on or before
     * {@code on} — not simply the newest, or a rule taking effect next month
     * would appear to bind people today.
     */
    Optional<EmploymentRules> findEffectiveOn(String companyId, LocalDate on);

    /** The next version number for a company. 1 for its first. */
    int nextVersionNumber(String companyId);

    /**
     * Stores a published version.
     *
     * <p>Only ever called with an {@link EmploymentRules} produced by
     * {@link EmploymentRules#publish}, which is the only constructor there is
     * and which cannot be reached without a completed 대표자 결재.
     */
    void save(EmploymentRules rules);

    /** Records that an employee has read a version. Idempotent. */
    void acknowledge(String rulesId, String employeeId, LocalDate acknowledgedOn);

    /** Employee ids that have acknowledged a version. */
    List<String> acknowledgedBy(String rulesId);
}
