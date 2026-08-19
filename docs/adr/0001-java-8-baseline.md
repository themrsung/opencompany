# 1. Java 8 baseline, isolated behind platform-compat

- Status: accepted
- Date: 2026-08-18

## Problem

Some client sites run ancient JREs and cannot be upgraded on our schedule. The
backend must run on Java 8. Java 8 is also years past public updates, so the
decision has to be contained rather than spread through the codebase, and it
must be *enforced* — a Java 9+ API that compiles here fails at runtime on the
client's box, which is the worst possible place to find out.

## Decision

Language level 8 via `--release 8` (not `source`/`target`, which check only
syntax and let Java 9+ APIs through). Spring Boot 2.7.18 — the last line
supporting Java 8 — with `javax.*`, never `jakarta.*`.

Two independent gates, because one silently-disabled gate is a runtime failure:

1. `maven-compiler-plugin` `<release>8</release>` — javac itself refuses Java 9+
   APIs against the historical platform signature.
2. `animal-sniffer-maven-plugin` against `org.codehaus.mojo.signature:java18` —
   catches anything that reaches bytecode through a shaded or relocated
   dependency, which `--release` cannot see.

A third check, `ops/verify-java8-gate.sh`, runs in CI and asserts the gate by
compiling a deliberate `List.of` call and requiring the build to fail. This
catches the case where someone "fixes" a build error by loosening `<release>`.

All Java-version-sensitive code lives in `platform-compat`: `Immutables`
(`List.of`/`Map.of`/`Set.of`), `Streams` (`Stream.toList`), `Optionals`
(`Optional.or`/`Optional.stream`), `Texts` (`isBlank`/`strip`/`repeat`).

## Consequences

- Bumping to 17 or 21 later means: change `<release>`, change the Boot version,
  rewrite `javax.*` to `jakarta.*`, and reduce `platform-compat` to delegations.
  Nothing else conceptually.
- We inherit Boot 2.7's EOL CVE surface. Mitigated by OWASP dependency-check in
  CI and `docs/security/dependency-posture.md`, and by keeping the dependency
  surface deliberately small.
- Flyway is pinned at 8.5.13. Flyway 13.x is compiled for Java 17 (class major
  61) and cannot even load on a Java 8 JRE. Verified by reading the class file
  header, not the release notes.
- `Immutables` deliberately guarantees insertion-order iteration, which the JDK
  factories do not. Approval lines and the chart of accounts depend on it, so
  the future delegation to `List.of` must preserve that or it is a behaviour
  change, not a cleanup.
