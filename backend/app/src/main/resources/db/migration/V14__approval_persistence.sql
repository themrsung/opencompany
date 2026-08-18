-- What the 결재 service layer needed and could not find.
--
-- Three gaps, each of which stops something the brief asks for from working at
-- all rather than merely working badly.

-- ---------------------------------------------------------------------------
-- 1. 취업규칙 (employment rules) had no table anywhere.
--
-- The domain type exists and is fully tested; nothing stored it, so a published
-- version lived only for the length of a request. §4 requires that employees
-- can see the version that was in force on any given date — including a date in
-- the past, during a dispute — which is a storage requirement before it is a UI
-- one.
--
-- Versioned supersession, never update: an amendment is a new row with a later
-- effective_from, and a repeal is likewise a new row. An in-place edit would
-- destroy the only evidence of what people were actually bound by last year.

CREATE TABLE employment_rules (
    id                    VARCHAR(36) PRIMARY KEY,
    company_id            VARCHAR(36) NOT NULL REFERENCES company (id),
    -- 1 for a company's first. Monotonic per company, and the number employees
    -- and labour inspectors refer to.
    version               INTEGER     NOT NULL CHECK (version >= 1),
    effective_from        DATE        NOT NULL,

    -- The 대표자 결재 that authorised this version. NOT NULL because §4 makes it
    -- a domain invariant: no admin, no master account and no API path may enact
    -- 취업규칙 without one.
    approval_document_id  VARCHAR(36) NOT NULL,
    -- A copy of that document's state, and the reason it is here rather than
    -- being read through the foreign key: it turns "must be approved" from a
    -- rule the application checks into a rule the database cannot express
    -- otherwise. A CHECK cannot see another table, and PostgreSQL will not
    -- point a foreign key at a partial unique index, so the approved subset is
    -- selected by carrying the state into the key itself — see the composite FK
    -- and the CHECK below. Insert a row naming a document that is still
    -- IN_PROGRESS and the foreign key has nothing to match.
    approval_document_state VARCHAR(24) NOT NULL,

    -- The representation mode in force on the approval's own business date,
    -- copied rather than re-read. A company that switches 각자대표 → 공동대표
    -- next year has not retroactively invalidated the rules enacted this year,
    -- and the row has to be able to say under what rule it was enacted.
    approved_under_mode                VARCHAR(16) NOT NULL
        CHECK (approved_under_mode IN ('SEVERAL', 'JOINT')),
    approved_under_required_approvals  INTEGER     NOT NULL,
    approved_under_designated          INTEGER     NOT NULL,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT employment_rules_version_unique UNIQUE (company_id, version),
    CONSTRAINT employment_rules_only_from_approved CHECK (
        approval_document_state = 'APPROVED'),
    CONSTRAINT employment_rules_quorum_sane CHECK (
        approved_under_required_approvals >= 1
        AND approved_under_required_approvals <= approved_under_designated
        AND (approved_under_mode = 'SEVERAL' OR approved_under_required_approvals >= 2))
);

-- The target for the composite foreign key above. It is a uniqueness statement
-- that is already true — id is the primary key — and exists only so that
-- (id, state) can be referenced.
ALTER TABLE approval_document
    ADD CONSTRAINT approval_document_id_state_unique UNIQUE (id, state);

-- ON UPDATE RESTRICT, not CASCADE: an enacted 취업규칙 pins its approval at
-- APPROVED. Cascading would rewrite the copy and then fail the CHECK anyway,
-- with a message about a constraint rather than about what was attempted.
-- APPROVED is already terminal, so nothing legitimate is being blocked.
ALTER TABLE employment_rules
    ADD CONSTRAINT employment_rules_approved_document_fk
    FOREIGN KEY (approval_document_id, approval_document_state)
    REFERENCES approval_document (id, state) ON UPDATE RESTRICT;

CREATE INDEX employment_rules_effective_idx
    ON employment_rules (company_id, effective_from);

-- Section-level, because §4 asks for a section-level diff against the prior
-- version. Storing the whole text as one blob would make "제12조 changed, the
-- rest did not" something a diff library guesses at from prose.
CREATE TABLE employment_rules_section (
    rules_id       VARCHAR(36)  NOT NULL REFERENCES employment_rules (id),
    -- 제N조 as printed, not an integer: 제12조의2 is a real section number.
    section_number VARCHAR(40)  NOT NULL,
    -- Printing order, which is not the lexicographic order of section_number.
    sort_order     INTEGER      NOT NULL,
    heading_ko     VARCHAR(500) NOT NULL,
    heading_en     VARCHAR(500),
    body_ko        TEXT         NOT NULL,
    body_en        TEXT,
    PRIMARY KEY (rules_id, section_number)
);
CREATE INDEX employment_rules_section_order_idx
    ON employment_rules_section (rules_id, sort_order);

-- Who actually signed, snapshotted. Under 공동대표 the quorum is the point of the
-- whole exercise, and "two representatives approved this" has to remain
-- answerable after both have left the company.
CREATE TABLE employment_rules_representative (
    rules_id   VARCHAR(36) NOT NULL REFERENCES employment_rules (id),
    account_id VARCHAR(36) NOT NULL REFERENCES user_account (id),
    sort_order INTEGER     NOT NULL,
    PRIMARY KEY (rules_id, account_id)
);

-- Per employee PER VERSION. Having read the 2024 rules says nothing about
-- having read the 2026 ones, and the receipt an employer needs to produce is
-- always for a specific version.
CREATE TABLE employment_rules_acknowledgement (
    rules_id        VARCHAR(36) NOT NULL REFERENCES employment_rules (id),
    employee_id     VARCHAR(36) NOT NULL REFERENCES employee (id),
    -- Business date: the day the organisation says they read it.
    acknowledged_on DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The primary key is what makes acknowledging twice a no-op rather than a
    -- second receipt with a different date.
    PRIMARY KEY (rules_id, employee_id)
);
CREATE INDEX employment_rules_ack_employee_idx
    ON employment_rules_acknowledgement (employee_id);

COMMENT ON TABLE employment_rules IS
    'Versioned 취업규칙. Never updated in place: an amendment or a repeal is a new '
    'version, so the text that applied on any past date stays answerable.';

-- ---------------------------------------------------------------------------
-- 2. An approval could not name the document it was about.
--
-- approval_document carries the routing — type, drafter, amount, state — and
-- nothing that points at the body. The only existing link runs through
-- approval_snapshot, which is keyed per action and therefore does not exist
-- until somebody signs; at submission time there is nothing at all. So
-- "approve this 휴가신청서 and deduct the days it asks for" had no way to find
-- out how many days it asks for.

ALTER TABLE approval_document
    ADD COLUMN document_id VARCHAR(36) REFERENCES document (id);

-- Nullable, and deliberately: an approval whose subject is the routing itself
-- (a 취업규칙 proposal filed before its text is drafted) legitimately has no
-- body yet. What must not happen is a body id that names nothing, which is what
-- the foreign key is for.
CREATE INDEX approval_document_body_idx ON approval_document (document_id);

COMMENT ON COLUMN approval_document.document_id IS
    'The document body this approval is about. NULL where the approval has no '
    'body yet; never a dangling id.';

-- ---------------------------------------------------------------------------
-- 3. The inbox was scanning approval_step_approver in full.
--
-- The table is keyed (step_id, account_id), so the primary key index answers
-- "who is on this step" and cannot answer "which steps is this person on" —
-- which is the direction the inbox asks in, on every load, for every user. The
-- brief names the inbox as one of the two screens that decide whether people
-- like the product, and a sequential scan of every approver row ever written is
-- how that screen gets slower every month it is used.
CREATE INDEX approval_step_approver_account_idx
    ON approval_step_approver (account_id);
