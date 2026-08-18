# 3. Deny-by-default permissions with a single evaluator

- Status: accepted
- Date: 2026-08-18

## Problem

Permission logic that lives in controllers drifts. Every new surface — REST, MCP,
a scheduled job, an in-process client module — grows its own slightly different
check, and the differences are invisible until one of them is wrong.

## Decision

Deny by default. A permission is `resource:action` with a scope of
`SELF | ORG_UNIT | ORG_UNIT_SUBTREE | COMPANY | ALL`.

Grants attach to a **Rank**, a **JobFunction**, an **OrgUnit**, or directly to a
**UserAccount**. The effective set is the union of grants minus explicit denies;
an explicit deny always wins, and cannot be out-voted by any number of grants.

`PermissionEvaluator.check(principal, resource, action, targetContext)` is the
single gate. REST, MCP, module service accounts and scheduled jobs all route
through it. There is no "internal" bypass, no `SYSTEM` principal that skips the
check, and an ArchUnit rule fails the build if a repository or controller is
reached without it.

Every decision is made on the **domain object**, not the URL — row-level, not
route-level. Positions are history-preserving, so a check against a past-dated
document resolves the org state *as of that document's business date*.

## Consequences

- An effective-permissions explainer endpoint is mandatory, not a nice-to-have:
  with grants arriving from four sources and denies overriding, "why can this
  user do this?" is not answerable by reading the database by hand. It returns
  the exact grant chain.
- As-of resolution means permission checks need a business date in context.
  Checks without one resolve against today, and that default is explicit at the
  call site rather than implied.
- Scope evaluation needs the org subtree. The tree is materialised-path indexed
  so `ORG_UNIT_SUBTREE` does not become an N+1.
