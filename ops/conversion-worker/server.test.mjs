// The worker as a process, with mdv deliberately absent.
//
// This is the state a developer's machine is in, and - more to the point - the
// state the container is in the moment someone changes the image and the mdv
// build step silently stops producing a `dist`. The worker must come up anyway:
// LibreOffice conversion is the older half of its job and has no reason to stop
// because the newer half is broken. A worker that refuses to boot takes office
// exports down with it, which is a far larger outage than "mdv is unavailable".
//
// Everything here goes over real HTTP against a real child process. The routing,
// the queue and the JSON envelopes are the parts most likely to be broken by a
// careless edit, and they are exactly the parts an in-process import would skip.

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const SERVER = fileURLToPath(new URL('./server.mjs', import.meta.url));

/**
 * Start a worker on an OS-chosen port and wait until it says it is listening.
 *
 * `PORT=0` rather than a fixed number: two of these run side by side in a suite,
 * and a hard-coded port makes a test that fails only when someone else is
 * already using it.
 */
async function startWorker(env = {}) {
  const child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      PORT: '0',
      // Certainly absent. The default would be the real submodule path, which
      // is unbuilt here but might not be in CI - and this file is about the
      // degraded case specifically.
      COREINTRA_MDV_CLI: '/nonexistent/mdv/dist/index.js',
      ...env,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let out = '';
  const stderr = [];
  child.stderr.on('data', (chunk) => stderr.push(String(chunk)));

  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`worker did not start in 15s. stdout=${out} stderr=${stderr.join('')}`));
    }, 15_000);
    child.stdout.on('data', (chunk) => {
      out += String(chunk);
      const match = /listening on (\d+)/u.exec(out);
      if (match) {
        clearTimeout(timer);
        resolve(Number(match[1]));
      }
    });
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`worker exited with ${code} before listening. stderr=${stderr.join('')}`));
    });
  });

  return {
    port,
    stderr,
    url: (path) => `http://127.0.0.1:${port}${path}`,
    async stop() {
      child.kill('SIGKILL');
      await once(child, 'exit');
    },
  };
}

test('the worker starts and serves /health with mdv absent', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  const response = await fetch(worker.url('/health'));
  assert.equal(response.status, 200);
  const body = await response.json();

  // Still "ok": this worker can convert office documents. The API needs to keep
  // sending it .docx and stop sending it .mdv, and it cannot work that out from
  // a bare 503.
  assert.equal(body.status, 'ok');
  assert.equal(body.mdv.available, false);
  assert.match(body.mdv.reason, /not built/u);
  // The pin is reported even when the renderer is missing: "which mdv would
  // have drawn this" is still the question an operator is asking.
  assert.equal(typeof body.mdv.commit, 'string');
  assert.equal(body.mdv.entry, '/nonexistent/mdv/dist/index.js');
  assert.equal(typeof body.poolSize, 'number');
});

test('/health reports the pinned commit when the image baked one in', async (t) => {
  const worker = await startWorker({ COREINTRA_MDV_COMMIT: 'c48e33829a8ea03d98ace35cf18189de1a181f23' });
  t.after(() => worker.stop());

  const body = await fetch(worker.url('/health')).then((r) => r.json());
  assert.equal(body.mdv.commit, 'c48e33829a8ea03d98ace35cf18189de1a181f23');
});

test('the pin falls back to the file the image build wrote', async (t) => {
  // `docker compose build` passes no build args, so the everyday image gets its
  // pin from a file rather than the environment. If this fallback breaks, every
  // render in that deployment records `unknown` provenance and nobody notices
  // until an auditor asks which mdv drew a document.
  const { writeFile, mkdtemp } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const dir = await mkdtemp(join(tmpdir(), 'coreintra-pin-'));
  const pinFile = join(dir, 'PIN');
  await writeFile(pinFile, 'tree:abc123\n');

  const worker = await startWorker({
    COREINTRA_MDV_PIN_FILE: pinFile,
    COREINTRA_MDV_COMMIT: '',
  });
  t.after(() => worker.stop());

  const body = await fetch(worker.url('/health')).then((r) => r.json());
  assert.equal(body.mdv.commit, 'tree:abc123');
});

test('an unreadable pin reports unknown rather than failing to start', async (t) => {
  const worker = await startWorker({
    COREINTRA_MDV_PIN_FILE: '/nonexistent/PIN',
    COREINTRA_MDV_COMMIT: '',
  });
  t.after(() => worker.stop());

  const body = await fetch(worker.url('/health')).then((r) => r.json());
  assert.equal(body.mdv.commit, 'unknown');
});

test('a render against an absent mdv is 503, not 500 and not a crash', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  const response = await fetch(worker.url('/render'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ source: '# Report', buildTime: '2026-08-18T09:00:00.000Z' }),
  });

  // 503: an operator has to fix this, and it must not look like a bad document.
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.problem.code, 'mdv_unavailable');
  assert.ok(body.problem.ko.trim() !== '', 'a refusal a Korean approver cannot read is not a refusal');
  assert.ok(body.problem.en.trim() !== '');

  // And the process is still up afterwards.
  assert.equal((await fetch(worker.url('/health'))).status, 200);
});

test('an absent renderer outranks a bad document: 503 before 422', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  // This request is *also* invalid - no `buildTime` - so it would earn a 422 on
  // a working worker. It gets 503 instead, and the precedence is deliberate:
  // telling an author to fix their document when the renderer is missing sends
  // them off to fix something that was never wrong. Whoever can act on the
  // problem should be the one who hears about it.
  const response = await fetch(worker.url('/render'), {
    method: 'POST',
    body: JSON.stringify({ source: '# Report' }),
  });
  assert.equal(response.status, 503);
  assert.equal((await response.json()).problem.code, 'mdv_unavailable');
});

test('the queue answers with a job id when told not to wait, and the id is pollable', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  // waitMs=0 is "queue it, do not wait" - the path the API uses so a slow
  // render never occupies a request thread.
  const queued = await fetch(worker.url('/render?waitMs=0'), {
    method: 'POST',
    body: JSON.stringify({ source: '# Report', buildTime: '2026-08-18T09:00:00.000Z' }),
  });
  assert.equal(queued.status, 202);
  const { jobId, state } = await queued.json();
  assert.match(jobId, /^[0-9a-f-]{36}$/u);
  assert.ok(['queued', 'running'].includes(state), `unexpected state ${state}`);

  // Poll until it settles. The job fails (mdv is absent), but the point is that
  // the id survives the round trip and the result is collectable by it.
  let polled;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    polled = await fetch(worker.url(`/jobs/${jobId}`));
    if (polled.status !== 202) break;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.equal(polled.status, 503);
  const body = await polled.json();
  assert.equal(body.jobId, jobId);
  assert.equal(body.problem.code, 'mdv_unavailable');
});

test('an unknown job id is 404 rather than a hang', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  const response = await fetch(worker.url('/jobs/00000000-0000-0000-0000-000000000000'));
  assert.equal(response.status, 404);
});

test('a malformed render request is rejected before it reaches the queue', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  const empty = await fetch(worker.url('/render'), { method: 'POST', body: '' });
  assert.equal(empty.status, 400);

  const notJson = await fetch(worker.url('/render'), { method: 'POST', body: 'not json at all' });
  assert.equal(notJson.status, 400);
  assert.match((await notJson.json()).error, /not JSON/u);

  // A render with no document is a caller bug, and saying so beats queueing a
  // job that can only fail.
  const noSource = await fetch(worker.url('/render'), {
    method: 'POST',
    body: JSON.stringify({ buildTime: '2026-08-18T09:00:00.000Z' }),
  });
  assert.equal(noSource.status, 400);
  assert.match((await noSource.json()).error, /source is required/u);
});

test('the office conversion route is untouched by any of this', async (t) => {
  const worker = await startWorker();
  t.after(() => worker.stop());

  // No LibreOffice on a developer's machine, so this cannot assert a PDF. What
  // it can assert is that /convert still routes, still enqueues, and still
  // answers in the shape it always did - the regression that would matter most
  // and the easiest one to cause while adding a second kind of job.
  const response = await fetch(worker.url('/convert?to=pdf&name=report.docx&waitMs=2000'), {
    method: 'POST',
    body: Buffer.from('PK not really a docx'),
  });
  assert.ok([200, 202, 422].includes(response.status), `unexpected status ${response.status}`);
  if (response.status !== 200) {
    const body = await response.json();
    assert.match(body.jobId, /^[0-9a-f-]{36}$/u);
  }
});
