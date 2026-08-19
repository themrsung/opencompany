# 9. The API surface, and MCP as a second client of it

- Status: accepted
- Date: 2026-08-18

## Problem

§10 asks for something stronger than "expose some endpoints": every capability
is an API endpoint, no UI-only functionality, and access is governed purely by
the account's permissions so that anything a person can do in the browser they
can do over the API and vice versa. On top of that sits an MCP server offering
the same capabilities as tools.

The failure mode to design against is the ordinary one: a UI that grows a
private endpoint "just for the inbox badge", an MCP tool that reaches a service
directly because routing it through the evaluator was inconvenient, and a
generated client that quietly stops matching the server. Each is small on the
day it happens and unrecoverable a year later.

## Decision

### One surface, three doors

REST, MCP and the (future) webhook replay path are **clients of the same
services**. A controller and an MCP tool call the identical service method with
the identical `PermissionPrincipal`, and neither may touch a repository — an
ArchUnit rule already fails the build on that. There is no endpoint the UI can
reach that a scoped API key cannot, and no tool that skips a check.

### Conventions, fixed once

- Resource-oriented REST under `/api/v1`, OpenAPI 3.1 generated from the code.
- **Cursor pagination only.** Offset pagination skips and duplicates rows while
  someone else is writing, which on an approval inbox means a document that is
  never seen. The cursor is opaque and encodes the sort key.
- **`ETag` / `If-Match` on mutations** of anything versioned. A stale editor
  gets a 412 naming the conflict rather than overwriting a colleague.
- **Idempotency keys on POSTs that create money or approvals.** A dropped
  response must not be able to double-post. A replay with the same key and a
  different body is rejected loudly rather than served the old response, because
  it means the client is confused about which operation it is retrying.
- **RFC 7807 problem+json**, machine-readable `code`, and **every validation
  failure at once**. Returning them one at a time turns a form into a
  conversation.

### Opaque access tokens, not JWTs

V3 shipped the rotating refresh token and the revocable registry but never
minted the access token that `ACCESS_TOKEN_LIFETIME` implied. V10 adds it, as an
**opaque token stored hashed on the session row**, wire form
`<sessionId>.<secret>` so lookup is a primary-key hit.

A signed self-validating token was rejected. The single most important property
of this system's session design is that revocation takes effect on the *next*
request: §8 promises a **Revoke now** button on a live vendor support session
that every user in the company can see and any master can press. A JWT would
keep working until it expired, which would make that button a lie. On one box,
checking the registry costs a primary-key lookup and one hash comparison —
cheaper than verifying a signature, and honest.

Both halves rotate together on refresh. Leaving the old access token live after
a refresh would hand an attacker who captured it a second window.

The client refreshes **single-flight**: a screen with six parallel queries will
see six 401s at once, and six racing rotations would trip the reuse detection
and sign the user out — the exact bug rotation exists to prevent.

### The generated client is the contract

`docs/api/openapi.json` is committed and is the contract; a pull request that
changes the wire shape shows it. `packages/api-client/src/schema.d.ts` is
generated from that file, carries a do-not-edit banner, and is also committed.
`ops/check-api-client-drift.sh` regenerates both in CI and fails on any diff.

The transport around those types is hand-written and small, because it is
behaviour rather than schema: single-flight refresh, idempotency keys, `If-Match`
plumbing, and problem+json parsing. Generating that would mean generating
judgement.

### MCP

The MCP server ships in-tree and authenticates with a scoped per-user or
per-service-account token evaluated by the same `PermissionEvaluator`. Read
tools and write tools are separated in the listing, every tool description
states the permission it requires and whether it writes, and **write tools
require explicit opt-in on the token** — a read-scoped token does not see them
at all, rather than seeing them and failing.

## Consequences

- A capability that is awkward to express as an endpoint is still an endpoint.
  That cost is accepted deliberately; the alternative is a UI-privileged path.
- The committed spec means a backend change touching the wire shape cannot merge
  without the regenerated client in the same commit. That is friction on
  purpose.
- Opaque tokens mean the API cannot be validated by a stateless gateway. This is
  a single-box product; there is no gateway, and if one is ever introduced it
  gets a real integration rather than an inherited shortcut.
- Cursor pagination makes "jump to page 40" impossible. Nothing in the product
  asks for it, and the inbox and who's-in views are both recency-ordered.
