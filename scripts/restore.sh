#!/usr/bin/env bash
# Restore drill / recovery: rebuild the DB from a backup dump into a TARGET database that will
# be created fresh. Never runs against the live database name unless explicitly pointed there.
#   DATABASE_URL=postgres://user:pass@host:5432/postgres \
#   TARGET_DB=libranet_restore \
#   DUMP=backups/libranet-db-20260811-030000.dump \
#   MINIO_DATA_DIR=/data/minio-restore BLOBS=backups/libranet-blobs-20260811-030000.tar.gz \
#   scripts/restore.sh
set -euo pipefail
DATABASE_URL="${DATABASE_URL:?admin DSN, e.g. postgres://user:pass@host:5432/postgres}"
TARGET_DB="${TARGET_DB:?target database name to CREATE}"
DUMP="${DUMP:?custom-format pg_dump file}"
BLOBS="${BLOBS:-}"
MINIO_DATA_DIR="${MINIO_DATA_DIR:-}"

[ -f "$DUMP" ] || { echo "dump not found: $DUMP"; exit 1; }

# 1. fresh database — destroy only the RESTORE TARGET, never implicitly the live one
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $TARGET_DB;"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE DATABASE $TARGET_DB;"

# Derive a DSN for the target by swapping the database component of the admin DSN.
TARGET_URL="${DATABASE_URL%/*}/$TARGET_DB"

# 2. schema + data
pg_restore --no-owner --dbname="$TARGET_URL" "$DUMP"

# 3. blobs (optional for a DB-only drill)
if [ -n "$BLOBS" ] && [ -n "$MINIO_DATA_DIR" ]; then
  mkdir -p "$MINIO_DATA_DIR"
  tar -xzf "$BLOBS" -C "$MINIO_DATA_DIR"
fi

# 4. smoke checks — a restore that can't answer these is NOT a restore
psql "$TARGET_URL" -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'libraries'    AS check, COUNT(*) FROM libraries
UNION ALL SELECT 'memberships', COUNT(*) FROM memberships
UNION ALL SELECT 'books',       COUNT(*) FROM books
UNION ALL SELECT 'loans',       COUNT(*) FROM loans
UNION ALL SELECT 'payments',    COUNT(*) FROM payments
UNION ALL SELECT 'audit_logs',  COUNT(*) FROM audit_logs
ORDER BY 1;
SELECT uid, name, status FROM libraries ORDER BY created_at LIMIT 5;
-- referential spot check: every loan's membership must exist
SELECT 'orphan_loans' AS check, COUNT(*) FROM loans l
LEFT JOIN memberships m ON m.id = l.membership_id WHERE m.id IS NULL;
SQL
echo "restore OK into '$TARGET_DB' — run the app against it for a full acceptance pass."
