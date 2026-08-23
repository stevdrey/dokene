# AGENTS.md

## Purpose

This file is the provider-neutral entry point for AI coding agents working on this repository.

All canonical agent guidance lives under `.agents/`. Do not duplicate these instructions into provider-specific directories unless a tool absolutely requires a compatibility shim.

## Required Context

Before changing code, architecture, security behavior, persistence, API contracts, or UI behavior:

1. inspect the current `main` branch;
2. read `README.md`;
3. read relevant material under `docs/`;
4. inspect related source code and tests;
5. read `.agents/README.md`;
6. load the smallest applicable skill set from `.agents/skills/`.

## Canonical Agent Layout

- `.agents/README.md` — repository-wide agent workflow and precedence rules.
- `.agents/skills/README.md` — skill index and selection guidance.
- `.agents/skills/<skill-name>/SKILL.md` — reusable implementation/review guidance.
- `docs/architecture/` — architecture and trust-boundary documentation.
- `docs/adr/` — accepted architecture decisions.
- `docs/security/` — threat model, security invariants, and related security documentation.

## Precedence

When guidance conflicts, use this order:

1. explicit task requirements;
2. accepted ADRs for the affected scope;
3. security invariants and security documentation;
4. `AGENTS.md` and `.agents/README.md`;
5. applicable skills;
6. local implementation conventions.

If implementation reality diverges from documentation, do not silently normalize the mismatch. Update the documentation or propose an ADR when the divergence represents a durable architectural decision.

## Provider Neutrality

The `.agents/` directory is the single source of truth for coding-agent guidance. Do not maintain separate copies such as `.windsurf/`, `.cursor/`, `.claude/`, `.codex/`, or similar provider-specific skill trees. If a provider requires a discovery file, keep it as a thin pointer to `.agents/` rather than a duplicated instruction set.
