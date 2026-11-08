# ADR 0031: Membership Invitation Acceptance and Audited Reactivation

## Status

Accepted. Issue: [#317](https://github.com/stevdrey/dokene/issues/317), part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes finding L2 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md). Migration V37.

It amends [ADR 0006](0006-durable-append-only-audit.md) (membership use cases and audit events) and [ADR 0008](0008-workspace-provisioning-and-tenant-selection.md) §4 (discovery lists active memberships only, and invitations get their own global endpoints). It refines one statement of [ADR 0028](0028-manual-dispatch-claim-and-last-moment-send-gate.md) (see §5).

## Context

`POST /api/memberships` created an `ACTIVE` membership. The invitee could enter the workspace at once, without having agreed to join it. The `INVITED` status existed in the domain and in the database, but nothing used it. Identity ids are visible to every co-member, so a hostile administrator could push a user into a look-alike workspace (audit L3).

Revocation is a status change (`REVOKED`, issue 315), and `uq_tenant_memberships_tenant_identity` allows one membership per identity and tenant. So a revoked member could never be added again (`409`), and a mistaken revocation could not be undone.

## Decision

### 1. An invitation grants nothing until the invitee accepts it

`MembershipService.addMembership` creates the membership as `INVITED`; the response carries `"status": "INVITED"`. Everything that grants access already requires `ACTIVE`:

- `dokene.discover_active_tenant_memberships` (V3) returns active memberships of active tenants only;
- `MembershipTenantContextResolver` builds a tenant context only from that discovery;
- `CurrentMembershipAuthorizer` re-resolves through the same path.

So an invitee gets no tenant context, does not appear in `GET /api/tenants`, gets `403` from `GET /api/tenants/{id}`, and gets `403` from every tenant-scoped endpoint. An administrator can still change an invitation's role or revoke it (cancel it) before it is accepted. The role and ownership rules do not change: `OWNER` is never invited, and owners can be neither demoted nor revoked through these use cases.

### 2. The invitee lists and accepts its own invitations

Two endpoints on the global `/api/tenants` boundary, where no tenant context exists:

| Endpoint | Result |
| --- | --- |
| `GET /api/tenants/invitations` | `200` with `[{tenantId, displayName, role, invitedAt}]`: the caller's `INVITED` memberships in active tenants, oldest first. `invitedAt` is the membership's creation time, so a role change does not move it, and a reactivated membership shows its original invitation date. |
| `POST /api/tenants/{tenantId}/invitation/acceptance` | `200` with the workspace (`{tenantId, displayName, role}`) the caller can now select. `404` when the caller holds no pending invitation there. |

The acceptance needs the session's CSRF token like every other state-changing request. A `404` is the same whether the tenant does not exist, belongs to someone else's invitation, was already accepted, was revoked, or the tenant is not active. It is not audited: the caller has no tenant context, and auditing it would let any authenticated identity append unbounded global denial rows by posting random tenant ids.

An invitee's row is hidden from it by tenant RLS, because it has no tenant context yet. Migration V37 therefore adds two `SECURITY DEFINER` functions in the style of `discover_active_tenant_memberships`:

- `dokene.discover_pending_tenant_invitations(payload, signature)`;
- `dokene.accept_tenant_membership_invitation(payload, signature, tenant_id, accepted_at)`.

Both verify an identity capability signed with the tenant-context key (`signed_context_subject(..., 'identity')`), so a runtime session without the backend's key cannot use them. Both see only the verified identity's own rows, and never an `OWNER` row or a non-active tenant. The acceptance changes `INVITED` to `ACTIVE`, sets `updated_at` to the acceptance time passed by the application (the clock every other membership transition uses), advances the optimistic-lock `version`, and returns nothing when there is no pending invitation. `EXECUTE` is granted to `dokene_runtime` only.

`MembershipInvitationService.acceptInvitation` runs the function and records `MEMBERSHIP_INVITATION_ACCEPTED` in the same transaction. The event is attributed to the invitee, under the tenant context `TenantContextResolver` now resolves, in that transaction, for the membership the function just activated. If the context does not resolve to that membership, or the audit fails, the acceptance rolls back. The service refuses to run inside a tenant context.

The UI shows pending invitations on the "no workspaces" page and on the settings tab. It shows the workspace name, the role, the invitation date and the tenant id, because two workspaces can share a name (audit L3). Accepting reloads the workspace list.

### 3. A revoked membership is recovered by an audited reactivation

`POST /api/memberships/{identityId}/reactivation` requires `MEMBERSHIP_INVITE`, the same permission an invitation needs, checked before the body or path is validated. `TenantMembership.reactivate` allows only `REVOKED` to `INVITED`, and never for an `OWNER`. The membership keeps its id and its role. The endpoint returns the membership (`200`, `"status": "INVITED"`) and records `MEMBERSHIP_REACTIVATED`, attributed to the acting member, in the same transaction.

| Case | Result |
| --- | --- |
| Membership not found in the tenant | `404` |
| Membership not `REVOKED` (`INVITED`, `ACTIVE`, `SUSPENDED`, the owner) | `409` |
| Caller without `MEMBERSHIP_INVITE` (operator, viewer, non-member) | `403`, audited denial |

A reactivation is an invitation again. The member must accept it before regaining access, so an administrator cannot put a former member back into a workspace without that member's consent. Adding the identity again with `POST /api/memberships` stays a `409`: reactivation is the explicit recovery path. A reactivation is a status update, so it fits the column grant of V36 (`role`, `status`, `updated_at`, `version`).

### 4. Audit vocabulary

Two event types, accepted by `ck_audit_shape` (V37) with a membership target, a member actor and membership, and only the non-owner role in `new_role`:

| Event | Actor | `new_role` |
| --- | --- | --- |
| `MEMBERSHIP_INVITATION_ACCEPTED` | the invitee | the accepted role |
| `MEMBERSHIP_REACTIVATED` | the member who reactivated | the membership's role |

`MEMBERSHIP_CREATED` keeps its meaning: an invitation was created. Both new events use the closed `MembershipStatusChanged(role)` metadata and store no free text.

### 5. A reactivated membership is the same membership

ADR 0028 compares the membership a request or a dispatch attempt names with the member's current active membership, so "a member who was removed and re-invited does not count". Before this ADR, re-adding was impossible. A reactivated membership keeps its id. After the member accepts it again, it counts as the same membership again.

That is deliberate. Between the revocation and the new acceptance the membership is not active, so every check refuses. After acceptance, the member is authorized again by an administrator and by their own consent. Each command still checks the current role. The only lasting effect is on a dispatch claim left `STARTED` by that member: recovery can then turn it into an unknown outcome, which ADR 0028 intends for every expired claim. It never sends.

### 6. A membership change is decided against the actor's current membership

Amended by issue [#325](https://github.com/stevdrey/dokene/issues/325). The request's tenant context carries the role resolved when the request started, so a change committed while the request runs (a demotion or revocation of the actor) used not to stop it. Invite, reactivate, change role and revoke now all run through `MembershipChangeAuthorizer.change`:

1. before any transaction, check the request's own context. A caller who never had the permission is refused and audited without taking a lock;
2. in one transaction, share-lock the tenant row (`FOR SHARE`) and lock the actor's and the target's membership rows in the request's tenant (`SELECT … ORDER BY id FOR NO KEY UPDATE`), through `TenantMembershipRepository.lockForMembershipChange`;
3. re-decide through `CurrentMembershipAuthorizer`, which requires the same, still `ACTIVE` membership with its current role, and only then run the mutation;
4. a refusal rolls the transaction back first and is audited as `AUTHORIZATION_DENIED` afterwards, so the audit's independent transaction never needs a second pooled connection while the change still holds its own.

Every membership change takes these locks, so a change to the actor that commits first makes the waiting change fail step 3, and one that starts later waits until this change commits. Locking in id order means two administrators changing each other never deadlock: one wins, the other is refused. `NO KEY` blocks every membership update but not foreign-key checks from other tables. The tenant lock is shared: a tenant suspension waits for the change, while other membership changes in the tenant do not wait for each other. The locks and the re-read are scoped to the request's tenant, so the same identity's membership in another tenant neither blocks nor authorizes the change. An architecture test allows only the known use cases to write memberships, so a new writer has to be reviewed against this path.

## Consequences

- Inviting someone no longer gives them access. Operators must tell invitees to sign in and accept.
- A revoked member can be recovered without database access. Every step is audited, and the member's consent is required again.
- The invitee's side runs through two narrow identity-scoped database functions. The runtime role still has no general view of other tenants' memberships.
- An invitation that is never accepted stays `INVITED` until it is revoked. Invitations do not expire, and an invitee cannot decline one; both are possible follow-ups.
- Membership changes in one tenant are serialized per actor and target row. They are rare, short and administrative, so the wait is negligible; a membership change no longer relies on an authorization decision that a concurrent change has overtaken (issue 325).
