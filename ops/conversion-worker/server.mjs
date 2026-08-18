// CoreIntra conversion worker.
//
// Headless LibreOffice plus a Node runtime for mdv rendering, behind one job
// queue. It is the heaviest component in the deployment and the only place the
// stack is deliberately not Java 8 — mdv has no JVM implementation, so its
// renderer runs here (ADR 0008).
//
// Three properties this file exists to guarantee:
//
//  1. The pool is BOUNDED. Each LibreOffice process costs 150-300 MB resident,
//     so an unbounded pool exhausts RAM long before it exhausts CPU, and the
//     box starts swapping rather than refusing work. Excess jobs queue.
//  2. Conversion NEVER occupies a request thread on the API. Small documents
//     convert inline within a timeout; anything slower becomes a job id the
//     caller polls.
//  3. A failure produces an error, never a truncated file. Output is written to
//     a temporary directory and only read back after LibreOffice exits zero.
//
// The mdv renderer joins on the same terms: same queue, same pool bound, same
// refusal to hand back a plausible-looking artefact. It differs in one way worth
// knowing — LibreOffice is a subprocess and mdv is an in-process CLI, so an mdv
// job runs on a worker thread to keep layout off this loop (`mdv-runner.mjs`).
// mdv absent is a degraded worker, not a dead one: office conversion still
// works and `/health` reports why rendering does not.

import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { randomUUID } from 'node:crypto';

import { createMdvRunner, DEFAULT_MDV_ENTRY, MdvRefusal, threadEngine } from './mdv-runner.mjs';
import { MDV_TARGETS } from './mdv-policy.mjs';

const PORT = Number(process.env.PORT ?? 3000);

// Sized explicitly. See docs/deploy/sizing.md for the arithmetic; the default
// of 3 assumes the documented 4 vCPU / 12 GB target with the API alongside.
const POOL_SIZE = Number(process.env.COREINTRA_CONVERSION_POOL_SIZE ?? 3);

// A document that has not converted in two minutes is not going to.
const JOB_TIMEOUT_MS = Number(process.env.COREINTRA_CONVERSION_TIMEOUT_MS ?? 120_000);

// Finished jobs are kept briefly so a poller can collect the result, then
// dropped. Keeping them forever is a memory leak with a slow fuse.
const RESULT_TTL_MS = Number(process.env.COREINTRA_CONVERSION_RESULT_TTL_MS ?? 600_000);

const SOFFICE = process.env.COREINTRA_SOFFICE_BIN ?? 'soffice';

/** Target format to the LibreOffice filter that produces it. */
const FILTERS = {
  pdf: 'pdf:writer_pdf_Export',
  docx: 'docx:MS Word 2007 XML',
  // Labelled legacy and lossy in the UI. Supported because clients still
  // receive .doc from counterparties who cannot open anything newer.
  doc: 'doc:MS Word 97',
  odt: 'odt:writer8',
  html: 'html:HTML (StarWriter)',
  txt: 'txt:Text',
};

// Where the built mdv CLI lives. Set in the image; a developer running this
// file from a checkout gets `vendor/mdv/packages/cli/dist`, which is normally
// absent — hence the degraded path rather than a crash on start-up.
const MDV_ENTRY = process.env.COREINTRA_MDV_CLI ?? DEFAULT_MDV_ENTRY;

// The pinned submodule commit, baked in at image build time. There is no git in
// the container, and ADR 0008 makes the pin part of the approval trail: "which
// mdv drew this" has to be answerable from the stored metadata alone.
//
// The environment wins; failing that, the file the image build wrote (ENV
// cannot be assigned from a RUN, so the computed pin has to travel as a file).
// `unknown` is the honest last resort and is recorded as such rather than
// omitted — a render whose provenance is unknown must say so, not look
// unremarkable.
const MDV_COMMIT = readPin();

function readPin() {
  if (process.env.COREINTRA_MDV_COMMIT) return process.env.COREINTRA_MDV_COMMIT;
  const file = process.env.COREINTRA_MDV_PIN_FILE;
  if (!file) return 'unknown';
  try {
    const pin = readFileSync(file, 'utf8').trim();
    return pin === '' ? 'unknown' : pin;
  } catch {
    return 'unknown';
  }
}

// Separate from JOB_TIMEOUT_MS: an mdv render is CPU on a thread we can kill
// cleanly, and a long document legitimately takes longer than an office file
// that has already been laid out by whoever wrote it.
const MDV_TIMEOUT_MS = Number(process.env.COREINTRA_MDV_TIMEOUT_MS ?? 120_000);
const MDV_HEAP_MB = Number(process.env.COREINTRA_MDV_HEAP_MB ?? 512);

const jobs = new Map();
const queue = [];
let active = 0;
let libreOfficeVersion = null;

const mdv = createMdvRunner({
  engine: threadEngine({
    entry: MDV_ENTRY,
    timeoutMs: MDV_TIMEOUT_MS,
    maxOldGenerationSizeMb: MDV_HEAP_MB,
  }),
  commit: MDV_COMMIT,
  // The thread inherits nothing by default. mdv reads `NO_COLOR` and `TZ`, and
  // `TZ` in particular must not leak in from the host: a render's timezone comes
  // from the request, never from wherever this container happens to think it is.
  env: { NO_COLOR: '1' },
});

/** The pinned version, recorded in every render's metadata. */
async function detectLibreOfficeVersion() {
  if (libreOfficeVersion) return libreOfficeVersion;
  const output = await run(SOFFICE, ['--version'], 15_000).catch(() => null);
  libreOfficeVersion = output?.stdout?.trim().split('\n')[0] ?? 'unknown';
  return libreOfficeVersion;
}

function run(command, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env: { ...process.env, HOME: process.env.HOME ?? '/tmp' } });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`exited ${code}: ${stderr.trim() || stdout.trim()}`));
    });
  });
}

/**
 * Converts one document.
 *
 * Each conversion gets its own profile directory. LibreOffice serialises on a
 * shared profile, so without this the "pool" would be several processes taking
 * turns on one lock — parallel in appearance only.
 */
/**
 * Refuses input whose bytes do not match the extension it claims.
 *
 * Found by testing: handed a text file named `.docx`, LibreOffice does not
 * fail — it guesses, imports it as plain text, and returns a perfectly valid
 * PDF containing the junk. That is worse than an error, because the caller
 * gets a document that looks converted and is meaningless, and it would be
 * hashed into an approval trail as though it were real.
 *
 * The API rejects these earlier (OoxmlPackage refuses anything without
 * word/document.xml), but the worker accepts requests from a service account
 * and must not depend on its caller having checked.
 */
function assertSourceLooksRight(content, sourceName) {
  const name = sourceName.toLowerCase();
  const zipLike = content.length > 4
    && content[0] === 0x50 && content[1] === 0x4b; // "PK"

  if ((name.endsWith('.docx') || name.endsWith('.hwpx') || name.endsWith('.odt')
       || name.endsWith('.xlsx') || name.endsWith('.pptx')) && !zipLike) {
    throw new Error(
      `${sourceName} is named as a package format but is not a ZIP archive. It may be a ` +
      `legacy binary (.doc / .hwp), a renamed file of another type, or corrupt. ` +
      `Converting it anyway would produce a plausible-looking document with the wrong contents.`);
  }
  // D0 CF 11 E0 — the OLE2 compound-file header shared by .doc and .hwp 5.0.
  const oleLike = content.length > 8
    && content[0] === 0xd0 && content[1] === 0xcf
    && content[2] === 0x11 && content[3] === 0xe0;
  if (name.endsWith('.docx') && oleLike) {
    throw new Error(
      `${sourceName} is a legacy binary document (OLE2) with a .docx name. Re-save it as ` +
      `DOCX, or upload it under its real extension so the correct import filter is used.`);
  }
}

async function convert({ content, sourceName, targetFormat }) {
  const filter = FILTERS[targetFormat];
  if (!filter) {
    throw new Error(`unsupported target format "${targetFormat}"`);
  }
  assertSourceLooksRight(content, sourceName);

  const workDir = await mkdtemp(join(tmpdir(), 'coreintra-conv-'));
  const profileDir = await mkdtemp(join(tmpdir(), 'coreintra-profile-'));
  const inputPath = join(workDir, basename(sourceName));

  try {
    await writeFile(inputPath, content);
    await run(SOFFICE, [
      '--headless',
      '--norestore',
      '--nolockcheck',
      `-env:UserInstallation=file://${profileDir}`,
      '--convert-to', filter,
      '--outdir', workDir,
      inputPath,
    ], JOB_TIMEOUT_MS);

    // Only read the output AFTER a clean exit. Reading during conversion is how
    // a truncated PDF reaches an approval trail and is hashed as authoritative.
    const produced = (await readdir(workDir)).filter((f) => f !== basename(inputPath));
    if (produced.length === 0) {
      throw new Error(
        `LibreOffice produced no output for ${sourceName}. The source may be corrupt, ` +
        `password-protected, or of a type this build has no import filter for.`);
    }
    const outputPath = join(workDir, produced[0]);
    return {
      filename: produced[0],
      content: await readFile(outputPath),
      rendererVersion: await detectLibreOfficeVersion(),
    };
  } finally {
    await rm(workDir, { recursive: true, force: true }).catch(() => {});
    await rm(profileDir, { recursive: true, force: true }).catch(() => {});
  }
}

// One queue, two kinds of work. Both are bounded by the same POOL_SIZE on
// purpose: the bound exists to stop the box swapping, and a machine does not
// care whether the resident memory belongs to a LibreOffice process or to a
// render thread's heap. Sizing them separately would mean the two limits are
// only correct when exactly one kind of job is arriving.
const RUNNERS = {
  office: (request) => convert(request),
  mdv: (request) => mdv.render(request),
  // Formatting is cheap next to a render, but it goes through the same queue
  // anyway: it loads the same module graph on the same kind of thread, and a
  // save storm during a busy export is exactly when an unbounded second path
  // would bite.
  'mdv-fmt': (request) => mdv.format(request),
};

function pump() {
  while (active < POOL_SIZE && queue.length > 0) {
    const job = queue.shift();
    active += 1;
    job.state = 'running';
    RUNNERS[job.kind](job.request)
      .then((result) => {
        job.state = 'done';
        job.result = result;
      })
      .catch((error) => {
        job.state = 'failed';
        // The message reaches a user, so it says what to do rather than naming
        // an internal cause.
        job.error = String(error.message ?? error);
        // A refusal carries a code and both languages; a bug carries neither,
        // and the difference is what tells the API whether to show the text to
        // the person waiting or to page somebody.
        if (error instanceof MdvRefusal) job.problem = error.problem;
      })
      .finally(() => {
        job.finishedAt = Date.now();
        active -= 1;
        pump();
      });
  }
}

function enqueue(request, kind = 'office') {
  const job = { id: randomUUID(), kind, state: 'queued', request, queuedAt: Date.now() };
  jobs.set(job.id, job);
  queue.push(job);
  pump();
  return job;
}

function settled(job) {
  return job.state === 'done' || job.state === 'failed';
}

/** Waits up to timeoutMs for a job, so small documents can convert inline. */
function waitFor(job, timeoutMs) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const poll = () => {
      if (settled(job) || Date.now() >= deadline) return resolve(job);
      setTimeout(poll, 25);
    };
    poll();
  });
}

setInterval(() => {
  const cutoff = Date.now() - RESULT_TTL_MS;
  for (const [id, job] of jobs) {
    if (settled(job) && job.finishedAt < cutoff) jobs.delete(id);
  }
}, 60_000).unref();

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

/**
 * What a finished mdv job looks like on the wire. Everything an approval needs
 * to be answerable later - which mdv drew this, under which config - travels
 * with the bytes rather than being fetched separately and possibly not at all.
 */
function renderEnvelope(job) {
  return {
    jobId: job.id,
    filename: job.result.filename,
    contentBase64: job.result.content.toString('base64'),
    rendererVersion: job.result.rendererVersion,
    mdvCommit: job.result.mdvCommit,
    renderConfig: job.result.renderConfig,
    renderConfigDigest: job.result.renderConfigDigest,
    diagnostics: job.result.diagnostics,
  };
}

/** A finished `mdv fmt`: the canonical text, and whether it moved. */
function formatEnvelope(job) {
  return {
    jobId: job.id,
    text: job.result.text,
    changed: job.result.changed,
    rendererVersion: job.result.rendererVersion,
    mdvCommit: job.result.mdvCommit,
  };
}

/**
 * A refusal is not a crash and the status has to say which kind of "no" this
 * is: 503 when this worker cannot render at all, which is an operator's job to
 * fix, and 422 when the document itself cannot be rendered, which is the
 * author's. Sending 500 for either would put both in the same alert.
 */
function renderFailure(res, job) {
  const problem = job.problem;
  if (problem === undefined) return json(res, 422, { jobId: job.id, error: job.error });
  const status = problem.code === 'mdv_unavailable' ? 503 : 422;
  return json(res, status, { jobId: job.id, error: job.error, problem });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');

    if (req.method === 'GET' && url.pathname === '/health') {
      // `status: 'ok'` with `mdv.available: false` is the honest answer for a
      // worker that can convert office documents and cannot render mdv. The API
      // needs to keep sending it .docx and stop sending it .mdv, which it cannot
      // decide from a bare 503.
      const mdvStatus = await mdv.probe();
      return json(res, 200, {
        status: 'ok',
        rendererVersion: await detectLibreOfficeVersion(),
        poolSize: POOL_SIZE,
        active,
        queued: queue.length,
        mdv: {
          available: mdvStatus.available,
          entry: MDV_ENTRY,
          commit: MDV_COMMIT,
          ...(mdvStatus.available
            ? { rendererVersion: mdvStatus.version.line, targets: MDV_TARGETS }
            : { reason: mdvStatus.reason }),
        },
      });
    }

    if (req.method === 'POST' && url.pathname === '/convert') {
      const targetFormat = url.searchParams.get('to') ?? 'pdf';
      const sourceName = url.searchParams.get('name') ?? 'document.docx';
      // 0 means "queue it, do not wait" — the caller polls.
      const waitMs = Number(url.searchParams.get('waitMs') ?? 8000);

      const content = await readBody(req);
      if (content.length === 0) return json(res, 400, { error: 'empty request body' });

      const job = enqueue({ content, sourceName, targetFormat });
      const finished = waitMs > 0 ? await waitFor(job, waitMs) : job;

      if (finished.state === 'done') {
        res.writeHead(200, {
          'content-type': 'application/octet-stream',
          'x-coreintra-job-id': job.id,
          'x-coreintra-renderer-version': finished.result.rendererVersion,
          'x-coreintra-filename': encodeURIComponent(finished.result.filename),
        });
        return res.end(finished.result.content);
      }
      if (finished.state === 'failed') {
        return json(res, 422, { jobId: job.id, error: finished.error });
      }
      // Still working. 202 with a job id, so the API never blocks a request
      // thread on a slow conversion.
      return json(res, 202, { jobId: job.id, state: finished.state, queued: queue.length });
    }

    // An mdv render is not a file conversion and does not answer in bytes.
    // ADR 0008 makes the render config part of what an approval is taken over,
    // so the caller has to receive the config *with* the artefact or the trail
    // has a gap it cannot close later. One envelope, one round trip; the
    // content is base64 because JSON has no bytes.
    if (req.method === 'POST' && url.pathname === '/render') {
      const waitMs = Number(url.searchParams.get('waitMs') ?? 8000);

      const body = await readBody(req);
      if (body.length === 0) return json(res, 400, { error: 'empty request body' });
      let request;
      try {
        request = JSON.parse(body.toString('utf8'));
      } catch (error) {
        return json(res, 400, { error: `body is not JSON: ${String(error?.message ?? error)}` });
      }
      if (typeof request?.source !== 'string' || request.source === '') {
        return json(res, 400, { error: 'source is required and must be a string' });
      }

      const job = enqueue(request, 'mdv');
      const finished = waitMs > 0 ? await waitFor(job, waitMs) : job;

      if (finished.state === 'done') return json(res, 200, renderEnvelope(finished));
      if (finished.state === 'failed') return renderFailure(res, finished);
      return json(res, 202, { jobId: job.id, state: finished.state, queued: queue.length });
    }

    // `mdv fmt` on save (ADR 0008). Stored mdv is canonical form, so a version
    // diff shows what an author changed rather than how they spaced a table.
    // Synchronous by default: this sits on the save path in the editor, and a
    // save that returns a job id to poll is a save the editor has to invent a
    // spinner for.
    if (req.method === 'POST' && url.pathname === '/format') {
      const waitMs = Number(url.searchParams.get('waitMs') ?? 8000);

      const body = await readBody(req);
      if (body.length === 0) return json(res, 400, { error: 'empty request body' });
      let request;
      try {
        request = JSON.parse(body.toString('utf8'));
      } catch (error) {
        return json(res, 400, { error: `body is not JSON: ${String(error?.message ?? error)}` });
      }
      if (typeof request?.source !== 'string') {
        return json(res, 400, { error: 'source is required and must be a string' });
      }

      const job = enqueue(request, 'mdv-fmt');
      const finished = waitMs > 0 ? await waitFor(job, waitMs) : job;

      if (finished.state === 'done') return json(res, 200, formatEnvelope(finished));
      if (finished.state === 'failed') return renderFailure(res, finished);
      return json(res, 202, { jobId: job.id, state: finished.state, queued: queue.length });
    }

    const jobMatch = url.pathname.match(/^\/jobs\/([0-9a-f-]{36})$/);
    if (req.method === 'GET' && jobMatch) {
      const job = jobs.get(jobMatch[1]);
      if (!job) return json(res, 404, { error: 'no such job; it may have expired' });
      // Anything mdv answers in JSON, not bytes. Polling has to return the same
      // envelope the synchronous route did, or a caller that timed out once
      // gets a different shape than the one it was written against.
      if (job.kind !== 'office') {
        if (job.state === 'done') {
          return json(res, 200, job.kind === 'mdv' ? renderEnvelope(job) : formatEnvelope(job));
        }
        if (job.state === 'failed') return renderFailure(res, job);
        return json(res, 202, { jobId: job.id, state: job.state });
      }
      if (job.state === 'done') {
        res.writeHead(200, {
          'content-type': 'application/octet-stream',
          'x-coreintra-renderer-version': job.result.rendererVersion,
          'x-coreintra-filename': encodeURIComponent(job.result.filename),
        });
        return res.end(job.result.content);
      }
      if (job.state === 'failed') return json(res, 422, { jobId: job.id, error: job.error });
      return json(res, 202, { jobId: job.id, state: job.state });
    }

    return json(res, 404, { error: 'not found' });
  } catch (error) {
    return json(res, 500, { error: String(error?.message ?? error) });
  }
});

server.listen(PORT, () => {
  // The bound port, not the requested one. `PORT=0` asks the OS to choose, and
  // printing the request would log "listening on 0" - which is how a test (and
  // a developer running two workers side by side) loses track of the process.
  const bound = server.address()?.port ?? PORT;
  process.stdout.write(
    `conversion worker listening on ${bound}, pool=${POOL_SIZE}\n`);
});
