package com.coreintra.runtime.support;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the issuer decided, on its way to becoming a row.
 *
 * <p>Everything §8 requires is checked here, before anything is written: the
 * bounded life, the typed company name, the quorum of named representatives,
 * and every capability individually. A half-valid support session is worse than
 * none, because the banner and the report both describe something that was
 * never actually agreed to.
 *
 * <p>The representation mode arrives as a string and a number rather than as
 * {@code RepresentationMode}. That type is the approval module's, and this
 * module does not depend on it. The quorum arithmetic it performs is repeated
 * here in the only form this module can verify: the number of distinct named
 * approvers must reach the number the mode required.
 */
public final class TemporaryMasterIssuance {

    public static final String MODE_SEVERAL = "SEVERAL";
    public static final String MODE_JOINT = "JOINT";

    private final String companyId;
    private final String accountId;
    private final String issuedByAccountId;
    private final String engineerName;
    private final String reason;
    private final String ticketReference;
    private final String typedCompanyName;
    private final OffsetDateTime issuedAt;
    private final BusinessInstant issuedOn;
    private final Duration timeToLive;
    private final String approvalDocumentId;
    private final String representationMode;
    private final int requiredApprovals;
    private final Set<String> capabilities;
    private final Set<TemporaryMasterApprover> approvers;

    private TemporaryMasterIssuance(Builder builder) {
        this.companyId = builder.companyId;
        this.accountId = builder.accountId;
        this.issuedByAccountId = builder.issuedByAccountId;
        this.engineerName = builder.engineerName;
        this.reason = builder.reason;
        this.ticketReference = builder.ticketReference;
        this.typedCompanyName = builder.typedCompanyName;
        this.issuedAt = builder.issuedAt;
        this.issuedOn = builder.issuedOn;
        this.timeToLive = builder.timeToLive;
        this.approvalDocumentId = builder.approvalDocumentId;
        this.representationMode = builder.representationMode;
        this.requiredApprovals = builder.requiredApprovals;
        this.capabilities = Immutables.setCopyOf(builder.capabilities);
        this.approvers = Immutables.setCopyOf(builder.approvers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String companyId() {
        return companyId;
    }

    public String accountId() {
        return accountId;
    }

    public String issuedByAccountId() {
        return issuedByAccountId;
    }

    public String engineerName() {
        return engineerName;
    }

    public String reason() {
        return reason;
    }

    public String ticketReference() {
        return ticketReference;
    }

    public String typedCompanyName() {
        return typedCompanyName;
    }

    public OffsetDateTime issuedAt() {
        return issuedAt;
    }

    public BusinessInstant issuedOn() {
        return issuedOn;
    }

    public Duration timeToLive() {
        return timeToLive;
    }

    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    public String representationMode() {
        return representationMode;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    public Set<String> capabilities() {
        return capabilities;
    }

    public Set<TemporaryMasterApprover> approvers() {
        return approvers;
    }

    public static final class Builder {

        private String companyId;
        private String companyName;
        private String accountId;
        private String issuedByAccountId;
        private String engineerName;
        private String reason;
        private String ticketReference;
        private String typedCompanyName;
        private OffsetDateTime issuedAt;
        private BusinessInstant issuedOn;
        private Duration timeToLive = TemporaryMasterGrantRow.DEFAULT_TTL;
        private String approvalDocumentId;
        private String representationMode;
        private int requiredApprovals;
        private final Set<String> capabilities = new LinkedHashSet<String>();
        private final Set<TemporaryMasterApprover> approvers =
                new LinkedHashSet<TemporaryMasterApprover>();

        public Builder company(String id, String name) {
            this.companyId = id;
            this.companyName = name;
            return this;
        }

        /** The TEMPORARY_MASTER account the engineer will sign in as. */
        public Builder accountId(String value) {
            this.accountId = value;
            return this;
        }

        public Builder issuedByAccountId(String value) {
            this.issuedByAccountId = value;
            return this;
        }

        public Builder engineerName(String value) {
            this.engineerName = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder ticketReference(String value) {
            this.ticketReference = value;
            return this;
        }

        /** What the issuer actually typed. Compared, never defaulted. */
        public Builder typedCompanyName(String value) {
            this.typedCompanyName = value;
            return this;
        }

        public Builder issuedAt(OffsetDateTime value) {
            this.issuedAt = value;
            return this;
        }

        public Builder issuedOn(BusinessInstant value) {
            this.issuedOn = value;
            return this;
        }

        public Builder timeToLive(Duration value) {
            this.timeToLive = value;
            return this;
        }

        public Builder approval(String documentId, String mode, int requiredApprovals) {
            this.approvalDocumentId = documentId;
            this.representationMode = mode;
            this.requiredApprovals = requiredApprovals;
            return this;
        }

        /** Ticks one capability. @throws IllegalArgumentException for a wildcard. */
        public Builder capability(String value) {
            capabilities.add(TemporaryMasterCapabilities.validate(value));
            return this;
        }

        public Builder approvedBy(String accountId, String name) {
            approvers.add(new TemporaryMasterApprover(accountId, name));
            return this;
        }

        public TemporaryMasterIssuance build() {
            if (Texts.isBlank(companyId) || Texts.isBlank(companyName)) {
                throw new IllegalArgumentException("a support session belongs to a named company");
            }
            if (Texts.isBlank(accountId) || Texts.isBlank(issuedByAccountId)) {
                throw new IllegalArgumentException(
                        "a support session needs the account it grants and the master who issued it");
            }
            if (Texts.isBlank(engineerName)) {
                throw new IllegalArgumentException(
                        "the engineer is named in the banner every user sees");
            }
            if (Texts.isBlank(reason)) {
                throw new IllegalArgumentException(
                        "a temporary master account needs a reason. It appears in the audit "
                                + "trail and in the banner every user in the company sees.");
            }
            if (issuedAt == null || issuedOn == null) {
                throw new IllegalArgumentException(
                        "issuance is stamped in both business time and UTC; neither substitutes "
                                + "for the other");
            }
            if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
                throw new IllegalArgumentException(
                        "a temporary master account needs a time to live; there is no open-ended "
                                + "grant. Default is " + TemporaryMasterGrantRow.DEFAULT_TTL.toHours()
                                + " hours.");
            }
            if (timeToLive.compareTo(TemporaryMasterGrantRow.MAX_TTL) > 0) {
                throw new IllegalArgumentException(
                        "a temporary master account may last at most "
                                + TemporaryMasterGrantRow.MAX_TTL.toHours() + " hours. There is no "
                                + "extension: issue a new one, which means a new approval and a "
                                + "new reason.");
            }
            if (!companyName.equals(typedCompanyName)) {
                throw new IllegalArgumentException(
                        "the company name must be typed exactly to confirm issuance. Expected \""
                                + companyName + "\". This step is deliberately not a checkbox.");
            }
            if (Texts.isBlank(approvalDocumentId)) {
                throw new IllegalArgumentException(
                        "issuing a temporary master account requires representative approval "
                                + "under the company's representation mode");
            }
            if (!MODE_SEVERAL.equals(representationMode) && !MODE_JOINT.equals(representationMode)) {
                throw new IllegalArgumentException(
                        "representation mode must be " + MODE_SEVERAL + " or " + MODE_JOINT
                                + "; got " + representationMode);
            }
            if (requiredApprovals < 1) {
                throw new IllegalArgumentException("a quorum of fewer than one is not a quorum");
            }
            if (MODE_JOINT.equals(representationMode) && requiredApprovals < 2) {
                throw new IllegalArgumentException(
                        "joint representation requires at least two approvals; a quorum of "
                                + requiredApprovals + " would be satisfiable by a single "
                                + "representative, which is several representation under another "
                                + "name");
            }
            if (approvers.size() < requiredApprovals) {
                throw new IllegalArgumentException(
                        "issuance was approved by " + approvers.size() + " representative(s) but "
                                + representationMode + " requires " + requiredApprovals
                                + ". Approvals are counted by person: two approvals from one "
                                + "person is one approval.");
            }
            return new TemporaryMasterIssuance(this);
        }
    }
}
