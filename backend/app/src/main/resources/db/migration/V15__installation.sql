-- Opening a fresh installation, and closing that door behind it.
--
-- Two things live here.
--
--  1. `installation` — one row, written by POST /api/v1/install, which is the
--     only endpoint in the product that no permission authorises. It has to be
--     unauthenticated: on an empty box there is nobody to be. The row is what
--     makes that safe, and it is a lock rather than a log.
--
--  2. `install_company_defaults()` — the rank ladder, the 직무 set, the six
--     attendance statuses and the Korean 연차 policy, as a function that can be
--     applied to ONE company instead of a migration that applies to whichever
--     companies happened to exist the day it ran.

-- ---------------------------------------------------------------------------
-- The installation row
-- ---------------------------------------------------------------------------
--
-- Why a table rather than "are there any accounts?":
--
-- The application refuses to install when a single user_account or company row
-- exists, which is the readable rule and the one an operator can check by hand.
-- It is not, on its own, a guard: two requests arriving together both read zero
-- and both proceed, and the loser of that race would have handed a second
-- stranger a master account. A primary key cannot be raced. The second
-- transaction blocks on this insert, wakes to a duplicate key and rolls back
-- everything it did, so the box has exactly one first master or none.
--
-- The CHECK is what makes the primary key useful: without it a second install
-- would simply choose another id.
CREATE TABLE installation (
    id                VARCHAR(36)  PRIMARY KEY
                      CONSTRAINT installation_is_singular CHECK (id = 'installation'),
    installed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Who was handed the keys, and which company was created with them. Both
    -- FKs: an installation row naming an account that does not exist would be
    -- a record of something that did not happen.
    master_account_id VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    company_id        VARCHAR(36)  NOT NULL REFERENCES company (id)
);

COMMENT ON TABLE installation IS
    'One row, written once by the installer. Its existence closes '
    'POST /api/v1/install permanently. Deleting it would reopen the door, which '
    'is why the trigger below refuses to.';

-- Append-once, in the same spirit as audit_log (V9): a promise the application
-- keeps is a promise until the next endpoint is added, so the database keeps
-- this one. Restoring a backup writes the row again with the rest of the dump;
-- editing it in place is what this refuses.
CREATE FUNCTION installation_refuse_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'installation is written once. Changing or removing this row would '
        'reopen POST /api/v1/install on a system that is already in use.';
END;
$$;

CREATE TRIGGER installation_written_once
    BEFORE UPDATE OR DELETE ON installation
    FOR EACH ROW EXECUTE FUNCTION installation_refuse_mutation();

CREATE TRIGGER installation_written_once_truncate
    BEFORE TRUNCATE ON installation
    FOR EACH STATEMENT EXECUTE FUNCTION installation_refuse_mutation();

-- ---------------------------------------------------------------------------
-- Company defaults, per company rather than per migration
-- ---------------------------------------------------------------------------
--
-- V6__seed_defaults.sql inserted exactly these rows with
-- `FROM company c` — every company that existed when the migration ran. On a
-- fresh installation that is none, and every company created afterwards starts
-- with an empty ladder, no 직무, no attendance statuses and no 연차 policy,
-- which is a company nobody can put anybody in.
--
-- The literals move here, unchanged, and V6 becomes spent history: THIS FILE IS
-- THE SOURCE OF TRUTH for the factory defaults from now on. Duplicating them in
-- Java would guarantee the two copies drift, so the application calls this
-- function instead of carrying its own table of Korean labels.
--
-- Everything is ON CONFLICT DO NOTHING and every id is derived from the company
-- id, so applying it twice changes nothing and a client who has already renamed
-- 사원 to something of their own keeps their row.
--
-- Every row it writes is EDITABLE by the client. Nothing in the application
-- reads these values by name or by id; built_in means "restorable to factory
-- state", not "special".
CREATE FUNCTION install_company_defaults(target_company VARCHAR)
    RETURNS INTEGER
    LANGUAGE plpgsql
AS $$
DECLARE
    written INTEGER := 0;
    batch   INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM company WHERE id = target_company) THEN
        RAISE EXCEPTION 'no such company: %', target_company;
    END IF;

    -- The six built-in statuses. A client-defined status carrying the same
    -- behaviour flags behaves identically in every report and every view.
    INSERT INTO attendance_status_type
        (id, company_id, code, label_ko, label_en, colour, icon,
         counts_as_working, requires_approval, deducts_leave_balance, visible_to_peers,
         auto_expires_after_seconds, built_in, active, sort_order)
    SELECT
        md5('status:' || target_company || ':' || s.code),
        target_company, s.code, s.label_ko, s.label_en, s.colour, s.icon,
        s.counts_as_working, s.requires_approval, s.deducts_leave_balance, s.visible_to_peers,
        s.auto_expires_after_seconds, TRUE, TRUE, s.sort_order
    FROM (VALUES
        -- code,       ko,       en,          colour,    icon,        working, approval, deducts, visible, expires, order
        ('WORKING',   '근무',    'Working',    '#2E7D32', 'desk',      TRUE,  FALSE, FALSE, TRUE,  NULL,      10),
        ('REMOTE',    '재택',    'Remote',     '#1565C0', 'home',      TRUE,  FALSE, FALSE, TRUE,  NULL,      20),
        ('FIELD',     '외근',    'Field work', '#00838F', 'briefcase', TRUE,  FALSE, FALSE, TRUE,  NULL,      30),
        -- 자리비움 expires on its own; a board full of stale "away" markers is
        -- a board people stop reading.
        ('AWAY',      '자리비움', 'Away',       '#EF6C00', 'clock',     FALSE, FALSE, FALSE, TRUE,  7200,      40),
        -- 휴가 requires approval AND deducts balance; the two always travel together.
        ('LEAVE',     '휴가',    'Leave',      '#6A1B9A', 'palm',      FALSE, TRUE,  TRUE,  TRUE,  NULL,      50),
        ('OFF',       '퇴근',    'Off',        '#546E7A', 'moon',      FALSE, FALSE, FALSE, TRUE,  NULL,      60)
    ) AS s(code, label_ko, label_en, colour, icon,
           counts_as_working, requires_approval, deducts_leave_balance, visible_to_peers,
           auto_expires_after_seconds, sort_order)
    ON CONFLICT (company_id, code) DO NOTHING;
    GET DIAGNOSTICS batch = ROW_COUNT;
    written := written + batch;

    -- The Korean 연차 default.
    --
    -- Shaped after 근로기준법 제60조 as commonly applied: one day per completed
    -- month in the first year, fifteen days from the second, and one additional
    -- day every two years thereafter, capped at twenty-five.
    --
    -- This reference is deliberately in a COMMENT and not in a code path.
    -- Statute changes; clients are often more generous than the minimum; an
    -- overseas subsidiary runs another scheme entirely. Nothing in the
    -- application knows these numbers — they are read from these rows like any
    -- other policy.
    INSERT INTO leave_policy
        (id, company_id, code, name_ko, name_en,
         monthly_accrual_days, annual_grant_days, annual_grant_after_years,
         maximum_days, carry_over_limit_days, carry_over_expiry_months,
         minimum_bookable_unit_days, built_in, active)
    VALUES (
        md5('leave-policy:' || target_company || ':ANNUAL'),
        target_company, 'ANNUAL', '연차유급휴가 (기본)', 'Annual paid leave (default)',
        1, 15, 1,
        25, NULL, 12,
        0.5, TRUE, TRUE)
    ON CONFLICT (company_id, code) DO NOTHING;
    GET DIAGNOSTICS batch = ROW_COUNT;
    written := written + batch;

    -- Tenure increments for that policy: +1 day at 3 years, +2 at 5, +3 at 7, +4 at 9.
    INSERT INTO leave_tenure_increment (id, policy_id, after_completed_years, additional_days)
    SELECT
        md5('leave-increment:' || p.id || ':' || i.years),
        p.id, i.years, i.days
    FROM leave_policy p
    CROSS JOIN (VALUES (3, 1), (5, 2), (7, 3), (9, 4)) AS i(years, days)
    WHERE p.company_id = target_company AND p.code = 'ANNUAL' AND p.built_in
    ON CONFLICT (policy_id, after_completed_years) DO NOTHING;
    GET DIAGNOSTICS batch = ROW_COUNT;
    written := written + batch;

    -- The 직급 ladder. Ordering and labels are entirely the client's: nothing in
    -- the code compares a rank by name, and 'representative' is a flag rather
    -- than a match on 대표.
    INSERT INTO rank (id, company_id, code, label_ko, label_en, seniority, representative, active)
    SELECT
        md5('rank:' || target_company || ':' || r.code),
        target_company, r.code, r.label_ko, r.label_en, r.seniority, r.representative, TRUE
    FROM (VALUES
        ('SAWON',   '사원',  'Staff',              10, FALSE),
        ('DAERI',   '대리',  'Assistant Manager',  20, FALSE),
        ('GWAJANG', '과장',  'Manager',            30, FALSE),
        ('CHAJANG', '차장',  'Deputy General Mgr', 40, FALSE),
        ('BUJANG',  '부장',  'General Manager',    50, FALSE),
        ('ISA',     '이사',  'Director',           60, FALSE),
        ('DAEPYO',  '대표',  'Representative',     70, TRUE)
    ) AS r(code, label_ko, label_en, seniority, representative)
    ON CONFLICT (company_id, code) DO NOTHING;
    GET DIAGNOSTICS batch = ROW_COUNT;
    written := written + batch;

    -- 직무. Independent of rank, many-to-many with employees.
    INSERT INTO job_function (id, company_id, code, label_ko, label_en, active)
    SELECT
        md5('function:' || target_company || ':' || f.code),
        target_company, f.code, f.label_ko, f.label_en, TRUE
    FROM (VALUES
        ('ACCOUNTING', '회계', 'Accounting'),
        ('HR',         '인사', 'Human Resources'),
        ('DEV',        '개발', 'Engineering'),
        ('SALES',      '영업', 'Sales'),
        ('LEGAL',      '법무', 'Legal'),
        ('GA',         '총무', 'General Affairs')
    ) AS f(code, label_ko, label_en)
    ON CONFLICT (company_id, code) DO NOTHING;
    GET DIAGNOSTICS batch = ROW_COUNT;
    written := written + batch;

    RETURN written;
END;
$$;

COMMENT ON FUNCTION install_company_defaults(VARCHAR) IS
    'Factory defaults for one company: 직급 ladder, 직무 set, six attendance '
    'statuses, 연차 policy and its tenure ladder. Idempotent. The literals moved '
    'here from V6__seed_defaults.sql, which could only reach companies that '
    'already existed; this file is now the source of truth for them.';

-- Companies created between V6 and this migration have none of the above.
-- Companies that V6 already covered conflict harmlessly.
SELECT install_company_defaults(id) FROM company;
