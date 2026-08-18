package com.coreintra.app.api.support;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import com.coreintra.runtime.support.TemporaryMasterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What every user in the company sees while a vendor support session is live.
 *
 * <h2>Why this endpoint is not permission-gated</h2>
 *
 * <p>§8 requires that while a temporary master session is live, <b>every user in
 * the company</b> sees a persistent, non-dismissible banner naming who is
 * connected, which capabilities are live, and when it expires. That is an
 * oversight mechanism, and an oversight mechanism whose visibility depends on a
 * grant is one the overseen party can switch off — or, more likely, one that
 * quietly stops working when somebody tidies up a permission nobody could
 * explain. The failure mode is silent and points the wrong way: users would
 * simply stop being told.
 *
 * <p>So this endpoint is authenticated and scoped to the caller's own company,
 * and asks for no permission beyond that. It is a deliberate and narrow
 * exception to §10's "access is governed purely by the account's permissions",
 * and it is narrow in a specific sense: the payload contains only what the
 * banner needs, in end-user terms, about the caller's own company. It exposes
 * no data the session is touching. Revocation itself is permission-gated in the
 * ordinary way.
 */
@RestController
@RequestMapping("/api/v1/support/session")
@Tag(name = "Support session",
        description = "The live vendor-support banner. Visible to everyone in the company, "
                + "because an oversight notice that can be switched off is not oversight.")
public class SupportSessionController {

    /** Only a master may end a session early. Issuing has its own, heavier gate. */
    private static final PermissionKey REVOKE = PermissionKey.parse("support.temporary_master:revoke");

    private final TemporaryMasterService sessions;
    private final OrgDirectory org;
    private final PermissionEvaluator evaluator;
    private final CurrentPrincipal current;

    public SupportSessionController(TemporaryMasterService sessions, OrgDirectory org,
            PermissionEvaluator evaluator, CurrentPrincipal current) {
        this.sessions = sessions;
        this.org = org;
        this.evaluator = evaluator;
        this.current = current;
    }

    /** Exactly what the banner renders, and nothing else. */
    public static class LiveSession {
        private final String grantId;
        private final String engineerName;
        private final List<String> capabilities;
        private final String expiresAt;
        private final boolean revocableByYou;

        LiveSession(TemporaryMasterGrantRow grant, List<String> capabilities, boolean revocableByYou) {
            this.grantId = grant.id();
            this.engineerName = grant.engineerName();
            this.capabilities = Immutables.copyOf(capabilities);
            this.expiresAt = String.valueOf(grant.expiresAt());
            this.revocableByYou = revocableByYou;
        }

        public String getGrantId() {
            return grantId;
        }

        public String getEngineerName() {
            return engineerName;
        }

        /**
         * In end-user terms, not permission strings.
         *
         * <p>§8 is explicit: "read every employee's salary history", not
         * "{@code hr.compensation:read}". A banner nobody can parse is a banner
         * nobody reads.
         */
        public List<String> getCapabilities() {
            return capabilities;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        /** Drives whether the Revoke now button is shown. Any master may press it. */
        public boolean isRevocableByYou() {
            return revocableByYou;
        }
    }

    @GetMapping("/live")
    @Operation(summary = "Live support sessions in your company",
            description = "Empty almost always. When it is not, the UI must show a banner that "
                    + "cannot be dismissed.")
    public List<LiveSession> live() {
        PermissionPrincipal principal = current.require();
        LocalDate today = LocalDate.now();
        Set<String> companies = org.resolve(principal, today).companyIds();

        List<LiveSession> live = new ArrayList<LiveSession>();
        for (String companyId : companies) {
            boolean mayRevoke = evaluator.check(principal, REVOKE, PermissionTarget.builder()
                    .companyId(companyId)
                    .asOfBusinessDate(today)
                    .description("live support session")
                    .build()).isAllowed();

            for (TemporaryMasterGrantRow grant : sessions.activeSessions(companyId)) {
                if (grant.isActiveAt(OffsetDateTime.now())) {
                    live.add(new LiveSession(grant, SupportCapabilityWording.describe(grant),
                            mayRevoke));
                }
            }
        }
        return Immutables.copyOf(live);
    }
}
