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

## Context Reuse and Task State

Build the smallest sufficient working set once, then reuse it throughout the task.

- Reuse task requirements, affected-file knowledge, ADR/security guidance, test commands, remote metadata, and previously inspected code while they remain valid.
- Do not re-read unchanged files, documentation, Issues, PR metadata, or review threads merely to reconfirm information already established in the current task.
- Re-read or re-fetch evidence only when relevant state may have changed, a new ambiguity appears, an external action invalidates prior evidence, or correctness depends on freshness.
- Once the affected scope is known, prefer targeted reads over broad repository scans.
- Preserve a concise working view of acceptance criteria, unresolved risks, changed files, and verification status so later steps do not require rediscovering the same context.

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

## Verification Efficiency

Preserve verification quality while minimizing redundant tool execution:

- Run the narrowest relevant test or static check while implementing a change.
- Do not repeat an identical successful verification command unless relevant source, test, build, dependency, or runtime configuration has changed since that successful run.
- Batch related edits before re-running integration tests or expensive validation suites.
- Run the full applicable project test/build once during final verification unless an intermediate full-suite execution is necessary to diagnose a failure or validate a cross-cutting change.
- Prefer deterministic workspace-local checks before commands that require Docker, external services, network access, credentials, or global caches.
- When a command fails, diagnose the failure before retrying it. Do not create blind retry loops.
- Reuse already established evidence from the current task instead of re-running a command only to reconfirm unchanged state.

## Escalation and External Capability Discipline

Treat sandbox, network, credential, Docker, and host-level escalation as capabilities to use deliberately, not as default execution paths.

- Before escalating a command, determine whether the required evidence can be obtained with an equivalent workspace-local or already-authorized operation.
- Batch operations that require the same external capability instead of repeatedly crossing the same trust boundary.
- Do not retry an escalated command after failure unless state, arguments, permissions, or another material condition has changed.
- Distinguish read-only remote inspection from remote writes. Prefer local repository evidence when it is sufficient.
- Never weaken sandboxing, authorization, tenant isolation, host security controls, or repository protections merely to reduce friction or tool calls.
- Do not use `sudo`, disable security controls, or broaden permissions unless the task explicitly requires it and the action is appropriate for the environment.
- If required verification cannot run within available capabilities, report that limitation precisely rather than substituting repeated escalation attempts.

## Java and Gradle Environment

Backend work targets the Java version declared by the project build.

- Before the first Gradle invocation in a shell/session, verify that the required JDK is active.
- If SDKMAN initialization or `sdk use` is required, perform it only when the current Java version is wrong or unknown; do not prepend SDKMAN setup to every Gradle command once the environment has been verified.
- After the Java environment is confirmed, prefer direct Gradle Wrapper commands such as `./gradlew test`, `./gradlew check`, or the narrowest relevant task.
- Use the Gradle Wrapper from the repository rather than a globally installed Gradle executable.
- Avoid reading global Gradle daemon logs or caches unless a Gradle failure actually requires that diagnostic evidence.

## Git and GitHub Interaction Efficiency

Minimize remote and repository-state calls without sacrificing correctness:

- Batch GitHub reads whenever practical. Prefer one sufficiently scoped query over several sequential requests for the same PR, Issue, review thread, or check state.
- Do not repeatedly call equivalent `gh pr view`, `gh issue view`, `gh api`, `gh pr checks`, or GitHub API queries when the relevant remote state has not changed.
- Reuse GitHub metadata, comments, review threads, commit SHAs, and other remote evidence already fetched during the current task.
- Re-fetch remote state only after an action that can reasonably change it, after the user explicitly requests a fresh verification, or when correctness depends on current server state.
- Prefer local `git status`, `git diff`, and `git log` evidence when remote access is not required.
- Batch related file staging into one deliberate `git add` operation after reviewing the intended diff.
- Perform commit and push only after the requested implementation and applicable verification are complete, unless an intermediate commit is explicitly useful or requested.
- Prefer one final remote sequence for push, PR update, review response, and related publication work instead of interleaving remote writes throughout implementation.
- Do not perform a follow-up read solely to confirm a successful GitHub write when the write operation already returned an authoritative success result, unless the user requested verification or the API response was ambiguous.

## Review and Stop Conditions

Do not optimize for an endless sequence of review rounds or for reaching zero comments.

A task or review iteration may stop when all of the following are true:

- the explicit acceptance criteria are satisfied;
- required verification for the changed scope passes, or any unavailable verification is clearly documented;
- no unresolved correctness, security, tenant-isolation, data-integrity, API-contract, migration-safety, or merge-safety blocker remains;
- documentation/ADR updates required by the change are complete;
- remaining observations are stylistic, speculative, or optional improvements that do not materially affect the requested outcome.

When a fresh review produces only already-resolved findings or optional improvements, summarize them without starting another implementation/review cycle unless the user explicitly requests those improvements.

## Provider Neutrality

The `.agents/` directory is the single source of truth for coding-agent guidance. Do not maintain separate copies such as `.windsurf/`, `.cursor/`, `.claude/`, `.codex/`, or similar provider-specific skill trees. If a provider requires a discovery file, keep it as a thin pointer to `.agents/` rather than a duplicated instruction set.
