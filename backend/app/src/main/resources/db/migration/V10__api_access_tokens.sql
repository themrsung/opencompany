-- Short-lived access tokens on the existing session registry (§7).
--
-- V3 shipped the rotating refresh token and the revocable registry, but nothing
-- an ordinary request could present: SessionService declared ACCESS_TOKEN_LIFETIME
-- and never minted anything with it. This adds the missing half.
--
-- The access token is OPAQUE and stored hashed on the session row, not a JWT.
-- On a single box the whole point of the session registry is that a sign-out or
-- a temporary-master revocation takes effect on the next request; a
-- self-validating token would keep working until it expired, which is exactly
-- the property that makes "Revoke now" a lie. A primary-key lookup plus one
-- hash comparison costs less than verifying a signature anyway, because the
-- session id travels in the token.

ALTER TABLE auth_session
    -- Salted hash, same treatment as the refresh token: a database dump yields
    -- no usable session.
    ADD COLUMN access_token_hash VARCHAR(200),
    ADD COLUMN access_expires_at TIMESTAMPTZ;

-- Both columns are written together at issue and at every rotation. Allowing
-- one without the other would produce a session that is either permanently
-- expired or never expires, and neither failure announces itself.
ALTER TABLE auth_session
    ADD CONSTRAINT auth_session_access_token_complete CHECK (
        (access_token_hash IS NULL) = (access_expires_at IS NULL));

-- Sweeping expired access tokens is a maintenance job, not a request-path
-- concern; the index is here so it stays cheap as the table grows.
CREATE INDEX auth_session_access_expiry_idx ON auth_session (access_expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON COLUMN auth_session.access_token_hash IS
    'Salted hash of the short-lived access token. The wire form is '
    '<sessionId>.<secret>, so lookup is a primary-key hit rather than a scan.';
