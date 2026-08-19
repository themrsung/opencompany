package com.coreintra.attendance.service;

/**
 * The per-company attendance choices that are policy rather than code.
 *
 * <p>An interface with one method today, and deliberately an interface: the
 * overlap rule is a decision an installation makes, and burying it in a constant
 * would make it a decision this codebase made on their behalf.
 *
 * <p>There is no settings table yet, so {@link #fixed} exists to wire an
 * installation-wide answer in one line until there is one.
 */
public interface AttendanceSettings {

    /** What to do when a new status overlaps an open one. */
    OverlapPolicy overlapPolicy(String companyId);

    /** The same answer for every company. For a single-tenant install, and for tests. */
    static AttendanceSettings fixed(final OverlapPolicy policy) {
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        return new AttendanceSettings() {
            @Override
            public OverlapPolicy overlapPolicy(String companyId) {
                return policy;
            }
        };
    }
}
