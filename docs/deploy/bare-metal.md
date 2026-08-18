# Bare-metal deployment

The secondary path. `docker compose up` is the primary one and is what most
installations should use; this exists for sites whose policy forbids containers
or whose hardware predates them.

Same artifact, different plumbing. The application does not know which it is.

## What you need

| | Version | Notes |
|---|---|---|
| JRE | **8 or newer** | The JAR is Java 8 bytecode (ADR 0001). A newer JRE is fine. |
| PostgreSQL | 16 | 15 works; the schema uses generated columns and `text_pattern_ops`, both long-standing. |
| Node | ≥ 20.11 | Conversion worker only. |
| LibreOffice | pinned, see below | **Install `libreoffice-writer`, not just `libreoffice-core`.** |
| nginx | any current | Serves the SPA and proxies `/api`. |

### The LibreOffice trap

`libreoffice-core` alone installs a `soffice` that reports a version happily and
then refuses every document with *"no export filter found"*. It looks like a
configuration problem and is a missing package. Install `libreoffice-writer`.

**Pin the version and record it.** It goes into the render metadata of every PDF
produced, and the reproducibility contract depends on it not drifting under you
at the next unattended upgrade:

```bash
apt-mark hold libreoffice-writer libreoffice-core
```

## Layout

```
/opt/coreintra/app.jar                  the application
/opt/coreintra/conversion-worker/       the Node worker
/etc/coreintra/coreintra.env            configuration (mode 0600)
/var/lib/coreintra/blobs/               documents — back this up
/var/lib/coreintra/fonts/               client fonts — back this up
```

`/etc/coreintra/coreintra.env` holds `COREINTRA_SECRET_ENCRYPTION_KEY`. Mode
0600, owned by the service user. Losing it means every user re-enrols their
authenticator; leaking it means the TOTP secrets in a database dump become
usable.

## systemd units

`/etc/systemd/system/coreintra-api.service`:

```ini
[Unit]
Description=CoreIntra API
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=coreintra
Group=coreintra
EnvironmentFile=/etc/coreintra/coreintra.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=70 -jar /opt/coreintra/app.jar
Restart=on-failure
RestartSec=10

# The API opens user-uploaded documents. Nothing here is exotic; all of it is
# free, and it narrows what a parser bug can reach.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/coreintra
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/coreintra-conversion.service`:

```ini
[Unit]
Description=CoreIntra conversion worker
After=network.target

[Service]
Type=simple
User=coreintra
Group=coreintra
EnvironmentFile=/etc/coreintra/coreintra.env
WorkingDirectory=/opt/coreintra/conversion-worker
ExecStart=/usr/bin/node server.mjs
Restart=on-failure
RestartSec=5

# Restartable on its own. A wedged worker degrades exports; it must never
# require restarting the intranet to clear:
#   systemctl restart coreintra-conversion
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/lib/coreintra
# LibreOffice needs a writable HOME for its profile.
Environment=HOME=/var/lib/coreintra/lo-home

[Install]
WantedBy=multi-user.target
```

## nginx

Use `ops/nginx/coreintra.conf` as-is; only `proxy_pass` changes
(`http://127.0.0.1:8080`). Keep the CSP header exactly as it is — mdv renders
under `default-src 'none'` with no `unsafe-inline` and no `unsafe-eval`, and
that line is worth holding.

## Fonts

Install into `/var/lib/coreintra/fonts` and run `fc-cache -f`. The application
serves the same files to the browser as webfonts and registers them into mdv's
`pdf.fonts`: one record, three consumers. If they disagree, WYSIWYG is broken —
see `docs/documents/fonts.md`, particularly the part about fontconfig
substituting silently.

## Backup

`ops/backup.sh` and `ops/restore.sh` shell out to `docker compose`. On
bare metal, run `pg_dump` directly and tar the two data directories plus the env
file — the four pieces are only meaningful together. `ops/verify-backup.sh`
still applies and is still worth running: an unverified backup is a hope.
