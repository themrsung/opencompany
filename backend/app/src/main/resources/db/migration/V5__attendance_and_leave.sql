-- Attendance and leave.

-- Statuses are ROWS, not an enum. The six built-ins are seeded with the same
-- behaviour flags a client-defined status carries, so 교육 / 출장 / 병가 /
-- 육아휴직 are first-class rather than an OTHER case that reports wrong.
CREATE TABLE attendance_status_type (
    id                    VARCHAR(36) PRIMARY KEY,
    company_id            VARCHAR(36) NOT NULL REFERENCES company (id),
    code                  VARCHAR(40) NOT NULL,
    label_ko              VARCHAR(100) NOT NULL,
    label_en              VARCHAR(100),
    colour                VARCHAR(16),
    icon                  VARCHAR(40),
    -- Behaviour flags.
    counts_as_working     BOOLEAN     NOT NULL DEFAULT FALSE,
    requires_approval     BOOLEAN     NOT NULL DEFAULT FALSE,
    deducts_leave_balance BOOLEAN     NOT NULL DEFAULT FALSE,
    visible_to_peers      BOOLEAN     NOT NULL DEFAULT TRUE,
    -- NULL = persists until cleared. Set for 자리비움 so a who's-in board does
    -- not fill with stale markers nobody trusts.
    auto_expires_after_seconds INTEGER,
    -- Seeded rows can be restored to factory state; client rows cannot.
    built_in              BOOLEAN     NOT NULL DEFAULT FALSE,
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order            INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT attendance_status_code_unique UNIQUE (company_id, code),
    -- A status that spends leave without an approval step is indistinguishable
    -- from a bug, and users stop trusting their balance.
    CONSTRAINT attendance_status_deduct_needs_approval CHECK (
        NOT deducts_leave_balance OR requires_approval),
    CONSTRAINT attendance_status_expiry_positive CHECK (
        auto_expires_after_seconds IS NULL OR auto_expires_after_seconds > 0)
);

CREATE TABLE attendance_record (
    id                    VARCHAR(36) PRIMARY KEY,
    employee_id           VARCHAR(36) NOT NULL REFERENCES employee (id),
    status_type_id        VARCHAR(36) NOT NULL REFERENCES attendance_status_type (id),
    -- Both ends share a business date. A 22:00-03:00 shift is 22:00 to 27:00 on
    -- ONE business day, never two fragments across a calendar boundary.
    business_date         DATE        NOT NULL,
    started_offset_seconds INTEGER    NOT NULL
        CONSTRAINT attendance_started_offset_in_window
        CHECK (business_offset_is_valid(started_offset_seconds)),
    ended_offset_seconds  INTEGER
        CONSTRAINT attendance_ended_offset_in_window
        CHECK (ended_offset_seconds IS NULL OR business_offset_is_valid(ended_offset_seconds)),
    started_absolute_ts   TIMESTAMP GENERATED ALWAYS AS
        (business_date + make_interval(secs => started_offset_seconds)) STORED,
    note                  TEXT,
    -- The 결재 document that authorised this, for statuses that require approval.
    source_document_id    VARCHAR(36) REFERENCES approval_document (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attendance_interval_ordered CHECK (
        ended_offset_seconds IS NULL OR ended_offset_seconds >= started_offset_seconds)
);
CREATE INDEX attendance_employee_day_idx
    ON attendance_record (employee_id, business_date, started_offset_seconds);
-- The who's-in view: everyone currently in an unclosed status.
CREATE INDEX attendance_open_idx
    ON attendance_record (business_date) WHERE ended_offset_seconds IS NULL;

-- Accrual is CONFIGURATION, never code. Statute changes, clients are more
-- generous than the minimum, and overseas subsidiaries run other schemes.
-- The Korean 연차 default is a seeded, editable row; its statutory reference
-- lives in a comment, not in a code path.
CREATE TABLE leave_policy (
    id                             VARCHAR(36) PRIMARY KEY,
    company_id                     VARCHAR(36) NOT NULL REFERENCES company (id),
    code                           VARCHAR(40) NOT NULL,
    name_ko                        VARCHAR(200) NOT NULL,
    name_en                        VARCHAR(200),
    monthly_accrual_days           NUMERIC     NOT NULL DEFAULT 0,
    annual_grant_days              NUMERIC     NOT NULL DEFAULT 0,
    annual_grant_after_years       INTEGER     NOT NULL DEFAULT 1,
    maximum_days                   NUMERIC,
    carry_over_limit_days          NUMERIC,
    carry_over_expiry_months       INTEGER     NOT NULL DEFAULT 0,
    -- 1 = whole days, 0.5 = half-days, 0.25 = quarter-days.
    minimum_bookable_unit_days     NUMERIC     NOT NULL DEFAULT 1,
    built_in                       BOOLEAN     NOT NULL DEFAULT FALSE,
    active                         BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT leave_policy_code_unique UNIQUE (company_id, code),
    CONSTRAINT leave_policy_unit_positive CHECK (minimum_bookable_unit_days > 0)
);

CREATE TABLE leave_tenure_increment (
    id                    VARCHAR(36) PRIMARY KEY,
    policy_id             VARCHAR(36) NOT NULL REFERENCES leave_policy (id) ON DELETE CASCADE,
    after_completed_years INTEGER     NOT NULL CHECK (after_completed_years >= 0),
    additional_days       NUMERIC     NOT NULL,
    CONSTRAINT leave_increment_unique UNIQUE (policy_id, after_completed_years)
);

-- The ledger. A stored balance answers "how many days" and nothing else; when
-- an employee disputes it — and they will, leave is money — only the rows can
-- say where it came from. Nothing is amended in place: a mistake is corrected
-- by posting its reverse, so the record shows both the error and the fix.
CREATE TABLE leave_transaction (
    id                    VARCHAR(36) PRIMARY KEY,
    employee_id           VARCHAR(36) NOT NULL REFERENCES employee (id),
    policy_id             VARCHAR(36) REFERENCES leave_policy (id),
    kind                  VARCHAR(20) NOT NULL CHECK (kind IN (
                              'GRANT', 'CARRY_OVER', 'USE', 'EXPIRY', 'ADJUSTMENT', 'CANCELLATION')),
    -- Always a POSITIVE magnitude. Direction comes from the kind, so a bug
    -- cannot silently invert someone's balance by flipping a sign.
    days                  NUMERIC     NOT NULL CHECK (days > 0),
    occurred_business_date DATE       NOT NULL,
    occurred_offset_seconds INTEGER   NOT NULL
        CONSTRAINT leave_transaction_offset_in_window
        CHECK (business_offset_is_valid(occurred_offset_seconds)),
    -- NULL = usable immediately. Set for next year's grant booked in advance.
    effective_from        DATE,
    -- NULL = does not lapse.
    expires_on            DATE,
    reason                TEXT,
    source_document_id    VARCHAR(36) REFERENCES approval_document (id),
    actor_account_id      VARCHAR(36) REFERENCES user_account (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leave_adjustment_needs_reason CHECK (
        kind <> 'ADJUSTMENT' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);
CREATE INDEX leave_transaction_balance_idx
    ON leave_transaction (employee_id, occurred_business_date, occurred_offset_seconds);

COMMENT ON TABLE leave_transaction IS
    'Append-only ledger. The balance is the sum of these rows, computed never '
    'stored, so it cannot drift from the transactions that explain it.';
