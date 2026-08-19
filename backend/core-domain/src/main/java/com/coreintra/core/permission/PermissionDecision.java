package com.coreintra.core.permission;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a check, plus the reasoning that produced it.
 *
 * <p>The reasoning is not debug output. With grants arriving from four sources,
 * denies overriding allows, and scope resolved against a position as it stood
 * on a past date, "why can this user do this?" is not answerable by reading the
 * database by hand. The explainer endpoint returns exactly this structure, and
 * admins will use it constantly.
 */
public final class PermissionDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Why one grant did or did not settle the question. */
    public static final class Consideration implements Serializable {

        private static final long serialVersionUID = 1L;

        private final PermissionGrant grant;
        private final boolean applied;
        private final String reason;

        Consideration(PermissionGrant grant, boolean applied, String reason) {
            this.grant = grant;
            this.applied = applied;
            this.reason = reason;
        }

        public PermissionGrant grant() {
            return grant;
        }

        /** True when this grant reached the target and therefore counted. */
        public boolean applied() {
            return applied;
        }

        /** Plain-language explanation, suitable for the admin UI as-is. */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return (applied ? "[applied] " : "[skipped] ") + grant + " — " + reason;
        }
    }

    private final boolean allowed;
    private final PermissionKey key;
    private final PermissionTarget target;
    private final PermissionGrant decidingGrant;
    private final String summary;
    private final List<Consideration> considerations;

    private PermissionDecision(boolean allowed, PermissionKey key, PermissionTarget target,
            PermissionGrant decidingGrant, String summary, List<Consideration> considerations) {
        this.allowed = allowed;
        this.key = key;
        this.target = target;
        this.decidingGrant = decidingGrant;
        this.summary = summary;
        this.considerations = Immutables.copyOf(considerations);
    }

    static PermissionDecision allowed(PermissionKey key, PermissionTarget target,
            PermissionGrant deciding, List<Consideration> considerations) {
        return new PermissionDecision(true, key, target, deciding,
                "Allowed by " + describe(deciding), considerations);
    }

    static PermissionDecision denied(PermissionKey key, PermissionTarget target,
            PermissionGrant deciding, String summary, List<Consideration> considerations) {
        return new PermissionDecision(false, key, target, deciding, summary, considerations);
    }

    private static String describe(PermissionGrant grant) {
        String label = grant.sourceLabel() == null ? grant.sourceId() : grant.sourceLabel();
        return grant.source() + " " + label + " granting " + grant.key() + " at " + grant.scope();
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isDenied() {
        return !allowed;
    }

    public PermissionKey key() {
        return key;
    }

    public PermissionTarget target() {
        return target;
    }

    /** The grant that settled it, or null when nothing applied (deny by default). */
    public PermissionGrant decidingGrant() {
        return decidingGrant;
    }

    /** One sentence, safe to show a user. */
    public String summary() {
        return summary;
    }

    /** Every grant considered, in the order considered, with why each did or did not apply. */
    public List<Consideration> considerations() {
        return considerations;
    }

    /** Throws {@link PermissionDeniedException} unless allowed. */
    public PermissionDecision orThrow() {
        if (!allowed) {
            throw new PermissionDeniedException(this);
        }
        return this;
    }

    /** Multi-line explanation for logs and the explainer UI. */
    public String explain() {
        StringBuilder out = new StringBuilder();
        out.append(allowed ? "ALLOWED" : "DENIED").append(' ').append(key)
                .append(" on ").append(target).append('\n')
                .append("  ").append(summary).append('\n');
        if (considerations.isEmpty()) {
            out.append("  (no grant of this permission reaches this account)\n");
        }
        for (Consideration consideration : considerations) {
            out.append("  ").append(consideration).append('\n');
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return (allowed ? "ALLOW " : "DENY ") + key + " — " + summary;
    }

    /** Accumulates considerations while the evaluator works. */
    static final class Trace {
        private final List<Consideration> considerations = new ArrayList<Consideration>();

        void applied(PermissionGrant grant, String reason) {
            considerations.add(new Consideration(grant, true, reason));
        }

        void skipped(PermissionGrant grant, String reason) {
            considerations.add(new Consideration(grant, false, reason));
        }

        List<Consideration> considerations() {
            return considerations;
        }
    }
}
