# ADR 0033: Stated Workspace Provisioning Policy

## Status

Accepted. Issue: [#414](https://github.com/stevdrey/dokene/issues/414), child of [#312](https://github.com/stevdrey/dokene/issues/312), part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It is the first of two changes for finding L3 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md). The second adds the per-identity quota and extends this ADR.

It amends [ADR 0008](0008-workspace-provisioning-and-tenant-selection.md) §3 (the provisioning authorization policy). It records one exception to [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) §4.4 (see §4).

## Context

ADR 0008 §3 said that, with provisioning enabled, an empty allowlist permits every authenticated identity. So one switch, `dokene.provisioning.enabled=true`, with nothing else set, let every signed-in user create workspaces. The shipped Compose setup did exactly that. A blank setting meant "everyone", which is the opposite of a safe default.

The policy check also ran inside the provisioning transaction. The audit recorder writes a denial row in its own transaction on a second pooled connection, so a refusal held one connection while it asked for another.

## Decision

### 1. Enabled provisioning requires one stated policy

`ProvisioningProperties` binds `dokene.provisioning` and validates it when the configuration is bound, so a wrong combination stops startup:

| Property | Default | Rule |
| :--- | :--- | :--- |
| `enabled` | `false` | Off means every request is refused, whatever the other settings say. |
| `allowed-identities` | empty | Comma-separated identity UUIDs. Entries are trimmed, blank entries dropped, duplicates removed. A malformed entry stops startup in every mode. |
| `allow-all-identities` | `false` | The explicit open setting: every signed-in identity may provision. |
| `max-workspaces-per-identity` | `3` | Range 1 to 10000, checked in every mode. Enforced by the quota change. |

With `enabled=true`, startup requires exactly one of a non-empty allowlist or `allow-all-identities=true`. Neither stops startup, and the message names both settings. Both together also stop startup. This gives three modes: `disabled`, `allowlist` and `open`.

### 2. The policy check runs before the transaction

`WorkspaceProvisioningService.provisionWorkspace` is no longer `@Transactional` as a whole. It normalizes its input, runs the policy check with no transaction open (the check reads configuration only), and then runs the rest in one transaction through a `TransactionTemplate`. On a refusal it increments the counter, asks for the denial audit row as before (`AUTHORIZATION_DENIED`, `TENANT_READ`, `NO_TENANT_CONTEXT`, no tenant), charged to the refused identity under the denial budget of [ADR 0032](0032-bounded-authorization-denial-audit.md), so a refusal writes at most one row, and throws `ProvisioningNotPermittedException`. No denial audit row is written while a provisioning transaction is held.

### 3. The mode is visible to operators

The backend logs one line at startup with the mode and the cap. The `allowlist` line carries the number of listed identities; the `open` line is logged at `WARN`. No identity id appears in it. The counter `dokene.provisioning.refused` counts refusals with the single tag `reason` (`policy`). It is incremented before the audit call, so it includes refusals whose audit row was suppressed or failed.

### 4. The refusal carries a machine readable code

The policy refusal of `POST /api/tenants` answers `403` with content type `application/problem+json` and exactly the members `type` (`about:blank`), `title` (`Forbidden`), `status` (`403`) and `code` (`PROVISIONING_NOT_PERMITTED`). `ProvisioningNotPermittedException` extends `TenantAccessDeniedException`, so code that catches the parent keeps working.

ADR 0023 §4.4 said endpoints from before Phase 3 keep their empty bodies. `POST /api/tenants` is the first exception: the quota change adds a second refusal on the same status, and a caller must be able to tell them apart. The `400`, `401` and `409` answers of this endpoint, its other `403` answers, and every other endpoint are unchanged.

### 5. The local stack states its policy in plain sight

`compose.yaml` sets `DOKENE_PROVISIONING_ALLOW_ALL_IDENTITIES` to `true` and `DOKENE_PROVISIONING_MAX_WORKSPACES_PER_IDENTITY` to `1000` next to the enabled line, marked as local values, and `.env.example` lists the same. The seed and verification scripts need no extra step.

## Consequences

- A blank allowlist can no longer mean everyone. Open access exists only where someone set `allow-all-identities`.
- This is a breaking configuration change. A deployment that enabled provisioning with an empty allowlist stops at startup until it sets an allowlist or the open setting. A backend run on the host from an older `.env` is in the same position.
- The open setting is still only a setting. Nothing technical stops someone from turning it on in production; the defence is that it is named, off by default, and logged at `WARN`.
- The local stack runs in open mode, so local behaviour differs from a real deployment by design.
- The cap is bound and validated here but not yet enforced. Until the quota change lands, an allowed identity can still create workspaces without limit.
