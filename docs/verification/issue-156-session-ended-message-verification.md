# Issue 156 verification: neutral message when the session is no longer active

Scope: frontend wording only (`LoginView`). The 401 handling, `wasExpired` state, form-value preservation and login button are unchanged.

Problem: the login view said "Tu sesión ha expirado por inactividad…" whenever a previously authenticated session became unauthenticated, including a sign-out in another tab or a server-side invalidation (backend restart). The BFF does not signal an idle timeout, so the message now states only what is known: "Tu sesión ya no está activa. Por favor inicia sesión nuevamente."

## Automated

- `npx vitest run src/features/auth`: 2 files, 13 tests passed (`evidence: issue-156-evidence/test-run.txt`). `LoginView.test.tsx` asserts the new text and that it does not mention "inactividad".
- Full frontend suite (`npm test`): 22 files, 361 tests passed. `tsc --noEmit` clean. The frontend has no `lint` script.

## Manual, local environment (`./scripts/dev-env.sh up --seed`)

Same browser profile, two tabs, user `testoperator` (OPERATOR):

1. Sign in on tab 1 and open tab 2 (already authenticated).
2. Tab 2: click "Cerrar sesión" (`issue-156-evidence/01-tab2-signed-out.jpg`).
3. Tab 1: navigate to Clientes. The customer list request returns 401 and the login view shows the neutral message with the "Iniciar sesión con OIDC" button (`issue-156-evidence/02-tab1-neutral-session-message.jpg`).

Not exercised manually: submitting "Crear cliente" with the stale session (the app reached the login view as soon as Clientes loaded). That path is covered by `CustomerFormModal.test.tsx` (401 shows a session error, not the workspace-permission message).
