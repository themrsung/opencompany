package com.coreintra.runtime.audit;

/**
 * Who acted. Not a label on the actor, but the kind of authority the action was
 * taken under.
 *
 * <p>The distinction that matters is {@link #TEMPORARY_MASTER}: an action taken
 * under a support session is the one a client will go looking for, and it has
 * to be findable without knowing which person the vendor happened to send.
 */
public enum AuditActorKind {

    /** An employee, acting as themselves. */
    USER,

    /** A company master, acting under standing god-rights. */
    MASTER,

    /** A vendor support session. Time-boxed, capability-ticked, reads logged. */
    TEMPORARY_MASTER,

    /** A client module or integration holding a scoped API key. */
    SERVICE_ACCOUNT,

    /**
     * A scheduled job. It runs as a named service account whose grants can be
     * inspected like anyone else's - there is no privileged internal caller.
     */
    SCHEDULED_JOB,

    /** An in-process module acting inside the application process. */
    MODULE,

    /**
     * Nobody yet. A sign-in attempt that never resolved to an account still
     * happened, and a trail that only records successes is a trail that cannot
     * show an attack.
     */
    ANONYMOUS
}
