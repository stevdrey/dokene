# Issue #324 tenant-context signing-key retirement: verification record

Status: single PR. It closes audit finding L6 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): no code ever retired a tenant-context signing key, so after a key-id change the previous key kept verifying forever. The design is in [ADR 0003](../adr/0003-postgresql-rls.md) and the rotation runbook in the [RLS recipe](../architecture/tenant-isolation-rls-recipe.md#key-rotation-lifecycle).

This record is sanitized: it gives outcomes and test names only. It contains no key, capability value, signature or reproduction script. The tests generate random keys per run and compare key rows only by digest.

## Environment

- **Run:** 2026-11-07 to 2026-11-08, branch `324-securityl6-implement-tenant-context-signing-key-retirement`, based on `main` at `44b78c3` (the audit's tree `c4b62d1` plus the fixes merged since, through #322). The code was rechecked on that base before the change: L6 still applied as reported. `TenantContextSigningKeyCallback` only upserted the configured key as `ACTIVE`, nothing wrote `RETIRED`, and the two verifiers (`signed_context_subject`, last defined in V35, and `append_audit_event`, last defined in V19) accepted any `ACTIVE` key. **Tested revision:** first `b24af99`, the first commit of this branch. Then the working tree of the review follow-up commit that updates this record, on top of `b24af99` (a commit cannot name its own hash). The Results table states which revision each result covers.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17, migrated by Flyway through V39. Each retirement test uses its own empty database, so key rows never leak between tests.
- **Data:** synthetic tenant ids and randomly generated keys only.

## Change

- **V39** adds `retire_after` to `dokene.tenant_context_signing_keys`, wipes the material of any row already `RETIRED` and pins its deadline, and `dokene.verification_signing_key(key_id)`: the key if it is `ACTIVE` and `retire_after` is unset or later than `statement_timestamp()`. It is security invoker, not executable by `PUBLIC` or `dokene_runtime`, and is now the only function that reads the key table. `signed_context_subject` and `append_audit_event` are replaced with identical bodies except for that lookup (`CREATE OR REPLACE` keeps their grants). V39 is expand-only: a backend of the previous release still running during the rollout is unaffected.
- **`TenantContextSigningKeyCallback`** (migration job, `AFTER_MIGRATE`) now runs in one transaction under a `SHARE ROW EXCLUSIVE` table lock: it refuses a configured key id that is `RETIRED` or past its deadline; upserts the configured key as `ACTIVE` with no deadline; gives every other `ACTIVE` key `retire_after = LEAST(existing, now() + grace)`; marks keys past their deadline `RETIRED` with `retired_at = retire_after` and overwrites their material with random bytes. It also refuses different key material under an existing key id. Database errors reach the caller and the logs with their SQLState only, never with the driver message or detail, and a failed rollback is attached as a suppressed exception instead of replacing the original failure.
- **Configuration:** `dokene.tenant-context.retirement-grace` (`DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE`, default `1h`, accepted range `0s` to `7d`), passed to the `migrate` service in Compose and documented in `.env.example` and `backend/README.md`.

Design choices:

- The deadline is enforced by the verifiers on the database clock, not only by the job. Otherwise an old key would stay valid until the next deployment, which may never come. The database clock is the single clock for every backend instance, so instances with skewed clocks cannot disagree about it.
- The grace is bounded at 7 days, so a misconfiguration cannot recreate the "valid forever" finding. `0s` is allowed for emergency revocation.
- `LEAST` makes re-runs idempotent: a later deployment with the same key id never extends the grace, and a shorter grace shortens it.
- V39 adds no CHECK tying `retired_at` to `status`. During a release rollback, a migration job of the previous release could violate it, and PostgreSQL's "Failing row contains" detail would print the configured key into that job's output. Rolling the release back to before V39 is supported with the current key id only (runbook).
- A key id never changes its key: different material under an existing id would revoke the old material at once, with no grace, so the job fails instead.
- A retired key id is never reactivated. Rolling back to it after its grace fails the job, which exits non-zero, rolls its transaction back and changes no key. The error names the key id and the remedy, never key material.

Residual risk: a live compromise of the backend still holds the current key and can forge contexts until that key is rotated (ADR 0003); with a zero grace, rotation revokes it as soon as the job commits. The migration job, database administrators and the secret store remain trusted. Retired key material is overwritten, so a later database dump or backup no longer holds it.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| During a rolling rotation, capabilities signed with the old key (`k1`) and the new key (`k2`) both verify, for tenant RLS and for the audit append; `k1` gets a deadline of the database clock plus the grace and `k2` none | `SigningKeyRetirementIntegrationTest.rollingRotationKeepsBothKeysValidUntilTheGraceEndsThenRetiresTheOldOne` |
| Once the deadline has passed, `k1` no longer verifies (tenant context is `NULL`, the audit append is refused with "Active signing key not found") without another job run, while `k2` still does; the next job run records `k1` as `RETIRED` with `retired_at` equal to its deadline, and its material digest changes | same test |
| With a 15-second grace, the old key stops verifying in real time without any further job run | `SigningKeyRetirementIntegrationTest.theOldKeyStopsVerifyingWhenAShortGraceElapsesInRealTime` |
| Re-running the job with the same key id, or with a 7-day grace, keeps the earlier deadline; re-running with `0s` retires the old key at once (emergency revocation) while the current key still verifies | `SigningKeyRetirementIntegrationTest.reRunningTheJobNeverExtendsTheGraceButAShorterGraceRevokesAtOnce` |
| Rollback within the grace: running the job again with `k1` reactivates it (no deadline) and schedules `k2`; both verify while the rollback rolls out | `SigningKeyRetirementIntegrationTest.rollingBackWithinTheGraceReactivatesThePreviousKey` |
| Rollback after retirement: the job fails with a message naming the retired key id; no message in the cause chain contains either key; the key rows (compared by digest) are unchanged; `k1` still does not verify and `k2` does | `SigningKeyRetirementIntegrationTest.rollingBackToARetiredKeyFailsTheJobWithoutChangesOrKeyMaterialInTheError` |
| Configuring different material under an existing key id fails the job; no message in the cause chain contains either key; the rows are unchanged; the original key still verifies and the new material does not | `SigningKeyRetirementIntegrationTest.reusingAKeyIdWithDifferentMaterialFailsTheJobWithoutChanges` |
| No function in schema `dokene` other than `verification_signing_key` reads the key table, and the runtime role cannot execute it (`42501`) | `SigningKeyRetirementIntegrationTest.keyMaterialIsReadOnlyThroughTheRetirementAwareLookup` |
| The grace accepts `0s` to `7d` and rejects negative, missing or larger values; a malformed key is rejected without being echoed; a database failure whose detail quotes a key surfaces only its SQLState, and failed rollback and restore calls are kept as suppressed exceptions, again without the key | `TenantContextSigningKeyCallbackTest` |
| The migration job still migrates clean and previous-release schemas idempotently and provisions the configured key | `MigrationJobSeparationIntegrationTest` (unchanged) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./gradlew test --tests '*SigningKeyRetirementIntegrationTest' --tests '*TenantContextSigningKeyCallbackTest' --tests '*MigrationJobSeparationIntegrationTest'` | automated | `b24af99`: 14 tests passed. Review follow-up: 17 tests, 0 failures, 0 skipped |
| `./gradlew check` (backend) | automated | BUILD SUCCESSFUL: 2536 tests, 0 failures, 0 errors, 0 skipped. The first run failed only `TenantPersistenceIntegrationTest.migratesTheTenantFoundationWithLeastPrivilegeRuntimeAccess`, which asserts the latest schema version; it was updated from 38 to 39 and the full run repeated (`b24af99`). Review follow-up: BUILD SUCCESSFUL, 2539 tests, 0 failures, 0 errors, 0 skipped |

Not run: a live Compose rotation across two backend replicas. The local Compose stack runs one backend instance; the overlap that a rolling deployment relies on is covered at the database level by verifying capabilities from both signers against the same schema. No frontend change was made, so no frontend checks were run.
