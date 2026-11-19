# ADR 0033: Stated Workspace Provisioning Policy

## Status

Accepted. Issue: [#414](https://github.com/stevdrey/dokene/issues/414), child of [#312](https://github.com/stevdrey/dokene/issues/312), part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It is the first of two changes for finding L3 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md). The second, [#312](https://github.com/stevdrey/dokene/issues/312) itself, enforces the per-identity quota and adds §6.

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
| `max-workspaces-per-identity` | `3` | Range 1 to 10000, checked in every mode. Enforced as described in §6. |

With `enabled=true`, startup requires exactly one of a non-empty allowlist or `allow-all-identities=true`. Neither stops startup, and the message names both settings. Both together also stop startup. This gives three modes: `disabled`, `allowlist` and `open`.

### 2. The policy check runs before the transaction

`WorkspaceProvisioningService.provisionWorkspace` is no longer `@Transactional` as a whole. It normalizes its input, runs the policy check with no transaction open (the check reads configuration only), and then runs the rest in one transaction through a `TransactionTemplate`. On a refusal it increments the counter, asks for the denial audit row as before (`AUTHORIZATION_DENIED`, `TENANT_READ`, `NO_TENANT_CONTEXT`, no tenant), charged to the refused identity under the denial budget of [ADR 0032](0032-bounded-authorization-denial-audit.md), so a refusal writes at most one row, and throws `ProvisioningNotPermittedException`. No denial audit row is written while a provisioning transaction is held.

### 3. The mode is visible to operators

The backend logs one line at startup with the mode and the cap. The `allowlist` line carries the number of listed identities; the `open` line is logged at `WARN`. No identity id appears in it. The counter `dokene.provisioning.refused` counts refusals with the single tag `reason` (`policy`, or `quota` for §6). It is incremented before the audit call, so it includes refusals whose audit row was suppressed or failed.

### 4. The refusal carries a machine readable code

The policy refusal of `POST /api/tenants` answers `403` with content type `application/problem+json` and exactly the members `type` (`about:blank`), `title` (`Forbidden`), `status` (`403`) and `code` (`PROVISIONING_NOT_PERMITTED`). `ProvisioningNotPermittedException` extends `TenantAccessDeniedException`, so code that catches the parent keeps working.

ADR 0023 §4.4 said endpoints from before Phase 3 keep their empty bodies. `POST /api/tenants` is the first exception: the quota change adds a second refusal on the same status, and a caller must be able to tell them apart. The `400`, `401` and `409` answers of this endpoint, its other `403` answers, and every other endpoint are unchanged.

### 5. The local stack states its policy in plain sight

`compose.yaml` sets `DOKENE_PROVISIONING_ALLOW_ALL_IDENTITIES` to `true` and `DOKENE_PROVISIONING_MAX_WORKSPACES_PER_IDENTITY` to `1000` next to the enabled line, marked as local values, and `.env.example` lists the same. The seed and verification scripts need no extra step.

### 6. The cap per identity is enforced

`max-workspaces-per-identity` is a lifetime cap on the workspaces one identity has provisioned: the number of its rows in `dokene.workspace_provisioning_records`. Workspaces the identity joined by invitation do not count, and nothing lowers the count, because the runtime role cannot delete provisioning records. The cap applies in `allowlist` and `open` mode alike; in `disabled` mode the policy refuses first.

- **Where.** The count needs the database, so it runs inside the provisioning transaction, after the idempotency lookup and before anything is written. A replay of an existing key creates nothing and is never capped: it answers `200` with the existing workspace, or `409` for another name, at any count.
- **Serialization.** A transaction-scoped advisory lock is taken per identity, so every request of one identity is serialized and two requests with different keys cannot both read a count below the cap and both create a workspace. A hash collision between identities only serializes them. The previous lock, per identity and key, is still taken after it, in a second statement, so a backend of the previous release, which takes only that one, still excludes the same request during a rolling deploy; the fixed order (identity, then key) cannot deadlock. The transaction runs at `READ COMMITTED`, pinned in code: the count must see the rows the previous lock holder committed, which a `REPEATABLE READ` snapshot taken before the lock wait would miss.
- **Count capability.** The runtime role still has no privilege on the table. The count is read through the `SECURITY DEFINER` function `dokene.count_workspace_provisioning_records` (V41), which verifies the identity capability like the lookup of ADR 0008 §2 and counts only that identity's rows. A missing or forged capability raises `insufficient_privilege` instead of answering 0, so the cap cannot fail open.
- **Refusal.** At the cap the service throws `WorkspaceQuotaExceededException`, the transaction rolls back and returns its connection, and only then is the refusal counted (`reason=quota`) and the denial audit row requested, in the same shape and under the same budget as the policy refusal of §2. The audit table only admits `NO_TENANT_CONTEXT` for a denial without a tenant, so the row does not say which refusal it was; the counter tag does. §2 holds for both refusals: no denial audit row is written while a provisioning transaction is held.
- **Answer.** `403` as `application/problem+json` with exactly `type`, `title`, `status` and `code` = `WORKSPACE_QUOTA_EXCEEDED`, the second code §4 anticipated. The body carries neither the count nor the cap. `WorkspaceQuotaExceededException` extends `TenantAccessDeniedException`. The frontend API client keeps the body of a `403`, so the workspace selector and the first-workspace view tell the user they reached the limit instead of saying they lack permission.

## Consequences

- A blank allowlist can no longer mean everyone. Open access exists only where someone set `allow-all-identities`.
- This is a breaking configuration change. A deployment that enabled provisioning with an empty allowlist stops at startup until it sets an allowlist or the open setting. A backend run on the host from an older `.env` is in the same position.
- The open setting is still only a setting. Nothing technical stops someone from turning it on in production; the defence is that it is named, off by default, and logged at `WARN`.
- The local stack runs in open mode, so local behaviour differs from a real deployment by design.
- An allowed identity creates at most `max-workspaces-per-identity` workspaces over its lifetime. Raising the cap is a configuration change and a restart; there is no per-identity override and no API that changes it.
- Because records are never deleted, an identity that reached the cap stays there even if its workspaces are archived. An operator who must let it provision again raises the cap.
- Every provisioning request of one identity now waits for that identity's earlier request to commit. Provisioning is rare, so this cost is accepted.
