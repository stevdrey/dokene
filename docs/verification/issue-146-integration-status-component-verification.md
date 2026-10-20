# Issue #146 WhatsApp integration status component: verification record

Status: component slice only. `IntegrationStatusPanel` is built and proven in isolation; nothing mounts it yet. Mounting it under Configuración, the template mapping editor and the outbound switch control belong to scope rows 9 and 10.

## Environment

- Date: 2026-10-20. Branch `feature/146-integration-status-component`, based on `50520d3`.
- Node `v26.7.0`, the repository's Vitest and Vite, jsdom.
- No backend: every reply is built from the frozen fixtures in `backend/src/test/resources/contracts/messaging/v1/` (`integration.json`, `templates.json`, `problems.json`), imported with `?raw`.

## Commands

| Command (from `frontend/`) | Result |
| --- | --- |
| `npm test` (`vitest run`) | 34 files, 674 tests passed, no console output from the feature |
| `npm run build` (`tsc -b && vite build`) | passed; the existing chunk size warning is unchanged because nothing imports the panel yet |
| `vitest run src/features/integrations` | 6 files, 129 tests passed |

## Automated coverage

| Test file | Tests | What it proves |
| --- | --- | --- |
| `integrationApi.test.ts` | 26 | routes, tenant and CSRF headers, `If-None-Match: *` or `If-Match` (never both), exact fixture request bodies, empty display number omitted, runtime narrowing of both responses, unnamed members dropped, strong numeric ETag only (AC-2, AC-13) |
| `readiness.test.ts` | 10 | ready only from the four server facts, the fixed missing order, `Comprobando…` and `Sin determinar` cases (AC-6) |
| `integrationErrors.test.ts` | 14 | `NOT_FOUND` detection, per code copy for save and verify, any 403 as forbidden, status copy fallback for malformed bodies, no server text echoed (AC-8, AC-10, AC-11) |
| `TemplateAvailability.test.tsx` | 13 | count line, returned order, the five badge outcomes with the provider status in `title` and visually hidden text, nothing editable, own loading, error and retry (AC-4, AC-7) |
| `IntegrationForm.test.tsx` | 21 | fields from the stored record, the server patterns on submit with focus on the first invalid field, the stored `+50600000011` resaved unchanged, display only phone preview, proof hint, double submit guard, disable confirmation with `Escape` and `Confirmar`, stale current values, no credential field (AC-9, AC-13, AC-14) |
| `IntegrationStatusPanel.test.tsx` | 45 | loading, loaded, not configured and error regions; readiness from fixtures; role gating for `OWNER`, `ADMIN`, `TENANT_ADMIN`, `OPERATOR`, `VIEWER` and no role; 403 hides the controls; create and replace preconditions; stale recovery on save and on create, including a failed re read and its retry; save and proof error copy; ownership proof outcomes and proof disabled while the form is open; templates re read after a `200`; tenant switch aborts and drops a late reply; 401 on a read and on a save; smuggled token absent from the DOM; heading order, one status region, native buttons in DOM order, focus on `Editar` and `Cancelar` (AC-3 to AC-14) |

The panel test file asserts after every test that no `console` method, `localStorage`, `sessionStorage` or `document.cookie` accessor was called (AC-13).

## Rendered check

A throwaway harness page (stubbed `fetch`, the same fixtures, deleted afterwards) rendered the ready, read only with a failed template read, not configured, and stale save states at 1280 px and 390 px in headless Chromium. No console errors and no horizontal overflow. The check found and fixed two defects: browser default margins on headings and paragraphs, and the stale notice using the success colour instead of the warning colour.

## Not covered here

- The end to end browser check of this panel belongs to the shell slice that mounts it and to integrated acceptance (#131), not to this PR.
- `jsdom` cannot press a button with a synthetic `Enter` or simulate Tab order, and `@testing-library/user-event` is not installed, so keyboard tests assert native elements, DOM order and focus targets only (AC-14).

## Untouched paths

The change set (checked with `git status --untracked-files=all` before the first commit; recheck with `git diff --stat main...HEAD` once committed) touches only `frontend/src/features/integrations/` (`api/`, `components/`, `hooks/`, `utils/`, `__tests__/`) and this note: 18 files. `types/index.ts`, `backend/`, `frontend/src/App.tsx`, `frontend/src/components/shell/`, `frontend/src/shared/` and `frontend/package.json` are unchanged, and no dependency was added (AC-1).
