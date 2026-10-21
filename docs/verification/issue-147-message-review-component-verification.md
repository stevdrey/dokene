# Issue #147 message review, edit and approval component: verification record

Status: component slice only. `MessageReview` (compose and review modes) is built and proven in isolation; nothing mounts it yet. Mounting it in the Mensajes detail and behind the AI panel's **Enviar a aprobación** belongs to the composition work (scope row 4).

A fixture pass is component evidence, not backend authorization proof. Every reply below is built from the frozen contract fixtures; the server's own permission, policy and idempotency checks are proven by the backend suites and by integrated acceptance (#131), not here.

## Environment

- Date: 2026-10-20. Branch `feature/147-message-review-component`, based on `3f2dcf0`.
- Node `v26.7.0`, the repository's Vitest and Vite, jsdom.
- No backend: every reply comes from `backend/src/test/resources/contracts/messaging/v1/` (`messages.json`, `problems.json`, `requests.json`), imported with `?raw`, served by a `fetch` stub (`__tests__/messageTestServer.ts`) that fails the test on any unexpected request.

## Commands

| Command (from `frontend/`) | Result |
| --- | --- |
| `npm test` (`vitest run`) | 40 files, 843 tests passed; no console output from any `features/messages` test (intermittent `act(...)` warnings come from the existing customers and followups suites under full suite load) |
| `npm run build` (`tsc -b && vite build`) | passed; the existing chunk size warning is unchanged because nothing imports the component yet |
| `vitest run src/features/messages` | 7 files, 244 tests passed |
| `vitest run src/features/followups/__tests__/AiAssistantPanel.test.tsx` | 72 tests passed; no file under `features/followups/` changed (AC-13) |

## Automated coverage

Every `describe` block carries a `// covers: AC-N` line that traces it to spec 0011.


| Test file | Tests | What it proves |
| --- | --- | --- |
| `messageApi.test.ts` | 23 | strict ETag parser (missing, weak, unquoted, leading zero, negative, mismatched with `body.version` all refused); every detail fixture read with its version; narrowing of detail and events, including a refused body for a bad `sentAt`, `failureCategory`, `action`, `templateIntent`, `channel` or `locale`; events ordered by sequence; the `submit.valid.aiDraft` body byte for byte with `If-Match`, `Idempotency-Key`, CSRF and tenant headers; approval, rejection and cancellation bodies equal to their fixtures; `/sends` with no body and no `Content-Type` (AC-3, AC-5, AC-15) |
| `messageErrors.test.ts` | 25 | aborts, tenant switches and stale sessions dropped; status alone for 401, 403, 404; network failure, uncoded 5xx and untrusted 2xx uncertain; coded 503 a refusal; reload codes and `RATE_LIMITED` routed to their flows; every other problem fixture refused by code; role allowlist; Spanish text for every v1 status, event type and code, including `SENT` and `APPROVAL_INVALIDATED`; phone masking that ignores formatting characters and a raw fallback for an unparseable timestamp (AC-2, AC-5, AC-6, AC-8, AC-9) |
| `MessageDetail.test.tsx` | 52 | loading, loaded, not found, error with Reintentar, invalid ETag as error (never `Revisión 0`), events failure with inline retry leaving the detail, 401; customer name or masked phone heading; outcome card without `providerMessageId`; timeline rows and hidden unapplied events; markup body rendered literally; contact removed and frozen notices; every matrix row for an operator, the other acting roles, and every row read only for `VIEWER`, `null` and an unknown role; tenant guard; tenant, message and open group switching (AC-1, AC-5, AC-6, AC-12, AC-13) |
| `MessageActions.test.tsx` | 36 | approve, reject with bounded note, cancel with `{}`; approve announces "Aún no se ha enviado" and never calls `/sends`; focus on the heading after a confirmed action; Escape ignored and Cerrar disabled while a request is in flight; customer name and masked phone in the send and Corregir groups; double click; focus into the group, Escape and Cerrar returning focus; 403, `STALE_VERSION`, `INVALID_TRANSITION`, `APPROVAL_INVALIDATED`, policy refusal with key reuse, coded 503 without a read, 404, 401; uncertain with moved version, unchanged version and Reintentar on the same key, failed read and 404 read; every other action locked while an attempt is unsure; Cerrar keeping the unsure attempt so reopening it retries on the same key; an unsure send whose read shows `QUEUED` starts polling; polling every 2 s to a terminal status, the 20 s window, unmount, a message change mid poll, frozen stop, a failed poll tick leaving Actualizar, a 404 poll tick; Actualizar reading again, keeping the detail with an alert on failure, and not found on 404; new key after a temporary failure; 429 countdown, Enviar locked, retry on the same key, lock kept after the group closes (AC-7 to AC-10, AC-14) |
| `useReviewActions.test.tsx` | 5 | with a stubbed detail handle: a moved version closes an open group with "Este mensaje cambió. Revísalo de nuevo antes de continuar." and ends the send lockout; a refusal that lands after the version moved closes instead of offering a retry; an unchanged retry after a refusal keeps its key and an edited note gets a new one; an unsure attempt survives Cerrar, blocks other intents and is let go once the version moves; a 429 on approve is a refusal and leaves Enviar unlocked (AC-7, AC-10, AC-14) |
| `MessageCompose.test.tsx` | 28 | compose rendering and counter in code points; blank and over 1000 disabled; viewer read only; confirm group copy, focus, close on edit, Escape; exact fixture body and the switch to review with no detail read, announcement and `onSubmitted`; double click; every AC-4 code with the text kept; key reuse and rotation; stale with `onPolicyStale` and recovery on a new policy version; unsure submit resending the same key, body and `If-Match`; 401; customer switch; Corregir success into compose with `sourcePolicyVersion`, the correction stale sentence, refused cancel, unsure cancel reusing its key; no `dangerouslySetInnerHTML` under `features/messages/`; every test asserts no request reached `/sends` (AC-2 to AC-4, AC-11 to AC-13) |

## Action state matrix

| Message status | Controls (allowlisted roles) | Test |
| --- | --- | --- |
| compose | Enviar a aprobación | `MessageCompose.test.tsx` "shows the customer…" |
| `PENDING_APPROVAL` | Aprobar, Rechazar, Cancelar, Corregir | `MessageDetail.test.tsx` matrix `pendingApproval` |
| `APPROVED`, contact present | Enviar, Cancelar, Corregir | matrix `approved`, `approvedAfterTransientFailure` |
| `APPROVED`, `contactRemoved` | Cancelar, Corregir | matrix `approvedContactRemoved` |
| `QUEUED`, `SENDING` (not frozen), `SENT` | none while polling; Actualizar otherwise | matrix `queued`, `sent`, the SENDING variant; `MessageActions.test.tsx` polling tests |
| `SENDING`, `outcomeUnknown` | Actualizar only, frozen notice | matrix `sendingOutcomeUnknown`; "stops polling when the provider outcome becomes unknown" |
| `DELIVERED`, `READ`, `FAILED`, `REJECTED`, `CANCELLED` | none | matrix rows for `delivered`, `read`, the three failed fixtures, `rejected`, `cancelled` |
| any status, `VIEWER`, no role or unknown role | none; "Tu rol solo permite consultar mensajes." | matrix viewer rows, role `null` and `SOMETHING_ELSE` |

## Spanish messages added by this component

| Where | Text |
| --- | --- |
| submit confirm | Se enviará a aprobación exactamente el texto mostrado. Nada se envía al cliente hasta que alguien lo apruebe y lo envíe. |
| submit success | Mensaje enviado a aprobación. Aún no se ha enviado al cliente. |
| compose stale | La situación del cliente cambió desde que se redactó este mensaje. Actualiza el seguimiento antes de enviarlo a aprobación. |
| Corregir stale | La situación del cliente cambió. Vuelve a redactar el mensaje desde el seguimiento. |
| send confirm | Se enviará exactamente el texto mostrado arriba. |
| Corregir confirm | Corregir cancela este mensaje y abre una copia editable. El nuevo mensaje necesitará una nueva aprobación. |
| approve confirm | Se aprobará exactamente el texto mostrado. Aprobar no envía el mensaje. |
| reject and cancel confirm | El mensaje se rechazará y no se enviará al cliente. · El mensaje se cancelará y no se enviará al cliente. |
| outcomes | Mensaje aprobado. Aún no se ha enviado. · Mensaje rechazado. No se enviará al cliente. · Mensaje cancelado. No se enviará al cliente. · Envío procesado. Estado actual: <estado>. |
| version moved | Este mensaje cambió. Revísalo de nuevo antes de continuar. · Este mensaje cambió: ahora está <estado>. |
| uncertain | La acción pudo haberse completado. Revisa el estado actual. · No se pudo confirmar la acción. |
| 429 | Se alcanzó el límite de envíos. Espera <n> s. (send only) · Hay demasiadas solicitudes. Espera un momento e intenta de nuevo. (any other action) |
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

## Caller contract

- `MessageReview` in compose mode reads its draft props once per mount. It remounts only on a change of tenant, customer or contact (its key). A caller that changes another compose prop, such as the draft body or `sourcePolicyVersion` for the same customer, must remount it (give it a React `key`) for the change to show. The composition slice (scope row 4) owns that.

## Not covered here

- No UI path in the v1 matrix lets a poll tick or a read move the version while a confirm group is open, because polling only runs on statuses without action controls. The close on version change is proven at the hook level in `useReviewActions.test.tsx`, not through the rendered component.
- The end to end browser check against the real backend belongs to the composition slice that mounts the component and to integrated acceptance (#131).
- `@testing-library/user-event` is not installed, so keyboard tests assert native elements, focus targets, Escape and focus return, not real Tab order.
