#!/usr/bin/env bash
set -Eeuo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=database_name="$POSTGRES_DB" \
  --set=migration_password="$DOKENE_DB_MIGRATION_PASSWORD" \
  --set=runtime_password="$DOKENE_DB_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE dokene_migration
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'migration_password';

CREATE ROLE dokene_runtime
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'runtime_password';

REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"database_name" TO dokene_migration;
GRANT CONNECT ON DATABASE :"database_name" TO dokene_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
