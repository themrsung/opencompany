package com.coreintra.app.install;

import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first grants, which no grant can authorise.
 *
 * <p>{@code PermissionGrantService} refuses to hand out a permission the caller
 * does not already hold. That rule is correct — without it, anyone who could
 * administer permissions could write themselves the ledger — and it is exactly
 * why an empty installation can never open itself. Somebody has to write the
 * first rows, and this is the only class in the product that does.
 *
 * <h2>It closes after one use</h2>
 *
 * <p>Refused the moment a single {@code permission_grant} row exists, revoked or
 * not. There is no parameter that relaxes that and no second method that skips
 * it: the guard is the entire justification for the class existing.
 *
 * <h2>The bootstrap set, and why it is wildcards</h2>
 *
 * <p>One row per top-level resource tree, action {@code *}, scope
 * {@link PermissionScope#ALL} — seven rows, listed in {@link #BOOTSTRAP}.
 *
 * <p>The alternative was a list of every concrete key in the product, which is
 * what the demo seed hands its operator and what reads better in the explainer.
 * It was rejected for the reason the escalation gate makes unavoidable: to grant
 * {@code accounting.entry:post} to the finance team, the master must hold
 * {@code accounting.entry:post}. A hard-coded list therefore fixes, at install
 * time and for ever, the set of permissions this installation can ever delegate.
 * The first module or migration to add a key would add one nobody could be
 * given — not because anybody decided that, but because the installer was
 * written before the key existed. That failure is silent, arrives months later,
 * and has no fix short of editing the database by hand.
 *
 * <p>Three properties keep the wildcards from being a god-mode account with
 * extra steps:
 *
 * <ul>
 *   <li>They cannot spread. {@code PermissionGrantService} refuses to
 *       <em>issue</em> a wildcard key, so the master can only ever delegate
 *       concrete permissions. These seven rows stay a one-off.</li>
 *   <li>They can be taken away. Revoking a wildcard needs
 *       {@code admin.permission:revoke} and nothing else — the grant service
 *       says so where it explains the asymmetry — so a client who wants a
 *       narrowed master can trim them once the real administrators exist.</li>
 *   <li>They are visible. Master status does not short-circuit the evaluator in
 *       this product, so the effective-permissions explainer shows these rows
 *       as the reason the first account can do anything, exactly as it would for
 *       anyone else.</li>
 * </ul>
 *
 * <p>Scope is {@code ALL} because nothing narrower would reach anything: scope
 * is resolved against the positions the principal holds, and the first account
 * holds none — there are no employees yet to hold one.
 *
 * <p>A new top-level resource tree needs a line in {@link #BOOTSTRAP}. Nothing
 * enumerates the product's keys at runtime, so nothing can check that for you.
 */
@Component
public class InstallationGrants {

    /**
     * The first grants have already been written.
     *
     * <p>Named rather than a bare {@code IllegalStateException} so the REST
     * layer can answer 409 instead of 500: on a box that is already open this is
     * a refusal, not a fault.
     */
    public static class GrantsAlreadyExistException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public GrantsAlreadyExistException(String message) {
            super(message);
        }
    }

    /**
     * The trees the product uses. {@code admin.*} covers company creation,
     * accounts, master status, permissions, audit, modules, webhooks and
     * temporary-master issuance; {@code company.*} the company settings that
     * govern representation and 결재선; the rest are their modules.
     */
    public static final List<PermissionKey> BOOTSTRAP = Immutables.listOf(
            PermissionKey.of("admin.*", "*"),
            PermissionKey.of("company.*", "*"),
            PermissionKey.of("hr.*", "*"),
            PermissionKey.of("approval.*", "*"),
            PermissionKey.of("accounting.*", "*"),
            PermissionKey.of("documents.*", "*"),
            PermissionKey.of("support.*", "*"));

    private final PermissionGrantRepository grants;

    public InstallationGrants(PermissionGrantRepository grants) {
        if (grants == null) {
            throw new NullPointerException("grants");
        }
        this.grants = grants;
    }

    /** True while the first grants have not been written. */
    @Transactional(readOnly = true)
    public boolean noGrantsExist() {
        return grants.count() == 0;
    }

    /**
     * Writes the first grants of an installation, attached to one account.
     *
     * <p>{@code grantedBy} is left null, and that is honest: nobody granted
     * these. The reason is mandatory, because the explainer will be asked why
     * this account can do everything and "no reason given" is not an answer.
     *
     * @throws IllegalStateException if any grant already exists — the check is
     *         the point of the class, not a precaution
     */
    @Transactional
    public List<PermissionGrantRow> writeFirstGrants(String accountId, List<PermissionKey> keys,
            String reason) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("accountId is blank");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "the first grants need a reason; it is what the explainer shows");
        }
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("no keys to grant");
        }
        long existing = grants.count();
        if (existing > 0) {
            throw new GrantsAlreadyExistException(
                    "이 설치본에는 이미 권한 부여 기록이 " + existing + "건 있습니다. 이후의 권한은 "
                            + "admin.permission:grant 권한을 가진 계정이 부여합니다. (This installation "
                            + "already has " + existing + " permission grant(s). Every grant after "
                            + "the first is issued by an account holding admin.permission:grant, "
                            + "through PermissionGrantService, which refuses to hand out anything "
                            + "the grantor does not already hold.)");
        }

        List<PermissionGrantRow> written = new ArrayList<PermissionGrantRow>(keys.size());
        for (PermissionKey key : keys) {
            PermissionGrantRow row = new PermissionGrantRow(UUID.randomUUID().toString(),
                    GrantSource.USER_ACCOUNT, accountId, key, PermissionScope.ALL, true);
            row.setReason(reason);
            written.add(grants.save(row));
        }
        return Immutables.copyOf(written);
    }
}
