package com.coreintra.app.api.org;

import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.service.GrantWriteRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovers the row id of a grant the caller has already been shown.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code PermissionGrantService.revoke} takes a grant id, and neither
 * {@code list} nor {@code grant} returns one: {@link PermissionGrant} is the
 * evaluator's value type and carries a key, a scope, a source and an effect,
 * but no identity. Without something here, the API could grant a permission and
 * then have no way to name it again in order to take it back — which is the
 * half of the feature that matters, because a grant made in error is exactly
 * the one somebody needs to remove in a hurry.
 *
 * <p>The right fix is upstream: {@code PermissionGrantService} should return
 * rows that carry their ids. That is another module and this agent does not own
 * it, so it is recorded in the handover instead of edited around.
 *
 * <h2>Why this is not an authorisation hole</h2>
 *
 * <p>This class decides nothing. The caller's controller has already asked
 * {@code PermissionGrantService} for the grants attached to one source, and that
 * call checked {@code admin.permission:read} against the source's reach and
 * threw if it did not hold. Only then is this map consulted, and only for the
 * same (source, sourceId) pair — it adds a column to rows the evaluator has
 * already released, and can add nothing else. It is a {@code @Service} rather
 * than a helper on the controller because ArchUnit forbids a controller holding
 * a repository, and that rule is worth keeping literal.
 *
 * <p>The pairing is exact rather than heuristic: {@code permission_grant_unique}
 * makes (source, sourceId, permission, scope, effect) unique among live rows, so
 * a fingerprint of those five fields identifies at most one revocable grant.
 */
@Service
public class GrantIdentities {

    private static final char SEPARATOR = '\u001f';

    private final GrantWriteRepository rows;

    public GrantIdentities(GrantWriteRepository rows) {
        this.rows = rows;
    }

    /**
     * Fingerprint to row id, for the live grants on one source.
     *
     * <p>Revoked rows are left out deliberately. They are still in the table for
     * the audit trail, they are invisible to the evaluator, and a revoked row
     * sharing a fingerprint with a live one — which happens the moment a
     * permission is taken away and given back — would otherwise make the id
     * ambiguous.
     */
    @Transactional(readOnly = true)
    public Map<String, String> liveIds(GrantSource source, String sourceId) {
        Map<String, String> ids = new HashMap<String, String>();
        for (PermissionGrantRow row : rows.findBySourceAndSourceId(source, sourceId)) {
            if (!row.isRevoked()) {
                ids.put(fingerprint(String.valueOf(row.key()), row.scope().name(), row.isAllow()),
                        row.id());
            }
        }
        return ids;
    }

    /** The same fingerprint, computed from what the service handed back. */
    public static String fingerprintOf(PermissionGrant grant) {
        return fingerprint(String.valueOf(grant.key()), grant.scope().name(), grant.isAllow());
    }

    private static String fingerprint(String permission, String scope, boolean allow) {
        return permission + SEPARATOR + scope + SEPARATOR + (allow ? "ALLOW" : "DENY");
    }
}
