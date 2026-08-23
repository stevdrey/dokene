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

## Skills

See `.agents/skills/README.md` for the available skills and when to apply them.
