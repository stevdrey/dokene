# Issue 156 verification: neutral message when the session is no longer active

Scope: frontend wording only. The 401 handling and login button are unchanged.

Problem: the login view said "Tu sesión ha expirado por inactividad…" whenever a previously authenticated session became unauthenticated, including a sign-out in another tab or a server-side invalidation (backend restart). The BFF does not signal an idle timeout, so the message now states only what is known: "Tu sesión ya no está activa. Por favor inicia sesión nuevamente."

Changes:
- `LoginView` shows the neutral message; the context flag was renamed `wasExpired` → `wasSessionEnded` so the name no longer implies expiry.
- The generic 401 strings in `shared/api/httpClient.ts` and `api/apiClient.ts` use the same wording, so inline errors and the login view agree.
- `issue-62-resilience-verification.md` keeps its historical quotes of the old message, annotated as superseded.

## Automated

- `npx vitest run src/features/auth src/features/customers`: 9 files, 129 tests passed (`issue-156-evidence/test-run.txt`, full verbose list, timings removed).
- Full frontend suite (`npm test`): 22 files, 361 tests passed. `tsc --noEmit` clean. The frontend has no `lint` script.
- `grep -rn "expirada\|wasExpired\|por inactividad" frontend/src` returns no matches.

## Manual, local environment (`./scripts/dev-env.sh up --seed`, frontend image rebuilt)

Same browser profile, two tabs, user `testoperator` (OPERATOR):

1. Sign in on tab 1 and open tab 2 (already authenticated).
2. First run: tab 2 "Cerrar sesión" (`01-tab2-signed-out.jpg`), then tab 1 navigates to Clientes. The list request returns 401 and the login view shows the neutral message (`02-tab1-neutral-session-message.jpg`).
3. Second run (the issue's exact repro, with the rebuilt image): on tab 1 open Clientes → Nuevo cliente and fill name and phone; sign out on tab 2; on tab 1 click "Crear cliente". The mutation returns 401 and the login view shows the neutral message with the "Iniciar sesión con OIDC" button (`03-tab1-create-customer-stale-session.jpg`).

Not verified: that the filled form values survive the redirect. The modal is no longer on screen once the login view renders, and no automated test asserts it: `CustomerFormModal.test.tsx` (401 case) only checks that the session error is shown instead of the workspace-permission message. The issue description reports the values as kept; this PR does not change that behavior and does not re-verify it.
