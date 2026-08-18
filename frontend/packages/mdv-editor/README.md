# @coreintra/mdv-editor

A fully-featured editor for `.mdv`, **forked from CodeMirror 6's markdown mode**
rather than written from scratch.

## Why fork CodeMirror rather than start over

mdv is a CommonMark superset. Roughly ninety percent of what an mdv editor must
do — incremental parsing, selection, undo history, bracket matching, list
continuation, IME composition — is exactly what a markdown editor does, and all
of it is subtle. CodeMirror 6 has it, is MIT-licensed, and its Lezer markdown
grammar is designed to be *extended* rather than replaced.

So `@codemirror/lang-markdown` is the base and mdv's additions layer on top:
block directives, front matter, dataset blocks and chart specs.

Two properties of CodeMirror 6 decided it over the alternatives:

- **It works under a strict CSP.** No `eval`, no `new Function`. The intranet
  serves `default-src 'none'` with no `unsafe-inline` and no `unsafe-eval`, and
  mdv itself renders happily under that; an editor that needed the CSP loosened
  would have given that away for a text box.
- **IME composition is handled properly.** Korean input goes through a composing
  state, and editors that reimplement input handling routinely break Hangul
  jamo composition in ways that only Korean users hit.

## Diagnostics come from `@mdv/lsp`, in a web worker

`@mdv/lsp` states that its transport is host-supplied — "stdio in a desktop
editor, a `MessagePort` in a web worker — so nothing in this package imports
`node:*`". So the language server runs **in the browser**, in a worker, with no
server round trip: diagnostics, completion, hover and code actions are all local
and instant.

That is why `DiagnosticsSource` is an interface: the worker-hosted LSP is the
implementation, and a server-side `mdv lint --format json` fallback can be
dropped in for environments where workers are unavailable, without the editor
knowing.

## Submission gating

`mdv lint` errors **block** submission to 결재. Warnings are shown and do not
block. A document that fails to render is not a document anyone should be asked
to approve.

## What this package deliberately does not do

It does not fork the mdv tree. `vendor/mdv` is a pinned submodule with its own
ownership rules and is never modified from here (ADR 0008).
