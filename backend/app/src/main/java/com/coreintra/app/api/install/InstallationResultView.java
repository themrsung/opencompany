package com.coreintra.app.api.install;

import com.coreintra.app.install.InstallationResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The installation, and the credentials that come with it — once.
 *
 * <p>{@link #getOtpauthUri()} and {@link #getRecoveryCodes()} appear in this
 * response and nowhere else in the system's life. The secret is kept encrypted
 * and the codes are kept as hashes, so no later request, no support session and
 * no master can read them back. That is the trade this product makes for having
 * no password reset flow, and it is why the field descriptions say so out loud.
 */
@Schema(description = "The opened installation. The credentials are shown exactly once.")
public class InstallationResultView {

    private final String companyId;
    private final String companyCode;
    private final String masterAccountId;
    private final String masterUsername;
    private final String secretBase32;
    private final String otpauthUri;
    private final List<String> recoveryCodes;
    private final List<String> bootstrapPermissions;
    private final List<String> approvalLineDocumentTypes;
    private final int defaultRowsWritten;

    public InstallationResultView(InstallationResult result) {
        this.companyId = result.companyId();
        this.companyCode = result.companyCode();
        this.masterAccountId = result.masterAccountId();
        this.masterUsername = result.masterUsername();
        this.secretBase32 = result.secretBase32();
        this.otpauthUri = result.otpauthUri();
        this.recoveryCodes = result.recoveryCodes();
        this.bootstrapPermissions = result.bootstrapPermissions();
        this.approvalLineDocumentTypes = result.approvalLineDocumentTypes();
        this.defaultRowsWritten = result.defaultRowsWritten();
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getCompanyCode() {
        return companyCode;
    }

    public String getMasterAccountId() {
        return masterAccountId;
    }

    public String getMasterUsername() {
        return masterUsername;
    }

    @Schema(description = "The TOTP secret, grouped in fours for manual entry. Shown once.")
    public String getSecretBase32() {
        return secretBase32;
    }

    @Schema(description = "Render as the QR code. Contains the secret: never log it. Shown once.")
    public String getOtpauthUri() {
        return otpauthUri;
    }

    @Schema(description = "Single-use recovery codes, in plaintext. Only hashes are stored, so "
            + "this is the only time they can be read. Shown once.")
    public List<String> getRecoveryCodes() {
        return recoveryCodes;
    }

    @Schema(description = "The grants the first master holds, as resource:action@scope. They are "
            + "wildcards because the grant service refuses to hand out a permission the grantor "
            + "does not hold; they cannot be re-granted, and they can be revoked.")
    public List<String> getBootstrapPermissions() {
        return bootstrapPermissions;
    }

    @Schema(description = "Document types that now have a company-wide 결재선.")
    public List<String> getApprovalLineDocumentTypes() {
        return approvalLineDocumentTypes;
    }

    @Schema(description = "Rows of factory catalogue written for the new company: the 직급 "
            + "ladder, the 직무 set, the attendance statuses and the 연차 policy.")
    public int getDefaultRowsWritten() {
        return defaultRowsWritten;
    }
}
