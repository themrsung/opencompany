package com.coreintra.app.api.permission;

import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionExplainerService;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The effective-permissions explainer.
 *
 * <p>Two endpoints, both of which admins will use constantly:
 * <ul>
 *   <li>{@code GET .../accounts/{id}/effective-permissions} — what can this
 *       account do, as of a date;</li>
 *   <li>{@code GET .../accounts/{id}/permission-check} — why did this one
 *       decision come out the way it did, with the full grant chain.</li>
 * </ul>
 *
 * <p>The second returns 200 for a denial rather than 403. A denial is the
 * <em>answer</em> here, not a failure of the request — an explainer that
 * refused to explain denials would be useless for the case admins actually ask
 * about. Whether the <em>caller</em> may inspect this subject is a separate
 * check, and that one does return 403.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PermissionExplainerController {

    private final PermissionExplainerService explainer;
    private final CurrentPrincipal currentPrincipal;

    public PermissionExplainerController(PermissionExplainerService explainer,
            CurrentPrincipal currentPrincipal) {
        this.explainer = explainer;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/accounts/{accountId}/effective-permissions")
    public EffectivePermissionsResponse effectivePermissions(
            @PathVariable String accountId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate resolved = asOf == null ? LocalDate.now() : asOf;
        EffectivePermissions effective = explainer.explainAccount(caller, accountId, resolved);

        List<GrantView> grants = new ArrayList<GrantView>();
        for (PermissionGrant grant : effective.grants()) {
            grants.add(GrantView.from(grant));
        }
        return new EffectivePermissionsResponse(accountId, resolved.toString(),
                effective.orgState().orgUnitIds(), effective.orgState().companyIds(),
                effective.orgState().rankIds(), effective.orgState().jobFunctionIds(), grants);
    }

    @GetMapping("/accounts/{accountId}/permission-check")
    public DecisionResponse explainDecision(
            @PathVariable String accountId,
            @RequestParam("permission") String permission,
            @RequestParam(name = "companyId", required = false) String companyId,
            @RequestParam(name = "orgUnitId", required = false) String orgUnitId,
            @RequestParam(name = "ownerEmployeeId", required = false) String ownerEmployeeId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate resolved = asOf == null ? LocalDate.now() : asOf;
        PermissionTarget target = PermissionTarget.builder()
                .companyId(companyId)
                .orgUnitId(orgUnitId)
                .ownerEmployeeId(ownerEmployeeId)
                .asOfBusinessDate(resolved)
                .description("explainer probe")
                .build();

        PermissionDecision decision = explainer.explainDecision(
                caller, accountId, PermissionKey.parse(permission), target);

        List<ConsiderationView> considered = new ArrayList<ConsiderationView>();
        for (PermissionDecision.Consideration consideration : decision.considerations()) {
            considered.add(new ConsiderationView(
                    GrantView.from(consideration.grant()),
                    consideration.applied(),
                    consideration.reason()));
        }
        return new DecisionResponse(
                decision.isAllowed(),
                permission,
                decision.summary(),
                decision.decidingGrant() == null ? null : GrantView.from(decision.decidingGrant()),
                considered);
    }

    /** A grant, flattened for the wire. */
    public static class GrantView {
        private final String permission;
        private final String scope;
        private final String source;
        private final String sourceId;
        private final String sourceLabel;
        private final String effect;

        GrantView(String permission, String scope, String source, String sourceId,
                String sourceLabel, String effect) {
            this.permission = permission;
            this.scope = scope;
            this.source = source;
            this.sourceId = sourceId;
            this.sourceLabel = sourceLabel;
            this.effect = effect;
        }

        static GrantView from(PermissionGrant grant) {
            return new GrantView(String.valueOf(grant.key()), grant.scope().name(),
                    grant.source().name(), grant.sourceId(), grant.sourceLabel(),
                    grant.isAllow() ? "ALLOW" : "DENY");
        }

        public String getPermission() {
            return permission;
        }

        public String getScope() {
            return scope;
        }

        public String getSource() {
            return source;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceLabel() {
            return sourceLabel;
        }

        public String getEffect() {
            return effect;
        }
    }

    public static class ConsiderationView {
        private final GrantView grant;
        private final boolean applied;
        private final String reason;

        ConsiderationView(GrantView grant, boolean applied, String reason) {
            this.grant = grant;
            this.applied = applied;
            this.reason = reason;
        }

        public GrantView getGrant() {
            return grant;
        }

        public boolean isApplied() {
            return applied;
        }

        /** Plain language, suitable for the admin UI without rewriting. */
        public String getReason() {
            return reason;
        }
    }

    public static class EffectivePermissionsResponse {
        private final String accountId;
        private final String asOf;
        private final java.util.Set<String> orgUnitIds;
        private final java.util.Set<String> companyIds;
        private final java.util.Set<String> rankIds;
        private final java.util.Set<String> jobFunctionIds;
        private final List<GrantView> grants;

        EffectivePermissionsResponse(String accountId, String asOf, java.util.Set<String> orgUnitIds,
                java.util.Set<String> companyIds, java.util.Set<String> rankIds,
                java.util.Set<String> jobFunctionIds, List<GrantView> grants) {
            this.accountId = accountId;
            this.asOf = asOf;
            this.orgUnitIds = orgUnitIds;
            this.companyIds = companyIds;
            this.rankIds = rankIds;
            this.jobFunctionIds = jobFunctionIds;
            this.grants = grants;
        }

        public String getAccountId() {
            return accountId;
        }

        /** The date the org state was resolved as of. Echoed so the UI can show it. */
        public String getAsOf() {
            return asOf;
        }

        public java.util.Set<String> getOrgUnitIds() {
            return orgUnitIds;
        }

        public java.util.Set<String> getCompanyIds() {
            return companyIds;
        }

        public java.util.Set<String> getRankIds() {
            return rankIds;
        }

        public java.util.Set<String> getJobFunctionIds() {
            return jobFunctionIds;
        }

        public List<GrantView> getGrants() {
            return grants;
        }
    }

    public static class DecisionResponse {
        private final boolean allowed;
        private final String permission;
        private final String summary;
        private final GrantView decidingGrant;
        private final List<ConsiderationView> considerations;

        DecisionResponse(boolean allowed, String permission, String summary, GrantView decidingGrant,
                List<ConsiderationView> considerations) {
            this.allowed = allowed;
            this.permission = permission;
            this.summary = summary;
            this.decidingGrant = decidingGrant;
            this.considerations = considerations;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getPermission() {
            return permission;
        }

        public String getSummary() {
            return summary;
        }

        /** Null when nothing applied — the deny-by-default case. */
        public GrantView getDecidingGrant() {
            return decidingGrant;
        }

        public List<ConsiderationView> getConsiderations() {
            return considerations;
        }
    }
}
