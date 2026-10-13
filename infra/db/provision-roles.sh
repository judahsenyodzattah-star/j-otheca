#!/usr/bin/env bash
# Phase A tenant-isolation: provision the least-privilege RUNTIME roles (never the app's schema owner).
#
# Secrets come ONLY from the environment (infra/.env / secret manager). NOTHING is hardcoded here and
# no password is stored in source control. Idempotent — safe to re-run.
#
# Roles (three; least privilege — NO cross-tenant write role):
#   jotheca_migrate  - Flyway/DDL principal (LOGIN, non-superuser). On a fresh DB set POSTGRES_USER to
#                      this role so it owns the schema; on an existing DB ops transfers object ownership
#                      to it (REASSIGN OWNED) as a one-time step, or keeps Flyway on the existing owner.
#   jotheca_app      - tenant-plane role: NOSUPERUSER + NOBYPASSRLS + non-owner => RLS-enforced. Serves ALL
#                      tenant traffic AND platform-plane WRITES (confined to the single armed target library).
#   jotheca_readall  - platform-plane READS: BYPASSRLS (reads across tenants for GET /api/v1/platform/**),
#                      SELECT-only, access audited.
#
# On a fresh docker-compose stack this file is mounted into postgres /docker-entrypoint-initdb.d/ and
# runs automatically on first init. On an EXISTING database, run it once manually before enabling the
# restricted-role cutover:
#   JOTHECA_MIGRATE_PW=... JOTHECA_APP_PW=... JOTHECA_READALL_PW=... \
#   PGHOST=... PGUSER=jotheca PGDATABASE=jotheca bash infra/db/provision-roles.sh
set -euo pipefail

: "${JOTHECA_MIGRATE_PW:?set JOTHECA_MIGRATE_PW (env / secret manager)}"
: "${JOTHECA_APP_PW:?set JOTHECA_APP_PW (env / secret manager)}"
: "${JOTHECA_READALL_PW:?set JOTHECA_READALL_PW (env / secret manager)}"
: "${PGUSER:=jotheca}"
: "${PGDATABASE:=jotheca}"

psql -v ON_ERROR_STOP=1 \
     -v migrate_pw="$JOTHECA_MIGRATE_PW" \
     -v app_pw="$JOTHECA_APP_PW" \
     -v readall_pw="$JOTHECA_READALL_PW" <<-'EOSQL'
  DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_migrate') THEN
      CREATE ROLE jotheca_migrate LOGIN;
    END IF;
  END $$;
  ALTER ROLE jotheca_migrate WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'migrate_pw';

  DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_app') THEN
      CREATE ROLE jotheca_app LOGIN;
    END IF;
  END $$;
  ALTER ROLE jotheca_app WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'app_pw';

  DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jotheca_readall') THEN
      CREATE ROLE jotheca_readall LOGIN;
    END IF;
  END $$;
  ALTER ROLE jotheca_readall WITH LOGIN NOSUPERUSER BYPASSRLS PASSWORD :'readall_pw';

  -- CONNECT is granted to PUBLIC by default; make it explicit for the runtime roles.
  DO $$ BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO jotheca_migrate, jotheca_app, jotheca_readall',
                   current_database());
  END $$;
EOSQL

echo "provision-roles: jotheca_migrate / jotheca_app / jotheca_readall ready (passwords from env)."
