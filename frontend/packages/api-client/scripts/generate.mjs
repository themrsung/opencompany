#!/usr/bin/env node
/**
 * Regenerates the API types from the OpenAPI document the backend produces.
 *
 * The spec is committed at `docs/api/openapi.json` and is the contract. CI runs
 * this and fails on any diff, so a backend change that alters the wire shape
 * cannot merge with a frontend that still believes the old one. §1 requires the
 * types be generated and never hand-written; this is the only path that writes
 * `src/schema.d.ts`, and that file carries a do-not-edit banner.
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../../../..');
const specPath = resolve(repoRoot, 'docs/api/openapi.json');
const outputPath = resolve(here, '../src/schema.d.ts');

const BANNER = `/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * Produced from docs/api/openapi.json by \`pnpm --filter @coreintra/api-client generate\`.
 * Edit the backend controllers and regenerate; CI fails on drift.
 */
`;

let spec;
try {
  spec = readFileSync(specPath, 'utf8');
} catch {
  console.error(
    `No OpenAPI document at ${specPath}.\n` +
      'Produce one first:  cd backend && ./mvnw -B -q -pl app -am test -Dtest=OpenApiSpecTest',
  );
  process.exit(1);
}

if (spec.trim() === '') {
  console.error(`${specPath} is empty.`);
  process.exit(1);
}

const generated = execFileSync(
  process.execPath,
  [resolve(repoRoot, 'frontend/node_modules/openapi-typescript/bin/cli.js'), specPath],
  { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
);

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, BANNER + generated, 'utf8');
console.log(`Wrote ${outputPath}`);
