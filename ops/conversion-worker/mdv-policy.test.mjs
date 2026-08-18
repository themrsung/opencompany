// The policy module is pure, so these tests run on a machine where `vendor/mdv`
// is unbuilt — which is the point of separating it. Every case here is a
// decision that reaches a user or an approval trail: a refusal we get wrong is
// either a document silently rendered wrongly, or a document refused for a
// reason nobody can act on.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

import {
  MDV_TARGETS,
  allowlistRefusal,
  blockedOriginReason,
  canonicalJson,
  encodableInWinAnsi,
  externalDataRefusal,
  needsShaping,
  pdfCoverageRefusal,
  profileRefusal,
  renderFingerprint,
  resolveBuildTime,
  targetRefusal,
  unrenderableCodePoints,
} from './mdv-policy.mjs';

// A refusal that reaches a user has to be actionable in both UI languages, and
// a missing half is invisible until a Korean approver hits it in production.
function assertSpeaksBothLanguages(problem) {
  assert.ok(problem !== undefined, 'expected a refusal');
  assert.equal(typeof problem.code, 'string');
  assert.ok(problem.code.length > 0);
  assert.ok(typeof problem.ko === 'string' && problem.ko.trim() !== '', `${problem.code} has no Korean text`);
  assert.ok(typeof problem.en === 'string' && problem.en.trim() !== '', `${problem.code} has no English text`);
}

// ─────────────────────────────────────────────────────────────────────────────
// Targets
// ─────────────────────────────────────────────────────────────────────────────

test('the implemented targets pass and are exactly what this build writes', () => {
  assert.deepEqual([...MDV_TARGETS], ['pdf', 'svg', 'json', 'csv']);
  for (const target of MDV_TARGETS) assert.equal(targetRefusal(target), undefined);
});

test('a target mdv declares but refuses is named as unimplemented, not unknown', () => {
  for (const target of ['html', 'png', 'md']) {
    const refusal = targetRefusal(target);
    assertSpeaksBothLanguages(refusal);
    assert.equal(refusal.code, 'unsupported_target', target);
    // The distinction matters to the caller: "ask for something else" versus
    // "you typed it wrong".
    assert.match(refusal.en, /pdf, svg, json, csv/u);
  }
});

test('a target nobody has heard of is a different refusal from one we know we lack', () => {
  const refusal = targetRefusal('docx');
  assertSpeaksBothLanguages(refusal);
  assert.equal(refusal.code, 'unknown_target');
});

// ─────────────────────────────────────────────────────────────────────────────
// Build time (ADR 0008)
// ─────────────────────────────────────────────────────────────────────────────

test('an absent buildTime is refused rather than defaulted', () => {
  // mdv's own default is the Unix epoch, silently. A document dated 1970 in an
  // approval trail is worse than a refusal, because it looks deliberate.
  for (const absent of [undefined, null, '']) {
    const resolved = resolveBuildTime(absent);
    assert.equal(resolved.buildTime, undefined);
    assertSpeaksBothLanguages(resolved.problem);
    assert.equal(resolved.problem.code, 'build_time_required');
  }
});

test('an unparseable buildTime is refused rather than becoming an Invalid Date', () => {
  const resolved = resolveBuildTime('last tuesday');
  assertSpeaksBothLanguages(resolved.problem);
  assert.equal(resolved.problem.code, 'build_time_invalid');
});

test('an ISO instant resolves to a real Date', () => {
  const resolved = resolveBuildTime('2026-08-18T09:00:00.000Z');
  assert.equal(resolved.problem, undefined);
  assert.equal(resolved.buildTime.toISOString(), '2026-08-18T09:00:00.000Z');
});

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprint
// ─────────────────────────────────────────────────────────────────────────────

const FINGERPRINT_INPUT = {
  mdv: { cli: '0.0.0', spec: '1.0.0', core: '0.0.0', commit: 'c48e33829a8ea03d98ace35cf18189de1a181f23' },
  target: 'pdf',
  buildTime: new Date('2026-08-18T09:00:00.000Z'),
  theme: 'print',
  locale: 'ko-KR',
  timezone: 'Asia/Seoul',
  level: 2,
  strict: true,
  options: { width: 800, block: 'chart-1' },
  allowExternal: false,
  allowedOrigins: [],
};

test('the same render config produces the same digest', () => {
  const a = renderFingerprint(FINGERPRINT_INPUT);
  const b = renderFingerprint({ ...FINGERPRINT_INPUT });
  assert.equal(a.digest, b.digest);
  assert.match(a.digest, /^sha256:[0-9a-f]{64}$/u);
});

test('key order and origin order do not move the digest', () => {
  // The digest is compared across machines and across months. If it moved when
  // someone reordered a literal, every historical comparison would be noise.
  const reordered = {
    allowedOrigins: ['https://b.example.com', 'https://a.example.com'],
    allowExternal: true,
    options: { block: 'chart-1', width: 800 },
    strict: true,
    level: 2,
    timezone: 'Asia/Seoul',
    locale: 'ko-KR',
    theme: 'print',
    buildTime: new Date('2026-08-18T09:00:00.000Z'),
    target: 'pdf',
    mdv: FINGERPRINT_INPUT.mdv,
  };
  const sorted = {
    ...FINGERPRINT_INPUT,
    allowExternal: true,
    allowedOrigins: ['https://a.example.com', 'https://b.example.com'],
  };
  assert.equal(renderFingerprint(reordered).digest, renderFingerprint(sorted).digest);
});

test('every field that changes the output changes the digest', () => {
  const base = renderFingerprint(FINGERPRINT_INPUT).digest;
  const variants = {
    buildTime: { buildTime: new Date('2026-08-18T09:00:01.000Z') },
    target: { target: 'svg' },
    theme: { theme: 'screen' },
    locale: { locale: 'en-GB' },
    timezone: { timezone: 'UTC' },
    level: { level: 3 },
    strict: { strict: false },
    options: { options: { width: 801, block: 'chart-1' } },
    allowExternal: { allowExternal: true },
    commit: { mdv: { ...FINGERPRINT_INPUT.mdv, commit: 'deadbeef' } },
    cli: { mdv: { ...FINGERPRINT_INPUT.mdv, cli: '0.0.1' } },
  };
  for (const [name, patch] of Object.entries(variants)) {
    const digest = renderFingerprint({ ...FINGERPRINT_INPUT, ...patch }).digest;
    assert.notEqual(digest, base, `${name} did not move the digest`);
  }
});

test('the snapshot is stored beside the digest and is diffable', () => {
  // A hash nobody can diff is a hash nobody trusts: two renders that disagree
  // must be explainable by a named field, months later, without the image.
  const { snapshot } = renderFingerprint(FINGERPRINT_INPUT);
  assert.equal(snapshot.schema, 1);
  assert.equal(snapshot.mdv.commit, FINGERPRINT_INPUT.mdv.commit);
  assert.equal(snapshot.security.allowFileUrls, false);
  // `fonts` must describe what actually reaches the exporter, not the container.
  assert.deepEqual(snapshot.fonts, { embedding: 'none', faces: 'standard-14' });
});

test('the stored snapshot re-hashes to the stored digest, before and after JSON', () => {
  // The approval trail keeps the snapshot as JSON. If re-hashing what was kept
  // did not reproduce the digest, the digest would be decorative: an auditor
  // holding the record could not tell it had never been altered. `buildTime`
  // is the field that makes this non-obvious - a `Date` going in, a string
  // coming back - so it is the one the test pins.
  const { digest, snapshot } = renderFingerprint(FINGERPRINT_INPUT);
  assert.equal(typeof snapshot.buildTime, 'string');
  assert.equal(snapshot.buildTime, '2026-08-18T09:00:00.000Z');

  const rehashed = `sha256:${createHash('sha256').update(canonicalJson(snapshot)).digest('hex')}`;
  assert.equal(rehashed, digest);

  const roundTripped = JSON.parse(JSON.stringify(snapshot));
  assert.deepEqual(roundTripped, snapshot);
  const afterJson = `sha256:${createHash('sha256').update(canonicalJson(roundTripped)).digest('hex')}`;
  assert.equal(afterJson, digest);
});

test('canonical JSON sorts keys and drops undefined', () => {
  assert.equal(canonicalJson({ b: 1, a: 2 }), '{"a":2,"b":1}');
  assert.equal(canonicalJson({ a: undefined, b: 1 }), '{"b":1}');
  // Arrays keep their order: in a font list, order is meaning.
  assert.equal(canonicalJson(['b', 'a']), '["b","a"]');
  assert.equal(canonicalJson({ d: new Date('2026-01-01T00:00:00.000Z') }), '{"d":"2026-01-01T00:00:00.000Z"}');
});

// ─────────────────────────────────────────────────────────────────────────────
// Fonts — the CJK / `?` guard
// ─────────────────────────────────────────────────────────────────────────────

test('WinAnsi coverage is Latin-1 plus the typographic scatter, and nothing else', () => {
  assert.equal(encodableInWinAnsi(0x41), true); // A
  assert.equal(encodableInWinAnsi(0xe9), true); // é
  assert.equal(encodableInWinAnsi(0x20ac), true); // € — in the extra set
  assert.equal(encodableInWinAnsi(0xac00), false); // 가
});

test('a Korean document is refused rather than exported as question marks', () => {
  // This is the single most important guard in the file. A valid-looking PDF of
  // question marks reaches an approval trail and is hashed as authoritative.
  const refusal = pdfCoverageRefusal('# 승인 요청서\n\n결재 부탁드립니다.');
  assertSpeaksBothLanguages(refusal);
  assert.equal(refusal.code, 'font_coverage');
  assert.match(refusal.en, /MDV5100/u);
  assert.match(refusal.en, /LibreOffice/u);
  // It must name the offending codepoints, or the author cannot find the text
  // to fix. They ride in `detail` rather than the sentence: `renderFailure()`
  // returns the whole problem object in the 422 body, so the approver sees it,
  // and a sentence that inlines eight codepoints is unreadable in both
  // languages. Assert the field that actually carries them.
  assert.match(refusal.detail, /U\+C2B9 승/u); // the character, not just its number
  // The sentence counts every distinct unrenderable codepoint; the detail shows
  // the first eight. The two numbers differ on purpose - an approver needs the
  // true scale, not the length of a list we truncated for them.
  assert.match(refusal.en, /\b13 codepoint\(s\)/u);
  assert.equal(refusal.detail.split(', ').length, 8);
});

test('a Latin-1 document passes the coverage guard', () => {
  assert.equal(pdfCoverageRefusal('# Quarterly report\n\nRevenue rose 4%. Café — naïve.'), undefined);
});

test('a script this exporter cannot shape is refused before it is drawn unshaped', () => {
  // Arabic and Indic are inside WinAnsi's blind spot in a different way: they
  // would not be missing glyphs, they would be *wrongly joined* ones, which is
  // the failure nobody spots in review.
  const refusal = pdfCoverageRefusal('تقرير ربع سنوي');
  assertSpeaksBothLanguages(refusal);
  assert.equal(refusal.code, 'shaping_unsupported');
  assert.match(refusal.en, /MDV5101/u);
  assert.equal(needsShaping('plain ascii'), false);
  assert.equal(needsShaping('देवनागरी'), true);
});

test('the unrenderable report is capped, counted and deduplicated', () => {
  const { sample, total } = unrenderableCodePoints('가나다가나다라마바사아자차카타파하', 3);
  assert.equal(sample.length, 3);
  assert.ok(total > 3, 'the total must survive the cap or the author cannot judge the scale');
  assert.equal(new Set(sample.map((s) => s.cp)).size, sample.length);
});

test('tabs and newlines are laid out, not drawn, so they never trip the guard', () => {
  assert.equal(pdfCoverageRefusal('a\tb\r\nc'), undefined);
});

// ─────────────────────────────────────────────────────────────────────────────
// SSRF and the allowlist
// ─────────────────────────────────────────────────────────────────────────────

test('loopback, private, link-local and metadata addresses are all refused', () => {
  // The worker sits inside the deployment network: its loopback is the API, its
  // 10.x is the database. An allowlist entry here is an SSRF gate, not a
  // convenience, so the check is repeated before the job is queued.
  const blocked = {
    'http://localhost:3000': /loopback/u,
    'http://api.localhost': /loopback/u,
    'http://127.0.0.1': /loopback/u,
    'http://10.1.2.3': /private/u,
    'http://172.16.0.1': /private/u,
    'http://192.168.1.1': /private/u,
    'http://169.254.169.254': /metadata/u,
    'http://100.64.0.1': /carrier-grade NAT/u,
    'http://[::1]': /loopback/u,
    'http://[fe80::1]': /link-local/u,
    'http://[fc00::1]': /unique-local/u,
    'http://[::ffff:127.0.0.1]': /loopback/u,
    'http://0.0.0.0': /unspecified/u,
  };
  for (const [origin, expected] of Object.entries(blocked)) {
    const reason = blockedOriginReason(origin);
    assert.ok(reason !== undefined, `${origin} was allowed`);
    assert.match(reason, expected, origin);
  }
});

test('a public origin over https is allowed through', () => {
  assert.equal(blockedOriginReason('https://data.example.com'), undefined);
});

test('`*` is refused: a wildcard allowlist is not an allowlist', () => {
  const reason = blockedOriginReason('*');
  assert.ok(reason !== undefined);
  assert.match(reason, /wildcard/u);
});

test('a non-http scheme is refused before DNS is ever consulted', () => {
  assert.match(blockedOriginReason('file:///etc/passwd') ?? '', /scheme/u);
  assert.match(blockedOriginReason('gopher://example.com') ?? '', /scheme/u);
  assert.match(blockedOriginReason('not a url') ?? '', /not a URL/u);
  assert.match(blockedOriginReason('') ?? '', /empty/u);
});

test('one bad entry refuses the whole job, and the refusal names it', () => {
  const refusal = allowlistRefusal(['https://data.example.com', 'http://10.0.0.5']);
  assertSpeaksBothLanguages(refusal);
  assert.equal(refusal.code, 'origin_blocked');
  assert.match(refusal.en, /10\.0\.0\.5/u);
  assert.equal(allowlistRefusal(['https://data.example.com']), undefined);
  assert.equal(allowlistRefusal([]), undefined);
});

// ─────────────────────────────────────────────────────────────────────────────
// PDF profiles
// ─────────────────────────────────────────────────────────────────────────────

test('the archival and accessibility profiles are refused, not quietly accepted', () => {
  // mdv's CLI takes both flags. `pdf-a-3b` reaches the exporter and does
  // nothing at all; `pdf-ua-1` stamps the ISO 14289-1 claim into a file whose
  // fonts are not embedded. A document that says it is archival or accessible
  // and is not is worse than one that says nothing, because the claim is what
  // stops anyone checking again.
  for (const profile of ['pdf-a-3b', 'pdf-ua-1']) {
    const refusal = profileRefusal(profile);
    assertSpeaksBothLanguages(refusal);
    assert.equal(refusal.code, 'profile_unsupported', profile);
    assert.match(refusal.en, /LibreOffice/u, `${profile} must say where to go instead`);
  }
});

test('the plain profile and no profile at all are fine', () => {
  assert.equal(profileRefusal(undefined), undefined);
  assert.equal(profileRefusal('pdf-1.7'), undefined);
});

test('an unknown profile is refused before mdv exits 2 at us', () => {
  const refusal = profileRefusal('pdf-x-4');
  assertSpeaksBothLanguages(refusal);
  assert.equal(refusal.code, 'profile_unknown');
});

// ─────────────────────────────────────────────────────────────────────────────
// External data that did not load
// ─────────────────────────────────────────────────────────────────────────────

test('a blocked or failed source is a refusal, not a placeholder chart', () => {
  // mdv's contract is that a failed source degrades to a placeholder and the
  // document still renders. That is right for a live editor and wrong for us:
  // "no data" inside an approved document is indistinguishable, three months
  // later, from a chart that was meant to be empty.
  for (const code of ['MDV4002', 'MDV4003', 'MDV4020', 'MDV4021', 'MDV4022', 'MDV4023']) {
    const refusal = externalDataRefusal([{ code, severity: 'warning', message: 'source did not load' }]);
    assertSpeaksBothLanguages(refusal);
    assert.equal(refusal.code, 'external_data_refused');
    assert.match(refusal.en, new RegExp(code, 'u'));
  }
});

test('unrelated diagnostics do not trigger the data refusal', () => {
  assert.equal(externalDataRefusal([{ code: 'MDV3080', severity: 'warning', message: 'palette' }]), undefined);
  assert.equal(externalDataRefusal([]), undefined);
});
