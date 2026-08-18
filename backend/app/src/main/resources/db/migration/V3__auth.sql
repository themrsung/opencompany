-- Authentication (ADR 0006). There is no password column anywhere in this file.

CREATE TABLE totp_credential (
    account_id       VARCHAR(36)  PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    -- AES-256-GCM, 'gcm1$nonce$ciphertext'. The only reversibly-stored
    -- credential in the system: the server must compute codes from it.
    secret_encrypted VARCHAR(500) NOT NULL,
    digits           INTEGER      NOT NULL DEFAULT 6 CHECK (digits BETWEEN 6 AND 8),
    step_seconds     INTEGER      NOT NULL DEFAULT 30 CHECK (step_seconds > 0),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULL means enrolment was started but never confirmed. An unconfirmed
    -- credential cannot authenticate.
    enrolled_at      TIMESTAMPTZ,
    last_used_at     TIMESTAMPTZ
);

-- Replay protection. The composite primary key is the mechanism, not an
-- optimisation: two concurrent requests presenting the same code both try to
-- insert this row and exactly one wins. A check-then-insert in application code
-- would let both through under load - precisely when someone is replaying.
CREATE TABLE consumed_time_step (
    account_id  VARCHAR(36) NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    time_step   BIGINT      NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, time_step)
);
CREATE INDEX consumed_time_step_pruning_idx ON consumed_time_step (consumed_at);

CREATE TABLE recovery_code (
    id         VARCHAR(36)  PRIMARY KEY,
    account_id VARCHAR(36)  NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    -- Salted SHA-256. These are machine-generated with >=128 bits of entropy,
    -- so a slow KDF would add cost for us and nothing for an attacker.
    code_hash  VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    used_at    TIMESTAMPTZ
);
CREATE INDEX recovery_code_unused_idx ON recovery_code (account_id) WHERE used_at IS NULL;

CREATE TABLE auth_session (
    id                 VARCHAR(36)  PRIMARY KEY,
    account_id         VARCHAR(36)  NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    -- Hashed. A database dump yields no usable session.
    refresh_token_hash VARCHAR(200) NOT NULL,
    -- Stable across rotations. Presenting a superseded token revokes the whole
    -- chain, because the only explanation is that it was captured.
    chain_id           VARCHAR(36)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    last_used_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    revoked_reason     VARCHAR(64),
    user_agent         VARCHAR(400),
    ip_address         VARCHAR(64)
);
CREATE INDEX auth_session_account_idx ON auth_session (account_id) WHERE revoked_at IS NULL;
CREATE INDEX auth_session_chain_idx ON auth_session (chain_id);

CREATE TABLE api_key (
    id          VARCHAR(36)   PRIMARY KEY,
    account_id  VARCHAR(36)   NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    name        VARCHAR(200)  NOT NULL,
    -- Clear and indexed: identifies a key in a log or config without revealing
    -- it, and makes lookup a single indexed hit rather than a hash comparison
    -- against every row.
    key_prefix  VARCHAR(24)   NOT NULL,
    secret_hash VARCHAR(200)  NOT NULL,
    -- Scopes NARROW the account's permissions and never widen them. A key
    -- scoped to reads cannot write even if its account may.
    scopes      VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(36)   REFERENCES user_account (id),
    expires_at  TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT api_key_prefix_unique UNIQUE (key_prefix)
);
CREATE INDEX api_key_account_idx ON api_key (account_id) WHERE revoked_at IS NULL;

CREATE TABLE auth_attempt (
    id             VARCHAR(36)  PRIMARY KEY,
    -- The username AS SUBMITTED, not a resolved account id, so attempts against
    -- names that do not exist are throttled identically. Otherwise the throttle
    -- itself reveals which usernames are real.
    username       VARCHAR(100) NOT NULL,
    succeeded      BOOLEAN      NOT NULL,
    failure_reason VARCHAR(40),
    attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ip_address     VARCHAR(64)
);
CREATE INDEX auth_attempt_throttle_idx ON auth_attempt (username, attempted_at DESC)
    WHERE succeeded = FALSE;

COMMENT ON TABLE auth_attempt IS
    'Drives progressive delay, not a hard lock. A hard lock would hand anyone '
    'who knows a username a denial-of-service against that account.';
