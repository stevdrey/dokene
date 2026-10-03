# Issue 97 verification: AI recommendation and editable draft in the follow-up workbench

Scope: [ADR 0020](../adr/0020-frontend-ai-assistance-panel.md). Frontend only; the backend contracts from Issues #94, #95 and #96 are consumed unchanged.

Automated: `npm test` (21 files, 261 tests), `npm run build` (`tsc -b` + Vite) in `frontend/`.

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
