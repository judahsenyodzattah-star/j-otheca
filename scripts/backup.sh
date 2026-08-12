#!/usr/bin/env bash
# Nightly full backup: Postgres (custom format) + object storage blob dir.
#   DATABASE_URL=postgres://user:pass@host:5432/libranet \
#   MINIO_DATA_DIR=/data/minio \
#   BACKUP_DIR=/backups \
#   scripts/backup.sh
# Retention is operator policy (see docs/ops/BACKUP-AND-RESTORE.md — keep 30 nightly, 12 monthly).
set -euo pipefail
DATABASE_URL="${DATABASE_URL:?set postgres://user:pass@host:5432/libranet}"
MINIO_DATA_DIR="${MINIO_DATA_DIR:?set the MinIO/S3 data directory}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
STAMP=$(date -u +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

DB_OUT="$BACKUP_DIR/libranet-db-$STAMP.dump"
BLOB_OUT="$BACKUP_DIR/libranet-blobs-$STAMP.tar.gz"

pg_dump --format=custom --no-owner --dbname="$DATABASE_URL" --file="$DB_OUT"

# media_objects rows reference blob keys under t/{tenantId}/... — both pieces MUST be from the
# same window, so blobs are captured immediately after the DB dump.
tar -czf "$BLOB_OUT" -C "$MINIO_DATA_DIR" .

# integrity: the custom-format dump must list its own table of contents
pg_restore --list "$DB_OUT" > /dev/null
echo "backup OK:"
echo "  $DB_OUT ($(du -h "$DB_OUT" | cut -f1))"
echo "  $BLOB_OUT ($(du -h "$BLOB_OUT" | cut -f1))"
