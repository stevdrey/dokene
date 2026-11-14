#!/usr/bin/env bash
set -Eeuo pipefail

# The role passwords never go on psql's command line, which any local user can read in the process table (issue 327):
# psql reads them from its own environment with \getenv (psql 15 or newer) and quotes them as literals with :'name'.
# Only the database name, which is not secret, is passed as a --set variable.
: "${DOKENE_DB_MIGRATION_PASSWORD:?DOKENE_DB_MIGRATION_PASSWORD must be set}"
: "${DOKENE_DB_RUNTIME_PASSWORD:?DOKENE_DB_RUNTIME_PASSWORD must be set}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=database_name="$POSTGRES_DB" <<'SQL'
\getenv migration_password DOKENE_DB_MIGRATION_PASSWORD
\getenv runtime_password DOKENE_DB_RUNTIME_PASSWORD

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
