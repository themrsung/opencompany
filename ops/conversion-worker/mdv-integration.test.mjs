// The other two test files prove the decisions; this one proves the wiring.
//
// It drives `createMdvRunner()` with no injected engine, so a real `mdv` runs
// on a real worker thread against the real built CLI. That can only happen
// where `vendor/mdv` has been built - inside the image, and in CI - so every
// test here skips, loudly and by name, when `packages/cli/dist` is absent.
//
// Skipping is the honest outcome on a developer's machine: the submodule has
// no `node_modules` and `CONTRACTS.md` §1.2 forbids installing into it, so
// there is nothing this file could fall back to that would still be a test of
// the integration. A skip that names the missing path is worth more than a
// mock that agrees with the fake in `mdv-runner.test.mjs`.

import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { createMdvRunner, DEFAULT_MDV_ENTRY, MdvRefusal, threadEngine } from './mdv-runner.mjs';

const DIST = fileURLToPath(new URL('../../vendor/mdv/packages/cli/dist', import.meta.url));
const BUILT = existsSync(DEFAULT_MDV_ENTRY);

// `node --test` prints this beside every skipped name, so a green run on a
// laptop cannot be mistaken for a green run in CI.
const SKIP = BUILT
  ? false
  : `vendor/mdv is unbuilt (no ${DIST}); build the submodule or run this in the image`;

// A Latin-1 document: the only thing this mdv build can put in a PDF, because
// it embeds no fonts and the standard 14 faces are WinAnsi-only.
const LATIN = '# Quarterly report\n\nRevenue rose 4%.\n';
const KOREAN = '# 분기 보고서\n\n매출이 4% 늘었습니다.\n';

const REQUEST = {
  source: LATIN,
  sourceName: 'report.mdv',
  target: 'pdf',
  buildTime: '2026-08-18T09:00:00.000Z',
  theme: 'print',
  locale: 'ko-KR',
  timezone: 'Asia/Seoul',
};

test('the built CLI reports a version this worker can parse', { skip: SKIP }, async () => {
  const status = await createMdvRunner({ commit: 'integration' }).probe();
  assert.equal(status.available, true, `probe said: ${status.reason ?? ''}`);
  // `versionLine()` in `packages/cli/src/index.ts` is what we parse. If the
  // shape drifts, this worker degrades to "no renderer" in production while
  // mdv is in fact fine - so pin the parse, not just the exit code.
  assert.match(status.version.cli, /^\d+\.\d+\.\d+$/u);
  assert.match(status.version.spec, /^\d+\.\d+\.\d+$/u);
  assert.match(status.version.core, /^\d+\.\d+\.\d+$/u);
});

test('a Latin document renders to real PDF bytes', { skip: SKIP }, async () => {
  const result = await createMdvRunner({ commit: 'integration' }).render(REQUEST);
  assert.equal(result.content.subarray(0, 5).toString('latin1'), '%PDF-');
  assert.ok(result.content.length > 1000, `PDF was ${result.content.length} bytes`);
  assert.equal(result.filename, 'out.pdf');
  assert.match(result.renderConfigDigest, /^sha256:[0-9a-f]{64}$/u);
  assert.equal(result.renderConfig.buildTime, '2026-08-18T09:00:00.000Z');
});

test('the same document twice is byte-identical', { skip: SKIP }, async () => {
  // SPEC 24.3 and ADR 0008 both rest on this. If it ever fails, the approval
  // trail cannot claim a re-render proves anything, and the digest beside the
  // artefact is decoration.
  const runner = createMdvRunner({ commit: 'integration' });
  const a = await runner.render(REQUEST);
  const b = await runner.render(REQUEST);
  assert.equal(a.renderConfigDigest, b.renderConfigDigest);
  assert.ok(a.content.equals(b.content), 'two renders of one document differed');
});

test('a Korean document is refused rather than exported as question marks', { skip: SKIP }, async () => {
  // The guard in `mdv-policy.mjs` is a prediction about this build's exporter.
  // This is where the prediction meets it: if mdv ever learns to embed fonts,
  // this test fails and the guard is what needs deleting - not this assertion.
  const error = await createMdvRunner({ commit: 'integration' })
    .render({ ...REQUEST, source: KOREAN })
    .then(() => undefined, (e) => e);
  assert.ok(error instanceof MdvRefusal, 'Korean reached the exporter');
  assert.equal(error.problem.code, 'font_coverage');
});

test('fmt is idempotent, so stored text stays canonical', { skip: SKIP }, async () => {
  // ADR 0008's consequence: `mdv fmt` on stored text is a no-op. If it were
  // not, every save would produce a diff and the version history would stop
  // being a record of what anyone actually changed.
  const runner = createMdvRunner({ commit: 'integration' });
  const once = await runner.format({ source: LATIN, sourceName: 'report.mdv' });
  const twice = await runner.format({ source: once.text, sourceName: 'report.mdv' });
  assert.equal(twice.text, once.text);
  assert.equal(twice.changed, false, 'formatting canonical text produced a diff');
});

// §13 acceptance 2, done properly: "refused, **not fetched**" is a claim about
// something that must not happen, and the only way to test that is to stand up
// the thing it must not reach and prove nobody knocked.
test('a src: pointing at loopback is refused and never fetched', { skip: SKIP }, async () => {
  const { createServer } = await import('node:http');
  const hits = [];
  const listener = createServer((req, res) => {
    hits.push(req.url);
    res.writeHead(200, { 'content-type': 'text/csv' });
    res.end('secret,value\nrow,1\n');
  });
  await new Promise((resolve) => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;

  try {
    const source = [
      '---',
      'mdv: "1.0"',
      'title: Innocent looking report',
      '---',
      '',
      '```mdv dataset id=stolen',
      `src: http://127.0.0.1:${port}/data.csv`,
      '```',
      '',
    ].join('\n');

    const runner = createMdvRunner({ commit: 'integration' });

    // Default posture: no allowlist, so no fetch capability exists at all.
    const plain = await runner
      .render({ ...REQUEST, source, target: 'json' })
      .then(() => undefined, (e) => e);
    assert.ok(plain instanceof MdvRefusal, 'the document rendered instead of being refused');
    assert.equal(plain.problem.code, 'external_data_refused');

    // And with someone actively trying to permit it.
    const allowed = await runner
      .render({
        ...REQUEST,
        source,
        target: 'json',
        allowExternal: true,
        allowedOrigins: [`http://127.0.0.1:${port}`],
      })
      .then(() => undefined, (e) => e);
    assert.ok(allowed instanceof MdvRefusal);
    assert.equal(allowed.problem.code, 'origin_blocked');

    assert.deepEqual(hits, [], `the worker fetched ${JSON.stringify(hits)} from inside the network`);
  } finally {
    await new Promise((resolve) => listener.close(resolve));
  }
});

test('an unbuilt mdv degrades rather than crashing the worker', async () => {
  // Runs everywhere, built or not: point the runner at a path that certainly
  // does not exist. `/health` depends on this answering rather than throwing,
  // which is what keeps office conversion serving while mdv is broken.
  const engine = threadEngine({
    entry: fileURLToPath(new URL('./no-such-mdv-build/index.js', import.meta.url)),
    timeoutMs: 10_000,
  });
  const status = await createMdvRunner({ engine, commit: 'integration' }).probe();
  assert.equal(status.available, false);
  assert.match(status.reason, /not built|could not be started/u);
});
