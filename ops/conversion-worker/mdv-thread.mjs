// One mdv invocation, on a worker thread.
//
// mdv's CLI is injectable — `run(argv, io)` with `io` supplying stdout, stderr,
// cwd and env — so we import it rather than spawning a process: a spawn would
// pay Node's start-up and mdv's module graph on every job, and the CLI is
// explicitly written to be called this way (`CONTRACTS.md` §3, and every
// command is tested in-process).
//
// It runs on a *thread* rather than the server's own loop because importing the
// CLI does not make the work small. Laying out and paginating a document is
// synchronous CPU, and on the main loop it would stall `/health` and every
// `/jobs/:id` poll for the duration — the API would see a wedged worker and take
// it out of rotation mid-render. LibreOffice gets this for free by being a
// subprocess; mdv needs a thread to get the same property.
//
// The thread is deliberately dumb: no policy, no filesystem layout decisions, no
// retries. It receives an argv, returns the exit code and the two streams. That
// is the whole seam, and it is why `mdv-runner.mjs` can be tested with a fake.

import { parentPort, workerData } from 'node:worker_threads';

const { entry, argv, cwd, env } = workerData;

let stdout = '';
let stderr = '';

// `CliIo` (packages/cli/src/io.ts). `isTty: false` keeps the CLI's ANSI
// decisions off; there is no terminal here and colour codes in a captured
// string would end up in an error message shown to a user.
const io = {
  stdout: { write: (chunk) => { stdout += chunk; } },
  stderr: { write: (chunk) => { stderr += chunk; } },
  cwd,
  env,
  isTty: false,
};

try {
  const cli = await import(entry);
  // `run` never throws and never calls `process.exit`: every failure becomes a
  // printed message and a code (SPEC 27). That contract is the reason a thread
  // is enough and a subprocess is not needed for isolation.
  const code = await cli.run(argv, io);
  parentPort.postMessage({ ok: true, code, stdout, stderr });
} catch (error) {
  // Getting here means the import failed or mdv threw where it promised not to.
  // Report it as this worker's fault, with whatever it managed to print.
  parentPort.postMessage({
    ok: false,
    error: String(error?.stack ?? error?.message ?? error),
    stdout,
    stderr,
  });
}
