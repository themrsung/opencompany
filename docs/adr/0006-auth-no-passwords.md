# 6. No passwords: username + TOTP

- Status: accepted
- Date: 2026-08-18

## Problem

Passwords are the single largest source of account compromise, and every
password system drags in a reset flow, which is itself a well-known account
takeover path. For an intranet holding approval authority and, in some
configurations, a general ledger, the reset flow is the weak point.

## Decision

**No passwords anywhere.** No password column, no reset flow, no "temporary
password". This is stated in the README because it surprises people.

- Primary factor: username + TOTP (RFC 6238), SHA-1 / 6 digits / 30s by default,
  ±1 step drift, per-user secret, QR enrolment.
- One-time recovery codes, hashed at rest, single-use, remaining count shown.
- **Replay protection**: a consumed `(userId, timeStep)` cannot be reused, so
  observing a code in transit does not allow a second use within its window.
  Progressive lockout on failures.
- Optional second factor: email OTP, available *only* when a mail system is
  configured. `MailSender` is an SPI with an SMTP implementation; with no mail
  configured the option is hidden, not broken.
- Sessions: short-lived access token plus rotating refresh token, HttpOnly +
  SameSite cookies, a server-side revocable session registry, and a visible
  active-session list with remote sign-out.
- Service accounts use scoped API keys: hashed at rest, prefix-visible for
  identification, per-key grants, expiry, last-used timestamp, one-click revoke.

Master is an account flag, not a magic user id, and there may be several. The
system refuses to demote or delete the last active master.

## Consequences

- Losing a device is an admin action, itself audited; for master accounts it
  requires 대표 approval under the active representation mode. There is no
  self-service recovery, and that is the point.
- SHA-1 in TOTP is RFC 6238's default and is what authenticator apps implement;
  it is a MAC construction here, not a collision-resistance claim, so it is not
  the weakness it looks like.
- Recovery codes are the only bearer secret in the system. They are shown once.
