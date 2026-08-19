-- Org model and permissions (ADR 0003).

CREATE TABLE company (
    id                           VARCHAR(36)  PRIMARY KEY,
    code                         VARCHAR(40)  NOT NULL,
    name_ko                      VARCHAR(200) NOT NULL,
    name_en                      VARCHAR(200),
    kind                         VARCHAR(20)  NOT NULL
        CHECK (kind IN ('HEAD_OFFICE', 'BRANCH', 'SUBSIDIARY')),
    parent_company_id            VARCHAR(36)  REFERENCES company (id),
    business_registration_number VARCHAR(40),
    base_currency_code           VARCHAR(12),
    established_on               DATE,
    active                       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT company_code_unique UNIQUE (code),
    -- A 본사 owns nobody above it; a 지사/자회사 must say who it belongs to.
    CONSTRAINT company_parent_matches_kind CHECK (
        (kind = 'HEAD_OFFICE' AND parent_company_id IS NULL)
        OR (kind <> 'HEAD_OFFICE' AND parent_company_id IS NOT NULL))
);

CREATE TABLE org_unit (
    id         VARCHAR(36)   PRIMARY KEY,
    company_id VARCHAR(36)   NOT NULL REFERENCES company (id),
    parent_id  VARCHAR(36)   REFERENCES org_unit (id),
    code       VARCHAR(40)   NOT NULL,
    name_ko    VARCHAR(200)  NOT NULL,
    name_en    VARCHAR(200),
    -- Materialised path, '/hq/support/finance/'. Always slash-terminated so a
    -- prefix match cannot half-match a sibling ('/hq/sales/' vs '/hq/salesops/').
    path       VARCHAR(1000) NOT NULL,
    depth      INTEGER       NOT NULL DEFAULT 0,
    sort_order INTEGER       NOT NULL DEFAULT 0,
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT org_unit_code_unique_per_company UNIQUE (company_id, code),
    CONSTRAINT org_unit_path_delimited CHECK (path LIKE '/%/'),
    CONSTRAINT org_unit_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

-- ORG_UNIT_SUBTREE checks run on every scoped request. text_pattern_ops makes
-- the prefix match index-assisted regardless of the database's collation.
CREATE INDEX org_unit_path_prefix_idx ON org_unit (path text_pattern_ops);
CREATE INDEX org_unit_company_idx ON org_unit (company_id, active);

CREATE TABLE rank (
    id             VARCHAR(36)  PRIMARY KEY,
    company_id     VARCHAR(36)  NOT NULL REFERENCES company (id),
    code           VARCHAR(40)  NOT NULL,
    label_ko       VARCHAR(100) NOT NULL,
    label_en       VARCHAR(100),
    -- Higher is more senior. Client-defined; gaps are intentional so a rung can
    -- be inserted later without renumbering everyone.
    seniority      INTEGER      NOT NULL,
    -- Whether holders are 대표이사 for representation purposes. A flag, not a
    -- code match, because a client may name the role anything and have several.
    representative BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT rank_code_unique_per_company UNIQUE (company_id, code)
);
CREATE INDEX rank_seniority_idx ON rank (company_id, seniority);

CREATE TABLE job_function (
    id         VARCHAR(36)  PRIMARY KEY,
    company_id VARCHAR(36)  NOT NULL REFERENCES company (id),
    code       VARCHAR(40)  NOT NULL,
    label_ko   VARCHAR(100) NOT NULL,
    label_en   VARCHAR(100),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT job_function_code_unique_per_company UNIQUE (company_id, code)
);

CREATE TABLE employee (
    id              VARCHAR(36)  PRIMARY KEY,
    company_id      VARCHAR(36)  NOT NULL REFERENCES company (id),
    employee_number VARCHAR(40),
    name_ko         VARCHAR(100) NOT NULL,
    name_en         VARCHAR(200),
    email           VARCHAR(320),
    hired_on        DATE,
    terminated_on   DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT employee_number_unique_per_company UNIQUE (company_id, employee_number),
    CONSTRAINT employee_terminated_after_hired CHECK (
        hired_on IS NULL OR terminated_on IS NULL OR terminated_on >= hired_on)
    -- No salary, no bank account, no payslip. This installation holds employment
    -- facts only; see the Employee entity for why that bound is deliberate.
);
CREATE INDEX employee_company_idx ON employee (company_id);

CREATE TABLE user_account (
    id           VARCHAR(36)  PRIMARY KEY,
    username     VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    kind         VARCHAR(24)  NOT NULL
        CHECK (kind IN ('USER', 'SERVICE_ACCOUNT', 'TEMPORARY_MASTER')),
    employee_id  VARCHAR(36)  REFERENCES employee (id),
    master       BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    locale       VARCHAR(16),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    CONSTRAINT user_account_username_unique UNIQUE (username),
    -- An employee has at most one account.
    CONSTRAINT user_account_employee_unique UNIQUE (employee_id),
    -- Service accounts and support sessions are never a person.
    CONSTRAINT user_account_employee_matches_kind CHECK (
        kind = 'USER' OR employee_id IS NULL)
    -- There is deliberately no password column. Credentials live in the auth
    -- module's tables; a leak of this table alone signs nobody in.
);

CREATE TABLE position (
    id               VARCHAR(36) PRIMARY KEY,
    employee_id      VARCHAR(36) NOT NULL REFERENCES employee (id),
    org_unit_id      VARCHAR(36) NOT NULL REFERENCES org_unit (id),
    rank_id          VARCHAR(36) NOT NULL REFERENCES rank (id),
    primary_position BOOLEAN     NOT NULL DEFAULT TRUE,
    effective_from   DATE        NOT NULL,
    -- Exclusive. Half-open intervals are the only way to make a position ending
    -- and another starting on the same day unambiguous.
    effective_to     DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT position_interval_ordered CHECK (
        effective_to IS NULL OR effective_to > effective_from)
);
-- The as-of lookup: "which positions did this employee hold on date D?"
CREATE INDEX position_asof_idx ON position (employee_id, effective_from, effective_to);
CREATE INDEX position_unit_asof_idx ON position (org_unit_id, effective_from, effective_to);

-- A position may carry several 직무, and they change independently of the rank.
CREATE TABLE position_job_function (
    position_id     VARCHAR(36) NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    job_function_id VARCHAR(36) NOT NULL REFERENCES job_function (id),
    PRIMARY KEY (position_id, job_function_id)
);

CREATE TABLE permission_grant (
    id          VARCHAR(36)  PRIMARY KEY,
    -- Which of the four sources this hangs off, and its id.
    source      VARCHAR(32)  NOT NULL CHECK (source IN (
                    'RANK', 'JOB_FUNCTION', 'ORG_UNIT', 'USER_ACCOUNT',
                    'TEMPORARY_MASTER_CAPABILITY')),
    source_id   VARCHAR(36)  NOT NULL,
    resource    VARCHAR(120) NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    scope       VARCHAR(24)  NOT NULL CHECK (scope IN (
                    'SELF', 'ORG_UNIT', 'ORG_UNIT_SUBTREE', 'COMPANY', 'ALL')),
    -- FALSE is an explicit deny, which beats any allow within its own scope.
    -- Modelled as a column rather than a separate table so the effective set is
    -- one query and the explainer sees allows and denies together.
    allow       BOOLEAN      NOT NULL,
    granted_by  VARCHAR(36)  REFERENCES user_account (id),
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason      TEXT,
    CONSTRAINT permission_grant_unique UNIQUE (source, source_id, resource, action, scope, allow),
    -- A bare '*' resource would make the explainer useless exactly where it
    -- matters most. Wildcards are '<resource>.*' or action '*', never both bare.
    CONSTRAINT permission_grant_no_bare_star CHECK (resource <> '*')
);
CREATE INDEX permission_grant_lookup_idx ON permission_grant (source, source_id);

COMMENT ON TABLE permission_grant IS
    'Deny by default. Effective set = union of grants minus explicit denies; '
    'an explicit deny wins within its own scope and is never out-voted.';
