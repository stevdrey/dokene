# Docker infrastructure

Container definitions will live here as the application becomes deployable.

Security baseline for runtime images:

- non-root user,
- minimal base image,
- read-only filesystem where practical,
- no embedded secrets,
- dropped Linux capabilities unless explicitly required,
- separate build and runtime stages.

Local PostgreSQL is currently defined in the root `compose.yaml`.

## Local database roles and clean startup

Copy `.env.example` from the repository root to `.env`, fill every blank value with local-only values, and generate distinct bootstrap, migration, and runtime passwords. `DOKENE_DB_PASSWORD` and `DOKENE_DB_RUNTIME_PASSWORD` must contain the same local runtime password. Do not commit `.env`.

The PostgreSQL initialization script creates two non-superuser roles:

- `dokene_migration` owns the `dokene` schema and can run Flyway DDL.
- `dokene_runtime` has only `USAGE` plus required table DML privileges. It owns no application tables and has `NOBYPASSRLS`.

For a clean local verification, remove only the disposable Compose volume, start PostgreSQL, then launch the backend with the variables from `.env` exported:

```bash
docker compose down --volumes --remove-orphans
docker compose up --wait postgres
set -a; . ./.env; set +a
(cd backend && ./gradlew bootRun)
```

Flyway must report applying `V1__create_tenant_foundation.sql`; Hibernate then validates the resulting schema. Stop `bootRun` after startup. Future migrations use `V<version>__<snake_case_description>.sql`, are immutable once shared, and must include deliberate ownership, grant, and RLS-policy review.
