# Dependency posture

The backend runs Spring Boot 2.7, which is past OSS end-of-life. That is a
deliberate consequence of the Java 8 baseline (ADR 0001), and it is a real cost
rather than a technicality. This document is what keeps it honest.

## Why we are here

Some client sites run JREs that cannot be upgraded on our schedule. Boot 2.7 is
the last line supporting Java 8. The alternative — refusing those clients, or
shipping bytecode their JRE cannot load — is worse than the CVE exposure,
provided the exposure is *managed* rather than ignored.

## What manages it

1. **The dependency surface is deliberately small.** Every dependency added is
   one more thing to track against advisories for a framework nobody upstream is
   patching. Where the JDK can do the job, it does:

   | Would normally be a dependency | What we do instead |
   |---|---|
   | TOTP library | `javax.crypto` HMAC-SHA1, ~120 lines, RFC 6238 vectors as tests |
   | base32 codec | 40 lines |
   | password hashing | there are no passwords (ADR 0006) |
   | JSON in the domain core | none — `business-time` and the accounting engine depend on nothing |

   Each of those is in the authentication or money path. A transitive CVE there
   is worth more than the code it saved.

2. **OWASP dependency-check runs in CI** (`dependency-posture` job) and uploads
   its report. It is `continue-on-error` deliberately: a job that fails the build
   on any CVE in an EOL framework fails permanently, gets marked
   `allow_failure`, and then nobody reads it. A report that is read is worth
   more than a gate that is bypassed.

3. **Versions are verified against the registry, not remembered.** Every
   dependency was checked at scaffold time. Two of those checks changed
   decisions:

   - `docx4j` has **no Java 8 line at all** — class major 55 even at 11.5.5 —
     which settled the §6.1 engine choice as Apache POI (ADR 0007).
   - `flyway-core` 13.x is compiled for Java 17 (class major 61) and cannot
     load on a Java 8 JRE. Pinned to 8.5.13.

   The lesson generalises: **check the class file version, not the release
   notes.** A library that documents Java 8 support and ships major-55 bytecode
   fails at a client site, not in CI.

## Known exposure

Boot 2.7.18 receives no OSS security patches. Commercial support (VMware
Tanzu / Broadcom) does exist and is the option if a client's risk posture
requires patched framework CVEs; it is a purchasing decision, not an
engineering one, and should be raised with any client whose security review
asks about it.

Mitigating factors specific to this deployment:

- **Single box, no public exposure by default.** The database publishes no port,
  and the API is reached through nginx.
- **No password authentication**, so the framework's authentication surface is
  not the account-takeover path it usually is.
- **Untrusted input is narrow and hardened.** Documents are the main untrusted
  input: XML parsing disables DTDs and external entities, and the conversion
  worker runs as a non-root user in a separate container with a bounded pool.

## When the baseline moves

ADR 0001 is written so this is cheap: change `<release>`, change the Boot
version, rewrite `javax.*` to `jakarta.*`, and reduce `platform-compat` to
delegations. `ops/verify-java8-gate.sh` should be deleted in the same change —
a gate that no longer gates anything is worse than no gate, because it implies
a check that is not happening.
