package com.coreintra.app.api.org;

import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.service.PermissionGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The grant book: what is attached to a 직급, a 직무, a unit or one account.
 *
 * <h2>Granting is gated twice, and neither gate is here</h2>
 *
 * <p>{@code PermissionGrantService} checks {@code admin.permission:grant} over
 * the source's reach <em>and</em> checks that the caller already holds the
 * permission being handed out. That second check is the one that stops
 * privilege escalation, and its denial names the permission the caller lacks
 * rather than "you may not grant" — which is the answer an administrator can
 * act on. This controller adds no check of its own; a second gate is a gate that
 * eventually disagrees with the first.
 *
 * <h2>Revoking is a write, not a delete</h2>
 *
 * <p>Hence {@code POST .../revocation} with a mandatory reason rather than
 * {@code DELETE}. Nothing leaves the table: the row is marked revoked with who
 * did it and why, the evaluator stops seeing it, and the explainer keeps showing
 * it. An auditor's question is "who could approve this last March, and who took
 * that away" — a deleted row cannot answer either half.
 */
@RestController
@RequestMapping("/api/v1/permissions/grants")
@Tag(name = "Permissions — grants",
        description = "Attaching and removing permissions. Grants hang off ranks, 직무, units "
                + "and accounts; the effective set is their union minus explicit denies.")
public class PermissionGrantController {

    private final PermissionGrantService grants;
    private final GrantIdentities identities;
    private final CurrentPrincipal currentPrincipal;

    public PermissionGrantController(PermissionGrantService grants, GrantIdentities identities,
            CurrentPrincipal currentPrincipal) {
        this.grants = grants;
        this.identities = identities;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "What is attached to one rank, 직무, unit or account",
            description = "Live grants only — revoked ones are kept for the audit trail and are "
                    + "not shown as though they still applied.")
    public CursorPage<GrantEntry> list(
            @RequestParam("source") String source,
            @RequestParam("sourceId") String sourceId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        GrantSource from = source(source);

        // The authorised read first. Only then the id lookup, which adds a
        // column to rows the evaluator has already released.
        List<PermissionGrant> attached = grants.list(caller, from, sourceId, on);
        Map<String, String> ids = identities.liveIds(from, sourceId);

        List<GrantEntry> entries = new ArrayList<GrantEntry>();
        for (PermissionGrant grant : attached) {
            entries.add(GrantEntry.from(grant, ids.get(GrantIdentities.fingerprintOf(grant))));
        }
        return Pages.page(entries, KEYS, IDENTITY, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Attach a permission",
            description = "Requires admin.permission:grant over the source, and requires the "
                    + "caller to hold the permission being granted. allow=false writes an "
                    + "explicit deny, which is gated identically: taking a right away is as "
                    + "consequential as giving one.")
    public ResponseEntity<GrantEntry> grant(
            @Valid @RequestBody GrantRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        GrantSource from = source(body.getSource());

        PermissionGrant created = grants.grant(caller, from, body.getSourceId(),
                PermissionKey.parse(body.getPermission()), scope(body.getScope()), body.isAllow(),
                body.getReason(), on);

        String id = identities.liveIds(from, body.getSourceId())
                .get(GrantIdentities.fingerprintOf(created));
        return ResponseEntity.ok(GrantEntry.from(created, id));
    }

    @PostMapping("/{grantId}/revocation")
    @Operation(summary = "Take a grant back",
            description = "The reason is mandatory and is kept on the row. Revoking an already "
                    + "revoked grant is refused rather than silently accepted, because the "
                    + "second caller is acting on a stale view.")
    public ResponseEntity<Void> revoke(
            @PathVariable String grantId,
            @Valid @RequestBody RevocationRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        grants.revoke(caller, grantId, body.getReason(), BusinessDates.resolve(businessDate));
        return ResponseEntity.noContent().build();
    }

    /** Ordered so the same source's grants read in a stable, explicable order. */
    private static final Pages.Keys<GrantEntry> KEYS = new Pages.Keys<GrantEntry>() {
        @Override
        public String sortKey(GrantEntry entry) {
            return entry.getPermission() + ' ' + entry.getScope() + ' ' + entry.getEffect();
        }

        @Override
        public String id(GrantEntry entry) {
            // Null only for a grant revoked between the two reads; the
            // fingerprint still orders it deterministically.
            return entry.getId() == null ? entry.getSourceId() + entry.getPermission() : entry.getId();
        }
    };

    private static final java.util.function.Function<GrantEntry, GrantEntry> IDENTITY =
            new java.util.function.Function<GrantEntry, GrantEntry>() {
                @Override
                public GrantEntry apply(GrantEntry entry) {
                    return entry;
                }
            };

    private static GrantSource source(String value) {
        try {
            return GrantSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("source must be one of RANK, JOB_FUNCTION, "
                    + "ORG_UNIT or USER_ACCOUNT; got '" + value + "'");
        }
    }

    private static PermissionScope scope(String value) {
        try {
            return PermissionScope.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be one of SELF, ORG_UNIT, "
                    + "ORG_UNIT_SUBTREE, COMPANY or ALL; got '" + value + "'");
        }
    }

    /** A permission to attach. */
    public static class GrantRequest {
        @NotBlank
        private String source;
        @NotBlank
        private String sourceId;
        @NotBlank
        private String permission;
        @NotBlank
        private String scope;
        private boolean allow = true;
        @NotBlank
        private String reason;

        /** RANK, JOB_FUNCTION, ORG_UNIT or USER_ACCOUNT. */
        public String getSource() {
            return source;
        }

        public void setSource(String value) {
            this.source = value;
        }

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String value) {
            this.sourceId = value;
        }

        /** {@code resource:action}. Wildcards are refused by the service. */
        public String getPermission() {
            return permission;
        }

        public void setPermission(String value) {
            this.permission = value;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String value) {
            this.scope = value;
        }

        /** False writes an explicit deny. */
        public boolean isAllow() {
            return allow;
        }

        public void setAllow(boolean value) {
            this.allow = value;
        }

        /** Mandatory. A grant nobody can explain is a grant nobody dares remove. */
        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }
    }

    /** Why this permission is being taken back. */
    public static class RevocationRequest {
        @NotBlank
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }
    }
}
