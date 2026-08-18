# 2. The 72-hour business day

- Status: accepted
- Date: 2026-08-18

## Problem

A shift that ends at 03:00 belongs to the previous business day. A briefing at
22:00 the night before belongs to the next one. Modelling these as ordinary
timestamps forces every consumer to re-derive "which day was this really?", and
they will not all derive it the same way.

## Decision

A moment is a `BusinessInstant`: `(LocalDate businessDate, int offsetSeconds)`
with `offsetSeconds` in `[-86400, +172800]` inclusive — a closed 72-hour window
whose clock face runs `-24:00:00` to `+48:00:00`.

- Wire form `YYYY-MM-DDT[-]HH:MM:SS.mmm`. No timezone, no `Z`. A `Z` is invalid
  input and is rejected loudly rather than coerced.
- **Ordering is date first, then offset.** `2026-08-30T26:01:00.000` precedes
  `2026-08-31T-03:22:00.000` even though the second names an earlier wall-clock
  moment. Day-level comparison always wins.
- Persisted as `business_date DATE` + `offset_seconds INT`, with a derived
  `absolute_ts` for range scans, indexed on `(business_date, offset_seconds)`.
- A real UTC `created_at` is recorded separately and always. Business time is
  what the organisation agrees happened; UTC is what the machine observed. They
  are never conflated and never derived from each other.

## Consequences

- Wire strings must never be sorted or compared lexically: `-` sorts below every
  digit, so `-03:22` would sort before `00:00` within a day (correct by luck)
  but the date-first rule is what actually governs. `BusinessInstantComparator`
  is the only ordering, and a property test asserts the two orderings genuinely
  differ on the pathological cases.
- `absolute_ts` is derived and exists only for range scans. It is never the
  ordering key, because it would reorder the two instants above.
- The frontend carries a structurally identical implementation in
  `packages/business-time` with the same comparator and the same rejection
  rules, tested against the same vectors, because a picker that cannot express
  `27:00` pushes users into lying about when work happened.
