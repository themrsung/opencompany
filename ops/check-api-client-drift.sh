#!/usr/bin/env bash
#
# Fails when the generated API client no longer matches the OpenAPI document.
#
# §1: API types are generated from the backend spec and never hand-written, and
# drift fails the build. Two things can drift, and this checks both:
#
#   1. docs/api/openapi.json against what the running application actually
#      serves — caught by the backend's OpenApiSpecTest, which rewrites the file;
#   2. packages/api-client/src/schema.d.ts against that document — caught here.
#
# Both are committed on purpose. A contract you cannot read in a pull request is
# a contract nobody reviews.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f docs/api/openapi.json ]]; then
  echo "docs/api/openapi.json is missing. Run the backend build to produce it." >&2
  exit 1
fi

pnpm --dir frontend --filter @coreintra/api-client generate

if ! git diff --quiet -- docs/api/openapi.json frontend/packages/api-client/src/schema.d.ts; then
  echo >&2
  echo "The generated API client is out of date." >&2
  echo >&2
  git --no-pager diff --stat -- docs/api/openapi.json frontend/packages/api-client/src/schema.d.ts >&2
  echo >&2
  echo "Regenerate and commit:" >&2
  echo "  cd backend && ./mvnw -B -q -pl app -am test -Dtest=OpenApiSpecTest" >&2
  echo "  pnpm --dir frontend --filter @coreintra/api-client generate" >&2
  exit 1
fi

echo "API client is in step with the spec."
