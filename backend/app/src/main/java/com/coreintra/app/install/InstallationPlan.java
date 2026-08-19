package com.coreintra.app.install;

import com.coreintra.approval.domain.RepresentationMode;
import java.time.LocalDate;

/**
 * What the installer is being asked to create.
 *
 * <p>A value rather than eleven parameters, and deliberately small: the first
 * company, the first master, and how that company's 대표 authority is
 * exercised. Everything else an installation needs — units, people, positions,
 * books — is ordinary work for the master to do afterwards through the API,
 * and putting any of it here would mean an installer that has to be edited
 * whenever the org model grows.
 *
 * <p>There is no password field, and no field that could become one.
 */
public final class InstallationPlan {

    private final String companyCode;
    private final String companyNameKo;
    private final String companyNameEn;
    private final String businessRegistrationNumber;
    private final String baseCurrencyCode;
    private final LocalDate establishedOn;
    private final String masterUsername;
    private final String masterDisplayName;
    private final String representationMode;
    private final int requiredApprovals;
    private final int designatedRepresentatives;

    private InstallationPlan(Builder builder) {
        this.companyCode = builder.companyCode;
        this.companyNameKo = builder.companyNameKo;
        this.companyNameEn = builder.companyNameEn;
        this.businessRegistrationNumber = builder.businessRegistrationNumber;
        this.baseCurrencyCode = builder.baseCurrencyCode;
        this.establishedOn = builder.establishedOn;
        this.masterUsername = builder.masterUsername;
        this.masterDisplayName = builder.masterDisplayName;
        this.representationMode = builder.representationMode;
        this.requiredApprovals = builder.requiredApprovals;
        this.designatedRepresentatives = builder.designatedRepresentatives;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String companyCode() {
        return companyCode;
    }

    public String companyNameKo() {
        return companyNameKo;
    }

    public String companyNameEn() {
        return companyNameEn;
    }

    public String businessRegistrationNumber() {
        return businessRegistrationNumber;
    }

    public String baseCurrencyCode() {
        return baseCurrencyCode;
    }

    public LocalDate establishedOn() {
        return establishedOn;
    }

    public String masterUsername() {
        return masterUsername;
    }

    public String masterDisplayName() {
        return masterDisplayName;
    }

    /** {@code SEVERAL} (각자대표) or {@code JOINT} (공동대표). */
    public String representationMode() {
        return representationMode;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    public int designatedRepresentatives() {
        return designatedRepresentatives;
    }

    public static final class Builder {

        private String companyCode;
        private String companyNameKo;
        private String companyNameEn;
        private String businessRegistrationNumber;
        private String baseCurrencyCode;
        private LocalDate establishedOn;
        private String masterUsername;
        private String masterDisplayName;
        // 각자대표 with a single 대표 is the arrangement a newly incorporated
        // company has unless it says otherwise, and it is the one that leaves
        // the fewest documents stuck. Changing it is one call.
        private String representationMode = RepresentationMode.Kind.SEVERAL.name();
        private int requiredApprovals = 1;
        private int designatedRepresentatives = 1;

        public Builder company(String code, String nameKo, String nameEn) {
            this.companyCode = code;
            this.companyNameKo = nameKo;
            this.companyNameEn = nameEn;
            return this;
        }

        public Builder registration(String businessRegistrationNumber, String baseCurrencyCode,
                LocalDate establishedOn) {
            this.businessRegistrationNumber = businessRegistrationNumber;
            this.baseCurrencyCode = baseCurrencyCode;
            this.establishedOn = establishedOn;
            return this;
        }

        public Builder master(String username, String displayName) {
            this.masterUsername = username;
            this.masterDisplayName = displayName;
            return this;
        }

        public Builder representation(String mode, int requiredApprovals,
                int designatedRepresentatives) {
            this.representationMode = mode;
            this.requiredApprovals = requiredApprovals;
            this.designatedRepresentatives = designatedRepresentatives;
            return this;
        }

        public InstallationPlan build() {
            return new InstallationPlan(this);
        }
    }
}
