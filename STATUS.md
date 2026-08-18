# Build status

Against the 15 milestones in the build brief. §14 asks for slippage to be
surfaced early rather than absorbed quietly, so this is the honest version.

**Verified green at the time of writing:** the Java 8 gate proves Java 9+ APIs
fail the build, 330 backend tests pass (including integration tests against a
real PostgreSQL 16), 47 frontend tests pass, and the TypeScript strict
typecheck is clean.

## Milestone by milestone

| # | Milestone | State |
|---|---|---|
| 1 | Skeleton | **Done** |
| 2 | Business time | **Done** |
| 3 | Org + permissions | **Done** |
| 4 | Auth | **Done** |
| 5 | Approvals | **Done** — domain + schema; REST surface is thin |
| 6 | Attendance & leave | **Done** — domain + schema; team-calendar UI not built |
| 7 | Document core | **Done** — byte-identical round trip; body editor not built |
| 8 | Conversion, export & fonts | **Mostly** — worker runs and converts; 도장 compositing and the font-manager UI not built |
| 9 | HWPX + mdv | **Partial** — see below |
| 10 | 취업규칙 | **Done** |
| 11 | Temporary master | **Done** (domain, acceptance-tested) — UI not built |
| 12 | API + MCP | **Not started** |
| 13 | Accounting | **Done** (engine + reports) — batches and amortisation not built |
| 14 | Module SDK | **Done** |
| 15 | Ops | **Done** — restore proven by script, not yet by a CI test |

## What is genuinely not there

Stated plainly, because a list of what works is only useful next to this.

**No user interface.** This is the single largest gap. The frontend has the
business-time package, the forked mdv editor package, and the workspace — but
the approval inbox, the who's-in view, the document editor, the font manager
and the temporary-master issuance flow are not built. The brief names the
approval inbox and the who's-in view as "the two screens that decide whether
people like this product"; neither exists yet. Nearly everything below the UI is
there to support them.

**Milestone 12 (API + MCP) is not started.** No OpenAPI generation, no generated
`packages/api-client`, no MCP server, no webhooks, no rate limits. Two REST
endpoints exist (the effective-permissions explainer) as a pattern; the rest of
the domain is reachable only from Java.

**Milestone 9 is partial.** The pivot model, the generated fidelity matrix, the
adapters' capability declarations and the forked editor are done. The HWPX
section-model mapping is not — `HwpxAdapter.write` refuses with an explanation
rather than producing an approximate file. hwpxlib is verified working on Java 8
(blank HWPX created, written, re-read), so this is remaining work rather than a
blocked path. Also missing: the in-app HWP editor and faithful preview, template
import with the field-mapping UI, and the seven seeded ko/en templates.

**Not built anywhere:** 도장/signature compositing, the audit log tables (the
design is settled and referenced throughout, the tables are not written),
webhooks, the demo seed script, i18n resource bundles (Korean strings are
currently inline where they appear).

## Where the brief's own risk assessment proved right

§14 predicted milestones 7–9 would be where the schedule slips. It was right,
and for a reason worth recording: those milestones depend on external binaries
and formats whose behaviour has to be *discovered* rather than designed. Three
findings changed decisions mid-build:

1. **docx4j has no Java 8 line at all** (class major 55 even at 11.5.5), which
   settled the §6.1 engine choice as Apache POI. Found by reading class file
   headers, not release notes.
2. **No object model can give a byte-identical round trip.** Measured: opening
   the test fixture through POI and saving it unchanged alters or drops 11 of
   its 12 parts. That forced the package-level architecture, which is why the
   round trip is byte-identical rather than merely equivalent.
3. **fontconfig substitutes silently, and installing a Korean font is not
   enough.** `fc-match Pretendard` returns DejaVu Sans — no Korean coverage at
   all — and after installing Noto CJK, LibreOffice still embedded a *Chinese*
   face. Substitution detection had to be ours.

## Corrections made during the build

- **ADR 0008 initially said `@mdv/lsp` does not exist**, on the strength of
  `CONTRACTS.md` listing it as "not scaffolded". That was wrong: the package is
  implemented, and its transport is host-supplied with no `node:*` imports, so
  the language server runs in a web worker in the browser. The ADR is corrected
  and the editor wires the LSP as the brief asked, rather than working around it.
- **`Amount.ofDouble` was removed.** It was a runtime tripwire that did not
  catch the mistake it claimed to and violated the ArchUnit rule it was meant to
  support. Replaced with build-time bans on `BigDecimal.valueOf(double)` and
  `new BigDecimal(double)` in money paths, which catch the real thing.

## Open questions from §15 answered by default

Defaults taken rather than asked, all from the brief itself:

- Korean 연차 seeded as an **editable policy row**, statutory reference in a
  comment rather than a code path.
- **No 세무 interop** (§9 excludes tax filing and bank integration).
- **No AGPL component in any shipped configuration** — the default install ships
  neither Collabora nor OnlyOffice, as §6.2 specifies.
- mdv conformance: **Level 2 (Standard)** as substantiated by the repo's own
  report. Complex-script shaping is Level 3, so Arabic and Indic are declared
  unsupported in the fidelity matrix rather than misrendered.

## Suggested order from here

1. **Milestone 12 (API + MCP)**, then the UI. Nearly every remaining gap is a
   screen, and screens need endpoints. Doing the API first also makes the MCP
   server almost free, since it exposes the same capabilities.
2. **The approval inbox and the who's-in view**, in that order — the two screens
   the brief says decide whether people like the product.
3. **HWPX section mapping**, which unblocks the seeded templates and the
   template-import flow.
