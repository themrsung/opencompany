package com.coreintra.runtime.audit;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;

import java.io.Serializable;

/**
 * One thing that happened, as the caller describes it.
 *
 * <p>Immutable, and validated at construction, because the alternative is a
 * half-filled audit row written in a catch block at three in the morning. If a
 * caller cannot say who acted and under which capability, the answer is to fail
 * the call, not to write "unknown" into the only record of it.
 *
 * <p>The before/after documents are JSON supplied by the caller and stored
 * verbatim. This module does not parse them: it has no opinion about the shape
 * of another module's rows, and a parser here would become a schema everyone
 * has to satisfy.
 */
public final class AuditEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String companyId;
    private final String actorAccountId;
    private final AuditActorKind actorKind;
    private final String actorDisplayName;
    private final String capability;
    private final String resource;
    private final String resourceId;
    private final AuditAction action;
    private final AuditOutcome outcome;
    private final int rowsTouched;
    private final String beforeJson;
    private final String afterJson;
    private final BusinessInstant occurredAt;
    private final String requestId;
    private final String ipAddress;
    private final String userAgent;
    private final String temporaryMasterGrantId;

    private AuditEvent(Builder builder) {
        this.companyId = builder.companyId;
        this.actorAccountId = builder.actorAccountId;
        this.actorKind = builder.actorKind;
        this.actorDisplayName = builder.actorDisplayName;
        this.capability = builder.capability;
        this.resource = builder.resource;
        this.resourceId = builder.resourceId;
        this.action = builder.action;
        this.outcome = builder.outcome;
        this.rowsTouched = builder.rowsTouched;
        this.beforeJson = builder.beforeJson;
        this.afterJson = builder.afterJson;
        this.occurredAt = builder.occurredAt;
        this.requestId = builder.requestId;
        this.ipAddress = builder.ipAddress;
        this.userAgent = builder.userAgent;
        this.temporaryMasterGrantId = builder.temporaryMasterGrantId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String companyId() {
        return companyId;
    }

    /** Null only for an attempt that never resolved to an account. */
    public String actorAccountId() {
        return actorAccountId;
    }

    public AuditActorKind actorKind() {
        return actorKind;
    }

    /** Snapshotted: the name they had when they acted, not the name they have. */
    public String actorDisplayName() {
        return actorDisplayName;
    }

    /** The permission actually used, in {@code resource:action} form. */
    public String capability() {
        return capability;
    }

    public String resource() {
        return resource;
    }

    public String resourceId() {
        return resourceId;
    }

    public AuditAction action() {
        return action;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    /** How many rows this touched. One and four thousand are different events. */
    public int rowsTouched() {
        return rowsTouched;
    }

    public String beforeJson() {
        return beforeJson;
    }

    public String afterJson() {
        return afterJson;
    }

    /** When the organisation agrees this happened. */
    public BusinessInstant occurredAt() {
        return occurredAt;
    }

    public String requestId() {
        return requestId;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Set for everything done under a support session. Null otherwise. */
    public String temporaryMasterGrantId() {
        return temporaryMasterGrantId;
    }

    public static final class Builder {

        private String companyId;
        private String actorAccountId;
        private AuditActorKind actorKind;
        private String actorDisplayName;
        private String capability;
        private String resource;
        private String resourceId;
        private AuditAction action;
        private AuditOutcome outcome = AuditOutcome.ALLOWED;
        private int rowsTouched;
        private String beforeJson;
        private String afterJson;
        private BusinessInstant occurredAt;
        private String requestId;
        private String ipAddress;
        private String userAgent;
        private String temporaryMasterGrantId;

        public Builder companyId(String value) {
            this.companyId = value;
            return this;
        }

        public Builder actor(String accountId, AuditActorKind kind, String displayName) {
            this.actorAccountId = accountId;
            this.actorKind = kind;
            this.actorDisplayName = displayName;
            return this;
        }

        public Builder capability(String value) {
            this.capability = value;
            return this;
        }

        public Builder target(String resource, String resourceId) {
            this.resource = resource;
            this.resourceId = resourceId;
            return this;
        }

        public Builder action(AuditAction value) {
            this.action = value;
            return this;
        }

        public Builder outcome(AuditOutcome value) {
            this.outcome = value;
            return this;
        }

        public Builder rowsTouched(int value) {
            this.rowsTouched = value;
            return this;
        }

        public Builder before(String json) {
            this.beforeJson = json;
            return this;
        }

        public Builder after(String json) {
            this.afterJson = json;
            return this;
        }

        public Builder occurredAt(BusinessInstant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder request(String requestId, String ipAddress, String userAgent) {
            this.requestId = requestId;
            this.ipAddress = ipAddress;
            this.userAgent = userAgent;
            return this;
        }

        public Builder underTemporaryMaster(String grantId) {
            this.temporaryMasterGrantId = grantId;
            return this;
        }

        public AuditEvent build() {
            if (Texts.isBlank(companyId)) {
                throw new IllegalArgumentException("an audit row belongs to a company");
            }
            if (actorKind == null) {
                throw new IllegalArgumentException("an audit row needs an actor kind");
            }
            if (Texts.isBlank(actorDisplayName)) {
                throw new IllegalArgumentException(
                        "an audit row needs the actor's display name as it was at the time; "
                                + "reading it back later shows the name they have now");
            }
            if (actorKind != AuditActorKind.ANONYMOUS && Texts.isBlank(actorAccountId)) {
                throw new IllegalArgumentException(
                        "only an ANONYMOUS actor may have no account; everything else has "
                                + "someone to name, because \"the system did it\" is the "
                                + "sentence this log exists to prevent");
            }
            if (Texts.isBlank(capability)) {
                throw new IllegalArgumentException(
                        "an audit row records the capability the action was taken under");
            }
            if (Texts.isBlank(resource)) {
                throw new IllegalArgumentException("an audit row needs a resource");
            }
            if (action == null || outcome == null) {
                throw new IllegalArgumentException("an audit row needs an action and an outcome");
            }
            if (occurredAt == null) {
                throw new IllegalArgumentException(
                        "an audit row is stamped with a business instant; the UTC clock time "
                                + "is recorded separately and is not a substitute");
            }
            if (rowsTouched < 0) {
                throw new IllegalArgumentException("rows touched cannot be negative");
            }
            return new AuditEvent(this);
        }
    }
}
