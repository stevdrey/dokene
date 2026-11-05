# Issue #321 administrative provider call limits: verification record

Status: single PR. It closes audit finding L12 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): `POST /api/templates/{templateIntent}/approval-refresh` had no limiter, and an ownership proof that Meta answered as unavailable cost no budget, so a member with `TEMPLATE_WRITE` or `INTEGRATION_MANAGE` could loop provider calls made with the tenant's token. The design is recorded in the [ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md) issue 321 addendum and in the [threat model](../security/threat-model.md).

This record is sanitized: it gives limits, outcomes and test names only. It contains no reproduction script, token, credential reference value or provider response.

## Environment

- **Run:** 2026-11-04, branch `321-securityl12-rate-limit-administrative-provider-calls`, based on `main` at `7fe5e29` (the audit's tree `c4b62d1` plus the fixes for #306, #307, #308, #313, #315 and #317). The code was rechecked on that base before the change: L12 still applied as reported. `TemplateRegistryService.refreshApproval` called the gateway with no limiter, and `WhatsAppIntegrationService.verifyOwnership` released the `MappingRejectionLimiter` reservation of an `Unavailable` proof without counting it.
- **Tested revision:** `734deb9`, the first commit of this branch. Every result below was run on that commit or on the working tree that was committed unchanged as it. Later commits change only this record.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17, migrated by Flyway through V37. No migration was added: `AUTHORIZATION_DENIED` already accepts `RATE_LIMITED` for any permission.
- **Provider:** simulated. The integration tests replace Meta with in-process scripted stand-ins (`ScriptedTemplateProviderGateway`, `ScriptedOwnership`) and count every call that reaches them. No live Meta call was made.
- **Data:** synthetic tenants and members only.

## Change

| Endpoint | Budget | Counted | Not counted |
| --- | --- | --- | --- |
| `POST /api/templates/{templateIntent}/approval-refresh` | `dokene.template.refresh-limit`: `tenant-calls` 30, `window` 15m | every refresh that reaches the gateway, whatever the answer, unavailable included | `404 NOT_FOUND`, `409 STALE_VERSION`, `409 INTEGRATION_DISABLED`, authorization refusals |
| `POST /api/integrations/whatsapp/ownership-verifications` | `dokene.integration.whatsapp.ownership-verification-limit`: `tenant-calls` 30, `window` 15m | every proof that reaches the verifier, whatever the outcome, unavailable included | disabled integration, `409 STALE_VERSION`, `404`, authorization refusals, a proof already refused by the rejection budget |

- Both use the new neutral primitive `ratelimit.CallBudget`, a synchronized sliding window per tenant id that never sees a phone number id, template name or token. A refused call records nothing, so a client retrying after `Retry-After` succeeds once the window moves.
- A spent budget is `429` with the closed problem `{"type":"about:blank","title":"Too Many Requests","status":429,"code":"RATE_LIMITED"}` and `Retry-After` in whole seconds (at least one, at most the window). The provider is not called and stored evidence or proofs are unchanged.
- Each throttled call appends one `AUTHORIZATION_DENIED` row with reason `RATE_LIMITED`, attributed to the member, with no target: permission `TEMPLATE_WRITE` for a refresh and `INTEGRATION_MANAGE` for a proof. That row and the `429` are how a limit is observed; neither carries a secret or identifier of the provider account.
- The existing per-tenant and per-actor rejection budget of ownership proofs (issue 208) is unchanged and still applies first.
- `RATE_LIMITED` (429) was added to `TemplateErrorCode`; `TemplateErrorCodeParityTest` keeps it identical to the messaging code.
- The defaults allow a tenant to refresh every one of its five intents several times per window, and stay above the largest legitimate sequence in the existing ownership test suite (21 proofs).

Residual risk: the budget is shared by the tenant's members, so a member looping calls can delay another member's refresh or proof for up to one window; that member already holds the permission and is visible in the audit log. State is in memory per instance, so it resets on restart and is multiplied by the number of instances, like the other in-memory limits.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Repeated refreshes, all answered unavailable, reach the provider exactly 30 times; the next refreshes from the same and from another member are `429` with the strict closed problem and `Retry-After` ≤ 900, reach no provider, leave the mapping unchanged, are audited once each (`TEMPLATE_WRITE`, `RATE_LIMITED`) and expose no template name or business account id; stale refreshes before the loop cost nothing; `PUT` still succeeds and another tenant's refresh still succeeds | `TemplateRegistryIntegrationTest.refreshesThatReachTheProviderAreBoundedPerTenantAndThrottledWithAnAuditedClosedProblem` |
| Repeated proofs answered unavailable cost no rejection budget but reach the provider exactly 30 times; the next proof, from another admin, is `429` with the strict closed problem and `Retry-After` ≤ 900, reaches no provider, exposes no phone number id and is audited (`INTEGRATION_MANAGE`, `RATE_LIMITED`); disabling the integration still succeeds and another tenant's proof still succeeds | `WhatsAppIntegrationConfigurationIntegrationTest.unavailableProofsSpendTheTenantProofBudgetAndAreThenThrottledWithoutAProviderCall` |
| Normal operation and the existing budgets: rejection throttling, successful proofs, concurrent proofs, audit fail-closed paths, refresh evidence, stale versions and isolation | the other tests of `TemplateRegistryIntegrationTest` and `WhatsAppIntegrationConfigurationIntegrationTest` |
| Grants up to the limit; reports the wait until the oldest call leaves (never below one second); a refused call records nothing so retries succeed after the window; keys are independent; idle keys are evicted; 64 concurrent callers never get more than the limit; invalid configuration is refused | `CallBudgetTest` |
| `RATE_LIMITED` has the messaging status and problem body | `TemplateErrorCodeParityTest` |
| `ratelimit` depends on no other Dokene module | `ModuleDependencyArchitectureTest` |

## Results

Runs on `734deb9`:

| Command (in `backend/`) | Kind | Result |
| --- | --- | --- |
| `./gradlew test --tests '*CallBudgetTest' --tests '*TemplateErrorCodeParityTest' --tests '*ModuleDependencyArchitectureTest' --tests '*WhatsAppIntegrationServiceOutboundSwitchTest' --tests '*MappingRejectionLimiterTest'` | automated | passed |
| `./gradlew test --tests '*TemplateRegistryIntegrationTest' --tests '*WhatsAppIntegrationConfigurationIntegrationTest'` | automated, simulated provider | passed: 22 and 30 tests, no failures or skips |
| `./gradlew check` | automated | passed: 2404 tests in 199 classes, no failures, errors or skips |

The frontend is unchanged and was not rerun: the integration panel already maps `RATE_LIMITED` to its own message, and no template UI calls the refresh endpoint.
