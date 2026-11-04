# Issue #308 migration credential separation: verification record

Status: single PR. It closes audit finding M4 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the long-running backend held the migration credential and the tenant-context signing key in its environment, and the audit log was append-only only for the runtime role. The design is in [ADR 0003, Migration job and secret delivery](../adr/0003-postgresql-rls.md#migration-job-and-secret-delivery) and [ADR 0006, Immutability and break-glass](../adr/0006-durable-append-only-audit.md#immutability-and-break-glass).

This record is sanitized: it gives outcomes, counts and test names only. It contains no credential, key or reproduction script; secret values were compared inside the containers and only match counts were recorded.

## Environment

- **Run:** 2026-11-03, branch `308-securitym4-separate-migration-credentials-from-the-runtime-service`, based on `main` at `d7c9f1e`. The code was rechecked on that base before the change: M4 still applied as reported (backend environment held `DOKENE_DB_MIGRATION_*` and `DOKENE_TENANT_CONTEXT_SIGNING_KEY`, no trigger on `audit_events`). **Tested revision:** the working tree that became the first commit of this branch on top of `d7c9f1e` (the commit that adds this record; a commit cannot name its own hash). Every check and run below used that tree; the review follow-up (narrowed secret directory, `migrate` build, broader runtime hint, secret-file safety) was re-verified on the branch head that adds it.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; Spring Boot 4.1.2; PostgreSQL 17; Docker Compose v5.6.1. The minimum supported Compose is 2.27.2: it adds `config --environment`, and its `up --wait` waits for a `service_completed_successfully` dependency to complete (checked in the upstream source at that tag; not run locally).
- **Automated tests:** Testcontainers PostgreSQL with the real `dokene_migration` and `dokene_runtime` roles.
- **Live run:** the repository `compose.yaml` in a separate Compose project (`dokene-qa308`, its own volume), local `.env` values only.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| The migration job migrates a clean database as `dokene_migration`, starts no runtime `DataSource`, and provisions the signing key | `MigrationJobSeparationIntegrationTest.migratesACleanDatabaseAndProvisionsTheSigningKey` |
| The job upgrades the previous release (V33) to the current schema and a second run is a no-op | `MigrationJobSeparationIntegrationTest.upgradesThePreviousReleaseAndIsIdempotent` |
| The runtime role can validate the Flyway history but cannot migrate, create objects, drop policies, disable the audit trigger, read signing keys or edit the history | `MigrationJobSeparationIntegrationTest.runtimeRoleCanValidateButNeverApplyMigrations` |
| A pending migration fails runtime validation | `MigrationJobSeparationIntegrationTest.runtimeRefusesToStartOnAPendingMigration` |
| The runtime configuration references no migration credential and imports only `/run/secrets/dokene` | `MigrationJobSeparationIntegrationTest.runtimeConfigurationReferencesNoMigrationCredential` |
| The backend validates by default, points at the job on a mismatch or on a schema too old for the runtime role to read its history, and migrates only with the test-only switch | `RuntimeSchemaValidationConfigurationTest` |
| The owner `dokene_migration` cannot `UPDATE`, `DELETE` or `TRUNCATE` audit rows (SQLSTATE `42501`) | `AuditIntegrationTest.ownerCannotRewriteOrEraseAuditEvents` |
| Break-glass works only inside the owner's transaction; after rollback the rows and the enabled triggers are back; the runtime role cannot disable the trigger | `AuditIntegrationTest.breakGlassDisablesImmutabilityOnlyInsideTheOwnersTransaction` |
| Existing runtime append-only, RLS and attribution guarantees are unchanged | the existing `AuditIntegrationTest` cases |
| Compose: only `migrate` gets the migration password; the backend gets only the signing-key secret and no such environment variable; the backend waits for `migrate` to complete | `scripts/tests/dev-env-smoke.sh`: "compose: only the migrate job holds the migration credential (#308)" |
| Secret files use Compose-resolved values (quotes, `DOKENE_SECRETS_DIR`); a directory granting group or other access (world-writable `1777`, readable `0755`) is refused and left unchanged; a changed secret removes `migrate` and `backend` before the files are rewritten, so a failed removal is detected again; `doctor` writes nothing; Compose 2.27.2 is the patch-aware minimum | `scripts/tests/dev-env-smoke.sh`: the "secret files", "doctor" and "compose minimum" checks |

**Full run:** `./gradlew check` passed: 2365 tests, 0 failures, 0 skipped (including the review follow-up). Existing pins updated for V34: `TenantPersistenceIntegrationTest` expects schema version 34 and lists `flyway_schema_history` among the tables the runtime role can see.

## Live run (Compose)

| Scenario | Outcome |
| --- | --- |
| Baseline `d7c9f1e` | Schema at V33. Backend environment held `DOKENE_DB_MIGRATION_PASSWORD`, `DOKENE_DB_MIGRATION_USERNAME`, `DOKENE_TENANT_CONTEXT_SIGNING_KEY` (the finding) |
| Upgrade: same volume, branch images, `docker compose up --wait` | `migrate` exited 0: "Current version 33", applied V34; backend healthy after "Successfully validated 35 migrations" |
| Backend environment after upgrade | 0 variable names matching `MIGRATION` or `SIGNING_KEY` in `env` and in `docker inspect`; `/proc/1/environ` contains neither secret value (0 matches each); `/run/secrets/dokene` holds only `DOKENE_TENANT_CONTEXT_SIGNING_KEY` (re-run after narrowing the mount to that directory: `/run/secrets` contains only `dokene`) |
| `migrate` container | Mounts `/run/secrets/dokene/DOKENE_DB_MIGRATION_PASSWORD` and `/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY`; `docker compose run --rm migrate` on its own: "up to date" |
| As `dokene_migration` with one audit row: `DELETE`, `UPDATE`, `TRUNCATE` on `audit_events` | Each refused: "audit_events is append-only: … is not allowed" |
| Break-glass: `BEGIN; ALTER TABLE … DISABLE TRIGGER audit_events_immutable; DELETE …; ROLLBACK;` | `DELETE 1` inside the transaction; after rollback 1 row and the trigger enabled (`O`) |
| As `dokene_runtime`: `DELETE` audit rows; disable the trigger; read signing keys; `CREATE TABLE` | `permission denied for table audit_events`; `must be owner of table audit_events`; `permission denied for table tenant_context_signing_keys`; `permission denied for schema dokene` |
| As `dokene_runtime`: read the Flyway history | Allowed (35 rows), needed for validation |
| Clean deployment (fresh volume) | `migrate` applied 34 migrations; backend healthy after validation |
| `restart-backend` path (`up --force-recreate --no-deps migrate oidc-bridge backend`) | `migrate` "up to date"; backend healthy |
| Backend against a schema whose history the runtime role cannot read (pre-V34 state, simulated by revoking the V34 grant) | Startup refused with "The database schema does not match this release. Run the migration job (Compose service 'migrate')", caused by "permission denied for table flyway_schema_history" |

## Residual risk and operator notes

- The backend still holds the signing key, because it signs tenant capabilities. A live compromise of the backend can forge tenant contexts until the key is rotated (ADR 0003 rotation lifecycle). The key is no longer readable from the process environment or `docker inspect`.
- The owner or a superuser can still disable the audit triggers; the protection holds against the backend and accidental migrations, not a malicious database administrator (ADR 0006).
- Compose refuses environment-sourced secrets for read-only services, so `./scripts/dev-env.sh` writes the two secret files from `.env` into the git-ignored `.secrets/` directory (created with mode 0700, files 0444 because the containers run as uid 10001) on `up` and `restart-backend`; `DOKENE_SECRETS_DIR` may name another directory of the user's own with mode 0700; one that grants group or other users any access is refused. A plain `docker compose up` needs those files, so the repository scripts point to `./scripts/dev-env.sh up`.
- The PostgreSQL bootstrap container still receives both role passwords to create the roles on first start; that is outside the long-running backend.
- A host-run backend now needs `./gradlew migrateDb` before `bootRun`.
