# Issue #321 administrative provider call limits: verification record

Status: single PR. It closes audit finding L12 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md). Two endpoints let a member loop provider calls made with the tenant's token:

- `POST /api/templates/{templateIntent}/approval-refresh` had no limiter;
- an ownership proof that Meta answered as unavailable cost no budget, so a member with `TEMPLATE_WRITE` or `INTEGRATION_MANAGE` could repeat it without limit.

The design is recorded in the [ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md) issue 321 addendum and in the [threat model](../security/threat-model.md).

This record is sanitized: it gives limits, outcomes and test names only. It contains no reproduction script, token, credential reference value or provider response.

## Environment

- **Base:** branch `321-securityl12-rate-limit-administrative-provider-calls`, first based on `main` at `7fe5e29` (the audit's tree `c4b62d1` plus the fixes for #306, #307, #308, #313, #315 and #317).
- **Recheck before the change:** L12 still applied as reported. `TemplateRegistryService.refreshApproval` called the gateway with no limiter, and `WhatsAppIntegrationService.verifyOwnership` released the `MappingRejectionLimiter` reservation of an `Unavailable` proof without counting it.
- **Tested revision:** `c3d8de8`, which merges `main` at `17819f7` (adding #316 and dependency bumps) into the branch after the code review fixes in `cf67489`. Every result in the Results table was run on that commit, or on the working tree that was committed unchanged as `cf67489`. Later commits change only this record.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17, migrated by Flyway through V38.
- **Provider:** simulated. In the integration tests, in-process scripted stand-ins replace Meta (`ScriptedTemplateProviderGateway`, `ScriptedOwnership`) and count every request that reaches them. The Graph adapter test uses a local stub HTTP server. No live Meta call was made.
- **Data:** synthetic tenants and members only.

## Change

| Endpoint | Budget (`tenant-calls` / `actor-calls` / `window`) | Charged | Not charged |
| --- | --- | --- | --- |
| `POST /api/templates/{templateIntent}/approval-refresh` | `dokene.template.refresh-limit`: 30 / 20 / 15m | every Graph request the refresh sends (up to five pages), whatever the answer, unavailable included | an answer decided without a request (no resolver, an unresolvable secret); `404 NOT_FOUND`, `409 STALE_VERSION`, `409 INTEGRATION_DISABLED`; authorization refusals |
| `POST /api/integrations/whatsapp/ownership-verifications` | `dokene.integration.whatsapp.ownership-verification-limit`: 30 / 20 / 15m | every proof that reaches the verifier, whatever the outcome, unavailable included | disabled integration, `409 STALE_VERSION`, `404`, authorization refusals, a proof already refused by the rejection budget |

- Both endpoints use the neutral primitive `ratelimit.ActorCallBudget`: a synchronized sliding window per tenant id and per (tenant, member). Its limits are validated in one place, `ratelimit.CallLimits`, and it never sees a phone number id, template name or token.
  - An operation is admitted only while both levels have a unit left, so one member cannot spend more than 20 of the tenant's 30.
  - A refused call records nothing, so a client that retries after `Retry-After` succeeds once the window has moved.
- Template refreshes are metered. The Graph adapter reports each page request through `TemplateProviderGateway.RequestMeter` right before sending it, and the service settles the admission with that count:
  - zero requests refunds it;
  - several requests charge the rest.

  The tenant bound is therefore 30 plus at most four pages of the last admitted refresh per window.
- A spent budget is `429` with `Retry-After` in whole seconds (at least one, at most the window). The provider is not called, and stored evidence and proofs are unchanged. The body is the closed problem:

  ```json
  {"type":"about:blank","title":"Too Many Requests","status":429,"code":"RATE_LIMITED"}
  ```
- A throttled member is audited once per window, however often it retries. The row is one `AUTHORIZATION_DENIED` with the new reason `PROVIDER_CALL_LIMITED` (V38, expand-only), attributed to the member, with no target. The permission is `TEMPLATE_WRITE` for a refresh and `INTEGRATION_MANAGE` for a proof.
  - The issue-208 rejection budget keeps `RATE_LIMITED`, so the two limits can be told apart in the audit log.
  - The `429`, together with these rows, is how a limit is observed. Neither carries a secret or any identifier of the provider account.
- `RATE_LIMITED` (429) was added to `TemplateErrorCode`. `TemplateErrorCodeParityTest` keeps it identical to the messaging code.
- The defaults allow a tenant to refresh each of its five intents several times per window. They also stay above the largest legitimate sequence in the existing ownership test suite: 16 proofs by one admin and 21 in all.

Residual risk:
- One member can still spend its own share and leave the others only the rest of the tenant budget for up to one window.
- State is held in memory per instance, so it resets on restart and is multiplied by the number of instances, like the other in-memory limits.

## Code review fixes

The code review of PR #358 raised nine findings. All were applied in `cf67489`:

1. Unbounded audit rows for throttled refreshes: fixed by auditing once per member per window.
2. Unbounded audit rows for throttled proofs: fixed the same way.
3. One member could exhaust the tenant budget: fixed by the per-member budget.
4. The budget counted refreshes, not Graph requests: fixed by per-page metering.
5. Local unavailable answers were charged: fixed by refunding a zero-request lookup.
6. Indistinct audit reasons: fixed by `PROVIDER_CALL_LIMITED`.
7. Duplicated limiter and properties code: fixed by the shared `ActorCallBudget` and `CallLimits`.
8. Unused `defaults()` methods: removed.
9. Missing tests: added; see below.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Covered by the test below. | `TemplateRegistryIntegrationTest.refreshesThatReachTheProviderAreBoundedPerMemberAndTenantAndThrottledWithAnAuditedClosedProblem` |
| Steps and expected outcomes for that test. | (1) Stale refreshes cost nothing. (2) One admin reaches the provider exactly 20 times, then gets `429` with the strict closed problem and `Retry-After` ≤ 900; the response has no template name or business account id. (3) Three more retries add no audit row: one `PROVIDER_CALL_LIMITED` row in all. (4) A second admin uses the remaining 10 and is throttled, and so is a third admin. (5) The provider is reached exactly 30 times and the mapping is unchanged. (6) `PUT` and another tenant's refresh still succeed. |
| A refresh answered without a provider request costs nothing, even 26 times; refreshes of five pages are charged five units each | `TemplateRegistryIntegrationTest.aRefreshAnsweredWithoutAProviderRequestCostsNoBudget` |
| Covered by the test below. | `WhatsAppIntegrationConfigurationIntegrationTest.unavailableProofsSpendTheProofBudgetAndAreThenThrottledWithoutAProviderCall` |
| Steps and expected outcomes for that test. | (1) Unavailable proofs cost no rejection budget. (2) One admin reaches the provider exactly 20 times, then gets a strict `429` with no phone number id. (3) A repeat adds no second audit row: one `PROVIDER_CALL_LIMITED` row and no `RATE_LIMITED`. (4) A second admin uses the remaining 10 and is throttled. (5) Disabling the integration and another tenant's proof still succeed. |
| Metering: three pages meter three requests; a missing resolver or a foreign sender meters none and sends nothing | `GraphApiTemplateProviderGatewayTest.theMeterCountsEveryPageRequestAndNothingForALocalAnswer` |
| Service level, with a moving clock: three pages cost three units; zero pages cost nothing; a looping member is audited once and cannot lock others out; a retry after `Retry-After` reaches the provider again | `TemplateRegistryServiceRefreshLimitTest` |
| Per-member and per-tenant limits, the wait at both levels (never below one second), refusals recording nothing, retry after the window, refund, extra charges, single settlement, once-per-window reporting, eviction, 96 concurrent callers bounded at both levels, missing keys | `ActorCallBudgetTest` |
| Defaults and refusal of non-positive limits and windows | `CallLimitsTest` |
| A throttled proof records `PROVIDER_CALL_LIMITED`, not `RATE_LIMITED` | `DurableIntegrationAuditAdapterTest.aThrottledProofIsADeniedManagePermissionEventWithItsOwnReason` |
| Normal operation and the existing budgets: rejection throttling, successful proofs, concurrent proofs, audit fail-closed paths, refresh evidence, stale versions and isolation | the other tests of `TemplateRegistryIntegrationTest` and `WhatsAppIntegrationConfigurationIntegrationTest` |
| Flyway reaches V38; audit rows keep their shape | `TenantPersistenceIntegrationTest`, `AuditIntegrationTest` |
| `RATE_LIMITED` has the messaging status and problem body; `ratelimit` depends on no other Dokene module | `TemplateErrorCodeParityTest`, `ModuleDependencyArchitectureTest` |

## Results

All commands run in `backend/`.

| Command | Revision | Kind | Result |
| --- | --- | --- | --- |
| `./gradlew test --tests '*ActorCallBudgetTest' --tests '*CallLimitsTest' --tests '*TemplateRegistryServiceRefreshLimitTest' --tests '*GraphApiTemplateProviderGatewayTest' --tests '*DurableIntegrationAuditAdapterTest' --tests '*ModuleDependencyArchitectureTest' --tests '*TemplateErrorCodeParityTest' --tests '*WhatsAppIntegrationServiceOutboundSwitchTest' --tests '*MappingRejectionLimiterTest'` | working tree committed as `cf67489` | automated | passed: 10, 2, 4, 16 and 5 tests in the new or changed classes, no failures |
| `./gradlew test --tests '*TemplateRegistryIntegrationTest' --tests '*WhatsAppIntegrationConfigurationIntegrationTest' --tests '*AuditIntegrationTest' --tests '*TenantPersistenceIntegrationTest' --tests '*OutboundMessagingSchemaUpgradeIntegrationTest'` | working tree committed as `cf67489` | automated, simulated provider | passed: 23, 30, 25, 37 and 1 tests, no failures or skips |
| `./gradlew check` | `c3d8de8` | automated | passed: 2434 tests in 201 classes, no failures, errors or skips |

Before the code review, the first implementation `734deb9` passed `./gradlew check` with 2404 tests in 199 classes. Those results no longer describe the code and are kept for history only.

The frontend was not rerun. It has no changes from this branch, and the merge brought only #361's dependency bump. The integration panel already maps `RATE_LIMITED` to its own message, and no template UI calls the refresh endpoint.
