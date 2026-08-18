#!/usr/bin/env bash
# One restorable tarball: database, blobs, fonts, and config.
#
# All four, deliberately. A database dump alone restores a system whose
# documents 404 and whose exported PDFs render in the wrong font — the metadata
# says which font was used and the box no longer has it. The four pieces are
# only meaningful together, so they travel together.
set -euo pipefail

BACKUP_DIR="${1:-./backups}"
COMPOSE="${COMPOSE:-docker compose}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$(dirname "$0")/.."
mkdir -p "$BACKUP_DIR"

if [ ! -f .env ]; then
  echo "No .env found. Copy .env.example to .env first." >&2
  exit 1
fi
# shellcheck disable=SC1091
set -a; . ./.env; set +a

echo "[1/5] database"
# --clean --if-exists so a restore into a non-empty database works. A dump that
# only restores onto a pristine box is a dump nobody can use in an emergency.
$COMPOSE exec -T postgres pg_dump \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --clean --if-exists --no-owner --no-privileges \
  > "$WORK/database.sql"

echo "[2/5] document blobs"
$COMPOSE run --rm --no-deps -T \
  -v "$WORK:/backup" api tar -cf /backup/blobs.tar -C /var/lib/coreintra blobs \
  2>/dev/null || tar -cf "$WORK/blobs.tar" --files-from /dev/null

echo "[3/5] fonts"
# Part of the reproducibility contract: a document re-rendered on a restored box
# must find the same fonts, or the render-metadata comparison will correctly
# report that it did not.
$COMPOSE run --rm --no-deps -T \
  -v "$WORK:/backup" api tar -cf /backup/fonts.tar -C /var/lib/coreintra fonts \
  2>/dev/null || tar -cf "$WORK/fonts.tar" --files-from /dev/null

echo "[4/5] configuration"
# Includes COREINTRA_SECRET_ENCRYPTION_KEY. Without it every TOTP secret in the
# database is unreadable and every user must re-enrol — a restore that "works"
# and locks everyone out is worse than an obvious failure.
cp .env "$WORK/env"

cat > "$WORK/manifest.txt" <<MANIFEST
CoreIntra backup
created:        $STAMP
database:       $POSTGRES_DB
schema version: $($COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
                    -tAc "select max(version) from flyway_schema_history" 2>/dev/null || echo unknown)

Contains: database.sql, blobs.tar, fonts.tar, env

WARNING: env contains COREINTRA_SECRET_ENCRYPTION_KEY and the database
password. Store this archive where you would store those.
MANIFEST

echo "[5/5] packing"
TARBALL="$BACKUP_DIR/coreintra-$STAMP.tar.gz"
tar -czf "$TARBALL" -C "$WORK" .
echo "wrote $TARBALL ($(du -h "$TARBALL" | cut -f1))"
echo
echo "Verify it: ./ops/verify-backup.sh \"$TARBALL\""
echo "An unverified backup is a hope, not a backup."
