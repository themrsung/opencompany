# 8. mdv is vendored as a pinned submodule and rendered in Node

- Status: accepted
- Date: 2026-08-18

## Problem

mdv (`themrsung/mdv`) is a first-class document format here, but it is a
TypeScript monorepo at version 0.0.0, unpublished, and **there is no JVM
implementation**. The backend is Java 8.

## Decision

- **Vendor as a pinned git submodule** at `vendor/mdv`, not a registry
  dependency: the packages are unpublished, and a private registry is
  infrastructure this deployment model (one box, possibly no outbound internet)
  should not require. The pin is a commit SHA, recorded in every render's
  metadata.
- **Never modify the vendored tree.** It has its own ownership rules
  (`CONTRACTS.md` SS1.1). Everything we need is additive and lives in
  `ops/conversion-worker`.
- **Server-side mdv rendering runs in Node, not Java.** It goes in the
  conversion-worker container alongside LibreOffice, behind the same job queue.
  We do not attempt a Java port and never shell out from a request thread.

Two findings from reading the repo that change what we can promise:

1. **Conformance is Level 2 (Standard).** `CONFORMANCE.md` records level 3 as
   *asked for* but level 2 as *substantiated*. Complex-script shaping is a
   Level 3 feature, so **Arabic and Indic must not be promised** — the fidelity
   matrix says unsupported rather than misrendering. Korean, Latin, CJK are
   Level 2 and fine.
2. ~~**`@mdv/lsp` does not exist.**~~ **Corrected — this finding was wrong.**
   It was taken from `CONTRACTS.md` §3, which lists the package under "not
   scaffolded". The document is out of date: `packages/lsp/src/` is implemented,
   and its transport is host-supplied with no `node:*` imports, so the language
   server runs in a web worker in the browser. The editor wires the LSP as the
   brief asked. `mdv lint --format json` remains behind the same
   `DiagnosticsSource` interface as the non-interactive path, which is what CI
   uses.

   Worth recording as a method note as well as a fact: this was believed for
   long enough to be written into an ADR and asserted as corrected in
   `STATUS.md` while the ADR still said the opposite. A package's own
   documentation is evidence about intent, not about what shipped. The findings
   below were all obtained by running the code.

Determinism is the reason mdv earns its place in the approval trail, so the full
render config is snapshotted with every approval: spec version, mdv commit,
theme, `buildTime`, `locale`, `timezone`, font set. `now()` is `config.buildTime`
and it is **never** allowed to default — an unpinned build time silently
destroys reproducibility, which is the one property we are relying on.

## Consequences

- Stored mdv is canonical form: `mdv fmt` runs on save, so version diffs are
  clean and `mdv fmt` on stored text is a no-op (asserted by test).
- Security defaults are inherited, not relaxed: `security.allowExternal: false`,
  raw HTML disabled, and a document's `plugins:` key is a request we ignore —
  only we register plugins.
- CJK requires explicit `pdf.fonts` entries; mdv will not pick up a CJK face
  implicitly. The font store therefore feeds three consumers from one record:
  fontconfig, the browser webfont, and mdv's `pdf.fonts`.

## Later findings, from running the pinned build rather than reading it

These are worse than the brief anticipated and they change what we ship.

1. **The pinned build embeds no fonts at all.** Not "CJK needs `pdf.fonts`" —
   `render-pdf/src/fonts.ts` says so in as many words, the CLI passes
   `fonts: []`, and `pdf.fonts` is not among `CONFIG_KEYS`. Korean comes out as
   `?` with `MDV5100` raised as a **warning** and an exit status of zero, which
   is the worst possible shape for a failure: a document that looks produced and
   is unreadable. The conversion worker therefore **refuses** an mdv PDF
   containing Korean rather than emitting one, and Korean documents take the
   LibreOffice path. The "one font store, three consumers" design stands; the
   third consumer is not yet able to accept the record.
2. **`profile: pdf-a-3b` is a no-op, and `pdf-ua-1` is actively harmful.** The
   latter stamps an ISO 14289-1 conformance claim into a file whose fonts are
   not embedded — which that standard requires. The brief asked what the state
   of the upstream font-embedding clause was: it is not merely open, it is
   *claimed and unmet*. Both profiles are refused until that changes, because
   emitting a false accessibility claim on an archived 결재 document is worse
   than emitting no claim.
3. **mdv does not refuse a missing `buildTime`** — core defaults it to
   `new Date(0)`. Every guarantee in this ADR about determinism rests on our own
   refusal to render without one, not on any check upstream. That refusal is
   ours to keep working.
4. Only `pdf`, `svg`, `json` and `csv` export targets are implemented in this
   build. `html`, `png` and `md` are refused rather than silently producing
   nothing.

The consequence for the product is narrow but real: **mdv is a first-class
format for authoring, diffing and viewing, and not yet a Korean PDF path.**
That is a defensible place to be — the diff is the reason the format is here —
but it must not be described as more than it is.
