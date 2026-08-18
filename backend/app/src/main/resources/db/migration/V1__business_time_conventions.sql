-- Business time conventions (ADR 0002).
--
-- Every business instant in this schema is stored as the same three columns:
--
--   <name>_business_date   DATE                      not null
--   <name>_offset_seconds  business_offset_seconds   not null
--   <name>_absolute_ts     TIMESTAMP  GENERATED ...  stored, derived
--
-- plus an index on (business_date, offset_seconds) -- the business ordering.
--
-- Three rules this file enforces so they cannot be forgotten per-table:
--
--  1. The 72-hour window is a DOMAIN constraint, not application validation.
--     A raw SQL insert from a migration, a support session or a client module
--     cannot write an offset outside [-86400, +172800].
--
--  2. absolute_ts is GENERATED ALWAYS ... STORED, never application-computed.
--     A derived column that application code maintains drifts the moment
--     anything writes without going through that code. It exists solely for
--     range scans and is never an ordering key -- ordering is date first, then
--     offset, and absolute_ts deliberately disagrees with that.
--
--  3. created_at (UTC) is separate and always present. Business time is what
--     the organisation agrees happened; UTC is what the machine observed.

CREATE DOMAIN business_offset_seconds AS INTEGER
    CONSTRAINT business_offset_within_72h_window
        CHECK (VALUE >= -86400 AND VALUE <= 172800);

COMMENT ON DOMAIN business_offset_seconds IS
    'Seconds from the business date''s midnight. -86400 = -24:00:00, +172800 = +48:00:00. '
    'A 03:00 shift end is 97200 (27:00) on the business day it belongs to.';

-- Convenience for reports that need the derived wall-clock moment in a query
-- where a generated column is not available (e.g. over a CTE).
CREATE FUNCTION business_absolute_ts(business_date DATE, offset_seconds INTEGER)
    RETURNS TIMESTAMP
    LANGUAGE SQL
    IMMUTABLE
    PARALLEL SAFE
AS $$
    SELECT business_date + make_interval(secs => offset_seconds)
$$;

COMMENT ON FUNCTION business_absolute_ts(DATE, INTEGER) IS
    'Derived wall-clock moment. For range scans only -- never an ORDER BY key. '
    'Business ordering is (business_date, offset_seconds).';
