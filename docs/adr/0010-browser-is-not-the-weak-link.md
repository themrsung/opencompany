# 10. The browser is not allowed to be the weak link

- Status: accepted
- Date: 2026-08-18

## Problem

Three of this system's guarantees are enforced with real effort on the server
and are trivially destroyed in the browser:

1. **Money.** `BigDecimal` everywhere, an ArchUnit rule banning floating point in
   the accounting module, amounts on the wire as exact decimal strings — all of
   it worth nothing if a React component does `Number(value).toFixed(2)`.
2. **Business time.** A 72-hour clock face where `27:00` is a correct value.
   `new Date("2026-08-30T27:00:00.000")` is `Invalid Date`, and a picker built on
   `<input type="time">` cannot express the value at all, so the browser can
   quietly make the model unreachable while the backend still supports it.
3. **Korean.** §12 requires Korean and English both first-class from commit one.
   Every project that says this and then writes English strings first ends up
   with a Korean translation that reads like a translation.

Each of these fails silently. A rounded figure looks like a figure; a missing
Korean string looks like a working screen in English; a picker that cannot
express `27:00` looks like a picker.

## Decision

### Money never becomes a `Number`

`@coreintra/money` holds amounts as `{ unscaled: bigint, scale: number }` —
the same shape as `BigDecimal`, for the same reason — and nothing in it calls
`Number()` or `parseFloat`. Parsing is strict: `1e3`, `.5` and `1.` are
rejected, because each is a sign that something upstream already turned an
amount into a float and back.

Display rounding is half away from zero, which is what a Korean finance team
reads on paper, and it is used only to decide what a cell *shows* — never what
is sent.

Every number on every screen renders through one `<Amount>` component, and that
component always carries the abbreviation and the exact value together. §9
requires the full stored value to be reachable from every screen showing a
number; a per-screen implementation of that requirement is one that gets
forgotten on the fourth screen.

The linter bans `parseFloat` and unary `+` on non-literals, so the ordinary
accident is a build failure rather than a rounding error.

### Business time is not a `Date`

`@coreintra/business-time` is a parallel implementation of the Java model, and
the two are held together by `spec/business-time-vectors.json`, which **both**
test suites load. Two implementations of a time model drift; the only reliable
defence is a shared set of vectors, so a change on one side fails the other
side's build.

The picker is a text field with its own parser rather than a native time input,
and it keeps a draft of what the user typed: deriving the text purely from the
model looks tidier and is unusable, because on the way to `27:00` the field
passes through `2`, `27` and `27:`, none of which parse.

### Korean is the source language, and the fallback

`ko.ts` is the catalogue; `en.ts` is typed against it, so adding a key without
its translation is a type error. The i18next fallback is **Korean, not English**
— the usual `fallbackLng: 'en'` means a missing Korean string silently shows
English to a Korean user, which both hides the gap and is precisely the
English-first retrofit the brief rules out. Falling back to the source language
means a missing *English* string shows Korean, which is visible and gets fixed.

A test asserts the Korean never drops into 해요체, because register slipping
mid-screen is how a product reads machine-translated.

### Fonts: coverage beats bytes

Pretendard, self-hosted from npm, never a CDN — an on-prem box may have no
outbound internet, and a font that fails to load is a broken product rather
than a degraded one. The **dynamic-subset** build is used, which costs a large
stylesheet (828 `@font-face` rules) and buys complete coverage: a 사원 whose name
uses a syllable outside the common 2,350 still renders. §13 makes a missing
glyph a test failure, so that is the right way to spend the bytes. Static
instances rather than the variable build, matching what the conversion worker
embeds, because what someone approves on screen must match what prints.

### oxlint, not typescript-eslint

The brief requires "no `any`", which `tsc` cannot enforce on its own.
typescript-eslint refuses to load against TypeScript 7
(typescript-eslint#10940), and pinning it to the TS 6 compiler API side by side
does not survive pnpm's peer resolution. oxlint parses TypeScript natively with
no dependency on the compiler API, so it runs. The cost is that type-aware
rules are unavailable — `no-floating-promises` in particular — and `tsc
--strict` carries that weight instead. Revisit when typescript-eslint ships TS 7
support.

## Consequences

- Arithmetic on amounts in the browser is deliberately awkward. It should be:
  almost every case that wants it should be asking the server instead.
- The shared vector file is a coupling between two packages in different
  languages, and it is the good kind — it is the only thing that keeps them
  honest.
- The large font stylesheet would be the wrong call on the public internet. It
  is documented at the import so that whoever ships a public-facing variant sees
  the trade rather than inheriting it.
- One linter and one typechecker cover between them what two linters would have,
  but the seam is visible: a floating promise is caught by review, not by CI.
