# 5. Two extension paths, with the risk stated

- Status: accepted
- Date: 2026-08-18

## Problem

Clients will extend the system. An in-process plugin that can reach core tables
directly turns every client's customisation into our support burden and our
data-integrity problem.

## Decision

Two supported paths, in preference order:

1. **External service (default, recommended).** Talks to the REST/MCP API with a
   scoped service account. Fully isolated; a crash or a bug cannot take the
   intranet down or corrupt core data.
2. **In-process plugin.** A jar implementing `intranet-module-api`, discovered
   by `ServiceLoader` from `modules/`, declaring a manifest (id, version,
   required permissions, DB schema namespace, UI entry point).

In-process modules get their **own DB schema namespace** and may not touch core
tables directly — only through the SPI. Migrations are namespaced. UI extension
is a manifest-declared remote entry mounted into reserved slots.

Installing an in-process module is master-only, audited, and shows a warning
stating plainly that the module runs with the granted permissions *inside the
application process*, is unsupported, and voids support guarantees. Modules can
be disabled without uninstalling.

## Consequences

- The SPI is a compatibility commitment. It is versioned separately and lives in
  its own Maven module with no dependency on core internals.
- Module permission grants go through the same `PermissionEvaluator` as
  everything else (ADR 3). A module cannot grant itself anything.
- An in-process module runs on Java 8 like the rest of the backend, so the SPI
  must not expose Java 9+ types in its signatures.
