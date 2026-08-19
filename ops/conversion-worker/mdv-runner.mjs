// The mdv renderer, behind a two-method seam.
//
// Everything above the seam — writing the input, choosing the argv, applying the
// policy in `mdv-policy.mjs`, reading the produced file, fingerprinting the
// config — is plain code that runs anywhere. Below the seam is `execute()`,
// which loads a real mdv build on a worker thread (`mdv-thread.mjs`). Tests
// inject a fake `execute` and exercise everything else, because `vendor/mdv` is
// a submodule with no `node_modules` and no `dist` on a developer's machine: a
// design that can only be tested inside the built image is a design nobody
// tests.
//
// Three properties this file exists to guarantee, in the register of the
// LibreOffice half of this worker:
//
//   1. The renderer is BOUNDED and never on the request thread. Jobs come in
//      through the same queue as LibreOffice jobs.
//   2. A failure produces an error, never a plausible artefact. mdv's own habit
//      is to degrade — a refused data source becomes a placeholder chart, an
//      unencodable glyph becomes `?` — which is right for a live editor and
//      wrong for a document somebody is about to approve.
//   3. Every render records what produced it: mdv's version line, the pinned
//      submodule commit, and a hash of the full render config.

import { mkdtemp, readdir, readFile, rm, writeFile, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, basename, extname } from 'node:path';
import { Worker } from 'node:worker_threads';
import { fileURLToPath } from 'node:url';

import {
  allowlistRefusal,
  externalDataRefusal,
  pdfCoverageRefusal,
  profileRefusal,
  renderFingerprint,
  resolveBuildTime,
  targetRefusal,
} from './mdv-policy.mjs';

/** Where the built CLI lives. The container sets this; see the Dockerfile. */
export const DEFAULT_MDV_ENTRY = fileURLToPath(
  new URL('../../vendor/mdv/packages/cli/dist/index.js', import.meta.url),
);

const THREAD = fileURLToPath(new URL('./mdv-thread.mjs', import.meta.url));

/** File extension per implemented target. */
const EXTENSIONS = { pdf: '.pdf', svg: '.svg', json: '.json', csv: '.csv' };

/**
 * A refusal the caller should show a user, as opposed to a bug.
 *
 * Carries a `problem` (stable code, Korean and English) rather than a string,
 * because these travel through the API to an approval screen.
 */
export class MdvRefusal extends Error {
  constructor(problem) {
    super(`${problem.code}: ${problem.en}`);
    this.name = 'MdvRefusal';
    this.problem = problem;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Below the seam: a real mdv build, on a thread
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Run one mdv invocation and collect its streams.
 *
 * The timeout terminates the thread rather than asking it to stop. A layout that
 * has gone quadratic will not notice an `AbortSignal` — the CLI only honours one
 * in `mdv watch` — and a thread that keeps a core busy after its job was
 * abandoned is how the pool bound gets quietly lost.
 */
export function threadEngine({ entry, timeoutMs, maxOldGenerationSizeMb } = {}) {
  return {
    entry,
    execute({ argv, cwd, env }) {
      return new Promise((resolve, reject) => {
        const worker = new Worker(THREAD, {
          workerData: { entry, argv, cwd, env: env ?? {} },
          // A document can ask for a lot of memory. Bounding the thread's heap
          // turns "the box starts swapping and every other job slows down" into
          // one failed job with a name on it.
          ...(maxOldGenerationSizeMb === undefined
            ? {}
            : { resourceLimits: { maxOldGenerationSizeMb } }),
        });
        let settled = false;
        const timer = setTimeout(() => {
          settled = true;
          void worker.terminate();
          reject(new Error(`mdv timed out after ${timeoutMs}ms`));
        }, timeoutMs);

        worker.on('message', (message) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          void worker.terminate();
          if (message.ok) resolve({ code: message.code, stdout: message.stdout, stderr: message.stderr });
          else reject(new Error(message.error));
        });
        worker.on('error', (error) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          reject(error);
        });
        worker.on('exit', (code) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          reject(new Error(`mdv thread exited with ${code} before answering`));
        });
      });
    },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Above the seam
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @param engine  `{ entry?, execute({argv, cwd, env}) }`. Injected by tests.
 * @param commit  The pinned `vendor/mdv` commit, baked into the image. Recorded
 *                in every render: ADR 0008 makes the pin part of the approval
 *                trail, and "which mdv drew this" must be answerable from the
 *                stored metadata alone, without the image to hand.
 */
export function createMdvRunner({ engine, commit, env } = {}) {
  const runner = engine ?? threadEngine({ entry: DEFAULT_MDV_ENTRY, timeoutMs: 120_000 });
  const childEnv = env ?? {};
  let probed;

  /**
   * Ask the build what it is. Cached: the answer cannot change without the
   * process restarting, and it is pinned into every render's metadata — the same
   * reason `detectLibreOfficeVersion()` is cached above.
   */
  async function probe() {
    if (probed !== undefined) return probed;
    probed = (async () => {
      if (runner.entry !== undefined) {
        try {
          await access(runner.entry);
        } catch {
          return {
            available: false,
            // Not a crash: the worker still converts office documents. mdv
            // absent is a degraded worker, and `/health` says so.
            reason: `mdv is not built at ${runner.entry}`,
          };
        }
      }
      let result;
      try {
        result = await runner.execute({ argv: ['--version'], cwd: tmpdir(), env: childEnv });
      } catch (error) {
        return { available: false, reason: `mdv could not be started: ${String(error.message ?? error)}` };
      }
      // `mdv 0.0.0 (spec 1.0.0, core 0.0.0)` — index.ts `versionLine()`.
      const match = /^mdv (\S+) \(spec ([^,]+), core ([^)]+)\)/u.exec(result.stdout.trim());
      if (match === null) {
        return { available: false, reason: `mdv --version said ${JSON.stringify(result.stdout.trim())}` };
      }
      return {
        available: true,
        version: { cli: match[1], spec: match[2], core: match[3], commit: commit ?? 'unknown', line: result.stdout.trim() },
      };
    })();
    return probed;
  }

  /**
   * Render one document.
   *
   * Two mdv invocations per job, deliberately. `export` reports its diagnostics
   * by printing cards to stderr and returns only an exit code, so the *only*
   * machine-readable channel this build offers is `lint --format json`. We lint
   * first, decide, then export. It costs a second parse and resolve of the same
   * document; it buys a refusal that names codes instead of one that greps
   * English prose out of a terminal stream.
   */
  async function render(request) {
    const {
      source,
      sourceName = 'document.mdv',
      target = 'pdf',
      buildTime,
      theme,
      locale,
      timezone,
      level,
      strict = false,
      allowExternal = false,
      allowedOrigins = [],
      options = {},
    } = request;

    const status = await probe();
    if (!status.available) {
      throw new MdvRefusal({
        code: 'mdv_unavailable',
        ko: '이 워커에는 mdv 렌더러가 설치되어 있지 않습니다.',
        en: `This worker has no mdv renderer: ${status.reason}`,
      });
    }

    const refusal =
      targetRefusal(target) ??
      (allowExternal ? allowlistRefusal(allowedOrigins) : undefined) ??
      (target === 'pdf' ? profileRefusal(options.profile) : undefined) ??
      (target === 'pdf' ? pdfCoverageRefusal(source) : undefined);
    if (refusal !== undefined) throw new MdvRefusal(refusal);

    const time = resolveBuildTime(buildTime);
    if (time.problem !== undefined) throw new MdvRefusal(time.problem);

    const fingerprint = renderFingerprint({
      mdv: status.version,
      target,
      buildTime: time.buildTime,
      theme,
      locale,
      timezone,
      level,
      strict,
      options,
      allowExternal,
      allowedOrigins,
    });

    const workDir = await mkdtemp(join(tmpdir(), 'coreintra-mdv-'));
    try {
      // Keep the caller's stem: it reaches the user in mdv's own diagnostics,
      // and `document.mdv:12:3` is worth more than `input.mdv:12:3`.
      const inputName = withMdvExtension(basename(sourceName));
      const inputPath = join(workDir, inputName);
      await writeFile(inputPath, source, 'utf8');

      const shared = globalFlags({ time: time.buildTime, theme, locale, timezone, level, strict, allowExternal, allowedOrigins });

      const diagnostics = await lint(inputName, shared, workDir);
      const dataRefusal = externalDataRefusal(diagnostics);
      if (dataRefusal !== undefined) throw new MdvRefusal(dataRefusal);
      const errors = diagnostics.filter((d) => d.severity === 'error');
      if (errors.length > 0) {
        throw new MdvRefusal({
          code: 'document_errors',
          ko: `문서에 오류 ${errors.length}건이 있어 렌더링하지 않았습니다.`,
          en: `The document has ${errors.length} error diagnostic(s); refusing to render it.`,
          detail: errors.slice(0, 5).map((d) => `${d.code} ${d.message}`).join('; '),
        });
      }

      const outName = `out${EXTENSIONS[target]}`;
      const argv = [
        'export',
        inputName,
        '--to',
        target,
        '-o',
        outName,
        ...shared,
        ...exportFlags(options),
      ];
      const result = await runner.execute({ argv, cwd: workDir, env: childEnv });
      if (result.code !== 0) throw new MdvRefusal(exitProblem(result, target));

      // Read the directory rather than trusting the name we asked for. An SVG
      // export of a multi-block document writes one file per block and ignores
      // the `-o` stem, so trusting `out.svg` would return whichever file
      // happened to be named that — or nothing — and call it the document.
      const produced = (await readdir(workDir)).filter((name) => name !== inputName).sort();
      if (produced.length === 0) {
        throw new MdvRefusal({
          code: 'no_output',
          ko: 'mdv가 출력 파일을 생성하지 않았습니다.',
          en: `mdv exited cleanly but produced no output for ${sourceName}.`,
          detail: clip(result.stderr),
        });
      }
      if (produced.length > 1) {
        throw new MdvRefusal({
          code: 'multiple_outputs',
          ko: `이 문서는 블록마다 파일을 생성합니다 (${produced.length}개). 블록을 지정하십시오.`,
          en: `This document exports to ${produced.length} files, one per visual block; name a single block instead of returning one of them.`,
          detail: produced.join(', '),
        });
      }

      return {
        filename: produced[0],
        content: await readFile(join(workDir, produced[0])),
        rendererVersion: status.version.line,
        mdvCommit: status.version.commit,
        renderConfig: fingerprint.snapshot,
        renderConfigDigest: fingerprint.digest,
        // Warnings survive into the response: MDV5100/5101 should be impossible
        // after the coverage guard, and if one appears we want it on the record
        // rather than discovered in a printout.
        diagnostics: diagnostics.map((d) => ({ code: d.code, severity: d.severity, message: d.message })),
      };
    } finally {
      await rm(workDir, { recursive: true, force: true }).catch(() => {});
    }
  }

  /**
   * Canonicalise a document (`mdv fmt`), and hand the text back.
   *
   * Stored mdv is canonical form (ADR 0008): `fmt` runs on save so that a
   * version diff shows what an author changed rather than how they happened to
   * space a table. `fmt` rewrites in place and prints only a status line, so
   * "return the canonicalised text" means writing to a temp file and reading it
   * back - there is no stdout mode to ask for.
   *
   * No `buildTime` is required here, and that is not an oversight: `fmt` parses
   * and re-prints, it never resolves, so no expression sees `now()` and nothing
   * about the output can depend on the clock.
   */
  async function format(request) {
    const { source, sourceName = 'document.mdv' } = request;

    const status = await probe();
    if (!status.available) {
      throw new MdvRefusal({
        code: 'mdv_unavailable',
        ko: '이 워커에는 mdv 렌더러가 설치되어 있지 않습니다.',
        en: `This worker has no mdv renderer: ${status.reason}`,
      });
    }

    const workDir = await mkdtemp(join(tmpdir(), 'coreintra-fmt-'));
    try {
      const inputName = withMdvExtension(basename(sourceName));
      const inputPath = join(workDir, inputName);
      await writeFile(inputPath, source, 'utf8');

      const result = await runner.execute({
        argv: ['fmt', inputName, '--no-color'],
        cwd: workDir,
        env: childEnv,
      });
      if (result.code !== 0) {
        // Exit 2 from `fmt` is usually not our bad argv: the command re-parses
        // its own output and refuses to write when the AST changed, which is a
        // parser bug on this document. Saying "worker bug" there would send
        // someone to the wrong file.
        throw new MdvRefusal({
          code: 'format_failed',
          ko: '문서를 정규화하지 못했습니다. 원본을 그대로 저장합니다.',
          en: `mdv fmt refused this document (exit ${result.code}); it was left unchanged.`,
          detail: clip(result.stderr || result.stdout),
        });
      }

      const text = await readFile(inputPath, 'utf8');
      return {
        text,
        changed: text !== source,
        rendererVersion: status.version.line,
        mdvCommit: status.version.commit,
      };
    } finally {
      await rm(workDir, { recursive: true, force: true }).catch(() => {});
    }
  }

  async function lint(inputName, shared, workDir) {
    const result = await runner.execute({
      argv: ['lint', inputName, '--format', 'json', ...shared],
      cwd: workDir,
      env: childEnv,
    });
    // Exit 1 means "diagnostics at or above the gate", which is a report, not a
    // failure. Exit 2, 3, 4 and anything else are.
    if (result.code !== 0 && result.code !== 1) throw new MdvRefusal(exitProblem(result, 'lint'));
    if (result.stdout.trim() === '') return [];
    try {
      const rows = JSON.parse(result.stdout);
      return Array.isArray(rows) ? rows : [];
    } catch {
      throw new MdvRefusal({
        code: 'lint_unreadable',
        ko: 'mdv 진단 결과를 해석할 수 없습니다.',
        en: 'mdv lint --format json did not return JSON; this build is not the one this worker was written against.',
        detail: clip(result.stdout),
      });
    }
  }

  return { probe, render, format };
}

/** Map an mdv exit code (SPEC 27) onto something a person can act on. */
function exitProblem(result, what) {
  const detail = clip(result.stderr);
  switch (result.code) {
    case 4:
      return {
        code: 'security_refusal',
        ko: '문서가 보안 정책상 허용되지 않는 자원을 요청했습니다.',
        en: 'The document asked for something the security policy refuses (mdv exit 4).',
        detail,
      };
    case 3:
      return {
        code: 'mdv_io_error',
        ko: 'mdv가 파일을 읽거나 쓰지 못했습니다.',
        en: 'mdv could not read or write a file (exit 3).',
        detail,
      };
    case 2:
      // Ours, not the user's: the worker built the command line.
      return {
        code: 'mdv_usage_error',
        ko: '내부 오류: mdv 호출이 잘못되었습니다.',
        en: `The worker invoked mdv ${what} incorrectly (exit 2). This is a worker bug.`,
        detail,
      };
    default:
      return {
        code: 'mdv_failed',
        ko: `mdv ${what} 실행이 실패했습니다 (종료 코드 ${result.code}).`,
        en: `mdv ${what} failed with exit code ${result.code}.`,
        detail,
      };
  }
}

/**
 * Flags every invocation carries, so lint and export see the same document.
 *
 * `--build-time` is not optional and is checked before we get here: mdv would
 * accept its absence and date the render 1970 without a word.
 */
function globalFlags({ time, theme, locale, timezone, level, strict, allowExternal, allowedOrigins }) {
  const flags = ['--no-color', '--build-time', time.toISOString()];
  if (theme !== undefined) flags.push('--theme', theme);
  if (locale !== undefined) flags.push('--locale', locale);
  if (timezone !== undefined) flags.push('--timezone', timezone);
  if (level !== undefined) flags.push('--level', String(level));
  if (strict === true) flags.push('--strict');
  // `--allow-file` is never passed. A `file:` source would read the worker's own
  // filesystem, and the worker's filesystem is other tenants' documents.
  if (allowExternal === true && allowedOrigins.length > 0) flags.push('--allow-external');
  return flags;
}

/** Export-only flags, in a fixed order so the argv is diffable in a log. */
function exportFlags(options) {
  const flags = [];
  if (options.width !== undefined) flags.push('--width', String(options.width));
  if (options.block !== undefined) flags.push('--block', String(options.block));
  if (options.scale !== undefined) flags.push('--scale', String(options.scale));
  if (options.pageSize !== undefined) flags.push('--page-size', String(options.pageSize));
  if (options.orientation !== undefined) flags.push('--orientation', String(options.orientation));
  if (options.profile !== undefined) flags.push('--profile', String(options.profile));
  if (options.paginate === true) flags.push('--paginate');
  if (options.compress === false) flags.push('--no-compress');
  if (options.embedSource === true) flags.push('--embed-source');
  if (options.embedSource === false) flags.push('--no-embed-source');
  return flags;
}

/** mdv only loads `.mdv` and `.md`; a document named otherwise is not refused. */
function withMdvExtension(name) {
  const ext = extname(name).toLowerCase();
  return ext === '.mdv' || ext === '.md' ? name : `${name}.mdv`;
}

function clip(text) {
  const value = String(text ?? '').trim();
  return value.length <= 600 ? value : `${value.slice(0, 600)}…`;
}
