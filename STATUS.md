# Build status

Against the 15 milestones in the build brief. §14 asks for slippage to be
surfaced early rather than absorbed quietly, so this is the honest version, and
the section on what is *not* there is the one worth reading.

**Verified green at the time of writing:** `./mvnw -B verify` passes with
**1,094 backend tests**, including integration tests against a real
PostgreSQL 16 and 17 ArchUnit rules. The Java 8 gate proves a Java 9+ API call
fails the build. The frontend typechecks under `strict` with
`noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`, lints with no
`any`, and builds.

The API is **143 endpoints** across 17 areas, generating an OpenAPI 3.1
document that is committed at `docs/api/openapi.json` and from which the
TypeScript client is generated. CI regenerates both and fails on any drift.

## Milestone by milestone

| # | Milestone | State |
|---|---|---|
| 1 | Skeleton | **Done** |
| 2 | Business time | **Done** |
| 3 | Org + permissions | **Done** — services, explainer, and the API over both |
| 4 | Auth | **Done** — access tokens were missing entirely and are now minted |
| 5 | Approvals | **Done** — domain, persistence, services, REST and MCP |
| 6 | Attendance & leave | **Done** — including the who's-in board |
| 7 | Document core | **Done** — byte-identical round trip, persistence, services |
| 8 | Conversion, export & fonts | **Done** — worker, font store, licence acknowledgement, signature compositing |
| 9 | HWPX + mdv | **Done for the backend** — HWPX write, seven seeded templates, mdv in the worker. In-app HWP editor and template-import field mapping are UI and are not built |
| 10 | 취업규칙 | **Done** — including a table that makes an unapproved version structurally impossible |
| 11 | Temporary master | **Done** — domain, persistence, API, capability wording |
| 12 | API + MCP | **Done** — 143 endpoints, generated client with a drift gate, MCP server, webhooks, rate limits |
| 13 | Accounting | **Done** — engine, persistence, batches, amortisation, six reports, permission gate |
| 14 | Module SDK | **Done** |
| 15 | Ops | **Done** — restore now proven by a CI test rather than by a script |

## What is genuinely not there

### A fresh installation could not be opened until very late in this build

This is the most important thing on the page. Writing the demo seed proved that
an empty box was unusable: **nothing creates a `UserAccount`**, and the grant
service correctly refuses any permission the caller does not already hold, so
the first grant was impossible. `company_representation` and
`approval_line_template` were read by the system and written by nobody.

An installer closes it. The question it forces — **what the minimum bootstrap
grant set should be** — is a security decision and is written up in
`docs/decisions/open-decisions.md` rather than quietly settled.

Related, and still true: `V6__seed_defaults.sql` seeds ranks, 직무, attendance
statuses and the 연차 policy **only for companies that existed when the
migration ran**, which on a fresh install is none. Company creation now applies
them; a client adding a second company would otherwise have hit the same wall.

### mdv cannot produce a Korean PDF

The pinned build **embeds no fonts at all** — not "CJK needs configuration".
Korean renders as `?`, `MDV5100` is raised as a *warning*, and the exit status
is zero. `pdf-ua-1` stamps an ISO 14289-1 accessibility claim onto a file whose
fonts are not embedded, which that standard requires; `pdf-a-3b` is a no-op.

The conversion worker refuses all three rather than producing a document that
looks finished and is not, and Korean PDF takes the LibreOffice path. mdv
remains a first-class **authoring, diffing and viewing** format — it is the only
format here that can produce a real version diff, which is why it is here — and
it is not yet a Korean PDF path. ADR 0008 and the fidelity matrix both say so.

### Things that are not built

- **The in-app HWP editor and faithful preview**, and **template import with the
  field-mapping UI** (§6.6, §6.7). The backend can read, write and convert
  HWPX; the editing surfaces for it are not built.
- **Leave balances in the demo seed.** A 휴가신청서 cannot be fully approved
  because final approval fires the deduction adapter, which reads the day count
  off a document body that the seeded template does not yet supply. The seed
  refuses rather than faking one.
- **Notifications** are a logging `Notifier` only. No in-app inbox, no mail
  wiring beyond the SPI.
- **i18n resource bundles on the backend.** Korean strings in Java are still
  inline where they appear. The frontend catalogue is complete and typed; the
  server's is not.
- **Client modules** beyond the SPI, the registry and the worked example.

### Known compromises, each documented where it lives

- **Webhook secrets are encrypted, not hashed**, contrary to the brief. We are
  the sender, so signing needs the plaintext back; no version of this feature
  can hash it. The prefix-visible half is kept.
- **The support-session banner is not permission-gated** — a narrow, argued
  exception to §10, because an oversight notice whose visibility depends on a
  grant is one the overseen party can switch off.
- **Rate-limit counters are in memory**, correct for the one-box deployment and
  named as the thing to replace if that changes.
- **No `@Version` column** on several tables, so ETags derive from the mutable
  fields. Correct for concurrency; an edit-then-exact-revert reuses a tag.
- **Retention cannot prune the audit log.** The append-only trigger refuses
  every delete, so pruning is a DBA act. That is the cost of §12's guarantee.

## Bugs this build found in work that already looked finished

Worth recording, because each had been green in CI:

1. **The integration tests had never run against a database.**
   `@DynamicPropertySource` on a standalone helper class is never invoked, so
   Boot fell back to `localhost:5432` and the suite failed with "connection
   refused" on a machine with Docker running the whole time.
2. **`SessionService` declared `ACCESS_TOKEN_LIFETIME` and minted no access
   token.** Nothing an ordinary request could present existed.
3. **`ApiKeyService.issue` threw on every call** — it minted the *public* prefix
   with the bearer-secret generator, which correctly refuses below 128 bits. The
   scoped-key path had never worked end to end.
4. **`permission_grant` had no `revoked_at`**, so revoking meant `DELETE` —
   breaking the no-hard-delete rule exactly where an auditor asks the question,
   and discarding the mandatory reason.
5. **`balanceSheet` double-counted a closed year's profit.** It excluded closing
   batches from `unclosedNetIncome`, so after any year-end close the same profit
   sat in both equity and the unclosed line and the `balanced` alarm fired. An
   alarm that goes off every January for every client is an alarm nobody reads.
6. **The OpenAPI document had 22 dangling `$ref`s** the first time it was
   generated — springdoc omits schemas that appear only as array item types.
7. **The `api-client-drift` CI job could never have passed**: no frontend
   dependencies installed, and the spec never regenerated.
8. **Twelve test classes were red for one reason** — each rolled its own
   cleanup, so every new table with a foreign key to an account broke a
   different set, in whichever class happened to run next.

## Where the brief's own risk assessment proved right, again

§14 predicted milestones 7–9 would be where the schedule slips, and it was right
for the same reason as before: those milestones depend on external binaries and
formats whose behaviour has to be *discovered*. This round the discoveries were
in hwpxlib — it stamps zip entries with the current time, so two writes seconds
apart hash differently; it deflates `mimetype`, which an OCF container requires
stored and first; and it silently drops any attached part whose media type is
not an image — and in mdv, above.

The other place effort went that the brief did not predict is **the seams
between modules**. Almost every defect in the list above lives at a boundary:
between a test helper and Spring, between a service and its schema, between
springdoc and a generator, between one test class's cleanup and the next class's
fixtures.
