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

import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { randomUUID } from 'node:crypto';

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

const jobs = new Map();
const queue = [];
let active = 0;
let libreOfficeVersion = null;

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

function pump() {
  while (active < POOL_SIZE && queue.length > 0) {
    const job = queue.shift();
    active += 1;
    job.state = 'running';
    convert(job.request)
      .then((result) => {
        job.state = 'done';
        job.result = result;
      })
      .catch((error) => {
        job.state = 'failed';
        // The message reaches a user, so it says what to do rather than naming
        // an internal cause.
        job.error = String(error.message ?? error);
      })
      .finally(() => {
        job.finishedAt = Date.now();
        active -= 1;
        pump();
      });
  }
}

function enqueue(request) {
  const job = { id: randomUUID(), state: 'queued', request, queuedAt: Date.now() };
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
      return json(res, 200, {
        status: 'ok',
        rendererVersion: await detectLibreOfficeVersion(),
        poolSize: POOL_SIZE,
        active,
        queued: queue.length,
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

    const jobMatch = url.pathname.match(/^\/jobs\/([0-9a-f-]{36})$/);
    if (req.method === 'GET' && jobMatch) {
      const job = jobs.get(jobMatch[1]);
      if (!job) return json(res, 404, { error: 'no such job; it may have expired' });
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
  process.stdout.write(
    `conversion worker listening on ${PORT}, pool=${POOL_SIZE}\n`);
});
