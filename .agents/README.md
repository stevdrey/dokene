# Canonical Agent Guidance

This directory is the single source of truth for AI coding-agent instructions in the repository.

## Operating Model

Agents should work from repository evidence rather than assumptions. Before implementation or review:

- inspect the current code and tests;
- identify the owning module and architectural boundary;
- read relevant ADRs and security documentation;
- load only the skills needed for the task;
- preserve existing public contracts unless the task explicitly changes them;
- prefer small, reviewable changes over broad speculative refactors.

## Execution Discipline

Use a deliberate task lifecycle rather than repeatedly rediscovering state:

1. discover the smallest sufficient context;
2. establish scope, acceptance criteria, risks, and verification targets;
3. implement a coherent batch of related changes;
4. run the narrowest meaningful verification;
5. expand verification only when the affected boundary requires it;
6. perform remote publication/review actions after local implementation and verification are complete.

Within a task, reuse evidence that remains valid. Do not re-read unchanged repository material, re-fetch unchanged remote metadata, or repeat successful checks without a state change that can affect the result.

Track verification outcomes conceptually as part of the task state: what was checked, what passed or failed, what changed afterward, and what still requires validation. This should prevent duplicate checks rather than create a new documentation burden.

Efficiency never overrides correctness or security. Do not relax sandboxing, authorization, tenant isolation, validation, repository protections, or other trust boundaries merely to avoid an approval or tool call.

## Security-First Expectations

Security is a first-class architectural property.

Every change should consider, where applicable:

- authentication and authorization;
- tenant isolation;
- input validation and output encoding;
- secret handling;
- PII exposure and logging;
- dependency/supply-chain risk;
- external integration trust boundaries;
- auditability and idempotency;
- least privilege and secure defaults.

AI output is untrusted input. It must never directly authorize or execute privileged side effects without deterministic validation and policy enforcement.

## Architecture Expectations

- Preserve module boundaries and dependency direction.
- Keep external providers behind narrow interfaces/adapters.
- Avoid framework leakage into domain models where a simple boundary can isolate it.
- Prefer explicit domain types over generic maps or weakly typed payloads.
- Record durable architectural decisions in `docs/adr/`.
- Update architecture/security documentation when behavior or trust boundaries materially change.

## Verification

Every behavioral change requires appropriate tests. Run the narrowest relevant checks during development and the applicable project build before completion.

For cross-cutting changes, verify both backend and frontend contracts when relevant.

A successful check remains valid until relevant source, tests, build configuration, dependencies, environment, or external state changes. Do not repeat it merely for reassurance.

When a check requires network, Docker, credentials, or host-level access, first determine whether that capability is actually necessary. Batch checks that require the same capability and diagnose failures before retrying.

## Review Convergence

Review is complete when acceptance criteria are satisfied, required checks pass, and no unresolved correctness, security, tenant-isolation, data-integrity, API-contract, migration-safety, or merge-safety blocker remains.

Classify remaining findings as actionable blockers or optional improvements. Do not keep cycling implementation and review solely to eliminate stylistic, speculative, or already-resolved observations.

## Skills

See `.agents/skills/README.md` for the available skills and when to apply them.
