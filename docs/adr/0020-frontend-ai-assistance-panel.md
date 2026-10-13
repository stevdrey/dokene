# ADR 0020: Frontend AI Assistance Panel in the Follow-Up Workbench

## Status

Accepted

## Context

[Issue #97](https://github.com/stevdrey/dokene/issues/97). The backend already exposes Next Best Action ([ADR 0015](0015-structured-next-best-action-recommendation-contracts.md), [ADR 0017](0017-deterministic-ai-action-gate.md)), constrained draft generation ([ADR 0018](0018-constrained-follow-up-message-draft-generation.md)) and stable failure semantics ([ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md)). The follow-up workbench ([ADR 0013](0013-due-follow-up-queue-and-operator-dispositions.md), [ADR 0014](0014-frontend-authenticated-shell-and-workspace-selection.md)) had no client for them.

The UI must let an operator request, understand and edit an AI-assisted recommendation and draft without implying that anything was approved or sent (that is Phase 3), without ever reusing a result for a different tenant, customer or follow-up state, and without making the manual workflow depend on AI availability.

## Decision

### 1. Two explicit, operator-driven steps

`AiAssistantPanel` is rendered inside `FollowUpDetail`. Nothing is requested on render or selection.

1. **Obtener recomendación** calls `POST /api/customers/{id}/recommendation`.
2. Only when the status is `AVAILABLE`, **Generar borrador** calls `POST /api/customers/{id}/draft` with the recommended `action` and `templateIntent`.

Recommendation and draft are separate calls on the backend (separate permission, rate-limit quota and cost), so the UI keeps them as separate conscious decisions instead of chaining them.

### 2. Advisory and local only

- Results live in component state (`useFollowUpAssistant`). Nothing is persisted: no `localStorage`/`sessionStorage`, no cache shared across customers or tenants, no backend write.
- The draft is a local `<textarea>` limited to 1000 Unicode code points, mirroring the backend bound. The limit is applied and displayed by code point (not UTF-16 units) and the UTF-16 based `maxLength` attribute is not used, so ~1000 emoji are accepted and a surrogate pair is never split. Editing it performs no network call.
- **No approval or send path exists in Phase 2**: no "Aprobar", "Enviar", "Programar" or "Enviado" control or wording. The panel states that nothing was sent and that sending is manual. "Copiar borrador" copies the edited text to the clipboard and has no business effect.
- Model-generated strings (`rationale`, `body`, `evidence`, `warnings`) are rendered as plain React text, never as HTML.

### 3. Deterministic reason and AI output are visually and semantically separate

The existing "¿Por qué contactar hoy?" block remains the deterministic, rule-based reason. The AI output lives in its own panel and region ("Recomendación de la IA"), always labelled as AI-generated, with a textual label (not colour alone).

### 4. Staleness is explicit or derived, never silently reused

Every result stores the `ETag` version it was produced for, and requests send `If-Match: "<item.policyVersion>"`.

- **Explicit `stale` state:** a `409`, a `STALE_STATE` result or a response for another policy version, raised by the recommendation **or** the draft request, moves the panel to a `stale` step. Both the recommendation and the draft are invalidated and the workbench reloads the queue (`onRequestRefresh`). It is an explicit state rather than a stored result compared against the item version because, once the queue refresh brings the new `policyVersion`, such a result would compare equal and the old advice would look current again.
- **Derived staleness:** a valid result whose version no longer equals the item's `policyVersion` (for example after a snooze, dismissal or manual follow-up reloads the queue) is hidden the same way.
- The only way forward from a stale panel is **Actualizar recomendación**; "Obtener recomendación" is reserved for the initial request and for non-state errors (403, 429, 5xx, malformed response).
- **The refresh outcome is part of the state.** The workbench's `replaceQueue` resolves `true` only when the replacement queue was applied. While the refresh is pending the recovery action is disabled and announced ("Actualizando la lista…"); if it fails the panel says so and offers **Reintentar actualizar lista** instead of "Actualizar recomendación", so the operator can never resend the old `If-Match` and loop on `409`.
- **The refresh does not unmount the detail.** An AI-triggered refresh (`refreshLoadedQueue`, separate from the primary `replaceQueue`) shows no full-screen spinner or error and reports failure through its boolean result. It works in two phases: it first reloads as many pages as the operator had already loaded (bounded by what they paged through, so the list is never truncated), and only if the selected customer is still missing does it ask `getFollowUpEligibility` **once**: if the customer is no longer eligible, or no longer `DUE`/`OVERDUE` (only `OVERDUE` under the "Vencidos" filter), paging stops (it cannot be on a later page); if it is still due, it follows the cursor up to 20 more pages to find it; if the check fails it stops and the existing fallback selection applies. A customer selected from a later page therefore keeps its detail, stale notice and edited draft, and a customer that really left the queue costs one extra request instead of a full scan. Filter changes, workspace changes and dispositions keep the first-page behaviour.
- **Pagination waits for the refresh.** While a background refresh is in flight, "Cargar más" is disabled and `handleLoadMore` refuses to start, so a page fetched from the old cursor cannot land after (or overwrite) the refreshed pages.
- **Deterministic ineligibility also refreshes the queue.** An `INELIGIBLE` result whose evaluation is no longer eligible (archived, do-not-contact, no consent, no longer due) does not change the policy version, so it is not a `STALE_STATE`; the assistant still asks the workbench to reload the queue (reason `ineligible`) and the workbench says "{Nombre} ya no es elegible para seguimiento. La lista se ha actualizado." **only after the reload succeeded**; if it fails the panel keeps the result, shows "No pudimos actualizar la lista de seguimientos." and offers "Reintentar actualizar lista". The notice names the customer and is cleared by the operator's own actions (selecting another customer, changing the filter or the search), never by the refresh's programmatic fallback selection. An `INELIGIBLE` caused only by a disallowed action leaves the customer eligible and does not refresh.
- **Edited drafts are not lost:** the hook keeps the operator's edited text when the panel goes stale; the panel shows it in an editable "Tu borrador editado" area (copy still works) and asks for confirmation before a refresh discards it. Regenerating the draft and querying again each use their own confirmation wording.

### 5. Tenant and customer isolation

- The panel state is scoped to one customer in one workspace: the workbench is already keyed by `tenantId` in `App`, and `FollowUpDetail` is keyed by `tenantId:customerId`, so switching workspace or customer unmounts the panel and discards its state.
- In-flight requests are aborted on unmount and responses arriving after abort are ignored; `httpClient`/`apiClient` additionally abort and discard late responses on tenant change (ADR 0014).
- Only Dokene APIs are called. The browser never calls an AI provider, holds no provider key, and receives no prompt or provider metadata.

### 6. Authorization is not a UI concern

`canUseAi` (false for `VIEWER`) only disables the controls and explains why. The backend (`FOLLOWUP_EVALUATE`, `MESSAGE_DRAFT`) stays authoritative; a `403` is rendered as a clear role message.

### 7. Responses are verified before they reach the UI

The client treats API data as untrusted. `followUpApi` rejects (as `InvalidAiResponseError`) any response that has the wrong shape, an `action`/`templateIntent` outside the closed vocabularies, a draft body that is blank or longer than 1000 Unicode code points, or that does not belong to the requested customer: content-bearing results (`AVAILABLE`, `NO_RECOMMENDATION`, `NO_DRAFT`) must name the requested customer, other statuses may omit the id (the backend sends `null` without an evaluation) but never name another one, and a mismatching `evaluation.customerId` is rejected.

**Strong ETag required.** A response is only displayable when its shape is valid, it names the requested customer **and** it carries a strong numeric `ETag` (`"0"`, `"7"`, `"123"`; weak `W/"7"`, empty, unquoted, leading-zero, negative, decimal or unsafe values are rejected). A missing or malformed ETag is rejected as `InvalidAiResponseError` (generic error path); the requested version is never used as a fallback, because that would let a response without a verifiable state version be shown as current. Consequence: an intermediary that weakens or strips ETags makes the assistant show the generic error instead of advice, so deployments must preserve the backend's strong ETag (CORS already exposes it). The older lenient `parseVersionFromEtag` used by the policy and disposition endpoints is out of scope for this ADR.

### 8. AI is optional

`AI_UNAVAILABLE` results and HTTP errors never disable manual actions. Messages are mapped from the closed `unavailableReason` vocabulary of ADR 0019: a retry button appears only when `retryable` is true; `NOT_AVAILABLE` states that the assistant is not enabled for the workspace. Unknown enum values from the network fall back to neutral labels, and malformed responses (runtime shape check in `followUpApi`) become a generic error.

### 9. Accessibility

A persistent `role="status"` live region announces loading, readiness, copy results and terminal outcomes without an alert (no recommendation, ineligible, no draft); failures use `role="alert"`; focus moves to the draft editor when a draft arrives; targets and focus rings follow the shared 44px / `:focus-visible` tokens; loading never replaces the surrounding follow-up context.

## Consequences

- Operators get explainable, editable drafts with no path to an unreviewed outbound message.
- Stale advice cannot be shown as current, and cross-tenant leakage through client state is prevented by construction (scoped state + abort + tenant-keyed remount).
- The recommendation and draft are not recoverable after navigating away; this is intentional until a later phase defines durable draft state.
- Phase 3 will add approval/dispatch on top of the backend message state machine; it must supersede this ADR for any send-related UI rather than extend this panel implicitly.

## Amended by ADR 0023

[ADR 0023](0023-phase-3-outbound-messaging-contracts.md) adds exactly one control to this panel, "Enviar a aprobación", which submits the edited draft to the message approval flow. Approve and send controls live in the `messages` feature, never in this panel.
