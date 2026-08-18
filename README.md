# CoreIntra

A self-hosted B2B intranet for Korean SMEs and their overseas subsidiaries.
결재, 근태, 문서, 그리고 선택적 회계까지 한 대의 서버에서 운영합니다.

Runs on one box, vendor-managed or on-prem — same artifact, different `.env`.

```bash
cp .env.example .env    # set the two passwords
make up                 # postgres + api + conversion worker + web
make seed               # a demo company you can actually click around
```

---

## There are no passwords

**No password column, no reset flow, no temporary passwords.** Sign-in is a
username plus a TOTP code from an authenticator app, with one-time recovery
codes for a lost device.

This surprises people, so to be explicit about what it means:

- There is nothing to phish, nothing to reuse from another breach, and no
  password reset email — historically the most reliable account takeover path.
- Losing your phone is an **admin action**, and it is audited. For a master
  account it additionally requires 대표 approval under the company's
  representation mode. There is no self-service recovery, deliberately.
- Recovery codes are the only bearer secret in the system. They are shown once,
  stored hashed, and each works exactly once.

Email OTP is available as a *second* factor, but only if the installation has a
mail system configured. With no mail configured the option is hidden rather than
offered-and-broken.

See [ADR 0006](docs/adr/0006-auth-no-passwords.md).

---

## What is unusual about this system

**A business day is 72 hours long.** A shift ending at 03:00 is stamped `27:00`
on the business day it belongs to, and a briefing the evening before is `-02:00`
on the next one. Ordering is *date first, then offset* — so `2026-08-30T26:01`
comes before `2026-08-31T-03:22` even though the second is earlier on the wall
clock. Business time records what the organisation agrees happened; a separate
UTC timestamp records what the machine observed, and the two are never
conflated. [ADR 0002](docs/adr/0002-business-instant-time-model.md).

**Every document is a real DOCX.** Not HTML with an export button. Typed fields
bind through Word content controls (`w:sdt`), which survive a round-trip through
Word, LibreOffice and 한글 — `{{mustache}}` tokens do not. HWPX is a first-class
read/write format; legacy `.hwp` is read-only. Anything the editor cannot
represent round-trips as an opaque block rather than being silently dropped.

**Money is never a `double`.** `BigDecimal` throughout, stored raw at full
precision, rounded only at display, crossing the wire as exact decimal strings.
An ArchUnit rule fails the build if floating point appears in a money path.
[ADR 0004](docs/adr/0004-money-and-decimals.md).

**Permissions are decided on the object, not the route.** One evaluator, no
internal bypass, deny-by-default, with an explainer endpoint that shows the
exact grant chain behind any decision.
[ADR 0003](docs/adr/0003-permission-model.md).

**The backend targets Java 8** because some client sites cannot upgrade. That
decision is contained in one module and enforced by three independent gates, so
it cannot quietly stop being true.
[ADR 0001](docs/adr/0001-java-8-baseline.md).

---

## Layout

| Path | What it is |
|---|---|
| `backend/` | Maven reactor, Java 8, Spring Boot 2.7 |
| `backend/platform-compat/` | Every Java-version-sensitive shim, isolated |
| `backend/business-time/` | `BusinessInstant` — build and read this first |
| `frontend/` | pnpm workspace, React 19, TypeScript strict |
| `frontend/packages/business-time/` | The TypeScript counterpart |
| `spec/business-time-vectors.json` | Shared corpus both implementations must pass |
| `ops/` | Dockerfiles, backup/restore, the Java 8 gate check |
| `docs/adr/` | Why things are the way they are |
| `examples/` | A working client module |

## Status

This is an in-progress build. [STATUS.md](STATUS.md) has the honest
milestone-by-milestone position, including what is deliberately not there yet —
most notably the user interface and the REST/MCP API surface.

## Development

```bash
make verify        # everything CI runs
make test-backend  # ./mvnw test
make test-frontend # pnpm test
```

Requires JDK 17+ to *build* (the output is Java 8 bytecode), Node 20.11+, pnpm,
and Docker for the integration tests.

## Licence

MIT. See [LICENSE](LICENSE).

Bundled Pretendard is SIL Open Font License 1.1. Client-installed fonts are the
client's own licensing responsibility — the font manager says so before it
accepts an upload. 함초롬바탕/함초롬돋움 are Hancom-licensed and are never bundled.
