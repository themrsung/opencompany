-- Revoking a permission stops being a DELETE.
--
-- V2 shipped permission_grant without any revocation columns, so the only way
-- to take a permission away was to delete the row. That breaks the rule the
-- rest of this schema keeps — nothing is ever hard-deleted — and it breaks it
-- in the worst possible place: the question "who could approve this last March,
-- and who took that away?" is exactly what an auditor asks, and a deleted row
-- cannot answer it. It also threw away the mandatory reason, which was
-- collected and then discarded.

ALTER TABLE permission_grant
    ADD COLUMN revoked_at     TIMESTAMPTZ,
    ADD COLUMN revoked_by     VARCHAR(36) REFERENCES user_account (id),
    ADD COLUMN revoked_reason TEXT;

-- A revocation without a reason is the thing this column exists to prevent.
ALTER TABLE permission_grant
    ADD CONSTRAINT permission_grant_revocation_complete CHECK (
        revoked_at IS NULL
        OR (revoked_reason IS NOT NULL AND length(btrim(revoked_reason)) > 0));

-- The uniqueness rule has to apply to LIVE grants only. Left as it was, the
-- same permission could never be granted again after being revoked once,
-- because the revoked row would still occupy the unique tuple — and the failure
-- would surface months later as "the system will not let me give 김민준 this
-- back", with nothing on screen explaining why.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_unique;
CREATE UNIQUE INDEX permission_grant_unique
    ON permission_grant (source, source_id, resource, action, scope, allow)
    WHERE revoked_at IS NULL;

-- The evaluator reads only live grants, and it reads them on the hottest path
-- in the system, so the partial index carries the predicate rather than making
-- every check filter after the fact.
DROP INDEX permission_grant_lookup_idx;
CREATE INDEX permission_grant_lookup_idx
    ON permission_grant (source, source_id) WHERE revoked_at IS NULL;

COMMENT ON COLUMN permission_grant.revoked_at IS
    'Set instead of deleting the row. A revoked grant is invisible to the '
    'evaluator and visible to the explainer and the audit trail.';
