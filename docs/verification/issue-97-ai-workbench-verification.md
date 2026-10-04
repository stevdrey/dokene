# Issue 97 verification: AI recommendation and editable draft in the follow-up workbench

Scope: [ADR 0020](../adr/0020-frontend-ai-assistance-panel.md). Frontend only; the backend contracts from Issues #94, #95 and #96 are consumed unchanged.

Automated: `npm test` and `npm run build` (`tsc -b` + Vite) in `frontend/` (see the PR for the final count; the follow-ups suite alone is 115 tests after the Copilot review fixes).

| Acceptance criterion | Evidence |
|---|---|
| Operator can request and view a recommendation/draft from the workbench | `AiAssistantPanel.test.tsx` (request, recommended action/intent forwarded to the draft call), `FollowUpWorkbench.ai.test.tsx` (`keeps the deterministic due reason distinct…`) |
| Deterministic due reason and AI rationale are distinguishable | `FollowUpWorkbench.ai.test.tsx` (separate heading/region), panel region "Recomendación de la IA", AI label |
| Draft is editable without a send/approval side effect | `AiAssistantPanel.test.tsx` (`lets the operator edit the draft locally without any further network call`, `never offers a way to approve or send the message`) |
| AI unavailable/refused states leave manual actions usable | `FollowUpWorkbench.ai.test.tsx` (`leaves manual follow-up usable when the AI is unavailable`, loading keeps context and actions), panel tests for `NO_RECOMMENDATION`, `NO_DRAFT`, `INELIGIBLE`, `AI_UNAVAILABLE` retryable / `NOT_AVAILABLE`, 403/429/500/503, malformed response |
| Stale recommendation is not silently reused | panel tests (policy-version change hides result and draft; `409`; `STALE_STATE` → refresh callback), `FollowUpWorkbench.ai.test.tsx` (`does not silently reuse a recommendation after a disposition…`) |
| Workspace switching cannot show Tenant A's AI result in Tenant B | `FollowUpWorkbench.ai.test.tsx` (switch clears result; late Tenant A response ignored), unmount aborts the in-flight request (`AiAssistantPanel.test.tsx`) |
| Loading/error/status states accessible | `role="status"` live region, `role="alert"` errors, focus moved to the draft editor (panel tests) |
| No OpenAI SDK/key/provider call in the frontend | `grep -rniE "openai|api[_-]?key" frontend/src` → no matches; the only network calls are `followUpApi.requestRecommendation`/`requestDraft` through `httpClient` |
| Review follow-up: response belongs to the requested customer (Copilot, high) | `followUpAiApi.test.ts` (`customer correlation`: other customer, other evaluation customer, missing id on content results, case-insensitive match, non-content results may omit it but never name another customer) |
| Review follow-up: conflict recovery says "Actualizar recomendación" and invalidates every step (Copilot) | `AiAssistantPanel.test.tsx` (`stale state after a conflict`: draft 409 / version mismatch / `STALE_STATE` hide recommendation and draft, one refresh request, old advice stays hidden after the queue brings the new version, refresh uses the current version) |
| Review follow-up: closed vocabularies, 1000 code point body, announcements, wording, edited draft preserved | `followUpAiApi.test.ts` (`closed vocabularies and draft bounds`), `AiAssistantPanel.test.tsx` (`edited draft survives a stale recommendation`, `discard confirmation wording`, `assistive technology announcements`) |
| Review follow-up (Codex): draft limit and counter by Unicode code point | `textLength.test.ts`, `AiAssistantPanel.test.tsx` (`draft length counted in Unicode code points`: 1000 emoji accepted, 1001 truncated without a lone surrogate, no `maxLength`, API draft counted by code point) |
| Review follow-up (Codex): a failed stale-state refresh is reported and retried before a new request | `AiAssistantPanel.test.tsx` (`stale refresh lifecycle`: pending disables and announces, failure offers "Reintentar actualizar lista", rejected refresh, edited draft kept), `FollowUpWorkbench.ai.test.tsx` (failed refresh keeps the detail, retry succeeds, the new request uses the new version) |
| Review follow-up (Codex): a customer from a later page keeps its detail across the AI refresh | `FollowUpWorkbench.ai.test.tsx` (`keeps a customer loaded from a later page selected…`, `does not reload extra pages… only the first page was loaded`) |
| Review follow-up (Codex): no pagination race during a background refresh | `FollowUpWorkbench.ai.test.tsx` (`blocks pagination while an AI-triggered background refresh is in flight`) |
| Review follow-up (Codex): deterministic ineligibility refreshes the queue (recommendation and draft), a disallowed action does not | `AiAssistantPanel.test.tsx` (`deterministic ineligibility changes the queue`), `FollowUpWorkbench.ai.test.tsx` (`refreshes the queue and says so when the AI reports the customer is no longer eligible`) |
| Review follow-up (Codex): the background refresh keeps looking for the selected customer | `FollowUpWorkbench.ai.test.tsx` (`background refresh keeps looking for the selected customer`: a newly due customer pushes the selection past the old count; cursor exhausted falls back without extra requests) |
| Review follow-up (Codex): the ineligibility notice only appears after a successful refresh, a failure is retryable | `AiAssistantPanel.test.tsx` (retry after a failed refresh, `false` treated as failure, no alert on success), `FollowUpWorkbench.ai.test.tsx` (`announces the ineligibility refresh only after it succeeds…`) |
| Contract/runtime hardening | `followUpAiApi.test.ts` (headers, `If-Match`, body, ETag parsing, unknown status / missing payload rejected) |
| Docs updated | ADR 0020, `docs/wiki/AI-and-Automation.md` ("Operator UI: AI assistance panel"), `docs/wiki/Roadmap.md`, `docs/security/security-invariants.md` #22 |

## Manual browser verification

Run with the real `<App/>` in Chrome (Browser pane) against a throwaway harness that simulated the Dokene API with `fetch` (session, two workspaces "QA Café Norte"/"QA Café Sur" with distinct customers, queue, recommendation/draft/error variants). The harness was not committed; the full Keycloak/PostgreSQL/backend stack (`compose.yaml`) was not available in this environment, so **a live OpenAI/backend round trip was not exercised**.

| Viewport | Result |
|---|---|
| 1440×900 | PASS: panel below the deterministic block; recommendation → draft; draft editor focused; long unbroken text wraps; no send/approve control; loading keeps customer context and Registrar/Posponer/Descartar visible |
| 1024×768 | Panel itself does not overflow. The page shows ~60px of horizontal overflow that **also occurs with the panel hidden** (`display:none`) and with different customer data, i.e. it comes from the existing `5fr 7fr` grid (`FollowUpWorkbench.tsx`) and is unrelated to this change; not fixed here |
| 768×1024 | PASS: stacked detail, no overflow; `AI_UNAVAILABLE` (retryable) alert with "Reintentar"; manual actions enabled |
| 390×844 | PASS: no horizontal overflow, all panel buttons ≥ 44px, draft editor focused |
| 320×568 | PASS: no horizontal overflow, panel and textarea within the viewport |

Interaction checks (same harness): workspace switch Norte → Sur removed the previous rationale and draft and showed only Sur's customer; `VIEWER` shows the button disabled with the role explanation; `403` renders the role message and keeps the request button enabled; crossing the 1024px breakpoint remounts the detail and discards the AI result (safe by design).

Not verified: keyboard-only pass with a screen reader, 200% zoom, automated axe checks (none are configured in the repository), and a run against the real backend with `dokene.ai.provider=fake`/`openai`.

## Real-stack browser verification (Chrome DevTools MCP)

Run against the real stack: Docker Compose (PostgreSQL + Keycloak), backend with `DOKENE_AI_PROVIDER=fake` on JDK 26, Vite dev server, OIDC login as `testoperator` / `testviewer` (synthetic users from `scripts/seed-local-qa.sh`) and two workspaces with due, consented customers. No live OpenAI call was made (the `fake` provider answers).

| Check | Result |
|---|---|
| Request recommendation, then draft, on real endpoints | PASS: ETag/version matched (no false stale state); `customerId`, vocabularies and body bound validated against real responses; focus moved to the draft editor; status region announced "Recomendación lista" / "Borrador listo para revisar" |
| Edited draft + policy version bumped out-of-band, then "Regenerar borrador" | Real `409`. **Defect found and fixed:** the queue refresh used the full-screen loading state, which unmounted the detail and wiped the stale notice and the edited draft. The AI-triggered refresh is now a background refresh (no spinner, no full-screen error). After the fix: `role="alert"` notice, recommendation and editor hidden, edited text kept and copyable, "Actualizar recomendación" asks for confirmation ("Consultar de nuevo descartará el borrador y tus cambios."), confirming requests a fresh recommendation with the current version |
| Workspace switch with a visible recommendation (Norte → QA 61 → Norte) | PASS: no rationale, draft or assistant panel left over, and nothing resurrected on return |
| `VIEWER` | PASS: control disabled with the role explanation; backend returns `403` for both `recommendation` and `draft` |
| Console / network | Only the intentional `409` |
| Layout 390×844 and 320×568 (mobile) | PASS: no horizontal overflow, targets ≥ 44px, no send/approve control |
| Layout 1024×768 | **Defect found and fixed:** the `5fr 7fr` grid let the detail column overflow by ~42px (pre-existing; reproduced with the panel hidden). Columns are now `minmax(0, 5fr) minmax(0, 7fr)`; `scrollWidth` equals `clientWidth` |

Regression tests for the background refresh: `FollowUpWorkbench.ai.test.tsx` (`keeps the follow-up detail and the edited draft mounted while the AI-triggered refresh is in flight`, `keeps the current queue and detail when the AI-triggered refresh fails`). The grid fix is a layout change verified in the browser only (jsdom has no layout).

### Real-stack re-verification after the Codex review fixes

Same stack as above (Compose, backend with the `fake` provider, Vite, `testoperator`), Chrome DevTools MCP:

| Check | Result |
|---|---|
| 1000 emoji pasted into the draft (2000 UTF-16 units) | PASS: accepted, counter `1000 / 1000`, no `maxlength` attribute |
| 1001 emoji | PASS: truncated to 1000 code points, no lone surrogate |
| Stale `409` while the queue request fails (`fetch` for `/api/follow-up-queue` rejected once) | PASS: alert "No pudimos actualizar la lista de seguimientos.", edited draft kept, the list still showed the old cadence and **no** "Actualizar recomendación" was offered; only "Reintentar actualizar lista" |
| Retry once the queue answers | PASS: the list showed the new cadence, the message "Actualizamos la lista…" and "Actualizar recomendación" appeared; confirming ("Consultar de nuevo descartará el borrador y tus cambios.") issued a new recommendation that succeeded |

Not exercised against the real stack: a customer selected from a second queue page (needs more than 50 due customers); it is covered by `FollowUpWorkbench.ai.test.tsx`.
