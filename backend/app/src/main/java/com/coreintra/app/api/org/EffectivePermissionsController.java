package com.coreintra.app.api.org;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EffectivePermissionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Why can this person do this?", asked the way an administrator has it.
 *
 * <h2>What this adds to the explainer that already exists</h2>
 *
 * <p>{@code PermissionExplainerController} answers by <em>account</em> and takes
 * the target apart by hand: give it a company id, a unit id and an owner
 * employee id and it will explain the decision about that synthetic target. That
 * is the right primitive and it is not duplicated here.
 *
 * <p>What an administrator actually has in front of them is two people and a
 * verb: "can 김민준 read 이서연's record?". Answering that with the existing
 * endpoint means first looking up which unit 이서연 was in on the date in
 * question — and getting that wrong silently produces a confident, wrong
 * explanation. So these endpoints take the <em>subject</em> and let
 * {@code EffectivePermissionsService} resolve the target from the subject's own
 * position as of the business date, which is the only resolution that can be
 * right by construction.
 *
 * <p>The same service also keys the effective set by employee rather than by
 * account, which matters for the ordinary case of a person whose account id
 * nobody has memorised.
 *
 * <h2>A denial answers 200</h2>
 *
 * <p>A denied decision is the <em>answer</em>, not a failed request; that is the
 * case administrators ask about most. Whether the caller may inspect the subject
 * at all is a separate check, and that one does return 403.
 */
@RestController
@RequestMapping("/api/v1/permissions")
@Tag(name = "Permissions — explainer",
        description = "The effective set for a person, and the grant chain behind one decision.")
public class EffectivePermissionsController {

    private final EffectivePermissionsService effective;
    private final CurrentPrincipal currentPrincipal;

    public EffectivePermissionsController(EffectivePermissionsService effective,
            CurrentPrincipal currentPrincipal) {
        this.effective = effective;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/effective")
    @Operation(summary = "Everything a person's account can do, as of a date",
            description = "Keyed by employee. Resolves the account behind the person, then the "
                    + "org state as it stood on the business date — a promotion since then "
                    + "neither grants nor removes authority over history.")
    public EffectiveGrantsResponse effectiveForEmployee(
            @RequestParam("employeeId") String employeeId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(asOf);
        return EffectiveGrantsResponse.from(effective.explainEmployee(caller, employeeId, on), on);
    }

    @GetMapping("/decision/about-employee")
    @Operation(summary = "Can this account do this to this person?",
            description = "The target is built from the subject employee's own position on the "
                    + "date, so the caller cannot get it subtly wrong. 200 for a denial.")
    public DecisionView aboutEmployee(
            @RequestParam("accountId") String accountId,
            @RequestParam("permission") String permission,
            @RequestParam("employeeId") String subjectEmployeeId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(asOf);
        return DecisionView.from(effective.explainDecisionAboutEmployee(caller, accountId,
                PermissionKey.parse(permission), subjectEmployeeId, on), permission);
    }

    @GetMapping("/decision/about-unit")
    @Operation(summary = "Can this account do this in this unit?",
            description = "200 for a denial: an explainer that refused to explain denials would "
                    + "be useless for the case administrators actually ask about.")
    public DecisionView aboutUnit(
            @RequestParam("accountId") String accountId,
            @RequestParam("permission") String permission,
            @RequestParam("companyId") String companyId,
            @RequestParam(name = "orgUnitId", required = false) String orgUnitId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(asOf);
        return DecisionView.from(effective.explainDecisionAboutUnit(caller, accountId,
                PermissionKey.parse(permission), companyId, orgUnitId, on), permission);
    }

    /** The whole effective set, with the org state it was resolved against. */
    public static class EffectiveGrantsResponse {
        private final String accountId;
        private final String asOf;
        private final Set<String> companyIds;
        private final Set<String> orgUnitIds;
        private final Set<String> rankIds;
        private final Set<String> jobFunctionIds;
        private final List<GrantEntry> grants;

        EffectiveGrantsResponse(String accountId, String asOf, Set<String> companyIds,
                Set<String> orgUnitIds, Set<String> rankIds, Set<String> jobFunctionIds,
                List<GrantEntry> grants) {
            this.accountId = accountId;
            this.asOf = asOf;
            this.companyIds = companyIds;
            this.orgUnitIds = orgUnitIds;
            this.rankIds = rankIds;
            this.jobFunctionIds = jobFunctionIds;
            this.grants = grants;
        }

        static EffectiveGrantsResponse from(EffectivePermissions permissions, LocalDate asOf) {
            List<GrantEntry> entries = new ArrayList<GrantEntry>();
            for (PermissionGrant grant : permissions.grants()) {
                // No row id: an effective grant may have arrived through a rank
                // or a 직무 the subject holds, and the id of that row belongs to
                // the grant book rather than to this answer. Revoking is done
                // where the grant lives, not where its effect is observed.
                entries.add(GrantEntry.from(grant, null));
            }
            return new EffectiveGrantsResponse(permissions.principal().accountId(), asOf.toString(),
                    permissions.orgState().companyIds(), permissions.orgState().orgUnitIds(),
                    permissions.orgState().rankIds(), permissions.orgState().jobFunctionIds(),
                    entries);
        }

        /** The account behind the employee. */
        public String getAccountId() {
            return accountId;
        }

        /** Echoed, so the UI can show which day it is describing. */
        public String getAsOf() {
            return asOf;
        }

        public Set<String> getCompanyIds() {
            return companyIds;
        }

        public Set<String> getOrgUnitIds() {
            return orgUnitIds;
        }

        public Set<String> getRankIds() {
            return rankIds;
        }

        public Set<String> getJobFunctionIds() {
            return jobFunctionIds;
        }

        public List<GrantEntry> getGrants() {
            return grants;
        }
    }

    /** One decision and the chain behind it. */
    public static class DecisionView {
        private final boolean allowed;
        private final String permission;
        private final String summary;
        private final GrantEntry decidingGrant;
        private final List<ConsiderationEntry> considerations;

        DecisionView(boolean allowed, String permission, String summary, GrantEntry decidingGrant,
                List<ConsiderationEntry> considerations) {
            this.allowed = allowed;
            this.permission = permission;
            this.summary = summary;
            this.decidingGrant = decidingGrant;
            this.considerations = considerations;
        }

        static DecisionView from(PermissionDecision decision, String permission) {
            List<ConsiderationEntry> considered = new ArrayList<ConsiderationEntry>();
            for (PermissionDecision.Consideration consideration : decision.considerations()) {
                considered.add(new ConsiderationEntry(
                        GrantEntry.from(consideration.grant(), null),
                        consideration.applied(), consideration.reason()));
            }
            return new DecisionView(decision.isAllowed(), permission, decision.summary(),
                    decision.decidingGrant() == null ? null
                            : GrantEntry.from(decision.decidingGrant(), null),
                    considered);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getPermission() {
            return permission;
        }

        /** Plain language, fit to show without rewriting. */
        public String getSummary() {
            return summary;
        }

        /** Null when nothing applied — the deny-by-default case. */
        public GrantEntry getDecidingGrant() {
            return decidingGrant;
        }

        /** Every grant that was looked at, including the ones that did not apply. */
        public List<ConsiderationEntry> getConsiderations() {
            return considerations;
        }
    }

    /** A grant that was considered, and what came of it. */
    public static class ConsiderationEntry {
        private final GrantEntry grant;
        private final boolean applied;
        private final String reason;

        ConsiderationEntry(GrantEntry grant, boolean applied, String reason) {
            this.grant = grant;
            this.applied = applied;
            this.reason = reason;
        }

        public GrantEntry getGrant() {
            return grant;
        }

        /** False for a grant whose scope did not reach this target. */
        public boolean isApplied() {
            return applied;
        }

        public String getReason() {
            return reason;
        }
    }
}
