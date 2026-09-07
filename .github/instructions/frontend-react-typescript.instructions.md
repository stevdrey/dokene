---
applyTo: "frontend/**"
---

# Frontend review instructions

For files under `frontend/`, apply the canonical frontend review guidance in `.agents/skills/frontend-react-typescript/SKILL.md` together with `AGENTS.md` and relevant API/architecture documentation.

During review, pay particular attention to:

- TypeScript strictness, unsafe assertions/`any`, and runtime validation at untrusted boundaries;
- component responsibility, state ownership, effect lifecycle, cancellation, and stale/duplicated state;
- API contract alignment, centralized transport concerns, and explicit loading/error/authorization states;
- browser security, secret/PII exposure, unsafe HTML/URL handling, CSRF/session assumptions, and authorization mistakenly enforced only in the UI;
- tenant-scoped caches/state and isolation when the active tenant changes;
- accessibility of critical interactions and destructive/high-impact flows;
- failure-path tests, duplicate submission behavior, API failures, and important accessibility/security cases.

Prefer concrete user-visible, security, correctness, or maintainability risks over stylistic preferences. Do not request memoization, new global state, abstraction, or dependencies without a demonstrated need.