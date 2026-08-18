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
2. **`@mdv/lsp` does not exist.** `CONTRACTS.md` SS3 lists it under "Not
   scaffolded ... out of scope for this pass". The brief's instruction to wire
   `@mdv/lsp` into the editing surface therefore cannot be followed as written.
   Live diagnostics come from `mdv lint --format json` via `@mdv/cli` instead,
   behind a `DiagnosticsSource` interface so the LSP drops in later without
   touching the editor.

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
