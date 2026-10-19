# Suite #123 scripts

Run from the root of a QA worktree with the stack up. `base.sh` sources the suite #121 environment helper (`../../issue-121-evidence/scripts/env121.sh`, which logs in as the seeded users) and uses `/home/stevdrey/qa123/` as the working folder.

- Provider faults use the suite #121 stub (`../../issue-121-evidence/scripts/Stub.java`, extended in this suite with `STATUS:COUNT:header=value` failures, a refusal output and a success-only delay `delay-ok.ms`). **Every provider response in `res0234`, `res04b`, `res05`, `res06`, `res07`, `res10` is injected**; the genuine OpenAI responses are the live baseline, RES-01 cases 4b, 6 (HTTP 401) and 7 (HTTP 404), the live recoveries inside `res07` and `res11`.
- Backend configuration per case is applied with a compose override file (environment only) and removed afterwards; the repository `.env` and compose files are not modified.
- `res10.sh` needs the disposable override `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=metrics` and `MANAGEMENT_ENDPOINT_METRICS_ACCESS=read-only`; the default configuration exposes no metrics endpoint.
- `res07.mjs`, `res08ui.mjs`, `res11.mjs` use the suite #122 Chrome harness (`../../issue-122-evidence/scripts/cdp.mjs`); `res08ui.mjs` injects a lost response by wrapping `window.fetch` (the real request is sent, then the promise rejects).
- `res0234.sh` was rerun after two harness fixes (stub handler threads outlive a client timeout, so a settle wait and a success-only delay were added); `res06.sh` after its expectation was aligned with the documented HTTP 409 stale contract; `res08.sh` after the manual-completion expectation was aligned with ADR 0013. The outputs here are from the final runs.
