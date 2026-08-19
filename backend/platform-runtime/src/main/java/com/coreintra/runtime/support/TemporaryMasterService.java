package com.coreintra.runtime.support;

import com.coreintra.compat.Optionals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Issues, checks and ends support sessions.
 *
 * <p>Every method that grants access takes the time, and no method extends a
 * session. Those two properties are the whole feature: a support session is a
 * deadline that happens to carry some capabilities, not a set of capabilities
 * that happens to have a deadline.
 *
 * <p>What this class does <em>not</em> do is decide whether the issuance was
 * properly approved. The representation-mode quorum is the approval module's
 * arithmetic, and the typed company name is the auth module's issuance screen.
 * Both arrive already decided, in {@link TemporaryMasterIssuance}, and are
 * re-checked here in the terms a persistence layer can verify.
 */
@Service
public class TemporaryMasterService {

    private final TemporaryMasterRepository grants;
    private final TemporaryMasterSwitchRepository switches;
    private final Clock clock;
    private final boolean disabledByConfiguration;

    public TemporaryMasterService(
            TemporaryMasterRepository grants,
            TemporaryMasterSwitchRepository switches,
            Clock clock,
            @Value("${coreintra.support.temporary-master-disabled:false}") boolean disabledByConfiguration) {
        this.grants = grants;
        this.switches = switches;
        this.clock = clock;
        this.disabledByConfiguration = disabledByConfiguration;
    }

    /**
     * Records an approved issuance.
     *
     * @throws IllegalStateException if this installation has switched vendor
     *         sessions off - permanently by the kill switch, or for this boot by
     *         configuration
     */
    @Transactional
    public TemporaryMasterGrantRow issue(TemporaryMasterIssuance issuance) {
        if (disabledByConfiguration) {
            throw new IllegalStateException(
                    "temporary master issuance is disabled for this installation by "
                            + "configuration (coreintra.support.temporary-master-disabled)");
        }
        if (issuanceDisabled()) {
            throw new IllegalStateException(
                    "temporary master issuance has been permanently disabled for this "
                            + "installation. It cannot be re-enabled from the application (§8).");
        }
        String id = UUID.randomUUID().toString();
        return grants.save(new TemporaryMasterGrantRow(id, issuance, OffsetDateTime.now(clock)));
    }

    /**
     * The permission question, answered for a support account.
     *
     * <p>An account with no grant is not a support session and gets nothing. A
     * grant with nothing ticked can read nothing - not one row, not the
     * company's own name - which is the §13 acceptance test, and is why this
     * returns false rather than falling back to master rights.
     */
    @Transactional(readOnly = true)
    public boolean allows(String supportAccountId, String capability) {
        return allowsAt(supportAccountId, capability, OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public boolean allowsAt(String supportAccountId, String capability, OffsetDateTime now) {
        return Optionals.stream(grants.findByAccountId(supportAccountId))
                .anyMatch(grant -> grant.allowsAt(capability, now));
    }

    /** The grant a support account is signed in under, or null if it has none. */
    @Transactional(readOnly = true)
    public TemporaryMasterGrantRow grantFor(String supportAccountId) {
        return grants.findByAccountId(supportAccountId).orElse(null);
    }

    /**
     * A grant by its own id, or null.
     *
     * <p>Separate from {@link #grantFor(String)} because the two callers ask
     * opposite questions. The request path asks "is this account a live support
     * session", thousands of times a minute, and is answered from the account
     * index. Revoking and reporting ask "which session is this", rarely, and
     * pay a primary-key lookup rather than making the hot path carry a second
     * index it never uses.
     */
    @Transactional(readOnly = true)
    public TemporaryMasterGrantRow grantById(String grantId) {
        return grants.findById(grantId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<TemporaryMasterGrantRow> activeSessions(String companyId) {
        return grants.findByCompanyIdAndRevokedAtIsNullAndExpiresAtAfter(
                companyId, OffsetDateTime.now(clock));
    }

    /**
     * Ends a session now. Any master can do this; the button is in the banner.
     *
     * <p>Expiry is checked, never swept, so a background job is not required for
     * correctness. Revocation is what makes "Revoke now" immediate, and it is
     * the reason such a job is still worth having: killing a live session
     * promptly rather than at the next request.
     */
    @Transactional
    public void revoke(String grantId, String byAccountId) {
        TemporaryMasterGrantRow grant = grants.findById(grantId).orElseThrow(
                () -> new IllegalArgumentException("no such support session: " + grantId));
        grant.revoke(byAccountId, OffsetDateTime.now(clock));
        grants.save(grant);
    }

    @Transactional
    public void attachSessionReport(String grantId, String reportId) {
        TemporaryMasterGrantRow grant = grants.findById(grantId).orElseThrow(
                () -> new IllegalArgumentException("no such support session: " + grantId));
        grant.attachSessionReport(reportId);
        grants.save(grant);
    }

    @Transactional(readOnly = true)
    public boolean issuanceDisabled() {
        return switches.findById(TemporaryMasterSwitchRow.INSTALLATION)
                .map(TemporaryMasterSwitchRow::issuanceDisabled)
                .orElse(Boolean.FALSE);
    }

    /**
     * The kill switch. One way, on purpose.
     *
     * <p>There is no counterpart that turns it back on: see
     * {@link TemporaryMasterSwitchRow#disableIssuance}.
     */
    @Transactional
    public void disableIssuanceForInstallation(String byAccountId, String reason) {
        TemporaryMasterSwitchRow row = switches.findById(TemporaryMasterSwitchRow.INSTALLATION)
                .orElseThrow(() -> new IllegalStateException(
                        "the temporary master switch row is seeded by the migration and is "
                                + "missing; the schema is not the one this build expects"));
        row.disableIssuance(byAccountId, reason, OffsetDateTime.now(clock));
        switches.save(row);
    }
}
