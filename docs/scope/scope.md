# Scope: Dokene

Dokene is a security first customer follow up service for small businesses. Operators see which customers are due for a follow up, get an advisory AI draft, and (from Phase 3 on) send an approved message through a messaging provider.

**Build approach:** Tracer Bullet (one thin real thread through every layer first, then thicken each strand end to end).
**Workflow:** GA (after `/develop`: `/check verify`, `/test`, a fresh model `/check review`, then `/document`). The project default level of rigor. `/architect` is the recommended first stop for a feature with a real decision, but skippable when you already know the build. Any feature can carry its own tag (e.g. `· Beta`) to do more or less.

_These are recommendations to keep your build orderly, not requirements. Skip anything that does not fit: if you already know how to build a feature, use `/develop` and skip `/architect`. You decide when a feature is `done`._

The governing decision for everything planned below is [ADR 0023](../adr/0023-phase-3-outbound-messaging-contracts.md): module ownership, the message state machine, routes, errors, ETags, idempotency and the permission matrix. Specs refine it, they do not reopen it.

## At a glance

| # | Feature | Phase | Status |
|---|---------|-------|--------|
| A | Tenant platform foundation | Phase 0 | existing |
| B | Authorization and durable audit | Phase 0 | existing |
| C | Browser session and workspace selection | Phase 0 | existing |
| D | Customer memory and contact policy | Phase 1 | existing |
| E | Purchase history | Phase 1 | existing |
| F | Follow up eligibility, queue and dispositions | Phase 1 | existing |
| G | AI recommendations and drafts | Phase 2 | existing |
| H | Containerized local environment | Phase 0 | existing |
| 1 | Message data model and state machine | Slice 1 | in-progress |
| 2 | Submission and approval API | Slice 1 | planned |
| 3 | Send pipeline with a local test provider | Slice 1 | planned |
| 4 | Approval queue and message UI | Slice 1 | planned |
| 5 | Tenant integration configuration and secret references | Slice 2 | planned |
| 6 | Provider template mapping | Slice 2 | planned |
| 7 | Live messaging provider adapter | Slice 2 | planned |
| 8 | Provider webhooks and delivery tracking | Slice 2 | planned |
| 9 | Integration and template settings UI | Slice 3 | planned |
| 10 | Operator controls for stuck sends and the kill switch | Slice 3 | planned |
| 11 | Open message window amendment to ADR 0023 | Slice 2 | planned |

## Already built (enrolled for context)

### A. Tenant platform foundation · existing
Modular monolith, shared schema tenancy, forced row level security with a signed tenant capability, scoped tenant context. code in `backend/src/main/java/io/github/stevdrey/dokene/tenant/`, migrations `V1` to `V3`

### B. Authorization and durable audit · existing
Typed tenant permissions and role map, fail closed evaluation, append only audit with closed metadata. code in `tenant/security/`, `audit/`

### C. Browser session and workspace selection · existing
Backend for Frontend session with OIDC on the server, CSRF, workspace provisioning and tenant selection, authenticated shell. code in `identity/`, `tenant/api/`, `frontend/src/features/auth/`, `frontend/src/features/tenants/`

### D. Customer memory and contact policy · existing
Customer profiles, normalized phone identity, channel consent, do not contact, archival. code in `customer/`, `frontend/src/features/customers/`

### E. Purchase history · existing
Purchase capture with corrections, voids and last purchase derivation. code in `purchase/`

### F. Follow up eligibility, queue and dispositions · existing
Deterministic eligibility, due queue, snooze, dismiss, manual completion, ETags and idempotency. code in `followup/`, `frontend/src/features/followups/`

### G. AI recommendations and drafts · existing
Structured recommendations, constrained drafts, deterministic action gate, failure handling, assistance panel, evaluation harness. code in `ai/`, `followup/application/`, `frontend/src/features/followups/components/AiAssistantPanel.tsx`

### H. Containerized local environment · existing
Whole stack in containers with one entry script and QA seed. code in `infra/docker/`, `scripts/dev-env.sh`

## Slice 1: one approved message sent through a local provider

The thinnest real thread: an operator submits a draft, someone approves it, the operator sends it, and the message shows `SENT` then `DELIVERED`, all through real tables, RLS, audit, idempotency and UI. The provider is a local test adapter inside the dev stack, so no external credentials are needed to prove the pipe (basis: Tracer Bullet, prove the layers connect before thickening any one of them).

### 1. Message data model and state machine · in-progress
The `messaging` module's aggregate and tables: messages, approvals, cancellations, send attempts, event log, idempotency keys, plus the new audit event types and the follow up cadence anchor. This is the ground every later strand stands on.
**Done when:** migrations apply with forced RLS on every new table, the one open message per customer index holds, every transition in the ADR 0023 table is enforced in domain code and refused otherwise, and audit rows commit atomically with transitions carrying only closed metadata.
spec [0001](../specs/0001-message-data-model-and-state-machine.md)
code `backend/src/main/java/io/github/stevdrey/dokene/messaging` (domain, application, persistence/jdbc), migrations `V16__create_outbound_messaging.sql` and `V17__extend_audit_for_outbound_messaging.sql`
- [x] Design it (spec): `/architect message data model and state machine`
- [ ] Build it: `/develop message data model and state machine`
  - [x] Schema and domain: V16 with RLS and the open message index, the `OutboundMessage` record with both exhaustive transition matrices (AC-1, AC-2, AC-3, AC-8)
  - [x] Submit thin thread: V17 audit extension, repository, idempotency store, template gate stand in, `submit` end to end against Postgres (AC-2, AC-4, AC-5, AC-6, AC-10, AC-12)
  - [x] Approve, reject, cancel and the follow up cadence anchor (AC-4, AC-5, AC-6, AC-9)
  - [x] Ports, provider context, delivery sink and event read model (AC-6, AC-7, AC-9, AC-10)
  - [x] ArchUnit dependency test and documentation updates (AC-11, AC-1)
  - [ ] Amendment of 2026-10-09: drop the contact foreign key from V16, T2 contact ownership guard, report time window and monotonic cadence anchor, with their tests (AC-13, AC-14)
- [ ] Verify it: `/check verify message data model and state machine`
- [ ] Test it: `/test message data model and state machine`
- [x] Review it (fresh model): `/check review message data model and state machine`
- [ ] Document it: `/document message data model and state machine`
(basis: ADR 0023 §2, §4.5, §4.7; the data model is the costliest thing to redo)

### 2. Submission and approval API · needs a decision
Create, list, read, events, approve or reject, and cancel endpoints with the shared pieces Phase 3 introduces: the closed code error body, ETag and `If-Match` on messages, idempotency key storage and replay.
**Done when:** the routes in ADR 0023 §3.1 (all but sends and resolutions) behave as specified, including `201` then `200` replay, `409` codes for stale version, invalid transition and key reuse, and cross tenant access is refused at both layers.
- [ ] Design it (spec): `/architect submission and approval API`
(basis: ADR 0023 §3.1, §4.1, §4.2, §4.4; the error body shape is a cross cutting pattern)

### 3. Send pipeline with a local test provider · needs a decision
The outbound port, the eleven step send policy, persist then call attempt handling, both kill switches, cadence advance on `SENT`, and a local provider adapter that accepts sends and reports delivery so the dev stack and tests run without external credentials.
**Done when:** `POST /sends` moves an approved message through `QUEUED`, `SENDING` and `SENT` with one attempt row committed before the provider call, a second send on the same message never calls the provider, unknown outcomes freeze the message, the sent customer leaves the due queue, and the local adapter is wired into the dev stack and seed.
- [ ] Design it (spec): `/architect send pipeline with a local test provider`
(basis: ADR 0023 §4.3, §4.6; idempotent sending is the Phase 3 exit condition)

### 4. Approval queue and message UI · needs a decision
The `messages` frontend feature: a pending approvals list, message detail with history, approve, reject, cancel and send actions, and the single "Enviar a aprobación" control in the AI assistance panel.
**Done when:** an operator can hand a draft to approval, a user with approve permission can decide it, a user with send permission can send it, status and failure category render in Latin American Spanish, controls hide by permission, and the view resets on workspace switch like the rest of the shell.
- [ ] Design it (spec): `/architect approval queue and message UI`
(basis: ADR 0023 §1 frontend ownership, §6; ADR 0020 amendment)

## Slice 2: the live provider

Thicken the provider strand: real tenant configuration, template mapping, the first external provider adapter and its webhooks, so the thread from Slice 1 reaches a real phone.

### 5. Tenant integration configuration and secret references · needs a decision
The `integration` module's tenant record (identifiers, enabled flags, secret reference), the secret resolver port with an environment backed implementation, and the integration API.
**Done when:** a tenant admin can read and update the integration through the API without any secret value ever leaving the server, secrets resolve only at send time, and the tenant outbound switch blocks sends immediately.
- [ ] Design it (spec): `/architect tenant integration configuration and secret references`
(basis: ADR 0023 §1, §3.3; security invariant 6)

### 6. Provider template mapping · needs a decision
The `template` module: each closed template intent mapped to a provider template name, language and ordered parameters, versioned with an ETag, and the templates API.
**Done when:** mappings can be read and updated per intent with `If-Match`, unmapped or disabled intents block submit and send, and the AI never produces or sees a provider template name.
- [ ] Design it (spec): `/architect provider template mapping`
(basis: ADR 0023 §3.2; the AI must not invent provider identifiers)

### 7. Live messaging provider adapter · needs a decision
The first external provider adapter behind the outbound port, translating the send command to the provider's template message call and normalizing every failure into the closed category set, with bounded timeouts.
**Done when:** an approved message reaches a real recipient through the provider sandbox, provider errors map to categories without leaking provider text, and the adapter is selected by configuration with the local adapter still available for tests.
- [ ] Design it (spec): `/architect live messaging provider adapter`
(basis: ADR 0023 §1 port contract; roadmap Phase 3)

### 8. Provider webhooks and delivery tracking · needs a decision
The stateless webhook chain outside the BFF: handshake, signature verification on raw bytes, schema validation, tenant resolution from stored configuration, deduplication, and status application through the inbound port.
**Done when:** signed delivery, read and failure events move messages forward exactly once, replayed and out of order events are tolerated, unsigned or unknown events are dropped without revealing anything, and the session, CSRF and tenant header rules of the BFF are untouched.
- [ ] Design it (spec): `/architect provider webhooks and delivery tracking`
(basis: ADR 0023 §3.4, §6; security invariant 7)

### 11. Open message window amendment to ADR 0023 · needs a decision · from spec 0001
ADR 0023 §2 counts `SENT` and `DELIVERED` as open for the one open message per customer rule, and those states leave only through provider reports. A customer whose message never gets a `delivered` or `read` report is blocked from every later message with no operator action that clears it. Decide the amendment (an age bound on the index predicate, or an `INTEGRATION_MANAGE` close command) without weakening the exactly once argument the ADR builds on that index.
**Done when:** ADR 0023 §2 and §4.7 are amended, the index predicate or the new command is specified, and the change lands before feature 7 sends to real customers.
- [ ] Design it (spec): `/architect open message window amendment`
(basis: spec 0001 premise note and cross check)

## Slice 3: operate it

Thicken the operator strand so admins can run Phase 3 without touching the database.

### 9. Integration and template settings UI · needs a decision
The `integrations` frontend feature under Configuración: integration identifiers and secret reference, enabled switches, and the template mapping editor.
**Done when:** an admin can configure the integration and mappings from the browser, operators see templates read only, and no secret value is ever rendered.
- [ ] Design it (spec): `/architect integration and template settings UI`

### 10. Operator controls for stuck sends and the kill switch · needs a decision
The resolution endpoint and UI for messages frozen with an unknown outcome, and the tenant outbound kill switch control, both admin only by the existing permission map.
**Done when:** an admin can mark a frozen message failed (never sent), flip the tenant outbound switch, both are audited, and operators without the permission cannot reach either.
- [ ] Design it (spec): `/architect operator controls for stuck sends and the kill switch`
(basis: ADR 0023 §2 T12, §3.3)

## Deferred
Out of scope for Phase 3, kept so the plan stays honest.
- **Scheduled sending and work claiming**: queue consumer, multi instance claiming, automatic retries · needs a decision (Phase 4)
- **Unknown outcome reconciliation job**: resolve frozen sends from provider state automatically · needs a decision (Phase 4)
- **Inbound customer replies**: reading and storing replies from the channel · needs a decision
- **Separation of duties policy**: two person approval, admin only template editing · needs a decision (Phase 5, changes the role map)
- **Error body migration for older endpoints**: move Phase 1 and 2 routes to the closed code error body · needs a decision
- **Soft retire phone contacts**: a retired marker instead of a delete so message history always resolves its contact · needs a decision (from spec 0001, when the customer module is next touched)
- **Metrics export and provider health dashboard**: ship the existing meter registry somewhere · needs a decision (Phase 4)

## References

*Project sources*: ADR 0023 and the ADRs it builds on (0004 to 0007, 0010, 0013, 0017, 0018, 0020), `docs/wiki/Roadmap.md` Phase 3, `docs/wiki/Messaging-and-Integrations.md`, `docs/security/security-invariants.md`, `docs/architecture/tenant-isolation-rls-recipe.md`, `AGENTS.md`.

*Practices and standards*: Tracer Bullet slicing (prove the layers connect before thickening any one), foundations before features (the data model is the costliest thing to redo), fail closed defaults and human approval for outbound messaging (repository security invariants), idempotent commands for retried side effects.

## Legend

**The decision box.** Every feature carries exactly one, the sub task whose label ends with `(spec)`. Skills locate it by that `(spec)` suffix, never by an exact label. Every other box is an execution box and `/architect` never ticks one.

**Feature lifecycle**: the scope updates as a feature moves; each row is what it shows and who sets it:

| State | Set by | The feature shows |
|---|---|---|
| `planned` · needs a decision | `/scope` | one box: `Design it (spec): /architect <feature>` |
| `in-progress` (designed) | `/architect` at spec capture | `Design it` ticked; spec linked; `Build it: /develop <feature>` plus 2 to 5 milestones; the tier's closing boxes (`Verify it`, `Test it`, `Review it`, `Document it` at GA); any surfaced follow up enrolled |
| `in-progress` (building) | `/develop` | milestone sub boxes tick one by one; code pointer filled |
| `in-progress` (verified) | `/check verify` | `Build it` and milestones ticked; `Verify it` ticked |
| `done` | you, when you decide it is; `/sync` reconciles | the tier's last stage (GA: after `/test`, review and document) is the suggested point to call it done |

- **Next step** = the first unticked box (always a command or a tracked milestone).
- **needs a decision** = run `/architect` first; otherwise straight to `/develop`. The tag drops once the spec is captured.
- **Atomic build tasks live in the spec's `## Build plan`, not here**: the scope carries only the milestone rollup.
- **Status** `planned` → `in-progress` → `done`, plus `existing` (pre workflow, left alone by `/develop` and `/sync`) and `dropped` (de scoped, kept for history).
- **Workflow tier tag** beside a heading (e.g. `· Beta`) sets that one feature's rigor above or below the project default; no tag inherits GA.
- **Pointer line** (`spec <n> · code in <path>`): the spec link added by `/architect`, the code path by `/develop`.
