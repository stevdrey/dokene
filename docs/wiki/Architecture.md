# Architecture

## Architecture style

Dokene starts as a **modular monolith** in a monorepo.

This is intentional. The product needs strong module boundaries and clear ownership, but it does not yet need the operational cost of distributed services.

The repository contains separately buildable frontend and backend applications:

```text
dokene/
├── backend/
├── frontend/
├── docs/
├── infra/
└── .github/
```

Frontend and backend are deployed as separate runtime components even though they live in the same repository.

## Runtime context

```text
┌──────────────────────┐
│      Web Browser     │
└──────────┬───────────┘
           │ HTTPS
           ▼
┌──────────────────────┐
│ React + TypeScript   │
│ Frontend             │
└──────────┬───────────┘
           │ REST / OpenAPI
           ▼
┌──────────────────────────────────────────────┐
│ Spring Boot modular monolith                 │
│                                              │
│ identity  tenant  customer  followup         │
│ messaging template ai integration audit      │
│ security                                     │
└───────┬────────────────┬─────────────────────┘
        │                │
        ▼                ▼
┌───────────────┐  ┌──────────────────────────┐
│ PostgreSQL    │  │ External providers       │
│ + RLS         │  │ AI / WhatsApp / future  │
└───────────────┘  └──────────────────────────┘
```

## Backend modules

The backend is organized around business capabilities rather than only technical layers.

### `identity`

Authentication identity, principals, memberships, and login/session-related boundaries.

### `tenant`

Tenant lifecycle, membership, roles, tenant context, and tenant-scoped policy.

### `customer`

Customer profile, contact data, consent state, business relationship metadata, and purchase history ownership.

### `followup`

Follow-up eligibility, timing, candidate selection, recommendation orchestration, and follow-up lifecycle.

### `messaging`

Message lifecycle, approval, queueing, send idempotency, provider-independent delivery contracts, and delivery state.

### `template`

Message templates, versioning, categories, language, provider-template references, and rendered-message provenance.

### `ai`

AI provider abstraction, structured request/response contracts, model invocation, schema validation, and AI-specific telemetry. This module must not own authorization or side-effect policy.

### `integration`

External integration configuration, provider metadata, webhook ingestion boundaries, and secret references. Secrets themselves must not be stored as ordinary plaintext values.

### `audit`

Append-oriented audit records for security-sensitive and externally visible actions.

### `security`

Cross-cutting security policy, authorization helpers, tenant enforcement, rate-limit/policy integration points, and secure defaults.

## Dependency direction

Business orchestration should depend on abstractions, not vendor SDKs.

Example:

```text
followup
  ├──> customer contracts
  ├──> AI port
  └──> messaging port

ai adapter
  └──> OpenAI / future provider

messaging adapter
  └──> Meta WhatsApp / future provider
```

The AI module must not directly decide that an external action is authorized.

The messaging provider adapter must not decide business eligibility.

## Application flow

A typical follow-up flow is:

```text
Scheduler / operator request
        ↓
Follow-up candidate query
        ↓
Eligibility + consent + tenant policy
        ↓
Context assembly
        ↓
AI structured recommendation/draft
        ↓
Schema validation
        ↓
Action policy / authorization gate
        ↓
PENDING_APPROVAL
        ↓
Human approval
        ↓
Queued send
        ↓
Messaging provider adapter
        ↓
Provider webhook / delivery status
        ↓
Audit + message state update
```

## Frontend architecture

The frontend uses React + TypeScript and is organized primarily by business feature.

Preferred shape:

```text
frontend/src/
├── app/
├── features/
│   ├── auth/
│   ├── customers/
│   ├── followups/
│   ├── messages/
│   ├── templates/
│   ├── integrations/
│   └── settings/
├── components/
├── api/
└── security/
```

The frontend is not a security boundary. Tenant isolation and authorization must always be enforced in the backend/database even when the UI hides unavailable actions.

## API contract

Backend and frontend are separate systems connected through an explicit API contract.

Preferred direction:

```text
Spring backend
    ↓
OpenAPI specification
    ↓
Generated/validated TypeScript client/types
    ↓
React frontend
```

This minimizes DTO drift and keeps the API contract machine-checkable.

## Persistence

PostgreSQL is the system of record for SaaS business state.

The initial multi-tenant strategy is:

- one shared database;
- one shared application schema;
- `tenant_id` on tenant-owned tables;
- application-level tenant scoping;
- PostgreSQL Row Level Security as defense in depth.

See [[Multi Tenancy and Data]].

## Scheduling and concurrency

Scheduled follow-up work must be safe when multiple application instances are running.

Rules:

- do not rely on a single in-memory scheduler lock;
- use database-backed claiming/locking or a coordination mechanism such as a scheduler library designed for clustered execution;
- make send operations idempotent;
- make retries explicit;
- preserve a durable state machine for external messages.

## Deployment evolution

Early deployment may use Docker Compose or a simple platform deployment with:

```text
frontend
backend
postgres
```

Future separation into services is justified only when there is evidence such as:

- independently scaling workloads;
- strict operational isolation requirements;
- different release cadences;
- team ownership boundaries;
- failure-domain requirements;
- enterprise tenancy requirements.

Microservices are not a roadmap milestone by themselves.

## Architecture decision records

Durable decisions belong in `docs/adr/`.

The Wiki explains the current system in an accessible form; ADRs record why durable choices were made and what alternatives were rejected.
