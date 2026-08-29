# Dokene Backend

Spring Boot modular monolith.

Planned domain/module boundaries:

- `identity` — authentication-facing identity model
- `tenant` — tenant context, membership, roles, permissions
- `customer` — customer profile and consent state
- `purchase` — purchase/history signals
- `followup` — scheduling and follow-up decision workflow
- `template` — message-template lifecycle and versions
- `messaging` — message state machine and outbound ports
- `ai` — provider abstraction and structured recommendations
- `integration` — external provider adapters
- `audit` — append-only security/business audit events
- `security` — policy enforcement and cross-cutting security controls

The domain core must not depend directly on concrete AI or messaging providers.

## Database migrations

Production schema changes live in `src/main/resources/db/migration` and are applied by Flyway. Use the immutable naming convention `V<version>__<snake_case_description>.sql`; never edit a migration that has been applied outside a disposable local database. Every migration must review object ownership, explicit runtime grants, and any required Row Level Security policy changes.

The `dokene_migration` role owns the `dokene` schema and applies DDL. The application connects as `dokene_runtime`, which has only the DML grants required by the current service and does not have `BYPASSRLS`. Set the following environment variables before starting the backend:

- `DOKENE_DB_URL`, `DOKENE_DB_USERNAME`, `DOKENE_DB_PASSWORD` for runtime traffic;
- `DOKENE_DB_MIGRATION_USERNAME`, `DOKENE_DB_MIGRATION_PASSWORD` for Flyway.

Flyway does not baseline a non-empty schema, validates applied migrations, and has clean disabled. A startup failure on an unexpected schema must be investigated rather than bypassed.
