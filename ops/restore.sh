#!/usr/bin/env bash
# Restore from a tarball produced by backup.sh.
set -euo pipefail

TARBALL="${1:?Usage: restore.sh <backup.tar.gz>}"
COMPOSE="${COMPOSE:-docker compose}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$(dirname "$0")/.."
tar -xzf "$TARBALL" -C "$WORK"

echo "About to restore:"
cat "$WORK/manifest.txt"
echo
echo "This REPLACES the current database, blobs and fonts."
read -r -p "Type the word 'restore' to continue: " CONFIRM
[ "$CONFIRM" = "restore" ] || { echo "aborted"; exit 1; }

# shellcheck disable=SC1091
set -a; . ./.env; set +a

echo "[1/4] stopping the application (the database stays up)"
$COMPOSE stop api conversion-worker web

echo "[2/4] database"
$COMPOSE exec -T postgres psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
  < "$WORK/database.sql"

echo "[3/4] blobs and fonts"
$COMPOSE run --rm --no-deps -T -v "$WORK:/backup" api \
  sh -c 'tar -xf /backup/blobs.tar -C /var/lib/coreintra && tar -xf /backup/fonts.tar -C /var/lib/coreintra'

echo "[4/4] starting"
$COMPOSE up -d

cat <<'NOTE'

Restore complete.

Check before declaring success:
  · sign in with an authenticator — proves COREINTRA_SECRET_ENCRYPTION_KEY in
    this .env matches the one the TOTP secrets were encrypted with
  · open an approved document and re-export it — proves the blobs restored and
    the fonts are present
  · compare the re-rendered PDF's metadata against the archived one — a
    difference here is explained, not guessed at
NOTE
