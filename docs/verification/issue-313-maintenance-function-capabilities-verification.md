# Issue #313 cross-tenant maintenance functions: verification record

Status: single PR. It closes audit finding L4 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the runtime role could call the cross-tenant maintenance functions with no capability and any cutoff. The design is in [ADR 0003](../adr/0003-postgresql-rls.md), [ADR 0028](../adr/0028-manual-dispatch-claim-and-last-moment-send-gate.md) §5 and [ADR 0030](../adr/0030-verified-webhook-inbox-and-delivery-reconciliation.md) §4 and §5.

This record is sanitized: it gives bounds, outcomes and test names only. It contains no reproduction script, capability value or secret.

## Environment

- **Run:** 2026-11-04, branch `313-securityl4-restrict-cross-tenant-maintenance-functions`, based on `main` at `263ad4b` (the audit's tree `c4b62d1` plus the fixes for #306, #307 and #308). The code was rechecked on that base before the change: L4 still applied as reported, with the same three functions granted to `dokene_runtime` and no capability argument. **Tested revision:** the working tree that became the first commit of this branch on top of `263ad4b` (the commit that adds this record; a commit cannot name its own hash). Every check below used that tree.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL, migrated by Flyway through V35.
- **Data:** synthetic tenants, members and callbacks only; no live Meta call.

## Change

Migration V35 replaces the three functions with versions that take a signed capability as their first two arguments:

| Function | Without a valid `maintenance` capability | Cutoff clamp (database clock) | Row cap |
| --- | --- | --- | --- |
| `find_due_webhook_events(payload, signature, due_at, max_rows)` | no rows | `LEAST(due_at, now)` | 500 |
| `purge_webhook_events(payload, signature, processed_before, max_rows)` | returns 0, deletes nothing | `LEAST(processed_before, now − 8 days)` (the minimum retention) | 5000 |
| `find_expired_dispatch_claims(payload, signature, started_before, after_attempt_id, max_rows)` | no rows | `LEAST(started_before, now − 1 minute)` (enabled recovery needs `claim-timeout ≥ send-timeout + 1m`) | 500 |

- The capability is `maintenance|<key_id>|<nil uuid>|<expiry>|<nonce>`, HMAC-signed with the tenant-context key by `HmacDatabaseContextSigner.issueMaintenanceContext()`, valid for 60 seconds, and verified by the existing `signed_context_subject`, which now also accepts the `maintenance` scope. Tenant RLS still asks for the `tenant` scope, so a maintenance capability never opens a tenant's rows, and a tenant capability never opens a maintenance function.
- The old signatures are dropped. `EXECUTE` on the new ones is revoked from `PUBLIC` and granted to `dokene_runtime` only; the verifiers are not executable by the runtime role.
- `JdbcWebhookInboxRepository` and `JdbcOutboundMessageRepository` issue a fresh capability per call. The processors, their ports and their configuration are unchanged.
- `resolve_verified_whatsapp_tenant` is unchanged: a point lookup by an exact phone-number id that returns one tenant id and writes nothing, used by the unauthenticated webhook before any tenant exists. It is recorded as accepted in the audit status.

Residual risk: a live compromise of the backend holds the signing key and can sign maintenance capabilities, as it can sign tenant contexts (ADR 0003). The clamps still keep such a caller from listing rows early or purging inside the deduplication window.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| A runtime session with no tenant context and no capability, a forged signature, a tampered payload or a tenant-scope capability gets no claim ids | `DispatchRecoveryIntegrationTest.theExpiredClaimFinderNeedsAMaintenanceCapabilityAndNeverListsInFlightClaims` |
| With a capability, an `infinity` cutoff does not list a claim started 10 seconds ago, and recovery leaves it alone | same test |
| With a capability the finder still returns ids only, honours the cutoff, the limit, a null cutoff and the attempt-id cursor | `DispatchRecoveryIntegrationTest.theExpiredClaimFinderIsTheOnlyCrossTenantReadAndExposesIdsOnly` |
| One definer function per name, capability arguments first, result shape unchanged, `EXECUTE` for `dokene_runtime` and not `PUBLIC`, verifiers not executable by the runtime role | `DispatchRecoveryIntegrationTest.theMaintenanceFunctionsAreOnlyExecutableByTheRuntimeRole` |
| Without a valid capability (none, forged, tenant scope, identity scope) the purge deletes nothing and the due finder lists nothing | `WhatsAppWebhookIntegrationTest.theInboxMaintenanceFunctionsNeedACapabilityAndNeverPurgeOrListEarly` |
| A maintenance capability set as the tenant context opens no inbox row, while the tenant's own capability set the same way opens its row | same test |
| With a capability and an `infinity` cutoff, a row processed 2 days ago is kept and a row due in an hour is not listed; once 9 days old it is purged, and once due it is listed | same test |
| Legitimate workers still work: recovery turns expired claims into unknown outcomes once, races, run lock, revoked members, audit outage and starvation cases | the other `DispatchRecoveryIntegrationTest` cases (claims backdated 2 minutes, past the new floor) |
| The sweeper still applies, parks, abandons and purges rows past retention end to end | the other `WhatsAppWebhookIntegrationTest` cases, including `inboxRowsAreTenantIsolatedAndPurgedAfterRetention` |
| The maintenance capability has the five-part shape, nil subject, 60 s expiry and a fresh nonce per issue | `HmacDatabaseContextSignerTest.issuesMaintenanceContextWithNilSubjectAndFreshNonce` |

## Results

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*HmacDatabaseContextSignerTest' --tests '*DispatchRecoveryIntegrationTest' --tests '*WhatsAppWebhookIntegrationTest' --tests '*ModuleDependencyArchitectureTest'` | passed: 6, 13, 13 and 21 tests, no failures or skips |
| `./gradlew check` (backend) | passed: 2369 tests in 196 classes, no failures, errors or skips. `TenantPersistenceIntegrationTest` now expects schema version 35 |

After an independent review, the inbox test gained the identity-scope case and the tenant-context check above. Only that test file changed, and `./gradlew test --tests '*WhatsAppWebhookIntegrationTest'` passed again (13 tests). The frontend is unchanged and was not rerun.
