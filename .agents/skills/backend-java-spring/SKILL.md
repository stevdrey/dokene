---
name: backend-java-spring
description: Use when implementing or reviewing Java 26 and Spring Boot backend code, APIs, persistence, security, integrations, concurrency, or tests.
---

# Java and Spring Backend

## Context

Read `AGENTS.md`, `.agents/README.md`, the backend build files, affected source/tests, relevant ADRs, and security documentation before changing backend code.

## Baseline

- Java 26 is the implementation baseline.
- Spring Boot is the application framework.
- Gradle is the build system.
- PostgreSQL is the primary relational database unless an ADR states otherwise.
- JUnit is the default testing framework.

Use current Java features when they improve semantics, safety, or maintainability rather than for novelty.

## Java Design Rules

- Prefer records for immutable value data where identity/mutability is unnecessary.
- Use sealed hierarchies when a domain is intentionally closed and exhaustive handling adds value.
- Use pattern matching and switch expressions when they improve clarity.
- Treat `static` as a semantic design choice, not a convenience modifier.
- Behavioral/domain/service helpers are instance methods by default.
- Use normal imports and simple type names. Fully qualified names require a real collision or external-contract reason.
- Avoid wildcard imports.
- Prefer typed domain objects over `Map<String, Object>` payloads.
- Keep mutability and ownership explicit.

## Spring Architecture

- Keep controllers thin: validate transport input, invoke application/domain services, map responses.
- Keep business rules out of controllers, JPA entities, configuration classes, and provider clients.
- Do not let Spring annotations become the domain model.
- Keep external providers behind narrow interfaces/adapters.
- Prefer constructor injection.
- Avoid service-locator patterns and direct access to the application context.
- Do not introduce circular dependencies; fix ownership/dependency direction instead.
- Keep transactions at intentional application-service boundaries.

## API Design

- Validate inputs at the boundary and re-check domain invariants in the domain/application layer.
- Use explicit request/response DTOs; do not expose persistence entities as API contracts.
- Use stable error shapes and appropriate HTTP status codes.
- Avoid leaking implementation details, stack traces, SQL messages, secrets, or internal identifiers unnecessarily.
- Treat IDs as identifiers, not authorization. Every resource access still requires policy/ownership checks.
- Prefer idempotent semantics for retried commands where duplicate side effects would be harmful.

## Security

Security is a first-class requirement.

- Authentication and authorization are separate concerns.
- Never trust a tenant identifier supplied by request input as the source of authorization context.
- Derive tenant/user context from authenticated identity and trusted server-side state.
- Enforce authorization at every privileged resource/action boundary.
- Apply least privilege to database users, external integrations, and application capabilities.
- Do not log credentials, access tokens, authorization headers, raw secrets, or unnecessary PII.
- Use secure defaults: deny unless explicitly allowed.
- Validate and authenticate webhooks; design replay protection and event deduplication.
- Treat AI/model output as untrusted input and validate it against deterministic policy before side effects.

## Multi-Tenancy and Persistence

When the system is multi-tenant:

- tenant-scoped business records require a non-null tenant identifier;
- every tenant-scoped query/action must execute within an explicit trusted tenant context;
- cross-tenant references must be prevented by schema/constraint design where practical;
- use PostgreSQL Row Level Security when it is part of the accepted architecture;
- test negative cross-tenant cases, not only happy paths.

For persistence:

- use migrations for schema changes;
- make constraints express invariants where practical;
- prefer database-enforced uniqueness/reference integrity over application-only checks;
- consider concurrency, transaction isolation, locking, and retries for race-prone workflows;
- do not silently swallow migration or data-integrity failures.

## Secrets and Integrations

- Never commit secrets.
- Do not persist provider credentials in plaintext unless an accepted design explicitly provides equivalent protection.
- Prefer references to an external secret manager for tenant/provider credentials.
- Scope external-provider permissions narrowly.
- Add timeouts to outbound calls.
- Define retry behavior deliberately; do not retry non-idempotent operations blindly.
- Use circuit breaking/backoff only when justified by the failure model.
- Validate external responses before mapping them into trusted domain state.

## Concurrency

- Use virtual threads for high-concurrency blocking I/O when they simplify orchestration.
- Do not use virtual threads as a substitute for CPU parallelism.
- Avoid unbounded concurrency and unbounded queues.
- Make cancellation, deadlines, and failure propagation explicit.
- Protect scheduled/distributed work against duplicate execution using locking or idempotency.

## Error Handling

- Fail predictably at validation, authorization, and data-integrity boundaries.
- Use precise domain/application exceptions rather than generic runtime failures.
- Distinguish user-correctable validation errors, denied actions, dependency failures, conflicts, and internal faults.
- Do not convert every exception to success/fallback behavior.

## Testing

At minimum, cover behavior changed by the task.

Include negative/security tests where relevant:

- unauthenticated access;
- unauthorized role/action;
- cross-tenant access;
- invalid/malformed input;
- duplicate/replayed commands;
- provider timeout/failure;
- database constraint/race cases.

Prefer integration tests for persistence/security boundaries and unit tests for deterministic domain rules.

Before completion, run the relevant Gradle tests/build and document any intentionally skipped verification.

## Review Checklist

Verify:

- Java 26 usage is intentional and idiomatic;
- `static` usage has class-level semantics;
- simple imported type names are used where unambiguous;
- controllers remain thin;
- domain/application boundaries are preserved;
- no persistence entity leaks into public contracts;
- tenant isolation and authorization are explicit;
- migrations/constraints preserve integrity;
- outbound integrations have validation/timeouts/error handling;
- sensitive data is not logged;
- tests cover important negative cases;
- documentation/ADRs match durable changes.
