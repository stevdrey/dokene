# Issue #147 message review, edit and approval component: verification record

Status: component slice only. `MessageReview` (compose and review modes) is built and proven in isolation; nothing mounts it yet. Mounting it in the Mensajes detail and behind the AI panel's **Enviar a aprobación** belongs to the composition work (scope row 4).

A fixture pass is component evidence, not backend authorization proof. Every reply below is built from the frozen contract fixtures; the server's own permission, policy and idempotency checks are proven by the backend suites and by integrated acceptance (#131), not here.

## Environment

- Date: 2026-10-20, re-run on 2026-10-21 after the second, third and fourth Codex review rounds and on 2026-10-22 after the fifth. Branch `feature/147-message-review-component`, based on `3f2dcf0`.
- Node `v26.7.0`, the repository's Vitest and Vite, jsdom.
- No backend: every reply comes from `backend/src/test/resources/contracts/messaging/v1/` (`messages.json`, `problems.json`, `requests.json`), imported with `?raw`, served by a `fetch` stub (`__tests__/messageTestServer.ts`) that fails the test on any unexpected request.

## Commands

| Command (from `frontend/`) | Result |
| --- | --- |
| `npm test` (`vitest run`) | 40 files, 872 tests passed; no console output from any `features/messages` test (intermittent `act(...)` warnings come from the existing customers and followups suites under full suite load) |
| `npm run build` (`tsc -b && vite build`) | passed; the existing chunk size warning is unchanged because nothing imports the component yet |
| `vitest run src/features/messages` | 7 files, 273 tests passed |
| `vitest run src/features/followups/__tests__/AiAssistantPanel.test.tsx` | 72 tests passed; no file under `features/followups/` changed (AC-13) |

## Automated coverage

Every `describe` block carries a `// covers: AC-N` line that traces it to spec 0011.


| Test file | Tests | What it proves |
| --- | --- | --- |
| `messageApi.test.ts` | 33 | strict ETag parser (missing, weak, unquoted, leading zero, negative, mismatched with `body.version` all refused); every detail fixture read with its version; narrowing of detail and events, including a refused body for a bad `sentAt`, `failureCategory`, `action`, `templateIntent`, `channel` or `locale`, a nested attempt with a bad `outcome`, `failureCategory` or `startedAt`, an event with a bad `failureCategory` or `attemptNumber`, a detail or command reply that describes another message than the one requested, a history that belongs to another message, and a submit reply for another customer or contact; `ApiError.retryAfterSeconds` read from a numeric `Retry-After` on 429 only; events ordered by sequence; the `submit.valid.aiDraft` body byte for byte with `If-Match`, `Idempotency-Key`, CSRF and tenant headers; approval, rejection and cancellation bodies equal to their fixtures; `/sends` with no body and no `Content-Type` (AC-3, AC-5, AC-15) |
| `messageErrors.test.ts` | 26 | aborts, tenant switches and stale sessions dropped; status alone for 401, 403, 404; network failure, uncoded 5xx, untrusted 2xx and unparseable 2xx JSON uncertain; `RATE_LIMITED` carrying its `Retry-After` seconds; coded 503 a refusal; reload codes (now including `CONTACT_NOT_OWNED`) and `RATE_LIMITED` routed to their flows; every other problem fixture refused by code; role allowlist; Spanish text for every v1 status, event type and code, including `SENT` and `APPROVAL_INVALIDATED`; phone masking that ignores formatting characters and a raw fallback for an unparseable timestamp (AC-2, AC-5, AC-6, AC-8, AC-9) |
| `MessageDetail.test.tsx` | 54 | loading, loaded, not found, error with Reintentar, invalid ETag as error (never `Revisión 0`), events failure with inline retry leaving the detail, 401; customer name or masked phone heading; outcome card without `providerMessageId`; timeline rows and hidden unapplied events; markup body rendered literally; contact removed and frozen notices, and no stranded contact warning on a message that already went out; every matrix row for an operator, including a pending message with a removed contact offering only Rechazar and Cancelar, the other acting roles, and every row read only for `VIEWER`, `null` and an unknown role; tenant guard; tenant, message and open group switching (AC-1, AC-5, AC-6, AC-12, AC-13) |
| `MessageActions.test.tsx` | 46 | approve, reject with bounded note, cancel with `{}`; approve announces "Aún no se ha enviado" and never calls `/sends`; focus on the heading after a confirmed action; Escape ignored and Cerrar disabled while a request is in flight; customer name and masked phone in the send and Corregir groups; double click with the busy confirm button aria-disabled and still focused; focus into the group, Escape and Cerrar returning focus; 403, `STALE_VERSION`, `INVALID_TRANSITION`, `APPROVAL_INVALIDATED`, policy refusal with key reuse, coded 503 without a read, 404, 401; uncertain with moved version, unchanged version and Reintentar on the same key, failed read and 404 read; every other action locked while an attempt is unsure; Cerrar keeping the unsure attempt so reopening it retries on the same key; an unsure send whose read shows `QUEUED` starts polling; polling every 2 s to a terminal status, the 20 s window, a tick that never answers ending at the deadline, unmount, a message change mid poll, frozen stop, a failed poll tick leaving Actualizar, a 404 poll tick; Actualizar reading again, keeping the detail with an alert on failure, not found on 404, two overlapping reads applying only the latest reply, and a `DELIVERED` message read again to `READ`; a repeated identical outcome announced again; new key after a temporary failure; 429 lockout announced once with the countdown outside the alert, Enviar locked for `Retry-After` seconds when present and 60 s otherwise, retry on the same key, lock kept after the group closes; after the fifth review round: `CONTACT_NOT_OWNED` on an action reading the message again so the removed contact restricts the controls, Cerrar on a rate limited group moving focus to the heading because the locked Enviar opener cannot take it, an approval replay announcing the current status, and a Corregir whose cancel reply (direct or read after an unsure cancel) carries `contactRemoved` staying on the review view with the redraft sentence (AC-7 to AC-10, AC-14) |
| `useReviewActions.test.tsx` | 5 | with a stubbed detail handle: a moved version closes an open group with "Este mensaje cambió. Revísalo de nuevo antes de continuar." and ends the send lockout; a refusal that lands after the version moved closes instead of offering a retry; an unchanged retry after a refusal keeps its key and an edited note gets a new one; an unsure attempt survives Cerrar, blocks other intents and is let go once the version moves; a 429 on approve is a refusal and leaves Enviar unlocked (AC-7, AC-10, AC-14) |
| `MessageCompose.test.tsx` | 34 | compose rendering and counter in code points; blank and over 1000 disabled; viewer read only; confirm group copy, focus, close on edit, Escape; exact fixture body and the switch to review with no detail read, announcement and `onSubmitted`; a replayed submit announcing the status the server returned; focus on the heading after a refusal; the `Retry-After` seconds on a 429; double click; every AC-4 code with the text kept; key reuse and rotation; stale with `onPolicyStale` and recovery on a new policy version, including one that arrived while the submit was in flight; unsure submit resending the same key, body and `If-Match`; 401; customer switch; Corregir success into compose with `sourcePolicyVersion` and focus on the draft heading, the correction stale sentence, refused cancel, unsure cancel reusing its key, an unsure cancel whose read shows `CANCELLED` opening compose and one whose read shows another change staying in review; no `dangerouslySetInnerHTML` under `features/messages/`; every test asserts no request reached `/sends` (AC-2 to AC-4, AC-11 to AC-13) |

## Action state matrix

| Message status | Controls (allowlisted roles) | Test |
| --- | --- | --- |
| compose | Enviar a aprobación | `MessageCompose.test.tsx` "shows the customer…" |
| `PENDING_APPROVAL`, contact present | Aprobar, Rechazar, Cancelar, Corregir | `MessageDetail.test.tsx` matrix `pendingApproval` |
| `PENDING_APPROVAL`, `contactRemoved` | Rechazar, Cancelar | "offers a pending message whose contact was removed…" |
| `APPROVED`, contact present | Enviar, Cancelar, Corregir | matrix `approved`, `approvedAfterTransientFailure` |
| `APPROVED`, `contactRemoved` | Cancelar | matrix `approvedContactRemoved` |
| `QUEUED`, `SENDING` (not frozen), `SENT` | none while polling; Actualizar otherwise | matrix `queued`, `sent`, the SENDING variant; `MessageActions.test.tsx` polling tests |
| `SENDING`, `outcomeUnknown` | Actualizar only, frozen notice | matrix `sendingOutcomeUnknown`; "stops polling when the provider outcome becomes unknown" |
| `DELIVERED` | Actualizar | matrix `delivered`; "reads a delivered message again so a later READ can show" |
| `READ`, `FAILED`, `REJECTED`, `CANCELLED` | none | matrix rows for `read`, the three failed fixtures, `rejected`, `cancelled` |
| any status, `VIEWER`, no role or unknown role | none; "Tu rol solo permite consultar mensajes." | matrix viewer rows, role `null` and `SOMETHING_ELSE` |

## Spanish messages added by this component

| Where | Text |
| --- | --- |
| submit confirm | Se enviará a aprobación exactamente el texto mostrado. Nada se envía al cliente hasta que alguien lo apruebe y lo envíe. |
| submit success | Mensaje enviado a aprobación. Aún no se ha enviado al cliente. · replay of a message that moved on: Este mensaje ya se había enviado a aprobación. Estado actual: <estado>. |
| contact removed notice | El teléfono ya no pertenece a este cliente. Este mensaje no puede avanzar; cancélalo y vuelve a redactarlo con el número correcto. |
| Corregir cancelled a message whose contact was removed meanwhile | El teléfono ya no pertenece a este cliente. Redacta un mensaje nuevo con el número correcto. |
| compose stale | La situación del cliente cambió desde que se redactó este mensaje. Actualiza el seguimiento antes de enviarlo a aprobación. |
| Corregir stale | La situación del cliente cambió. Vuelve a redactar el mensaje desde el seguimiento. |
| send confirm | Se enviará exactamente el texto mostrado arriba. |
| Corregir confirm | Corregir cancela este mensaje y abre una copia editable. El nuevo mensaje necesitará una nueva aprobación. |
| approve confirm | Se aprobará exactamente el texto mostrado. Aprobar no envía el mensaje. |
| reject and cancel confirm | El mensaje se rechazará y no se enviará al cliente. · El mensaje se cancelará y no se enviará al cliente. |
| outcomes | Mensaje aprobado. Aún no se ha enviado. · approval replay of a message that moved on: Este mensaje ya se había aprobado. Estado actual: <estado>. · Mensaje rechazado. No se enviará al cliente. · Mensaje cancelado. No se enviará al cliente. · Envío procesado. Estado actual: <estado>. |
| version moved | Este mensaje cambió. Revísalo de nuevo antes de continuar. · Este mensaje cambió: ahora está <estado>. |
| uncertain | La acción pudo haberse completado. Revisa el estado actual. · No se pudo confirmar la acción. |
| 429 | Se alcanzó el límite de envíos. Espera <n> s. (send only; n is the `Retry-After` seconds, 60 when absent; the first sentence is the alert, the count is not live) · Hay demasiadas solicitudes. Espera un momento e intenta de nuevo. (any other action) |
| session and access | Tu sesión terminó. Vuelve a iniciar sesión. · Tu rol solo permite consultar mensajes. · No tienes permiso para esta acción. |
| v1 values missing from the 0004 table | `SENT` event: Enviado al proveedor · `APPROVAL_INVALIDATED`: La aprobación ya no es válida. Revisa el mensaje de nuevo. |

The status, failure category, attempt outcome, event type, actor kind and error code labels come from the spec 0004 label table, carried into `utils/messageLabels.ts`.

## Rendered check

A throwaway harness page (Vite on port 5199, `/api` fulfilled by Playwright from the same fixtures, deleted afterwards) rendered compose, `PENDING_APPROVAL` with the reject group open, `APPROVED` with the send group open, `DELIVERED` with the outcome card, and `APPROVED` with the contact removed for a viewer, at 1280 px and 390 px in headless Chromium. No console errors and no horizontal overflow. The check led to one change: the outcome card's time labels now read "Hora de envío", "Hora de entrega" and "Hora de lectura", so "Enviado" and "Entregado" appear only as the server status.

## Decisions taken inside the spec

- The 429 lockout ends early on a version change. Closing the group keeps **Enviar** disabled until the 60 s end; the per second countdown runs only while the group is open.
- While a submit or a review action is unsure, the text and note are read only, so a retry can only resend the same request with the same key.
- While a review action is unsure, every other action is disabled. Cerrar keeps the attempt, and reopening the same action shows it again with **Reintentar** on the same key. The hold ends when the version moves, because the server state then answers whether the attempt landed.
- A changed reject or cancel note after a refusal gets a new key, the same rule as compose text.
- A 2xx whose body or `ETag` cannot be trusted is handled as uncertain (read again), because the command may have landed.
- Only a 429 on **Enviar** starts the 60 s lockout. A 429 on approve, reject, cancel or Corregir is shown as a refusal, because the send limiter is the only one that answers 429 in v1.
- A refusal that arrives after the held version moved closes the group with the version moved sentence, so a retry can never carry a stale `If-Match`.
- When a confirmed action closes its group and focus would be lost, focus moves to the message heading. Cerrar and Escape still return focus to the control that opened the group.
- The Acciones region is not rendered when there are no controls, no alert and no open group.
- The 429 alert announces the limit once. The per second count is rendered next to it, outside the live region, so assistive technology is not interrupted every second for a minute.
- When an unsure Corregir is resolved by a read that shows the message `CANCELLED`, the editable copy opens as on a direct success. A read that shows any other change stays in review with the may have completed sentence, because the cancel did not land.
- Compose focuses its heading when it opens from Corregir, because the confirm button that had focus left the page with the review view. Compose opened from props does not take focus.
- A policy version that arrives while a submit is in flight or unsure is adopted once that request settles, so a `STALE_VERSION` reply can recover from it instead of leaving the draft disabled.
- Two overlapping reads of the detail (Actualizar twice) apply only the latest reply; an earlier reply that lands later is dropped.
- A removed contact leaves only Rechazar (pending) and Cancelar, as the spec 0001 contact lifecycle rule asks: approving or sending is refused with `CONTACT_NOT_OWNED`, and Corregir would copy the removed contact into a draft that could never be submitted. The notice tells the operator to cancel and redraft with the correct number.
- `DELIVERED` offers Actualizar. Spec 0004's matrix lists it under "none", but its AC-2 rule puts Actualizar on every non terminal status without an action, and `DELIVERED` is not terminal (spec 0001: a provider report can still move it to `READ`). No polling runs on it.
- The 429 lockout lasts the `Retry-After` seconds the server sends (spec 0004 AC-5 and AC-7), read once in `httpClient` into `ApiError.retryAfterSeconds`; 60 s applies only when the header is absent or not a plain number.
- A 2xx whose JSON does not parse is uncertain, like an untrusted body: the server already answered, so the command may have landed.
- A detail or command reply that describes a different `messageId` than the one requested is refused, so no command can be sent against a message other than the one shown.
- Nested send attempts and events are narrowed member by member (`outcome`, `failureCategory`, `startedAt`, `attemptNumber`), so a bad value lands in the error state instead of being rendered as a React child.
- A replayed submit announces the status the server returned when it is past `PENDING_APPROVAL`, since another operator may have approved or sent the message before the retry.
- Each announcement replaces the live region's child node even when the sentence repeats, so a second identical outcome is announced too.
- The poll deadline also aborts a tick that never answers, so the poll cannot stay active past the 20 s window.
- Compose moves focus to its heading when a refusal closes the confirm group, the same rule the review view applies after a confirmed action.
- An events reply whose `messageId` is not the requested one, and a submit reply whose `customerId` or `contactId` is not the one submitted, are refused like any untrusted body.
- A busy confirm button is `aria-disabled` through the shared Button's loading path, never natively disabled, so focus stays on it and a refusal leaves the keyboard user in place. Only the 429 lockout disables it natively.
- `CONTACT_NOT_OWNED` on approve or send is a reload code, not a refusal: `contactRemoved` changes without a version bump (`MessageController.get`), so the component reads the message again, shows the code's sentence as the alert and lets the stranded notice and the restricted control set take over (fifth review round, 2026-10-22).
- A replayed approval announces the status the server returned when it is past `APPROVED`, the same rule the replayed submit follows (fifth review round).
- Corregir never opens the copy when the cancel reply, or the read after an unsure cancel, carries `contactRemoved`; the cancel went through, and the alert sends the operator to a fresh draft with the current contact (fifth review round).
- When Cerrar returns focus to an opener that is natively disabled (Enviar during the 429 lockout) the browser refuses it, so the heading fallback takes focus instead of the body (fifth review round).
- The stranded contact warning renders only on `PENDING_APPROVAL` and `APPROVED`. A contact removed after the message went out is history, and the sentence about cancelling would be wrong.

## Caller contract

- `MessageReview` in compose mode reads its draft props once per mount. It remounts only on a change of tenant, customer or contact (its key). A caller that changes another compose prop, such as the draft body or `sourcePolicyVersion` for the same customer, must remount it (give it a React `key`) for the change to show. The composition slice (scope row 4) owns that.

## Not covered here

- No UI path in the v1 matrix lets a poll tick or a read move the version while a confirm group is open, because polling only runs on statuses without action controls. The close on version change is proven at the hook level in `useReviewActions.test.tsx`, not through the rendered component.
- The end to end browser check against the real backend belongs to the composition slice that mounts the component and to integrated acceptance (#131).
- `@testing-library/user-event` is not installed, so keyboard tests assert native elements, focus targets, Escape and focus return, not real Tab order.
