// The runner, exercised against a fake mdv.
//
// `vendor/mdv` is a submodule with no `node_modules` and no `dist` on a
// developer's machine, so a test that needs a real mdv build is a test that
// only ever runs in CI. That is the whole reason `createMdvRunner()` takes an
// engine: everything above the seam - choosing the argv, refusing before we
// spend a thread, mapping an exit code onto something a person can act on,
// fingerprinting the config - is decided here and can be tested anywhere.
//
// The fake engine is deliberately literal. It records the argv it was handed
// and returns whatever the test tells it to, because the argv *is* the contract
// between this worker and mdv: a flag we forget to pass is a document rendered
// with the wrong build time, and that reaches an approval trail.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createMdvRunner, MdvRefusal } from './mdv-runner.mjs';

const VERSION_LINE = 'mdv 0.0.0 (spec 1.0.0, core 0.0.0)';
const COMMIT = 'c48e33829a8ea03d98ace35cf18189de1a181f23';

/** A fake mdv. `replies` is consulted by command name (argv[0]). */
function fakeEngine(replies = {}) {
  const calls = [];
  return {
    calls,
    entry: undefined, // `probe()` skips the access() check when there is no entry
    async execute({ argv, cwd, env }) {
      calls.push({ argv: [...argv], cwd, env });
      const reply = replies[argv[0]];
      if (typeof reply === 'function') return reply({ argv, cwd, env });
      return reply ?? { code: 0, stdout: '', stderr: '' };
    },
  };
}

/** The happy path: a version, a clean lint, and one file on disk. */
function workingEngine(overrides = {}) {
  return fakeEngine({
    '--version': { code: 0, stdout: `${VERSION_LINE}\n`, stderr: '' },
    lint: { code: 0, stdout: '[]', stderr: '' },
    export: async ({ argv, cwd }) => {
      // Real mdv writes the file; the fake must too, because the runner reads
      // the directory back rather than trusting the name it asked for.
      const out = argv[argv.indexOf('-o') + 1];
      const { writeFile } = await import('node:fs/promises');
      const { join } = await import('node:path');
      await writeFile(join(cwd, out), 'PDF-BYTES');
      return { code: 0, stdout: '', stderr: '' };
    },
    ...overrides,
  });
}

const REQUEST = {
  source: '# Quarterly report\n\nRevenue rose 4%.',
  sourceName: 'report.mdv',
  target: 'pdf',
  buildTime: '2026-08-18T09:00:00.000Z',
  theme: 'print',
  locale: 'ko-KR',
  timezone: 'Asia/Seoul',
};

// ─────────────────────────────────────────────────────────────────────────────
// Probe
// ─────────────────────────────────────────────────────────────────────────────

test('probe parses the version line and caches it', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const first = await runner.probe();
  assert.equal(first.available, true);
  assert.deepEqual(first.version, {
    cli: '0.0.0',
    spec: '1.0.0',
    core: '0.0.0',
    commit: COMMIT,
    line: VERSION_LINE,
  });
  // Cached: the answer cannot change without the process restarting, and it is
  // pinned into every render's metadata, so a second probe must not re-ask.
  await runner.probe();
  assert.equal(engine.calls.filter((c) => c.argv[0] === '--version').length, 1);
});

test('a version line mdv did not write is unavailable, not a guess', async () => {
  const engine = fakeEngine({ '--version': { code: 0, stdout: 'mdv\n', stderr: '' } });
  const status = await createMdvRunner({ engine, commit: COMMIT }).probe();
  assert.equal(status.available, false);
  assert.match(status.reason, /--version said/u);
});

test('an engine that throws degrades, it does not crash the worker', async () => {
  const engine = {
    async execute() {
      throw new Error('Cannot find module /app/vendor/mdv/packages/cli/dist/index.js');
    },
  };
  const status = await createMdvRunner({ engine, commit: COMMIT }).probe();
  assert.equal(status.available, false);
  assert.match(status.reason, /could not be started/u);
});

// ─────────────────────────────────────────────────────────────────────────────
// Refusals that must happen before a thread is spent
// ─────────────────────────────────────────────────────────────────────────────

test('an unavailable mdv is a refusal carrying both languages', async () => {
  const engine = fakeEngine({ '--version': { code: 1, stdout: '', stderr: 'boom' } });
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const error = await runner.render(REQUEST).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'mdv_unavailable');
  assert.ok(error.problem.ko.trim() !== '');
  assert.ok(error.problem.en.trim() !== '');
});

test('a refused target never reaches the engine', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  for (const target of ['html', 'png', 'md']) {
    const error = await runner.render({ ...REQUEST, target }).then(
      () => undefined,
      (e) => e,
    );
    assert.ok(error instanceof MdvRefusal, `${target} should be refused`);
    assert.equal(error.problem.code, 'unsupported_target');
  }
  // Only the version probe: no export was attempted for any of the three.
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('an absent buildTime is refused before a thread is spent', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const error = await runner.render({ ...REQUEST, buildTime: undefined }).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'build_time_required');
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('an allowlist entry pointing inside the deployment refuses the job', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const error = await runner
    .render({ ...REQUEST, allowExternal: true, allowedOrigins: ['http://10.0.2.5'] })
    .then(
      () => undefined,
      (e) => e,
    );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'origin_blocked');
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('a Korean document is refused before mdv draws it as question marks', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const error = await runner.render({ ...REQUEST, source: '# 승인 요청서' }).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'font_coverage');
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('an archival or accessibility profile is refused before mdv can mislabel the file', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  for (const profile of ['pdf-a-3b', 'pdf-ua-1']) {
    const error = await runner.render({ ...REQUEST, options: { profile } }).then(
      () => undefined,
      (e) => e,
    );
    assert.ok(error instanceof MdvRefusal, profile);
    assert.equal(error.problem.code, 'profile_unsupported', profile);
  }
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('the coverage guard is for PDF only: SVG carries its own text', async () => {
  // SVG references fonts by name and resolves them at view time, so a Hangul
  // codepoint is not a question mark - it is a font problem on the viewer's
  // machine, which is a different conversation and not ours to refuse.
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  const result = await runner.render({ ...REQUEST, source: '# 승인 요청서', target: 'svg' });
  assert.equal(result.filename, 'out.svg');
});

// ─────────────────────────────────────────────────────────────────────────────
// The argv - the actual contract with mdv
// ─────────────────────────────────────────────────────────────────────────────

test('every invocation pins the build time, and lint and export agree', async () => {
  const engine = workingEngine();
  const runner = createMdvRunner({ engine, commit: COMMIT });
  await runner.render(REQUEST);
  const lint = engine.calls.find((c) => c.argv[0] === 'lint');
  const exported = engine.calls.find((c) => c.argv[0] === 'export');
  for (const call of [lint, exported]) {
    const at = call.argv.indexOf('--build-time');
    assert.ok(at !== -1, 'every invocation must pin the build time');
    assert.equal(call.argv[at + 1], '2026-08-18T09:00:00.000Z');
    assert.ok(call.argv.includes('--no-color'), 'colour codes must not reach a JSON parse');
  }
  // Same document, same flags: a lint that saw a different document than the
  // export did is a clean report about a file nobody rendered.
  assert.equal(lint.argv[1], exported.argv[1]);
});

test('--allow-external is never passed when the caller did not ask', async () => {
  const engine = workingEngine();
  await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST);
  const exported = engine.calls.find((c) => c.argv[0] === 'export');
  assert.equal(exported.argv.includes('--allow-external'), false);
  assert.equal(exported.argv.includes('--allow-file'), false);
});

test('--allow-file is never passed at all: the worker filesystem is other tenants', async () => {
  const engine = workingEngine();
  await createMdvRunner({ engine, commit: COMMIT }).render({
    ...REQUEST,
    allowExternal: true,
    allowedOrigins: ['https://data.example.com'],
  });
  const exported = engine.calls.find((c) => c.argv[0] === 'export');
  assert.equal(exported.argv.includes('--allow-external'), true);
  assert.equal(exported.argv.includes('--allow-file'), false);
});

// ─────────────────────────────────────────────────────────────────────────────
// Results and failures
// ─────────────────────────────────────────────────────────────────────────────

test('a successful render carries the version, the commit and the config digest', async () => {
  const engine = workingEngine();
  const result = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST);
  assert.equal(result.content.toString(), 'PDF-BYTES');
  assert.equal(result.rendererVersion, VERSION_LINE);
  assert.equal(result.mdvCommit, COMMIT);
  assert.match(result.renderConfigDigest, /^sha256:[0-9a-f]{64}$/u);
  // The snapshot is stored beside the digest: a hash nobody can diff is a hash
  // nobody trusts.
  assert.equal(result.renderConfig.mdv.commit, COMMIT);
  assert.equal(result.renderConfig.buildTime, '2026-08-18T09:00:00.000Z');
});

test('the same request twice produces the same digest', async () => {
  const a = await createMdvRunner({ engine: workingEngine(), commit: COMMIT }).render(REQUEST);
  const b = await createMdvRunner({ engine: workingEngine(), commit: COMMIT }).render(REQUEST);
  assert.equal(a.renderConfigDigest, b.renderConfigDigest);
});

test('lint errors refuse the render and name the codes', async () => {
  const engine = workingEngine({
    lint: {
      code: 1,
      // The shape `packages/cli/src/report.ts` `formatJson()` actually emits:
      // one flat row per diagnostic, carrying its own `file`. Modelling the
      // nested `{ file, diagnostics: [] }` shape here would have passed a test
      // against a reply mdv never sends.
      stdout: JSON.stringify([
        { file: 'in.mdv', code: 'MDV1234', severity: 'error', message: 'bad block' },
      ]),
      stderr: '',
    },
  });
  const error = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'document_errors');
  assert.match(error.problem.detail, /MDV1234/u);
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('a blocked external source is a refusal, not a placeholder chart', async () => {
  const engine = workingEngine({
    lint: {
      code: 1,
      stdout: JSON.stringify([
        { file: 'in.mdv', code: 'MDV4022', severity: 'warning', message: 'blocked' },
      ]),
      stderr: '',
    },
  });
  const error = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'external_data_refused');
});

// §13 acceptance 2. The document is the attacker here: a client uploads an
// .mdv whose `src:` names an address inside our own network. Three separate
// things have to hold, and only the first is mdv's doing.
const SSRF_DOCUMENT = [
  '---',
  'mdv: "1.0"',
  'title: Innocent looking report',
  '---',
  '',
  '```mdv dataset id=stolen',
  'src: http://169.254.169.254/latest/meta-data/iam/security-credentials/',
  '```',
  '',
].join('\n');

test('a document whose src: is a metadata address is refused, and no fetch is ever offered', async () => {
  // mdv only installs a `fetch` capability when `--allow-external` is passed
  // (`capabilitiesFor` in packages/cli/src/pipeline.ts). We never pass it
  // unless the caller supplied an allowlist, so for an ordinary render the
  // renderer is not merely refusing the address - it has no way to reach the
  // network at all. That is the property worth asserting: not "it said no",
  // but "it could not have said yes".
  const engine = workingEngine({
    lint: {
      code: 1,
      stdout: JSON.stringify([
        {
          file: 'in.mdv',
          code: 'MDV4002',
          severity: 'error',
          message: 'External data is disabled',
        },
      ]),
      stderr: '',
    },
  });
  const error = await createMdvRunner({ engine, commit: COMMIT })
    .render({ ...REQUEST, source: SSRF_DOCUMENT })
    .then(() => undefined, (e) => e);

  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'external_data_refused');
  for (const call of engine.calls) {
    assert.equal(call.argv.includes('--allow-external'), false, 'a fetch capability was offered');
  }
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('a caller cannot allowlist its way to a loopback address', async () => {
  // The second line of defence: an admin (or a bug, or a compromised caller)
  // asks for external data *and* names a private origin. The job is refused
  // whole, before a thread is spent, rather than left to mdv's own host check -
  // by then the DNS lookup has already told someone we exist.
  const engine = workingEngine();
  for (const origin of ['http://127.0.0.1:8080', 'http://169.254.169.254', 'http://[::1]']) {
    const error = await createMdvRunner({ engine, commit: COMMIT })
      .render({ ...REQUEST, source: SSRF_DOCUMENT, allowExternal: true, allowedOrigins: [origin] })
      .then(() => undefined, (e) => e);
    assert.ok(error instanceof MdvRefusal, origin);
    assert.equal(error.problem.code, 'origin_blocked', origin);
  }
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'lint').length, 0);
  assert.equal(engine.calls.filter((c) => c.argv[0] === 'export').length, 0);
});

test('an export that produced nothing is a refusal, not an empty file', async () => {
  const engine = workingEngine({ export: { code: 0, stdout: '', stderr: '' } });
  const error = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'no_output');
});

test('mdv exit codes map onto something a person can act on', async () => {
  const cases = [
    [4, 'security_refusal'],
    [3, 'mdv_io_error'],
    [2, 'mdv_usage_error'],
    [70, 'mdv_failed'],
  ];
  for (const [code, expected] of cases) {
    const engine = workingEngine({ export: { code, stdout: '', stderr: 'stderr text' } });
    const error = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST).then(
      () => undefined,
      (e) => e,
    );
    assert.ok(error instanceof MdvRefusal, `exit ${code} should refuse`);
    assert.equal(error.problem.code, expected, `exit ${code}`);
    assert.ok(error.problem.ko.trim() !== '', `exit ${code} needs Korean`);
    assert.ok(error.problem.en.trim() !== '', `exit ${code} needs English`);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// mdv fmt on save
// ─────────────────────────────────────────────────────────────────────────────

/** A fake `fmt`: rewrites the file in place, the way the real command does. */
function formattingEngine(rewrite) {
  return fakeEngine({
    '--version': { code: 0, stdout: `${VERSION_LINE}\n`, stderr: '' },
    fmt: async ({ argv, cwd }) => {
      const { readFile, writeFile } = await import('node:fs/promises');
      const { join } = await import('node:path');
      const path = join(cwd, argv[1]);
      await writeFile(path, rewrite(await readFile(path, 'utf8')));
      return { code: 0, stdout: '', stderr: '' };
    },
  });
}

test('fmt returns the canonicalised text and says whether it moved', async () => {
  const engine = formattingEngine((text) => text.replace(/\s+\|\s+/gu, ' | '));
  const runner = createMdvRunner({ engine, commit: COMMIT });

  const result = await runner.format({ source: 'a    |    b\n', sourceName: 'doc.mdv' });
  assert.equal(result.text, 'a | b\n');
  assert.equal(result.changed, true);
  assert.equal(result.mdvCommit, COMMIT);
});

test('fmt on already-canonical text is a no-op, and says so', async () => {
  // ADR 0008 leans on this: stored mdv is canonical, so a save must not
  // manufacture a diff. `changed: false` is what lets the caller skip writing a
  // new version for a document nobody edited.
  const engine = formattingEngine((text) => text);
  const result = await createMdvRunner({ engine, commit: COMMIT }).format({ source: 'a | b\n' });
  assert.equal(result.text, 'a | b\n');
  assert.equal(result.changed, false);
});

test('fmt needs no build time: it parses and re-prints, it never resolves', async () => {
  // If this ever starts needing one, the save path breaks for every document -
  // so pin the fact rather than discovering it in the editor.
  const engine = formattingEngine((text) => text);
  const runner = createMdvRunner({ engine, commit: COMMIT });
  await runner.format({ source: '# Report\n' });
  const fmt = engine.calls.find((c) => c.argv[0] === 'fmt');
  assert.equal(fmt.argv.includes('--build-time'), false);
});

test('a document fmt refuses is left exactly as it was', async () => {
  // `fmt` re-parses its own output and refuses to write when the AST moved.
  // The document must come back untouched rather than half-formatted.
  const engine = fakeEngine({
    '--version': { code: 0, stdout: `${VERSION_LINE}\n`, stderr: '' },
    fmt: { code: 2, stdout: '', stderr: 'Refusing to format doc.mdv: does not parse back' },
  });
  const error = await createMdvRunner({ engine, commit: COMMIT })
    .format({ source: '# Report\n' })
    .then(() => undefined, (e) => e);
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'format_failed');
  assert.ok(error.problem.ko.trim() !== '');
  assert.match(error.problem.detail, /does not parse back/u);
});

test('lint output that is not JSON is a refusal naming this worker, not the author', async () => {
  const engine = workingEngine({ lint: { code: 0, stdout: 'Lint found problems', stderr: '' } });
  const error = await createMdvRunner({ engine, commit: COMMIT }).render(REQUEST).then(
    () => undefined,
    (e) => e,
  );
  assert.ok(error instanceof MdvRefusal);
  assert.equal(error.problem.code, 'lint_unreadable');
});
