# Development Model

## Repository model

Dokene uses a monorepo so backend, frontend, infrastructure, documentation, and cross-cutting changes can evolve atomically.

```text
dokene/
├── backend/
├── frontend/
├── docs/
├── infra/
├── .agents/
└── .github/
```

A monorepo does not mean the frontend and backend are one runtime application. They remain separate deployable components connected through an explicit API contract.

## Backend baseline

- Java 26;
- Spring Boot;
- Gradle;
- PostgreSQL;
- Flyway;
- Spring Security;
- JPA where appropriate;
- JUnit-based testing.

Backend code should preserve modular-monolith boundaries and avoid letting framework convenience erase domain ownership.

## Frontend baseline

- React;
- TypeScript;
- Vite;
- PNPM;
- feature-oriented organization;
- generated or contract-validated API types where practical.

The frontend is not a security boundary. Authorization and tenant isolation remain backend/database responsibilities.

## Agent guidance

Repository-wide agent instructions live in:

```text
AGENTS.md
```

Canonical provider-neutral skills live in:

```text
.agents/skills/
```

Do not maintain duplicated skill trees for individual agent vendors. Provider-specific discovery files, if ever required, should only point to the canonical `.agents/` content.

Use the smallest relevant set of skills for a task.

## Issue workflow

Implementation Issues should normally contain:

- background;
- current state;
- goal;
- non-goals;
- affected areas;
- architectural boundaries;
- security considerations;
- acceptance criteria;
- verification commands;
- documentation/ADR impact.

Large Issues should be decomposed when independent acceptance criteria or security boundaries would otherwise be obscured.

## Pull request expectations

A PR should answer:

- what problem is being solved;
- why the implementation fits current architecture;
- what changed;
- what did not change;
- how tenant/security boundaries are preserved;
- how the change was tested;
- whether documentation or ADRs changed;
- residual risks or follow-up work.

Avoid broad opportunistic refactoring unless it is required for the requested change.

## Architecture Decision Records

Create/update an ADR when a change establishes a durable decision involving, for example:

- module ownership;
- tenancy strategy;
- authentication/authorization model;
- persistence strategy;
- API contract strategy;
- AI provider boundary;
- action authorization policy;
- messaging provider boundary;
- scheduler/concurrency model;
- secret-management model;
- deployment topology;
- major dependency/framework choice.

An ADR should record:

1. context;
2. decision;
3. alternatives considered;
4. consequences;
5. follow-up work.

Accepted ADRs are authoritative for the scope they govern.

## Testing strategy

Testing should progressively cover:

### Unit tests

Domain rules, policy, transitions, parsing/validation, and isolated application services.

### Integration tests

PostgreSQL behavior, Flyway migrations, RLS, repositories, provider adapters, webhook validation, and authentication/authorization boundaries.

### Security/negative tests

Especially important for:

- cross-tenant access;
- missing/forged tenant context;
- authorization bypass attempts;
- opt-out override attempts;
- duplicate send protection;
- webhook forgery/replay;
- invalid AI action values;
- mass-assignment/IDOR cases.

### Frontend tests

Prefer behavior-oriented tests around critical workflows rather than testing implementation details.

### End-to-end tests

Use for high-value user journeys such as customer creation, follow-up review, approval, and safe message lifecycle.

## Database migrations

Production schema changes must be represented as migrations.

Do not rely on ORM auto-DDL to evolve production state.

Migrations should be forward-safe, deterministic, reviewed for tenant isolation, and tested against realistic PostgreSQL behavior.

## CI/CD

CI should be path-aware where practical:

```text
backend/**  → backend build/tests
frontend/** → frontend build/tests
both        → both pipelines
```

Cross-cutting checks should include security and supply-chain controls regardless of application area when appropriate.

## Documentation model

The canonical Wiki source lives under:

```text
docs/wiki/
```

This makes documentation reviewable through normal PRs.

The GitHub Wiki is a published view synchronized from those files. Do not maintain separate independent content in both locations.

Architecture detail that is too implementation-specific for the Wiki can remain in `docs/architecture/`, while durable decisions remain in `docs/adr/`.

## Definition of done

A change is not complete merely because code compiles.

Completion should consider:

- goal/acceptance criteria satisfied;
- tests passing;
- security/tenant impact reviewed;
- external side effects safe and idempotent where relevant;
- failure paths understood;
- docs/ADR updated when behavior or architecture changed;
- no secrets or unnecessary PII introduced into logs/configuration;
- CI remains healthy.
