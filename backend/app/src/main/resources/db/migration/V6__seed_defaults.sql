-- Seeded defaults. Every row here is EDITABLE by the client.
--
-- Nothing in the application reads these values by name or by id. They exist so
-- a fresh install is usable on first boot, and a client who renames, reorders
-- or deletes any of them breaks nothing.

-- The six built-in statuses. A client-defined status carrying the same
-- behaviour flags behaves identically in every report and every view; built_in
-- only means "restorable to factory state".
INSERT INTO attendance_status_type
    (id, company_id, code, label_ko, label_en, colour, icon,
     counts_as_working, requires_approval, deducts_leave_balance, visible_to_peers,
     auto_expires_after_seconds, built_in, active, sort_order)
SELECT
    md5('status:' || c.id || ':' || s.code),
    c.id, s.code, s.label_ko, s.label_en, s.colour, s.icon,
    s.counts_as_working, s.requires_approval, s.deducts_leave_balance, s.visible_to_peers,
    s.auto_expires_after_seconds, TRUE, TRUE, s.sort_order
FROM company c
CROSS JOIN (VALUES
    -- code,       ko,       en,          colour,    icon,        working, approval, deducts, visible, expires, order
    ('WORKING',   '근무',    'Working',    '#2E7D32', 'desk',      TRUE,  FALSE, FALSE, TRUE,  NULL,      10),
    ('REMOTE',    '재택',    'Remote',     '#1565C0', 'home',      TRUE,  FALSE, FALSE, TRUE,  NULL,      20),
    ('FIELD',     '외근',    'Field work', '#00838F', 'briefcase', TRUE,  FALSE, FALSE, TRUE,  NULL,      30),
    -- 자리비움 expires on its own; a board full of stale "away" markers is a
    -- board people stop reading.
    ('AWAY',      '자리비움', 'Away',       '#EF6C00', 'clock',     FALSE, FALSE, FALSE, TRUE,  7200,      40),
    -- 휴가 requires approval AND deducts balance; the two always travel together.
    ('LEAVE',     '휴가',    'Leave',      '#6A1B9A', 'palm',      FALSE, TRUE,  TRUE,  TRUE,  NULL,      50),
    ('OFF',       '퇴근',    'Off',        '#546E7A', 'moon',      FALSE, FALSE, FALSE, TRUE,  NULL,      60)
) AS s(code, label_ko, label_en, colour, icon,
       counts_as_working, requires_approval, deducts_leave_balance, visible_to_peers,
       auto_expires_after_seconds, sort_order)
ON CONFLICT (company_id, code) DO NOTHING;

-- The Korean 연차 default.
--
-- Shaped after 근로기준법 제60조 as commonly applied: one day per completed month
-- in the first year, fifteen days from the second, and one additional day every
-- two years thereafter, capped at twenty-five.
--
-- This reference is deliberately in a COMMENT and not in a code path. Statute
-- changes; clients are often more generous than the minimum; an overseas
-- subsidiary runs another scheme entirely. Nothing in the application knows
-- these numbers — they are read from these rows like any other policy.
INSERT INTO leave_policy
    (id, company_id, code, name_ko, name_en,
     monthly_accrual_days, annual_grant_days, annual_grant_after_years,
     maximum_days, carry_over_limit_days, carry_over_expiry_months,
     minimum_bookable_unit_days, built_in, active)
SELECT
    md5('leave-policy:' || c.id || ':ANNUAL'),
    c.id, 'ANNUAL', '연차유급휴가 (기본)', 'Annual paid leave (default)',
    1, 15, 1,
    25, NULL, 12,
    0.5, TRUE, TRUE
FROM company c
ON CONFLICT (company_id, code) DO NOTHING;

-- Tenure increments for that policy: +1 day at 3 years, +2 at 5, +3 at 7, +4 at 9.
INSERT INTO leave_tenure_increment (id, policy_id, after_completed_years, additional_days)
SELECT
    md5('leave-increment:' || p.id || ':' || i.years),
    p.id, i.years, i.days
FROM leave_policy p
CROSS JOIN (VALUES (3, 1), (5, 2), (7, 3), (9, 4)) AS i(years, days)
WHERE p.code = 'ANNUAL' AND p.built_in
ON CONFLICT (policy_id, after_completed_years) DO NOTHING;

-- The 직급 ladder. Ordering and labels are entirely the client's: nothing in
-- the code compares a rank by name, and 'representative' is a flag rather than
-- a match on 대표.
INSERT INTO rank (id, company_id, code, label_ko, label_en, seniority, representative, active)
SELECT
    md5('rank:' || c.id || ':' || r.code),
    c.id, r.code, r.label_ko, r.label_en, r.seniority, r.representative, TRUE
FROM company c
CROSS JOIN (VALUES
    ('SAWON',   '사원',  'Staff',              10, FALSE),
    ('DAERI',   '대리',  'Assistant Manager',  20, FALSE),
    ('GWAJANG', '과장',  'Manager',            30, FALSE),
    ('CHAJANG', '차장',  'Deputy General Mgr', 40, FALSE),
    ('BUJANG',  '부장',  'General Manager',    50, FALSE),
    ('ISA',     '이사',  'Director',           60, FALSE),
    ('DAEPYO',  '대표',  'Representative',     70, TRUE)
) AS r(code, label_ko, label_en, seniority, representative)
ON CONFLICT (company_id, code) DO NOTHING;

-- 직무. Independent of rank, many-to-many with employees.
INSERT INTO job_function (id, company_id, code, label_ko, label_en, active)
SELECT
    md5('function:' || c.id || ':' || f.code),
    c.id, f.code, f.label_ko, f.label_en, TRUE
FROM company c
CROSS JOIN (VALUES
    ('ACCOUNTING', '회계', 'Accounting'),
    ('HR',         '인사', 'Human Resources'),
    ('DEV',        '개발', 'Engineering'),
    ('SALES',      '영업', 'Sales'),
    ('LEGAL',      '법무', 'Legal'),
    ('GA',         '총무', 'General Affairs')
) AS f(code, label_ko, label_en)
ON CONFLICT (company_id, code) DO NOTHING;
