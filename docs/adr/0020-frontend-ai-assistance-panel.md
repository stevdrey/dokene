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
- The draft is a local `<textarea>` (max 1000 characters, mirroring the backend bound). Editing it performs no network call.
- **No approval or send path exists in Phase 2**: no "Aprobar", "Enviar", "Programar" or "Enviado" control or wording. The panel states that nothing was sent and that sending is manual. "Copiar borrador" copies the edited text to the clipboard and has no business effect.
- Model-generated strings (`rationale`, `body`, `evidence`, `warnings`) are rendered as plain React text, never as HTML.

### 3. Deterministic reason and AI output are visually and semantically separate

The existing "¿Por qué contactar hoy?" block remains the deterministic, rule-based reason. The AI output lives in its own panel and region ("Recomendación de la IA"), always labelled as AI-generated, with a textual label (not colour alone).

### 4. Staleness is derived, never silently reused

Every result stores the `ETag` version it was produced for. The panel receives the queue item's `policyVersion`; a result is stale when `result.version !== item.policyVersion` or when the server returned `STALE_STATE`.

- A stale result is hidden (recommendation and draft) and replaced by an alert with an explicit **Actualizar recomendación** action.
- Requests send `If-Match: "<item.policyVersion>"`. A `409`, or a result produced for another version, is treated as a conflict: the result is discarded and the workbench reloads the queue (`onRequestRefresh`).
- After a disposition (snooze, dismissal, manual follow-up) the reloaded queue carries the new `policyVersion`, so previous AI output becomes stale automatically.
- Regenerating a draft (or re-querying) with unsaved edits requires an inline confirmation.

### 5. Tenant and customer isolation

- The panel state is scoped to one customer in one workspace: the workbench is already keyed by `tenantId` in `App`, and `FollowUpDetail` is keyed by `tenantId:customerId`, so switching workspace or customer unmounts the panel and discards its state.
- In-flight requests are aborted on unmount and responses arriving after abort are ignored; `httpClient`/`apiClient` additionally abort and discard late responses on tenant change (ADR 0014).
- Only Dokene APIs are called. The browser never calls an AI provider, holds no provider key, and receives no prompt or provider metadata.

### 6. Authorization is not a UI concern

`canUseAi` (false for `VIEWER`) only disables the controls and explains why. The backend (`FOLLOWUP_EVALUATE`, `MESSAGE_DRAFT`) stays authoritative; a `403` is rendered as a clear role message.

### 7. AI is optional

`AI_UNAVAILABLE` results and HTTP errors never disable manual actions. Messages are mapped from the closed `unavailableReason` vocabulary of ADR 0019: a retry button appears only when `retryable` is true; `NOT_AVAILABLE` states that the assistant is not enabled for the workspace. Unknown enum values from the network fall back to neutral labels, and malformed responses (runtime shape check in `followUpApi`) become a generic error.

### 8. Accessibility

A persistent `role="status"` live region announces loading, readiness and copy results; failures use `role="alert"`; focus moves to the draft editor when a draft arrives; targets and focus rings follow the shared 44px / `:focus-visible` tokens; loading never replaces the surrounding follow-up context.

## Consequences

- Operators get explainable, editable drafts with no path to an unreviewed outbound message.
- Stale advice cannot be shown as current, and cross-tenant leakage through client state is prevented by construction (scoped state + abort + tenant-keyed remount).
- The recommendation and draft are not recoverable after navigating away; this is intentional until a later phase defines durable draft state.
- Phase 3 will add approval/dispatch on top of the backend message state machine; it must supersede this ADR for any send-related UI rather than extend this panel implicitly.
