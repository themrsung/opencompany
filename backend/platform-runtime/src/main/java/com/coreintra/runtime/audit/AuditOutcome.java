package com.coreintra.runtime.audit;

/**
 * How it ended.
 *
 * <p>{@link #DENIED} is recorded, not swallowed. A refusal is the most
 * interesting row in the table: it is the one that shows someone reaching for
 * something they were not given, and a log that only holds successes cannot
 * show it.
 */
public enum AuditOutcome {

    ALLOWED,

    /** The permission gate said no. */
    DENIED,

    /** Allowed, then broke. The attempt is still part of the record. */
    FAILED
}
