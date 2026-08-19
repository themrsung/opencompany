# 4. Money is BigDecimal, stored raw, rounded only at display

- Status: accepted
- Date: 2026-08-18

## Problem

Floating point cannot represent 0.1. A single `double` anywhere in a money path
produces entries that do not balance, and the failure is intermittent and
depends on the values. Rounding on write destroys information permanently and
cannot be undone by a later report.

## Decision

`BigDecimal` everywhere in money paths. Never `double`, never `float` — enforced
by an ArchUnit rule that fails the build, not by review.

- **Store raw at full precision. Round only at display.** No rounding on write,
  ever.
- Amounts cross the wire as **exact decimal strings**, never JSON numbers — a
  JSON number is a `double` in most parsers, which reintroduces the problem at
  the boundary. Optional thousands separators are accepted on input;
  `1e3`, `.5` and `1.` are rejected.
- Equality for the balance check is **numeric** (`compareTo == 0`), not
  `equals` — `1.00` and `1.0` are the same amount, and an entry must not be
  rejected for differing scale. Rounding gaps are still a rejection.
- Currencies are client-definable. KRW (0 display decimals) and USD (2) are
  seeded. `displayDecimals` is presentation only and never rescales storage. A
  "currency" may be any unit of account — a commodity, a share count — so no
  money-specific semantics are assumed.
- Foreign-currency postings carry both `amount` and `baseAmount`. **The system
  never looks up an FX rate.** The rate is always supplied by the caller and
  recorded with the posting.

## Consequences

- The shared `<Amount>` component is the only way a number reaches a screen, so
  full-decimal display cannot be accidentally omitted from a report. Rounded and
  exact values are shown distinctly, never ambiguously.
- `displayDecimals: 0` for KRW means the UI shows `1,000` while storage may hold
  `1000.0000001`. The expand affordance is therefore not optional polish; it is
  how a user sees what is actually stored.
