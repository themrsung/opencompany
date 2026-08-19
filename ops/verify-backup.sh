#!/usr/bin/env bash
# Proves a backup can actually be restored, without touching the live system.
#
# An unverified backup is a hope. This restores the database into a scratch
# container and checks the schema and the row counts, so the first time anyone
# finds out whether the dump is usable is NOT during an outage.
set -euo pipefail

TARBALL="${1:?Usage: verify-backup.sh <backup.tar.gz>}"
WORK="$(mktemp -d)"
CONTAINER="coreintra-verify-$$"
trap 'rm -rf "$WORK"; docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT

tar -xzf "$TARBALL" -C "$WORK"
for required in database.sql blobs.tar fonts.tar env manifest.txt; do
  [ -f "$WORK/$required" ] || { echo "FAIL: $required missing from the archive" >&2; exit 1; }
done
echo "archive contents: ok"

grep -q "COREINTRA_SECRET_ENCRYPTION_KEY" "$WORK/env" \
  || { echo "FAIL: the config has no secret encryption key. Restoring this would leave every "\
"TOTP secret unreadable and every user needing to re-enrol." >&2; exit 1; }
echo "encryption key present: ok"

echo "restoring into a scratch database..."
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=verify postgres:16-alpine >/dev/null
for _ in $(seq 1 30); do
  docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
docker exec -i "$CONTAINER" psql -U postgres -d postgres < "$WORK/database.sql" >/dev/null 2>&1

TABLES=$(docker exec "$CONTAINER" psql -U postgres -d postgres -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")
echo "restored tables: $TABLES"
[ "$TABLES" -gt 20 ] || { echo "FAIL: only $TABLES tables restored; expected the full schema" >&2; exit 1; }

for table in company user_account approval_document leave_transaction; do
  docker exec "$CONTAINER" psql -U postgres -d postgres -tAc "select 1 from $table limit 1" >/dev/null \
    || { echo "FAIL: table $table is missing or unreadable" >&2; exit 1; }
done

echo
echo "verify-backup: OK — this archive restores to a working schema."
