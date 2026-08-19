// Policy for mdv rendering: the decisions that must hold whether or not mdv is
// installed on this box.
//
// Nothing in this file imports the mdv build, and nothing here touches the
// filesystem or the network. That is deliberate. It is what lets us prove, on a
// machine where `vendor/mdv` is unbuilt, that a Korean document is refused a PDF,
// that an unpinned build time is refused, and that a loopback origin never
// reaches a fetch. A guard that can only run where the renderer is installed is
// a guard that quietly stops running the day the image changes.
//
// Every refusal returned from here is a `problem`: a stable code plus Korean and
// English text. The worker's callers are the document service and, through it,
// people approving 결재 documents; a refusal they cannot read is a refusal they
// will work around.

import { createHash } from 'node:crypto';

/**
 * The export targets this mdv build actually writes.
 *
 * `packages/cli/src/commands/export.ts` declares seven `EXPORT_TARGETS` and then
 * implements four; `html`, `png` and `md` are refused by name with a reason. We
 * mirror the implemented set here rather than importing it, because the whole
 * point is to answer the question before mdv is loaded — and because if a later
 * pin implements `png`, this list failing a test is exactly the reminder we want.
 */
export const MDV_TARGETS = Object.freeze(['pdf', 'svg', 'json', 'csv']);

/** Targets mdv names but refuses, and the reason it gives. */
export const MDV_REFUSED_TARGETS = Object.freeze({
  html: 'this build has no self-contained HTML exporter',
  png: 'PNG needs a Canvas backend that is not in this build',
  md: 'Markdown export is a degraded copy of the input, not a render',
});

/** A refusal, in both UI languages. `code` is stable; the prose is not. */
function problem(code, ko, en, detail) {
  return { code, ko, en, ...(detail === undefined ? {} : { detail }) };
}

// ─────────────────────────────────────────────────────────────────────────────
// Target
// ─────────────────────────────────────────────────────────────────────────────

/** `undefined` when the target is fine, a problem when it is not. */
export function targetRefusal(target) {
  if (MDV_TARGETS.includes(target)) return undefined;
  const reason = MDV_REFUSED_TARGETS[target];
  if (reason !== undefined) {
    return problem(
      'unsupported_target',
      `이 빌드는 ${target} 내보내기를 지원하지 않습니다. 사용 가능한 형식: ${MDV_TARGETS.join(', ')}.`,
      `This mdv build does not implement ${target} export (${reason}). Available: ${MDV_TARGETS.join(', ')}.`,
    );
  }
  return problem(
    'unknown_target',
    `알 수 없는 형식 ${target}. 사용 가능한 형식: ${MDV_TARGETS.join(', ')}.`,
    `Unknown target ${target}. Available: ${MDV_TARGETS.join(', ')}.`,
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Build time
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolve the pinned build time, or refuse.
 *
 * mdv never refuses this itself. `packages/core/src/resolve.ts` defaults
 * `buildTime` to `new Date(0)` — "wrong but reproducible" is the comment — and
 * the CLI's `buildConfig` passes `new Date(flags.buildTime)` straight through
 * without checking it for NaN (only a `--config` *file* gets that check). So an
 * unpinned render silently dates itself 1970 and a mistyped one silently becomes
 * an Invalid Date. Either lands in an approval trail looking authoritative.
 *
 * ADR 0008: the build time is never allowed to default. This is where that is
 * enforced, because it is the only place that knows the render was requested by
 * a person rather than by a test.
 */
export function resolveBuildTime(value) {
  if (value === undefined || value === null || value === '') {
    return {
      problem: problem(
        'build_time_required',
        '렌더링 시각(buildTime)이 지정되지 않았습니다. 재현 가능한 출력에는 반드시 필요합니다.',
        'A pinned buildTime is required: mdv silently falls back to 1970-01-01 rather than reading the clock, which would date the document wrongly and reproducibly.',
      ),
    };
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return {
      problem: problem(
        'build_time_invalid',
        `렌더링 시각을 해석할 수 없습니다: ${String(value)}. ISO 8601 형식이어야 합니다.`,
        `buildTime ${String(value)} is not a date mdv can parse; it must be an ISO 8601 instant.`,
      ),
    };
  }
  return { buildTime: date };
}

// ─────────────────────────────────────────────────────────────────────────────
// Fonts — what this exporter can actually draw
// ─────────────────────────────────────────────────────────────────────────────

// Copied from `packages/render-pdf/src/fonts.ts`. Copied, not imported, so the
// check runs with mdv absent; the integration test asserts the two agree, which
// is the only way this copy can rot without anyone noticing.
const WINANSI_EXTRA = new Set([
  0x20ac, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039, 0x0152,
  0x017d, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc, 0x2122, 0x0161, 0x203a,
  0x0153, 0x017e, 0x0178,
]);

/** `true` when WinAnsiEncoding has a code for this codepoint. */
export function encodableInWinAnsi(cp) {
  if (cp >= 0x20 && cp <= 0x7e) return true;
  if (cp >= 0xa0 && cp <= 0xff) return true;
  return WINANSI_EXTRA.has(cp);
}

/** Scripts this exporter cannot shape (`packages/render-pdf/src/fonts.ts`). */
const COMPLEX_RANGES = [
  [0x0590, 0x05ff], // Hebrew
  [0x0600, 0x06ff], // Arabic
  [0x0700, 0x074f], // Syriac
  [0x0900, 0x0dff], // Indic
  [0x0e00, 0x0e7f], // Thai
  [0x1780, 0x17ff], // Khmer
  [0xfb1d, 0xfdff], // Hebrew / Arabic presentation forms
  [0xfe70, 0xfeff],
];

/** `true` when the string contains a script this exporter cannot shape. */
export function needsShaping(value) {
  for (const ch of value) {
    const cp = ch.codePointAt(0) ?? 0;
    for (const [lo, hi] of COMPLEX_RANGES) {
      if (cp >= lo && cp <= hi) return true;
    }
  }
  return false;
}

/** Codepoints in `text` that the PDF exporter would draw as `?`, deduplicated. */
export function unrenderableCodePoints(text, limit = 8) {
  const out = [];
  const seen = new Set();
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp === 0x09 || cp === 0x0a || cp === 0x0d) continue; // laid out, not drawn
    if (encodableInWinAnsi(cp) || seen.has(cp)) continue;
    seen.add(cp);
    if (out.length < limit) out.push({ cp, char: ch });
  }
  return { sample: out, total: seen.size };
}

/**
 * Refuse a PDF whose text this build cannot draw.
 *
 * This is the single most important guard in the file, and the one that
 * contradicts the brief hardest. **This mdv build embeds no font at all**:
 * `packages/cli/src/commands/export.ts` calls the exporter with `fonts: []` and
 * `createStandardFontMetrics()`, so coverage is the standard 14 faces and
 * WinAnsi. There is no `pdf.fonts` config key in this build to point at the
 * font store — that key exists only in a doc comment. A Korean document does not
 * fail: every Hangul codepoint is drawn as `?`, reported once as `MDV5100`, and
 * the file is a perfectly valid PDF full of question marks.
 *
 * A valid-looking PDF of question marks is the same failure as the truncated PDF
 * the LibreOffice path refuses: it reaches an approval trail and is hashed as
 * authoritative. So Korean text goes to LibreOffice, and mdv's PDF exporter is
 * for Latin-1 documents only until a pin embeds fonts.
 */
export function pdfCoverageRefusal(text) {
  // Shaping is tested first, and the order is the whole point. A complex script
  // is *also* outside WinAnsi, so a coverage-first check would answer every
  // Arabic document with "13 codepoints would be drawn as ?" and the shaping
  // branch would be unreachable — true, but not the truest thing we can say.
  // Missing glyphs are fixed by embedding a font; unshaped Arabic is not, and
  // the author needs to know which of those two conversations they are in.
  if (needsShaping(text)) {
    return problem(
      'shaping_unsupported',
      '이 문서에는 이 빌드가 자형 정렬(shaping)할 수 없는 문자가 포함되어 있습니다. PDF는 LibreOffice 경로로 변환하십시오.',
      'The document contains a script this mdv build cannot shape (MDV5101); it would be drawn unshaped. Convert to PDF through the LibreOffice path instead.',
    );
  }
  const { sample, total } = unrenderableCodePoints(text);
  if (total === 0) return undefined;
  const shown = sample
    .map((entry) => `U+${entry.cp.toString(16).toUpperCase().padStart(4, '0')} ${entry.char}`)
    .join(', ');
  return problem(
    'font_coverage',
    `이 mdv 빌드는 글꼴을 내장하지 않으므로 한글 등 ${total}종의 문자가 물음표(?)로 출력됩니다. PDF는 LibreOffice 경로로 변환하십시오.`,
    `This mdv build embeds no fonts, so ${total} codepoint(s) outside WinAnsi would be drawn as "?" (MDV5100). Convert to PDF through the LibreOffice path instead.`,
    shown,
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF profiles
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Refuse a PDF profile this build accepts but does not deliver.
 *
 * The brief asks for `pdf-a-3b` on archived 결재 documents and `pdf-ua-1` where
 * accessibility is required. mdv's CLI accepts both. Neither survives contact
 * with what the exporter actually does, and the two fail differently:
 *
 * - **`pdf-a-3b` is a silent no-op.** `packages/render-pdf/src` mentions the
 *   value only in the options enum. No `pdfaid` XMP identification, no
 *   OutputIntent, no embedded fonts - PDF/A-3b requires all three. The file is
 *   an ordinary PDF that nothing marks as archival, so it would be filed as
 *   archival on our say-so alone.
 * - **`pdf-ua-1` is worse: it is a false claim.** `writer.ts` stamps the ISO
 *   14289-1 clause 5 identification (`pdfuaid:part 1`), which is the assertion
 *   "this file is PDF/UA". The same build embeds no font, and ISO 14289-1
 *   requires every font embedded. The only check the exporter performs is that
 *   figures carry `/Alt` (`MDV5110`). So the output announces an accessibility
 *   conformance it does not have, to exactly the reader who cannot check.
 *
 * A document that claims to be archival or accessible and is not is worse than
 * one that claims nothing, because the claim is what stops anyone looking
 * again. Refuse both until a pin implements font embedding; the LibreOffice
 * path produces PDF/A today and is where archival exports belong.
 */
export function profileRefusal(profile) {
  if (profile === undefined || profile === null || profile === 'pdf-1.7') return undefined;
  if (profile === 'pdf-a-3b') {
    return problem(
      'profile_unsupported',
      '이 mdv 빌드는 PDF/A-3b를 생성하지 못합니다. 보존용 PDF는 LibreOffice 경로로 변환하십시오.',
      'This mdv build accepts --profile pdf-a-3b and does nothing with it: no PDF/A identification, no OutputIntent, no embedded fonts. The file would be an ordinary PDF filed as archival. Use the LibreOffice path for archival PDF.',
    );
  }
  if (profile === 'pdf-ua-1') {
    return problem(
      'profile_unsupported',
      '이 mdv 빌드는 PDF/UA-1을 충족하지 못하면서 적합성 표시만 기록합니다. 접근성 PDF는 LibreOffice 경로로 변환하십시오.',
      'This mdv build stamps the PDF/UA-1 conformance claim (pdfuaid:part 1) into a file whose fonts are not embedded, which ISO 14289-1 requires. It would assert an accessibility conformance it does not have. Use the LibreOffice path.',
    );
  }
  return problem(
    'profile_unknown',
    `알 수 없는 PDF 프로파일 ${String(profile)}.`,
    `Unknown PDF profile ${String(profile)}; mdv accepts pdf-1.7, pdf-a-3b, pdf-ua-1.`,
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// External data
// ─────────────────────────────────────────────────────────────────────────────

/** Diagnostic codes mdv emits when a `src:` was refused, blocked or failed. */
export const EXTERNAL_DATA_CODES = Object.freeze([
  'MDV4002', // external data disabled
  'MDV4003', // origin not in the allowlist
  'MDV4020', // unusable src
  'MDV4021', // integrity mismatch
  'MDV4022', // blocked: private, loopback, metadata address
  'MDV4023', // fetch failed or timed out
]);

/**
 * Refuse a render whose data did not load.
 *
 * mdv's contract is that a blocked or failed source "degrades to a placeholder
 * with a stated reason" and the document still renders. That is right for a live
 * editor and wrong for us: a chart that says "no data" inside an approved
 * 지출결의서 is indistinguishable, three months later, from a chart that was
 * meant to be empty. Same rule as the LibreOffice path — an error, never a
 * plausible artefact.
 */
export function externalDataRefusal(diagnostics) {
  const hits = diagnostics.filter((d) => EXTERNAL_DATA_CODES.includes(d.code));
  if (hits.length === 0) return undefined;
  const codes = [...new Set(hits.map((d) => d.code))].sort().join(', ');
  return problem(
    'external_data_refused',
    `외부 데이터를 불러오지 못해 차트가 자리표시자로 대체됩니다 (${codes}). 승인 문서로 사용할 수 없습니다.`,
    `External data did not load (${codes}); mdv would render placeholders in place of the charts. Refusing rather than producing a plausible-looking document.`,
    hits.map((d) => `${d.code}: ${d.message}`).join('; '),
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// SSRF
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Why this origin must never be handed to mdv as an allowlist entry.
 *
 * mdv checks the host itself at fetch time (`packages/core/src/data/fetch.ts`,
 * `blockedReason`) and its check is good. We repeat it here, before the job is
 * queued, for two reasons: mdv's refusal arrives as a *diagnostic* inside a
 * document that still renders, and a request that reaches the fetch has already
 * told an attacker's DNS server that this worker exists. The worker sits inside
 * the deployment's network — its loopback is the API, its 10.x is the database.
 *
 * Mirrors mdv's rules deliberately; where they disagree, the stricter answer
 * wins, because this list is written by an admin and not by the document.
 */
export function blockedOriginReason(origin) {
  const trimmed = String(origin).trim();
  if (trimmed === '') return 'an empty origin';
  if (trimmed === '*') {
    // mdv accepts `*`. We do not: an allowlist of "anything" on a box that can
    // reach the database is not an allowlist.
    return 'a wildcard origin, which this worker never accepts';
  }
  let url;
  try {
    url = new URL(trimmed);
  } catch {
    return 'not a URL';
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return `scheme ${url.protocol.replace(':', '')}, and only http and https may be fetched`;
  }
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/gu, '');
  if (host === '') return 'an empty host';
  if (host === 'localhost' || host.endsWith('.localhost')) return 'a loopback name';
  const v4 = parseIpv4(host);
  if (v4 !== undefined) return blockedIpv4(v4);
  if (host.includes(':')) return blockedIpv6(host);
  return undefined;
}

function parseIpv4(host) {
  const parts = host.split('.');
  if (parts.length !== 4) return undefined;
  const out = [];
  for (const part of parts) {
    if (part === '' || part.length > 3 || !/^[0-9]+$/u.test(part)) return undefined;
    const value = Number.parseInt(part, 10);
    if (value > 255) return undefined;
    out.push(value);
  }
  return out;
}

/**
 * The last two groups of an IPv4-mapped address, as hex, back into four octets.
 *
 * `::ffff:7f00:1` is 127.0.0.1 wearing the only spelling `new URL()` will give
 * us. Anything that is not exactly two hex groups is not a mapped address and
 * is left to the generic prefix checks below.
 */
function parseMappedHex(tail) {
  const groups = tail.split(':');
  if (groups.length !== 2) return undefined;
  const out = [];
  for (const group of groups) {
    if (group === '' || group.length > 4 || !/^[0-9a-f]+$/u.test(group)) return undefined;
    const value = Number.parseInt(group, 16);
    out.push((value >> 8) & 0xff, value & 0xff);
  }
  return out;
}

function blockedIpv4(octets) {
  const [a, b] = octets;
  if (a === 0) return 'an unspecified address';
  if (a === 127) return 'a loopback address';
  if (a === 10) return 'a private address';
  if (a === 172 && b >= 16 && b <= 31) return 'a private address';
  if (a === 192 && b === 168) return 'a private address';
  if (a === 169 && b === 254) {
    return octets[2] === 169 && octets[3] === 254 ? 'the cloud metadata address' : 'a link-local address';
  }
  if (a === 100 && b >= 64 && b <= 127) return 'a carrier-grade NAT address';
  if (a >= 224) return 'a multicast or reserved address';
  return undefined;
}

function blockedIpv6(host) {
  const compact = host.replace(/%.*$/u, '');
  if (compact === '::1') return 'a loopback address';
  if (compact === '::') return 'an unspecified address';
  const mapped = /^::ffff:(.+)$/u.exec(compact);
  if (mapped) {
    // `::ffff:127.0.0.1` is what an attacker writes; `::ffff:7f00:1` is what
    // `new URL()` hands us, because WHATWG host parsing normalises the dotted
    // tail into hex groups. Checking only the dotted form is a hole you cannot
    // see in a test that uses the string it wrote itself - the URL has already
    // rewritten it by the time we look. Both spellings mean 127.0.0.1.
    const inner = parseIpv4(mapped[1]) ?? parseMappedHex(mapped[1]);
    if (inner !== undefined) return blockedIpv4(inner);
  }
  const head = compact.split(':')[0] ?? '';
  if (head.length === 0) return undefined;
  const group = Number.parseInt(head.padStart(4, '0'), 16);
  if (Number.isNaN(group)) return undefined;
  if ((group & 0xffc0) === 0xfe80) return 'a link-local address';
  if ((group & 0xfe00) === 0xfc00) return 'a unique-local address';
  return undefined;
}

/** Refuse the whole job when any allowlist entry points inside the deployment. */
export function allowlistRefusal(origins) {
  for (const origin of origins) {
    const reason = blockedOriginReason(origin);
    if (reason === undefined) continue;
    return problem(
      'origin_blocked',
      `외부 데이터 허용 목록의 ${String(origin)} 항목이 차단되었습니다: ${reason}.`,
      `Allowlist entry ${String(origin)} is refused: it is ${reason}. The worker can reach the deployment's own network, so an allowlist is an SSRF gate, not a convenience.`,
    );
  }
  return undefined;
}

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprint
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Canonical JSON: object keys sorted, `undefined` dropped, dates as ISO strings.
 *
 * Hand-rolled because the fingerprint must not change when someone reorders a
 * literal, and `JSON.stringify` preserves insertion order. Arrays keep their
 * order: in a font list, order is meaning.
 */
export function canonicalJson(value) {
  return JSON.stringify(canonicalise(value));
}

function canonicalise(value) {
  if (value === null || typeof value !== 'object') return value;
  if (value instanceof Date) return value.toISOString();
  if (Array.isArray(value)) return value.map(canonicalise);
  const out = {};
  for (const key of Object.keys(value).sort()) {
    if (value[key] === undefined) continue;
    out[key] = canonicalise(value[key]);
  }
  return out;
}

/**
 * The render configuration, hashed.
 *
 * ADR 0008: determinism is why mdv earns its place in the approval trail, so the
 * full render config is snapshotted with every approval. The hash is what the
 * documents module stores beside the artefact; two renders that share it must be
 * byte-identical, and two that do not must be explainable by a named field. So
 * the object is stored alongside the digest — a hash nobody can diff is a hash
 * nobody trusts.
 *
 * `fonts` records what actually reaches the exporter, which in this build is
 * "nothing": see {@link pdfCoverageRefusal}. It would be a lie to fingerprint the
 * container's font store here — mdv never reads it.
 */
export function renderFingerprint(input) {
  const snapshot = {
    schema: 1,
    mdv: {
      cli: input.mdv?.cli,
      spec: input.mdv?.spec,
      core: input.mdv?.core,
      commit: input.mdv?.commit,
    },
    target: input.target,
    buildTime: input.buildTime,
    theme: input.theme ?? null,
    locale: input.locale ?? null,
    timezone: input.timezone ?? null,
    level: input.level ?? null,
    strict: input.strict === true,
    options: canonicalise(input.options ?? {}),
    fonts: { embedding: 'none', faces: 'standard-14' },
    security: {
      allowExternal: input.allowExternal === true,
      allowedOrigins: [...(input.allowedOrigins ?? [])].sort(),
      allowFileUrls: false,
    },
  };
  // Store the canonical form, not the input. Hashing one object and storing a
  // different one is how a digest stops being checkable: `buildTime` arrives
  // here as a `Date`, survives to the caller as a `Date`, and comes back from
  // the approval trail as a string after a JSON round trip. All three hash the
  // same today - `canonicalise` sees to that - but only because nobody has yet
  // added a field where they would not. Canonicalising once, here, means the
  // object we hand out is byte-for-byte the object we hashed, and re-hashing a
  // stored snapshot is a check rather than a coincidence.
  const canonical = canonicalise(snapshot);
  const json = JSON.stringify(canonical);
  return { digest: `sha256:${createHash('sha256').update(json).digest('hex')}`, snapshot: canonical };
}
