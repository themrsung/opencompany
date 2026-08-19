package com.coreintra.app.install;

import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * What the installation produced, including the only copy of the credentials.
 *
 * <p>The secret and the recovery codes exist in this object, in the HTTP
 * response it becomes, and nowhere else: the TOTP secret is stored encrypted and
 * the recovery codes are stored only as hashes, so neither can be read back out
 * of the installation afterwards. Losing this response means recovering the box
 * from the database, which is the intended cost of having no reset flow.
 *
 * <p>Nothing here is logged. A log line carrying {@link #otpauthUri()} would
 * hand the master account to anyone who can read the log, which on a self-hosted
 * box is a larger set of people than it sounds.
 */
public final class InstallationResult {

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

    public InstallationResult(String companyId, String companyCode, String masterAccountId,
            String masterUsername, String secretBase32, String otpauthUri,
            List<String> recoveryCodes, List<String> bootstrapPermissions,
            List<String> approvalLineDocumentTypes, int defaultRowsWritten) {
        this.companyId = companyId;
        this.companyCode = companyCode;
        this.masterAccountId = masterAccountId;
        this.masterUsername = masterUsername;
        this.secretBase32 = secretBase32;
        this.otpauthUri = otpauthUri;
        this.recoveryCodes = Immutables.copyOf(recoveryCodes);
        this.bootstrapPermissions = Immutables.copyOf(bootstrapPermissions);
        this.approvalLineDocumentTypes = Immutables.copyOf(approvalLineDocumentTypes);
        this.defaultRowsWritten = defaultRowsWritten;
    }

    public String companyId() {
        return companyId;
    }

    public String companyCode() {
        return companyCode;
    }

    public String masterAccountId() {
        return masterAccountId;
    }

    public String masterUsername() {
        return masterUsername;
    }

    /** Grouped in fours, for someone typing it into an authenticator by hand. */
    public String secretBase32() {
        return secretBase32;
    }

    /** The QR code's contents. Carries the secret; never log it. */
    public String otpauthUri() {
        return otpauthUri;
    }

    /** Plaintext, shown once. Only hashes are kept. */
    public List<String> recoveryCodes() {
        return recoveryCodes;
    }

    /** The wildcard rows the master holds, so the response says what was handed over. */
    public List<String> bootstrapPermissions() {
        return bootstrapPermissions;
    }

    /** Document types that now have a company-wide 결재선. */
    public List<String> approvalLineDocumentTypes() {
        return approvalLineDocumentTypes;
    }

    /** Rows of factory catalogue written for the new company. */
    public int defaultRowsWritten() {
        return defaultRowsWritten;
    }
}
