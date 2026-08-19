-- Platform runtime: the audit trail, temporary master accounts, idempotency,
-- webhooks, rate limits and the module registry.
--
-- These tables have no business meaning of their own. They exist because §8
-- and §12 make two promises that cannot be kept by application code alone:
-- that a support session can be handed out without handing over the company,
-- and that the record of what that session did cannot be edited afterwards by
-- anyone, master included. A promise enforced in a service is a promise that
-- lasts until the next endpoint is added. These are enforced here.

--------------------------------------------------------------------------------
-- Audit log (§12)
--------------------------------------------------------------------------------

-- Append-only, and the trigger below is what makes that word mean something.
--
-- The temporary master account is only survivable because of this table. A
-- vendor engineer with real access is acceptable to a client exactly when the
-- client can read, afterwards, every row the engineer touched - including the
-- rows they only looked at. So reads are logged too, and the log is outside
-- the reach of every account in the system.
CREATE TABLE audit_log (
    id                      VARCHAR(36)  PRIMARY KEY,
    company_id              VARCHAR(36)  NOT NULL REFERENCES company (id),

    -- NULL only for an attempt that never resolved to an account (a failed
    -- sign-in). Everything else has an actor, because "the system did it" is
    -- the sentence this whole design exists to prevent.
    actor_account_id        VARCHAR(36)  REFERENCES user_account (id),
    actor_kind              VARCHAR(24)  NOT NULL
                            CHECK (actor_kind IN ('USER', 'MASTER', 'TEMPORARY_MASTER',
                                                  'SERVICE_ACCOUNT', 'SCHEDULED_JOB',
                                                  'MODULE', 'ANONYMOUS')),
    -- Snapshotted. A name read back from user_account years later is the name
    -- they have now, which is not what the trail is for.
    actor_display_name      VARCHAR(200) NOT NULL,

    -- The permission actually used, in `resource:action` form, not the one the
    -- caller claimed. This is the column that answers "under which capability
    -- was this read allowed", which is the question asked of a support session.
    capability              VARCHAR(120) NOT NULL,

    resource                VARCHAR(120) NOT NULL,
    resource_id             VARCHAR(200),
    action                  VARCHAR(24)  NOT NULL
                            CHECK (action IN ('READ', 'CREATE', 'UPDATE', 'VOID',
                                              'EXPORT', 'LOGIN', 'LOGOUT')),
    outcome                 VARCHAR(16)  NOT NULL
                            CHECK (outcome IN ('ALLOWED', 'DENIED', 'FAILED')),

    -- A read that returned 4,000 rows is a different event from one that
    -- returned 1, and the difference is the whole point of an export warning.
    rows_touched            INTEGER      NOT NULL DEFAULT 0 CHECK (rows_touched >= 0),

    -- Kept as text, not jsonb: Hibernate 5.6's schema validator reports jsonb
    -- as Types#OTHER and refuses to start, the same reason V1 dropped its
    -- CREATE DOMAIN. The cast in the CHECK keeps the guarantee anyway - a row
    -- carrying something that is not JSON cannot be written.
    before_json             TEXT         CHECK (before_json IS NULL OR before_json::jsonb IS NOT NULL),
    after_json              TEXT         CHECK (after_json IS NULL OR after_json::jsonb IS NOT NULL),

    -- Business time: when the organisation agrees this happened.
    occurred_business_date  DATE         NOT NULL,
    occurred_offset_seconds INTEGER      NOT NULL,
    CONSTRAINT audit_log_occurred_offset_in_window
        CHECK (business_offset_is_valid(occurred_offset_seconds)),
    occurred_absolute_ts    TIMESTAMP GENERATED ALWAYS AS
                            (occurred_business_date + make_interval(secs => occurred_offset_seconds)) STORED,

    -- UTC: when the machine observed it. Never conflated with the above. An
    -- auditor reconciling against a firewall log needs this one; an auditor
    -- reconciling against a shift roster needs the triple.
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    request_id              VARCHAR(64),
    ip_address              VARCHAR(64),
    user_agent              VARCHAR(400),

    -- Set for every action taken under a support session, so the session
    -- report is a query rather than a reconstruction.
    temporary_master_grant_id VARCHAR(36)
);

CREATE INDEX audit_log_company_time_idx
    ON audit_log (company_id, occurred_business_date, occurred_offset_seconds);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_account_id, created_at);
CREATE INDEX audit_log_resource_idx ON audit_log (resource, resource_id);
CREATE INDEX audit_log_grant_idx ON audit_log (temporary_master_grant_id)
    WHERE temporary_master_grant_id IS NOT NULL;

CREATE FUNCTION audit_log_refuse_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % is refused', TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT = 'Retention is set in audit_retention_policy and can only be raised. '
                     'No account, master included, can edit or remove the trail.';
END;
$$;

COMMENT ON FUNCTION audit_log_refuse_mutation() IS
    'Append-only enforcement. A convention in a service layer lasts until the next '
    'endpoint; a trigger lasts until someone with database credentials removes it, '
    'and that act is itself visible. This is the difference between an audit log '
    'and a log.';

-- Row-level for the ordinary paths, statement-level for TRUNCATE, which row
-- triggers do not see.
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_refuse_mutation();

CREATE TRIGGER audit_log_append_only_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_refuse_mutation();

-- §12: retention is configurable upward only. Shortening it is how a trail is
-- destroyed without deleting anything, so the direction is a constraint, not a
-- setting. The floor is five years, the period Korean commercial books must be
-- kept for.
CREATE TABLE audit_retention_policy (
    company_id            VARCHAR(36)  PRIMARY KEY REFERENCES company (id),
    retention_days        INTEGER      NOT NULL CHECK (retention_days >= 1825),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by_account_id VARCHAR(36)  REFERENCES user_account (id)
);

CREATE FUNCTION audit_retention_only_grows() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.retention_days < OLD.retention_days THEN
        RAISE EXCEPTION 'audit retention cannot be shortened: % -> % days',
            OLD.retention_days, NEW.retention_days
            USING ERRCODE = 'restrict_violation',
                  HINT = 'Retention is configurable upward only (§12).';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_retention_policy_upward_only
    BEFORE UPDATE ON audit_retention_policy
    FOR EACH ROW EXECUTE FUNCTION audit_retention_only_grows();

--------------------------------------------------------------------------------
-- Temporary master account (§8)
--------------------------------------------------------------------------------

-- One issued support session. The domain object in the auth module decides
-- whether a session may exist at all (the typed company name, the never-
-- grantable list, the representation-mode quorum); this table records what was
-- decided and re-states the two invariants a row can carry on its own - a
-- bounded life, and no wildcard capabilities.
--
-- Nothing here is ever deleted. A session that ended is a session with
-- revoked_at set or an expires_at in the past, and both remain readable
-- forever, because "there was no support session" and "the record of it is
-- gone" must not look the same.
CREATE TABLE temporary_master_grant (
    id                      VARCHAR(36)  PRIMARY KEY,
    company_id              VARCHAR(36)  NOT NULL REFERENCES company (id),

    -- The TEMPORARY_MASTER user_account the engineer signs in as. Separate
    -- from the vendor's own identity, so revoking the session revokes exactly
    -- one thing.
    account_id              VARCHAR(36)  NOT NULL REFERENCES user_account (id),

    issued_by_account_id    VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    engineer_name           VARCHAR(200) NOT NULL,

    -- Shown in the banner every user in the company sees, and in the trail.
    -- A support session with no stated reason is the thing §8 is written
    -- against, so it is NOT NULL and non-blank rather than merely encouraged.
    reason                  TEXT         NOT NULL CHECK (length(btrim(reason)) > 0),
    ticket_reference        VARCHAR(120),

    -- The company name the issuer typed out in full. Kept because the
    -- confirmation is evidence, not a checkbox: a client asking "who agreed to
    -- this" is answered by a person having typed their own company's name.
    typed_company_name      VARCHAR(200) NOT NULL,

    issued_at               TIMESTAMPTZ  NOT NULL,
    time_to_live_seconds    INTEGER      NOT NULL
                            CHECK (time_to_live_seconds > 0 AND time_to_live_seconds <= 86400),
    expires_at              TIMESTAMPTZ  NOT NULL,

    -- Issuance is a business event as well as a machine one - it belongs on
    -- the day the company agreed to it, which under a 72-hour day is not the
    -- day the clock says.
    issued_business_date    DATE         NOT NULL,
    issued_offset_seconds   INTEGER      NOT NULL,
    CONSTRAINT temporary_master_grant_issued_offset_in_window
        CHECK (business_offset_is_valid(issued_offset_seconds)),
    issued_absolute_ts      TIMESTAMP GENERATED ALWAYS AS
                            (issued_business_date + make_interval(secs => issued_offset_seconds)) STORED,

    -- Evidence of the approval this was issued under. The approval itself was
    -- decided in the approval module under the company's representation mode;
    -- what is recorded here is which document, which mode, and how many
    -- representatives that mode required.
    approval_document_id    VARCHAR(36)  NOT NULL REFERENCES approval_document (id),
    representation_mode     VARCHAR(16)  NOT NULL CHECK (representation_mode IN ('SEVERAL', 'JOINT')),
    required_approvals      INTEGER      NOT NULL CHECK (required_approvals >= 1),
    CONSTRAINT temporary_master_grant_joint_quorum_sane
        CHECK (representation_mode = 'SEVERAL' OR required_approvals >= 2),

    -- §8: auditing is doubled, and it is not a per-session choice. The column
    -- exists so the row states it, and the CHECK means it can only ever state
    -- one thing.
    audits_reads            BOOLEAN      NOT NULL DEFAULT TRUE CHECK (audits_reads),

    revoked_at              TIMESTAMPTZ,
    revoked_by_account_id   VARCHAR(36)  REFERENCES user_account (id),
    session_report_id       VARCHAR(36),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- No extension: the deadline is fixed at issue and the row cannot express
    -- anything else. Wanting longer means a new approval and a new reason.
    CONSTRAINT temporary_master_grant_expiry_matches_ttl
        CHECK (EXTRACT(EPOCH FROM (expires_at - issued_at)) = time_to_live_seconds),
    CONSTRAINT temporary_master_grant_revocation_paired
        CHECK ((revoked_at IS NULL) = (revoked_by_account_id IS NULL))
);

CREATE INDEX temporary_master_grant_live_idx
    ON temporary_master_grant (company_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX temporary_master_grant_account_idx ON temporary_master_grant (account_id);

-- Ticked one at a time. A grant with no rows here can read nothing - not one
-- row, not the company's own name - and that is the default, which is why the
-- absence of rows is meaningful rather than a half-written record.
CREATE TABLE temporary_master_capability (
    grant_id   VARCHAR(36)  NOT NULL REFERENCES temporary_master_grant (id) ON DELETE CASCADE,
    capability VARCHAR(120) NOT NULL,
    PRIMARY KEY (grant_id, capability),

    -- A wildcard is a grant-all wearing a pattern. Refused at construction in
    -- the domain and refused again here, because the two paths into this table
    -- (the application and a future import) must not disagree.
    CONSTRAINT temporary_master_capability_no_wildcard
        CHECK (position('*' IN capability) = 0),

    -- The list that would let a session escape its own boundaries: grant
    -- itself more, make itself permanent, mint another session, or remove the
    -- record of what it did.
    CONSTRAINT temporary_master_capability_grantable
        CHECK (capability NOT IN (
            'admin.permission:grant',
            'admin.permission:revoke',
            'admin.master:create',
            'admin.master:update',
            'admin.master:delete',
            'admin.temporaryMaster:issue',
            'hr.employmentRules:amend',
            'hr.employmentRules:create',
            'hr.employmentRules:repeal',
            'company.representation:update',
            'admin.audit:disable',
            'admin.audit:delete'))
);

-- Who approved, snapshotted. Under 공동대표 the quorum is only meaningful if
-- the individuals are named: two approvals from one person is one approval.
CREATE TABLE temporary_master_approver (
    grant_id                  VARCHAR(36)  NOT NULL REFERENCES temporary_master_grant (id) ON DELETE CASCADE,
    representative_account_id VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    representative_name       VARCHAR(200) NOT NULL,
    PRIMARY KEY (grant_id, representative_account_id)
);

-- The kill switch, and the reason it is a table rather than a setting: a
-- client who has decided never to accept vendor sessions again should not be
-- able to be talked out of it by a support engineer with a settings screen.
-- One row, installation-wide, and the trigger below makes the decision one-way.
CREATE TABLE temporary_master_switch (
    installation           VARCHAR(16)  PRIMARY KEY CHECK (installation = 'INSTALLATION'),
    issuance_disabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    disabled_at            TIMESTAMPTZ,
    disabled_by_account_id VARCHAR(36)  REFERENCES user_account (id),
    disabled_reason        VARCHAR(400),
    CONSTRAINT temporary_master_switch_disable_recorded
        CHECK (issuance_disabled = FALSE
               OR (disabled_at IS NOT NULL AND disabled_by_account_id IS NOT NULL))
);

INSERT INTO temporary_master_switch (installation) VALUES ('INSTALLATION');

CREATE FUNCTION temporary_master_switch_is_one_way() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.issuance_disabled AND NOT NEW.issuance_disabled THEN
        RAISE EXCEPTION 'temporary master issuance was permanently disabled for this installation'
            USING ERRCODE = 'restrict_violation',
                  HINT = 'It cannot be re-enabled from the application (§8).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER temporary_master_switch_one_way
    BEFORE UPDATE ON temporary_master_switch
    FOR EACH ROW EXECUTE FUNCTION temporary_master_switch_is_one_way();

--------------------------------------------------------------------------------
-- Idempotency (§10)
--------------------------------------------------------------------------------

-- Every POST that creates money or approvals carries a key. The unique index
-- is the mechanism, not a check-then-insert in application code: two retries
-- arriving together both try to insert this row and exactly one wins.
--
-- request_hash is what makes a replay safe to answer from here. The same key
-- with a different body is not a retry, it is a second request wearing the
-- first one's name, and it is refused rather than silently answered with the
-- first one's result.
CREATE TABLE idempotency_key (
    id                VARCHAR(36)  PRIMARY KEY,
    company_id        VARCHAR(36)  NOT NULL REFERENCES company (id),

    -- The key space is per caller: two clients may pick the same key and must
    -- not collide. A service account is an account, so one column covers both.
    account_id        VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    idempotency_key   VARCHAR(200) NOT NULL,

    endpoint          VARCHAR(400) NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,  -- SHA-256 hex of the request body

    state             VARCHAR(16)  NOT NULL CHECK (state IN ('IN_FLIGHT', 'COMPLETED')),
    response_status   INTEGER,
    response_body     TEXT,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT idempotency_key_per_account_unique UNIQUE (account_id, idempotency_key),
    -- A completed record without a response is a record that cannot answer the
    -- retry it exists for.
    CONSTRAINT idempotency_key_completion_paired
        CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL AND response_status IS NOT NULL))
);

-- These do expire, unlike everything else here: a key is a receipt with a
-- stated lifetime, not a record of what happened. The trail of what the request
-- actually did is in audit_log and outlives it.
CREATE INDEX idempotency_key_expiry_idx ON idempotency_key (expires_at);

--------------------------------------------------------------------------------
-- Webhooks (§10)
--------------------------------------------------------------------------------

CREATE TABLE webhook_subscription (
    id                   VARCHAR(36)   PRIMARY KEY,
    company_id           VARCHAR(36)   NOT NULL REFERENCES company (id),
    url                  VARCHAR(2000) NOT NULL CHECK (url LIKE 'https://%' OR url LIKE 'http://%'),

    -- Encrypted, not hashed, and this is a deliberate departure from how
    -- api_key stores its secret in V3.
    --
    -- We are the sender here. Signing a delivery needs the secret back in the
    -- clear, so a one-way hash would make the signature impossible to compute -
    -- there is no version of this feature where the stored form is a hash.
    -- AES-256-GCM under the installation key, same 'gcm1$nonce$ciphertext'
    -- shape as totp_credential, so a database dump alone still does not let
    -- anyone forge our signature.
    secret_encrypted     VARCHAR(500)  NOT NULL,
    -- The visible half: enough to tell two secrets apart in the UI and in a
    -- support call, useless for signing anything.
    secret_prefix        VARCHAR(12)   NOT NULL,

    description          VARCHAR(200),
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_account_id VARCHAR(36)  REFERENCES user_account (id),
    retired_at           TIMESTAMPTZ
);

CREATE INDEX webhook_subscription_company_idx
    ON webhook_subscription (company_id) WHERE retired_at IS NULL AND active;

-- Subscribed event types, one per row: a client that wants approvals but not
-- attendance says so by what it does not tick.
CREATE TABLE webhook_subscription_event (
    subscription_id VARCHAR(36) NOT NULL REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    event_type      VARCHAR(60) NOT NULL
                    CHECK (event_type ~ '^(document|approval|status)\.[a-z][a-zA-Z]+$'),
    PRIMARY KEY (subscription_id, event_type)
);

-- One row per (subscription, event), not per attempt: a redelivery is the same
-- row moving on, so a client cannot be sent an event twice by a dispatcher
-- that restarted mid-flight. Attempts are counted here rather than kept as
-- children because the only question ever asked of them is "how many, and when
-- next".
CREATE TABLE webhook_delivery (
    id               VARCHAR(36)  PRIMARY KEY,
    subscription_id  VARCHAR(36)  NOT NULL REFERENCES webhook_subscription (id),
    company_id       VARCHAR(36)  NOT NULL REFERENCES company (id),
    event_id         VARCHAR(36)  NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    payload          TEXT         NOT NULL CHECK (payload::jsonb IS NOT NULL),
    signature        VARCHAR(200) NOT NULL,

    -- CANCELLED is separate from DEAD on purpose: DEAD is what the receiver
    -- did to us, CANCELLED is what we did to the receiver (the subscription
    -- was retired while this row was still in flight). Collapsing the two
    -- would make "your endpoint was failing" indistinguishable from "you
    -- turned it off", and the first of those is an accusation.
    state            VARCHAR(16)  NOT NULL CHECK (state IN ('PENDING', 'DELIVERED', 'DEAD', 'CANCELLED')),
    attempts         INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts     INTEGER      NOT NULL CHECK (max_attempts BETWEEN 1 AND 12),
    next_attempt_at  TIMESTAMPTZ,
    last_status_code INTEGER,
    last_error       VARCHAR(400),

    -- Claimed by whoever is dispatching. The brief allows one box, but "one
    -- box" is a deployment fact that outlives no upgrade, and SKIP LOCKED over
    -- these two columns costs nothing today.
    claimed_at       TIMESTAMPTZ,
    claimed_by       VARCHAR(64),

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    dead_at          TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,

    CONSTRAINT webhook_delivery_event_once UNIQUE (subscription_id, event_id),
    CONSTRAINT webhook_delivery_pending_is_scheduled
        CHECK ((state = 'PENDING') = (next_attempt_at IS NOT NULL)),
    CONSTRAINT webhook_delivery_attempts_bounded CHECK (attempts <= max_attempts),
    -- Dead means we gave up, and giving up is only honest once the attempts
    -- were actually made.
    CONSTRAINT webhook_delivery_dead_is_exhausted
        CHECK (state <> 'DEAD' OR (dead_at IS NOT NULL AND attempts >= max_attempts)),
    -- A cancelled row says why, because the client reading it did not cause it
    -- and will otherwise assume their endpoint was at fault.
    CONSTRAINT webhook_delivery_cancelled_is_explained
        CHECK (state <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND last_error IS NOT NULL))
);

CREATE INDEX webhook_delivery_due_idx ON webhook_delivery (next_attempt_at)
    WHERE state = 'PENDING';
CREATE INDEX webhook_delivery_subscription_idx ON webhook_delivery (subscription_id, created_at);

-- The transactional outbox: where an event is written before anyone tries to
-- send it.
--
-- Why not publish inline. A document is approved, and in the same request we
-- POST to the client's endpoint. Two things then go wrong that cannot both be
-- fixed at once. If the POST is inside the approval's transaction, a subscriber
-- whose TLS certificate expired last night makes 결재 slow, and a subscriber
-- who returns 500 rolls back an approval that genuinely happened. If the POST
-- is after the commit, then a JVM restart between the two loses the event with
-- nothing anywhere recording that it was ever owed.
--
-- Writing this row in the same transaction as the domain change removes the
-- gap: the event exists exactly when the thing it describes exists, both or
-- neither. A relay then turns rows here into webhook_delivery rows, at its own
-- pace, retrying as often as it likes, because everything after this table is
-- at-least-once and the delivery rows are idempotent per (subscription, event).
CREATE TABLE outbox_event (
    id                     VARCHAR(36) PRIMARY KEY,
    company_id             VARCHAR(36) NOT NULL REFERENCES company (id),
    event_type             VARCHAR(60) NOT NULL
                           CHECK (event_type ~ '^(document|approval|status)\.[a-z][a-zA-Z]+$'),
    -- What it happened to, so a client can correlate without parsing payloads.
    resource               VARCHAR(120) NOT NULL,
    resource_id            VARCHAR(200),
    payload                TEXT        NOT NULL CHECK (payload::jsonb IS NOT NULL),

    -- Business time: the day the organisation agrees the event happened on,
    -- which is not necessarily the day the relay gets to it.
    occurred_business_date  DATE       NOT NULL,
    occurred_offset_seconds INTEGER    NOT NULL,
    CONSTRAINT outbox_event_occurred_offset_in_window
        CHECK (business_offset_is_valid(occurred_offset_seconds)),
    occurred_absolute_ts   TIMESTAMP GENERATED ALWAYS AS
                           (occurred_business_date + make_interval(secs => occurred_offset_seconds)) STORED,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL means the relay has not fanned this out yet. Set once; the row is
    -- kept afterwards because "was this event ever emitted?" is a question
    -- clients ask months later.
    relayed_at             TIMESTAMPTZ,
    relay_error            VARCHAR(400)
);

-- The relay's queue. Partial, because the unrelayed set is small and permanent
-- rows are the overwhelming majority.
CREATE INDEX outbox_event_pending_idx ON outbox_event (created_at) WHERE relayed_at IS NULL;
CREATE INDEX outbox_event_resource_idx ON outbox_event (company_id, resource, resource_id);

--------------------------------------------------------------------------------
-- Rate limits (§10)
--------------------------------------------------------------------------------

-- Per account and per key, configurable. The counters live in memory: a limit
-- is a protection against a runaway caller, not an accounting record, and
-- persisting every increment would make the database the thing that falls over
-- first. What is persisted is the policy, because that is what an
-- administrator sets and expects to survive a restart.
CREATE TABLE rate_limit_policy (
    id             VARCHAR(36)  PRIMARY KEY,
    -- NULL company means an installation-wide default.
    company_id     VARCHAR(36)  REFERENCES company (id),
    subject_kind   VARCHAR(16)  NOT NULL CHECK (subject_kind IN ('ACCOUNT', 'API_KEY', 'DEFAULT')),
    subject_id     VARCHAR(36),
    limit_key      VARCHAR(120) NOT NULL,
    permits        INTEGER      NOT NULL CHECK (permits > 0),
    window_seconds INTEGER      NOT NULL CHECK (window_seconds > 0 AND window_seconds <= 86400),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at     TIMESTAMPTZ,

    CONSTRAINT rate_limit_policy_subject_paired
        CHECK ((subject_kind = 'DEFAULT') = (subject_id IS NULL))
);

-- The most specific live policy wins, so at most one may exist per subject.
CREATE UNIQUE INDEX rate_limit_policy_lookup_idx
    ON rate_limit_policy (subject_kind, COALESCE(subject_id, ''), limit_key)
    WHERE retired_at IS NULL;

--------------------------------------------------------------------------------
-- Module registry (§11)
--------------------------------------------------------------------------------

-- What a master approved when they installed a module, kept apart from what
-- the module now claims to need. An upgrade that widens the manifest is a new
-- approval, and the comparison is only possible because the approved list is
-- stored rather than re-read from the jar.
CREATE TABLE module_registration (
    id                      VARCHAR(36)  PRIMARY KEY,
    company_id              VARCHAR(36)  NOT NULL REFERENCES company (id),
    -- The same shape the SPI enforces in ModuleManifest. It becomes a schema
    -- name and a URL segment, so it is constrained once here rather than
    -- sanitised at every use site.
    module_id               VARCHAR(40)  NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{2,39}$'),
    name                    VARCHAR(200) NOT NULL,
    version                 VARCHAR(40)  NOT NULL,
    vendor                  VARCHAR(200) NOT NULL,
    kind                    VARCHAR(24)  NOT NULL
                            CHECK (kind IN ('EXTERNAL_SERVICE', 'IN_PROCESS')),

    -- A namespace, not a table prefix: an in-process module owns a schema and
    -- may not touch core tables. Unique across the installation because two
    -- modules sharing one namespace would each believe the other's tables are
    -- theirs.
    -- The mod_ prefix is what keeps "may not touch core tables" from resting on
    -- a code review: a module cannot be registered against `public`, and the
    -- 63-character limit is Postgres's own identifier length.
    schema_namespace        VARCHAR(63)  NOT NULL
                            CHECK (schema_namespace ~ '^mod_[a-z][a-z0-9_]{2,39}$'),
    ui_entry_point          VARCHAR(400),

    -- The manifest as declared, kept verbatim beside the columns parsed out of
    -- it. The columns are what queries use; this is the evidence of what was
    -- actually agreed to, which matters when a later version disputes it.
    manifest_json           TEXT         NOT NULL CHECK (manifest_json::jsonb IS NOT NULL),

    -- A flag, not a state machine: §11 asks that modules can be disabled
    -- without uninstalling, and there is nothing in between.
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    installed_by_account_id VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    approval_document_id    VARCHAR(36)  REFERENCES approval_document (id),
    installed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disabled_at             TIMESTAMPTZ,
    disabled_reason         VARCHAR(400),
    -- Disabling is not uninstalling (§11). Both are recorded; neither deletes.
    retired_at              TIMESTAMPTZ,

    CONSTRAINT module_registration_unique UNIQUE (company_id, module_id),
    -- Someone will ask why the client's integration stopped, and the answer has
    -- to be in the row rather than in a chat log.
    CONSTRAINT module_registration_disable_recorded
        CHECK (enabled OR (disabled_at IS NOT NULL AND disabled_reason IS NOT NULL))
);

CREATE UNIQUE INDEX module_registration_schema_idx ON module_registration (schema_namespace);

CREATE TABLE module_registration_permission (
    module_registration_id VARCHAR(36)  NOT NULL REFERENCES module_registration (id) ON DELETE CASCADE,
    permission_key         VARCHAR(120) NOT NULL,
    PRIMARY KEY (module_registration_id, permission_key)
);

COMMENT ON TABLE module_registration_permission IS
    'The permissions the installing master ticked. A module asking for one that '
    'is not here does not start, rather than being trusted and watched.';
