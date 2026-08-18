-- Business time conventions (ADR 0002).
--
-- Every business instant in this schema is stored as the same three columns:
--
--   <name>_business_date   DATE                      not null
--   <name>_offset_seconds  INTEGER  not null, CHECK business_offset_is_valid()
--   <name>_absolute_ts     TIMESTAMP  GENERATED ...  stored, derived
--
-- plus an index on (business_date, offset_seconds) -- the business ordering.
--
-- Three rules this file enforces so they cannot be forgotten per-table:
--
--  1. The 72-hour window is a DATABASE constraint, not application validation.
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

-- The 72-hour window, defined once and applied as a CHECK on every offset column.
--
-- This was originally a CREATE DOMAIN, which reads better. Hibernate 5.6's
-- schema validator reports a domain-typed column as Types#DISTINCT rather than
-- INTEGER and refuses to start, so a domain would force ddl-auto validation off
-- across the whole application -- losing a real check to gain a nicer type name.
-- A shared IMMUTABLE function keeps the rule in exactly one place while leaving
-- the column an ordinary int4.
CREATE FUNCTION business_offset_is_valid(offset_seconds INTEGER)
    RETURNS BOOLEAN
    LANGUAGE SQL
    IMMUTABLE
    PARALLEL SAFE
AS $$
    SELECT offset_seconds >= -86400 AND offset_seconds <= 172800
$$;

COMMENT ON FUNCTION business_offset_is_valid(INTEGER) IS
    'The 72-hour window. -86400 = -24:00:00, +172800 = +48:00:00. A 03:00 shift '
    'end is 97200 (27:00) on the business day it belongs to. Every offset column '
    'carries CHECK (business_offset_is_valid(<column>)).';

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
