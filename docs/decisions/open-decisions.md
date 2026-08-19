# Open decisions

§0.2 of the brief asks for every open decision found in it, each with a proposed
default and a one-line rationale, and says not to guess silently on anything
security- or money-related. §15 names eight it expects.

This build was asked to run autonomously, so every question below has been
**answered with a default rather than left waiting**. Each one is reversible,
and each says what it would cost to change. Where the answer touches security or
money it is argued rather than asserted, and the ones that need a human are
marked **NEEDS A DECISION** — they are implemented in the safe direction
meanwhile.

---

## The eight from §15

### 1. Leave-accrual defaults
**Taken:** the Korean 연차 rule seeded as an **editable policy row**, with the
statutory reference in a comment rather than in a code path.
*Why:* §5 requires accrual to be configuration, not statute. A client whose
collective agreement is more generous edits a row; nobody edits Java.

### 2. Does payroll or salary data live in this system?
**Taken: no.** There is no salary column anywhere, and `hr.compensation:read`
exists only as a *temporary-master capability* — a permission the support flow
can name in order to refuse it.
*Why:* it changes the temporary-master threat model completely. If the answer
becomes yes, revisit §8's capability list before writing a single column.
**NEEDS A DECISION** if payroll is ever in scope.

### 3. How many concurrent client installations?
**Taken:** one box per client, sized for 4 vCPU / 12 GB / ~300 users, with no
assumption about how many boxes exist.
*Why:* nothing in the design is per-fleet. The one place it shows is the rate
limiter, whose counters are in memory and correct for a single JVM; the class
says so and names itself as the thing to replace.

### 4. Does accounting need to interoperate with an existing 세무 system?
**Taken: no.** §9 excludes tax filing and bank integration, and the engine is
built so they can sit on top: entries are pure, reports are pure functions, and
the FX rate is always supplied by the caller and recorded.

### 5. HWP priority — is HWPX-only acceptable for v1?
**Taken:** HWPX is the read/write target; legacy `.hwp` is **read-only,
best-effort**. Writing legacy `.hwp` is not implemented.
*Why:* §6.5 prefers HWPX and calls the binary format best-effort. The
docx → HWPX → docx round trip preserves every content control and its bound
value, which is the property that matters, and 누름틀 carries the binding in a
way that survives 한글 saving the file.
**NEEDS A DECISION** if a client mandates writing legacy `.hwp` — that is a
significant scope difference, exactly as the brief warned.

### 6. Licensing — is an AGPL component acceptable in any shipped configuration?
**Taken: no.** The default install ships neither Collabora (MPL-2.0) nor
OnlyOffice (AGPL-3.0). The `DocumentEditor` abstraction exists so a client can
plug one in themselves.
*Why:* §6.2 asks for the AGPL implication to be raised before OnlyOffice becomes
a default, and it materially affects a vendor-hosted commercial offering. Not
shipping it is the reversible choice.

### 7. mdv — conformance level, vendoring, and whether it is the default for reports
**Taken:** Level 2 (Standard), vendored as a pinned submodule, and **not** the
default format for reports.
*Why:* `CONFORMANCE.md` substantiates Level 2, so Arabic and Indic are declared
unsupported in the fidelity matrix rather than misrendered. On vendoring: the
packages are unpublished at 0.0.0, and a submodule keeps the pin visible in the
tree.

On making it the default — **NEEDS A DECISION**, and there is new evidence. mdv
is the only format here that produces a real diff, which is a strong argument
for board packs. But the pinned build **embeds no fonts at all**: Korean renders
as `?` with a warning and an exit status of zero, `pdf-ua-1` stamps an
accessibility claim onto a file that cannot meet it, and `pdf-a-3b` is a no-op.
The worker refuses all three rather than producing a document that looks
finished. Until that is fixed upstream, **mdv is a first-class authoring, diffing
and viewing format and not a Korean PDF path.** See ADR 0008.

### 8. Any mandated 전자결재 format or 외감 filing requirement?
**Taken:** none assumed. Exports cover DOCX, DOC, PDF and HWP/HWPX, and the
fidelity matrix is data so a mandated pair can be checked before it is promised.
**NEEDS A DECISION** per client.

---

## Decisions the build itself forced

These were not in §15. They came out of the work and are the ones most worth
your attention.

### A fresh installation cannot be opened — **NEEDS A DECISION**
Found by writing the demo seed. Nothing creates a `UserAccount`, and the grant
service correctly refuses any permission the caller does not already hold, so
the first grant on an empty box is impossible. An installer is being added; the
question it forces is **what the bootstrap grant set should be** — the smallest
set that lets a master open the system, rather than a comfortable one. That is a
security decision and it should be reviewed rather than inherited.

### Opaque session tokens rather than JWTs
**Taken:** opaque, stored hashed, checked against the registry on every request.
*Why:* §8 promises a **Revoke now** button on a live support session. A
self-validating token keeps working until it expires, which would make that
button a lie. On one box the check is cheaper than verifying a signature. ADR 0009.

### The support-session banner is not permission-gated
**Taken:** authenticated and scoped to the caller's own company, with no
permission required — a narrow, deliberate exception to §10.
*Why:* an oversight notice whose visibility depends on a grant is one the
overseen party can switch off, and more likely one that quietly stops working
when somebody tidies up a permission nobody could explain. The failure is silent
and points the wrong way.

### Webhook secrets are encrypted, not hashed
**Taken:** encrypted at rest, prefix visible. This departs from the brief.
*Why:* we are the sender, so signing needs the plaintext back. No version of
this feature can hash it. The migration says so at the column.

### Money never becomes a `Number` in the browser
**Taken:** exact decimal strings end to end, with the arithmetic in
`@coreintra/money`.
*Why:* the backend's no-floating-point guarantee is worth nothing if a React
component does `Number(value).toFixed(2)`. ADR 0010.

### Korean is the i18n fallback, not English
**Taken:** `fallbackLng: 'ko'`.
*Why:* the usual `'en'` means a missing Korean string silently shows English to
a Korean user — which both hides the gap and is the English-first retrofit the
brief rules out. Falling back to the source language makes a missing *English*
string visible instead.

### An in-memory rate limiter
**Taken:** counters in memory, policies in the database.
*Why:* a limit protects against a runaway caller; it is not an accounting
record. A row per request would put the heaviest write load in the system on the
path already under attack.

### Retention cannot prune the audit log
**Taken:** the append-only trigger refuses every delete, so pruning beyond the
retention period is a DBA act that requires disabling it.
*Why:* §12 says no account can delete the log, master included. Making retention
enforceable in the application would mean building the delete path the rule
forbids. Visible by construction rather than convenient.
