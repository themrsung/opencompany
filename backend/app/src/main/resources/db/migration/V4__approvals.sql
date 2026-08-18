-- 결재 (approval routing).

-- Representation mode is a COMPANY-level setting with effective dating, and it
-- governs every representative-level approval in the system. Per-document choice
-- would let the drafter decide how much authority their own document needs.
CREATE TABLE company_representation (
    id                          VARCHAR(36) PRIMARY KEY,
    company_id                  VARCHAR(36) NOT NULL REFERENCES company (id),
    mode                        VARCHAR(16) NOT NULL CHECK (mode IN ('SEVERAL', 'JOINT')),
    -- Under JOINT this must be >= 2: a quorum of 1 is 각자대표 under another
    -- name, and would silently defeat the joint-representation invariant.
    required_approvals          INTEGER     NOT NULL,
    designated_representatives  INTEGER     NOT NULL,
    effective_from              DATE        NOT NULL,
    effective_to                DATE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT representation_quorum_sane CHECK (
        required_approvals >= 1
        AND required_approvals <= designated_representatives
        AND (mode = 'SEVERAL' OR required_approvals >= 2)),
    CONSTRAINT representation_interval_ordered CHECK (
        effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX company_representation_asof_idx
    ON company_representation (company_id, effective_from, effective_to);

CREATE TABLE approval_line_template (
    id            VARCHAR(36)  PRIMARY KEY,
    company_id    VARCHAR(36)  NOT NULL REFERENCES company (id),
    document_type VARCHAR(100) NOT NULL,
    -- NULL means the company-wide default for this document type.
    org_unit_id   VARCHAR(36)  REFERENCES org_unit (id),
    name_ko       VARCHAR(200) NOT NULL,
    name_en       VARCHAR(200),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX approval_template_lookup_idx
    ON approval_line_template (company_id, document_type, org_unit_id) WHERE active;

CREATE TABLE approval_template_step (
    id              VARCHAR(36)  PRIMARY KEY,
    template_id     VARCHAR(36)  NOT NULL REFERENCES approval_line_template (id) ON DELETE CASCADE,
    position        INTEGER      NOT NULL,
    kind            VARCHAR(20)  NOT NULL
        CHECK (kind IN ('DRAFT', 'REVIEW', 'CONCURRENCE', 'APPROVE', 'CC')),
    -- A role expression, never a person: a template naming individuals would
    -- have to be rewritten whenever anyone moved, and would route to leavers.
    role_expression VARCHAR(300) NOT NULL,
    optional_step   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- NULL on the base line; set on steps contributed by a threshold rule.
    -- Inclusive: a rule at 5000000 applies at exactly 5000000.
    minimum_amount  NUMERIC,
    rationale       TEXT
);
CREATE INDEX approval_template_step_idx ON approval_template_step (template_id, position);

CREATE TABLE approval_document (
    id                  VARCHAR(36)  PRIMARY KEY,
    company_id          VARCHAR(36)  NOT NULL REFERENCES company (id),
    document_type       VARCHAR(100) NOT NULL,
    title               VARCHAR(500) NOT NULL,
    drafter_account_id  VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    drafter_org_unit_id VARCHAR(36)  REFERENCES org_unit (id),
    template_id         VARCHAR(36)  REFERENCES approval_line_template (id),
    state               VARCHAR(24)  NOT NULL CHECK (state IN (
                            'DRAFTING', 'IN_PROGRESS', 'PARTIALLY_APPROVED', 'ON_HOLD',
                            'APPROVED', 'RETURNED', 'RECALLED')),
    -- Money is NUMERIC, never a floating type (ADR 0004). Amounts drive
    -- threshold rules, so a rounding error here changes who has to sign.
    amount              NUMERIC,
    currency_code       VARCHAR(12),
    -- Business time: what the organisation agrees happened.
    submitted_business_date   DATE,
    submitted_offset_seconds  INTEGER
        CONSTRAINT approval_document_submitted_offset_in_window
        CHECK (submitted_offset_seconds IS NULL OR business_offset_is_valid(submitted_offset_seconds)),
    submitted_absolute_ts     TIMESTAMP GENERATED ALWAYS AS
        (submitted_business_date + make_interval(secs => submitted_offset_seconds)) STORED,
    -- Frozen at submission. The submitted artefact is immutable.
    submitted_snapshot_hash   VARCHAR(80),
    -- UTC: what the machine observed. Never conflated with the above.
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT approval_document_submitted_together CHECK (
        (submitted_business_date IS NULL) = (submitted_offset_seconds IS NULL))
);
CREATE INDEX approval_document_inbox_idx ON approval_document (state, company_id);
CREATE INDEX approval_document_drafter_idx ON approval_document (drafter_account_id, state);
CREATE INDEX approval_document_submitted_idx
    ON approval_document (submitted_business_date, submitted_offset_seconds);

CREATE TABLE approval_step (
    id                VARCHAR(36)  PRIMARY KEY,
    document_id       VARCHAR(36)  NOT NULL REFERENCES approval_document (id) ON DELETE CASCADE,
    position          INTEGER      NOT NULL,
    kind              VARCHAR(20)  NOT NULL
        CHECK (kind IN ('DRAFT', 'REVIEW', 'CONCURRENCE', 'APPROVE', 'CC')),
    -- The expression this step was resolved FROM, kept for the audit trail.
    role_expression   VARCHAR(300),
    -- >1 for a 공동대표 quorum or a parallel 합의 group.
    required_approvals INTEGER     NOT NULL DEFAULT 1 CHECK (required_approvals >= 1),
    state             VARCHAR(16)  NOT NULL CHECK (state IN (
                          'UPCOMING', 'PENDING', 'COMPLETED', 'RETURNED', 'HELD', 'SKIPPED'))
);
CREATE INDEX approval_step_document_idx ON approval_step (document_id, position);

-- Resolved at submission and SNAPSHOTTED, so a later reorganisation cannot
-- reroute an in-flight approval or change who was supposed to have signed.
CREATE TABLE approval_step_approver (
    step_id            VARCHAR(36) NOT NULL REFERENCES approval_step (id) ON DELETE CASCADE,
    account_id         VARCHAR(36) NOT NULL REFERENCES user_account (id),
    -- Snapshotted display data, so the trail still reads correctly years later
    -- even if the person's rank or name has since changed.
    resolved_rank_label VARCHAR(100),
    resolved_display_name VARCHAR(200),
    PRIMARY KEY (step_id, account_id)
);

-- Append-only. No UPDATE, no DELETE: this is the record of what was decided.
CREATE TABLE approval_action (
    id                     VARCHAR(36) PRIMARY KEY,
    step_id                VARCHAR(36) NOT NULL REFERENCES approval_step (id) ON DELETE CASCADE,
    actor_account_id       VARCHAR(36) NOT NULL REFERENCES user_account (id),
    actor_display_name     VARCHAR(200) NOT NULL,
    action                 VARCHAR(24) NOT NULL CHECK (action IN (
                               'APPROVE', 'RETURN', 'HOLD', 'DELEGATED_FINAL', 'ACTING', 'RECALL')),
    acted_business_date    DATE        NOT NULL,
    acted_offset_seconds   INTEGER     NOT NULL
        CONSTRAINT approval_action_acted_offset_in_window
        CHECK (business_offset_is_valid(acted_offset_seconds)),
    acted_absolute_ts      TIMESTAMP GENERATED ALWAYS AS
        (acted_business_date + make_interval(secs => acted_offset_seconds)) STORED,
    comment                TEXT,
    -- SHA-256 of the document as it stood at this moment. This is what makes
    -- the trail mean something: it proves WHAT was approved, not merely that
    -- something was.
    document_snapshot_hash VARCHAR(80) NOT NULL,
    -- For 대결 only: whom the actor acted for. Without it the trail would imply
    -- the absent approver signed personally.
    on_behalf_of_account_id VARCHAR(36) REFERENCES user_account (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_action_reason_present CHECK (
        action IN ('APPROVE', 'RECALL') OR (comment IS NOT NULL AND length(btrim(comment)) > 0)),
    CONSTRAINT approval_action_acting_names_principal CHECK (
        action <> 'ACTING' OR on_behalf_of_account_id IS NOT NULL)
);
CREATE INDEX approval_action_step_idx ON approval_action (step_id);
CREATE INDEX approval_action_trail_idx
    ON approval_action (acted_business_date, acted_offset_seconds);

COMMENT ON TABLE approval_action IS
    'Append-only. The immutable record of who decided what, when (business time), '
    'and against which document snapshot.';
