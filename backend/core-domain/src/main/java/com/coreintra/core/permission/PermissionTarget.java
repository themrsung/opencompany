package com.coreintra.core.permission;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * The domain object a check is about.
 *
 * <p>Every decision in this system is made on the object, never on the URL.
 * That is what this type is for: a controller cannot ask "may this user call
 * this endpoint", only "may this user post <em>this entry</em>".
 *
 * <p>The {@link #asOfBusinessDate()} is load-bearing. A permission check on a
 * past-dated document resolves the org state as it was on that document's
 * business date, not as it is today — otherwise last quarter's approvals become
 * un-auditable the moment someone is promoted.
 */
public final class PermissionTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String companyId;
    private final String orgUnitId;
    private final String ownerEmployeeId;
    private final LocalDate asOfBusinessDate;
    private final String description;

    private PermissionTarget(Builder builder) {
        this.companyId = builder.companyId;
        this.orgUnitId = builder.orgUnitId;
        this.ownerEmployeeId = builder.ownerEmployeeId;
        this.asOfBusinessDate = builder.asOfBusinessDate;
        this.description = builder.description;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A target with no company, unit or owner — the installation as a whole.
     *
     * <p>Only an {@link PermissionScope#ALL} grant can reach it. Use for
     * genuinely global operations (installation settings), never as a shortcut
     * to avoid populating a real target.
     */
    public static PermissionTarget installationWide(LocalDate asOf) {
        return builder().asOfBusinessDate(asOf).description("installation").build();
    }

    public String companyId() {
        return companyId;
    }

    public String orgUnitId() {
        return orgUnitId;
    }

    /** The employee this row is about, for {@link PermissionScope#SELF}. */
    public String ownerEmployeeId() {
        return ownerEmployeeId;
    }

    /** The date the org state is resolved as of. Never null. */
    public LocalDate asOfBusinessDate() {
        return asOfBusinessDate;
    }

    /** Short human description, echoed into audit entries and the explainer. */
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "target[" + (description == null ? "" : description)
                + " company=" + companyId + " unit=" + orgUnitId
                + " owner=" + ownerEmployeeId + " asOf=" + asOfBusinessDate + "]";
    }

    public static final class Builder {
        private String companyId;
        private String orgUnitId;
        private String ownerEmployeeId;
        private LocalDate asOfBusinessDate;
        private String description;

        public Builder companyId(String value) {
            this.companyId = value;
            return this;
        }

        public Builder orgUnitId(String value) {
            this.orgUnitId = value;
            return this;
        }

        public Builder ownerEmployeeId(String value) {
            this.ownerEmployeeId = value;
            return this;
        }

        public Builder asOfBusinessDate(LocalDate value) {
            this.asOfBusinessDate = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public PermissionTarget build() {
            if (asOfBusinessDate == null) {
                // Defaulting silently would make every past-dated check resolve
                // against today's org chart, which is the exact bug this field
                // exists to prevent. Make the caller say so.
                throw new IllegalStateException(
                        "asOfBusinessDate is required. Pass the target document's business date; "
                                + "pass today's only when the check genuinely concerns today.");
            }
            return new PermissionTarget(this);
        }
    }
}
