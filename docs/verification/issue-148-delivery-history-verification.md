# Issue #148 delivery history with safe failure and uncertain outcome UX: verification record

Status: component slice only. `DeliveryHistory` is built and proven in isolation against the frozen v1 contract fixtures; nothing mounts it yet. Mounting it on the customer page and wiring **Abrir en revisión** to the review component belongs to the composition work (scope row 4), and the real callback and history integration is completed after the dispatch (#138) and webhook (#139) work lands.

A fixture pass is component evidence, not backend authorization proof. Every reply below is built from the frozen contract fixtures; the server's own tenant filter and `MESSAGE_READ` check are proven by the backend suites and by integrated acceptance (#131), not here.

## Environment

- Date: 2026-10-21. Branch `feature/148-delivery-history`, stacked on the #147 commits (`2df53be`, `390a02d`).
- Node `v26.7.0`, the repository's Vitest and Vite, jsdom.
- No backend: every reply comes from `backend/src/test/resources/contracts/messaging/v1/messages.json` and `problems.json`, imported with `?raw`, served by the existing `fetch` stub (`__tests__/messageTestServer.ts`) that fails the test on any unexpected request. `pages.firstPage` is read as it is; the other pages are built in the tests from the detail fixtures with a `listItemOf(detail)` helper that copies only the `MessageListItem` members.

## Commands

| Command (from `frontend/`) | Result |
| --- | --- |
| `npm test` (`vitest run`) | 43 files, 1028 tests passed (after the base merges and the four review rounds of 2026-10-21 and 2026-10-22) |
| `npm run build` (`tsc -b && vite build`) | passed; the existing chunk size warning is unchanged because nothing imports the component yet |
| `vitest run src/features/messages` | 10 files, 429 tests passed; no console output from any `features/messages` test |
| `git diff --stat main...HEAD` | 16 files for #148 (10 new code and test files, 4 edited, the note and the evidence folder); the branch total includes the unmerged #147 files |

## Automated coverage

Every `describe` block carries a `// covers: AC-N` line that traces it to spec 0012.

| Test file | Tests | What it proves |
| --- | --- | --- |
| `deliveryOutcome.test.ts` | 40 | the outcome table for every status and both `outcomeUnknown` values; `SENT` never contains "Entregado" or "Leído" and is never `success`; an unknown status renders raw with a `neutral` badge; a sentence for every failure category, `null` and an unlisted value falling to `UNKNOWN`; no table sentence contains "Reenviar" or "Reintentar envío"; `mergeItem` replaces only on a higher version, keeps the reference on an equal one except `contactRemoved`, drops a lower one; `listItemFromDetail` copies only list members; `canReadMessages` true for any non empty role, false for `null`, `undefined` and `''` (AC-1, AC-3, AC-5, AC-8) |
| `messageApi.test.ts` (`listCustomerHistory`) | 16 of 41 | the query in the order `customerId`, `limit`, `cursor` with no `status`; the tenant header; `pages.firstPage` and `pages.lastPage` read as they are; an item of another customer dropped; an unknown status accepted; a refused body for a missing `items`, an undefined or numeric `nextCursor`, a missing `version`, a bad `origin`, `action`, `templateIntent`, `contactRemoved` or `outcomeUnknown`, a missing `messageId` and an array body; the abort signal forwarded (AC-2) |
| `DeliveryHistory.test.tsx` | 55 | loading region and hidden **Actualizar historial** until the first page answers; every detail fixture plus a non frozen `SENDING` row with its label and secondary line; server order, action, origin, `createdAt` and the contact removed tag; the `firstPage` fixture then `lastPage` hiding **Cargar más** with no announcement and focus moved to the heading; unknown status raw; empty page; opening a row reads detail and events in parallel, keeps focus on the header, shows body, masked phone, the set timestamps with their labels, the timeline and never `providerMessageId` or attempts; one open row at a time with the closed row's reads aborted; guidance for `INVALID_RECIPIENT`, `POLICY_VIOLATION`, `UNKNOWN` and a `null` category without any control; the row patched from a higher detail version; **Abrir en revisión** only with the prop; the uncertain row with exactly one **Actualizar** collapsed and open; first load failure with **Reintentar**, network, 400, 404, uncoded 503 and bad body as the error state, 403 and 401 sentences; a row 404 closing the row, keeping the notice and removed by the next refresh; row 403, row 500 with **Reintentar** reading both again, events 500 with the detail kept and events 403 as the permission sentence; a 401 detail or events row read replacing the component; a forbidden first page with no control; a late row refresh dropped once the open row answered 404; the tenant, missing tenant and missing role guards sending nothing; `VIEWER` reading everything; a late first page and a late row read of the previous customer or tenant never rendered (the closed row's reads proven aborted through their signal); a focused first page **Reintentar** moving focus to the heading; markup rendered literally; every control a native button (AC-1 to AC-6, AC-9 to AC-12) |
| `DeliveryHistoryRefresh.test.tsx` | 35 | row **Actualizar** reading the detail, announcing "El estado cambió: ahora Fallido." on a label change and "Sin cambios desde la última lectura." on an equal version or a higher version with the same label; disabled while in flight with focus kept; 500, 403 and 404 handling with the held state kept, a 404 on an open row closing it and keeping the notice, a 401 replacing the component; an open row refresh reading detail and events again and showing the fresh timestamps; a refresh surviving the row closing and another row opening; two reads of one message answered in either order keeping the higher version (newest first, and the older refresh first with the newer own read last); an open row refresh taking only `contactRemoved` from an equal version and nothing from a lower one; **Actualizar historial** reading the open row again when it raises that row's version so header and panel agree, clearing a **Cargar más** failure when it replaces the cursor, inserting new rows on top, applying higher versions, dropping older ones, keeping the open row and its detail, replacing the cursor when no page was loaded and keeping it (and the later pages) otherwise, aborting an in flight **Cargar más** with both controls disabled, a second click and a **Cargar más** during the refresh sending nothing, a 401 replacing the component and aborting the open row reads, failure and 403 sentences, no regression of an open row from an older list version, `contactRemoved` taken from an equal version; **Cargar más** appending in server order with dedupe, a repeated item following the version rule, a 401 replacing the component, "Se cargaron 2 mensajes más.", focus kept, the same cursor on **Reintentar**, the hint after a second failure and the 403 sentence; a focused **Reintentar** that leaves with its alert handing focus to **Cargar más**, or to the heading with the last page, and an unfocused one leaving focus alone; after the second review round: an own detail read that fails after a row refresh already loaded the panel leaves the panel loaded with no alert, a `PENDING_APPROVAL` to `APPROVED` refresh announcing "El estado cambió: ahora Aún no enviado. Aprobado, sin enviar." instead of no change, and no `abort` listener left on the mount signal once three row reads settled; after the third round: a refreshed first page that shares no row with the held list starting the list again with its own cursor and closing the open row that left, and the focus assertions of the leaving **Reintentar** test waiting for the focus effect; after the fourth round: a row **Actualizar** that leaves with its own reply (resolved uncertain row, 404) handing focus to the row toggle, an unfocused one or one that stays leaving focus alone, an equal version that only flips `contactRemoved` announced as that change instead of "Sin cambios", and a status change plus a removed contact announced together (AC-2, AC-6 to AC-9, AC-12) |

The two component suites share `deliveryHistoryTestSupport.tsx`, whose `afterEach` asserts for every test that the stub saw no unexpected request, that only `GET` requests were sent, that none of `console.log`, `info`, `warn`, `error` or `debug` was called (so a React `act` or key warning fails the test) and that `Storage.prototype.setItem` was never called. jsdom has no `indexedDB`, so that part of AC-11 rests on the source: the component imports nothing that touches storage. The existing `MessageCompose.test.tsx` source guard scans every file under `features/messages/` for `dangerouslySetInnerHTML`, the new files included.

## Outcome table as rendered

| `status` (`outcomeUnknown`) | Badge | Secondary line | Controls on the collapsed row |
| --- | --- | --- | --- |
| `PENDING_APPROVAL` | Aún no enviado (warning) | Pendiente de aprobación | none |
| `APPROVED` | Aún no enviado (neutral) | Aprobado, sin enviar | none |
| `QUEUED` | En cola (warning) | Esperando el envío al proveedor | none |
| `SENDING` (false) | Enviando al proveedor (warning) | Esperando la respuesta del proveedor | none |
| `SENDING` (true) | Resultado sin confirmar (error) | El proveedor no confirmó el envío. No lo reenvíes; actualiza o pide a un administrador que lo resuelva. | Actualizar |
| `SENT` | Aceptado por el proveedor (neutral) | Aún sin confirmación de entrega | none |
| `DELIVERED` | Entregado (success) | Entregado al teléfono del cliente | none |
| `READ` | Leído (success) | El cliente abrió el mensaje | none |
| `FAILED` | Fallido (error) | No se entregó al cliente. | none (guidance in the open row) |
| `CANCELLED` | Cancelado (archived) | No se enviará al cliente | none |
| `REJECTED` | Rechazado (archived) | No se enviará al cliente | none |
| anything else | the raw status (neutral) | none | none |

Every open row adds **Actualizar** and, with the `onOpenMessage` prop, **Abrir en revisión**. An open uncertain row shows exactly one **Actualizar**. There is no resend, retry send, resolve or poll anywhere.

## Spanish messages added by this component

| Where | Text |
| --- | --- |
| heading and loading | Historial de mensajes · Cargando mensajes del cliente… |
| empty and failures | Este cliente no tiene mensajes todavía. · No se pudo cargar el historial. · No se pudieron cargar más mensajes. · Si sigue fallando, usa Actualizar historial. · No se pudo actualizar el historial. Intenta de nuevo. · No se pudo cargar el mensaje. |
| access | No tienes permiso para ver estos mensajes. |
| announcements | El estado cambió: ahora <label>. · Sin cambios desde la última lectura. · Se cargaron <n> mensajes más. |
| failure guidance | one sentence per category (spec 0012 guidance table) followed by Este mensaje no se reenviará automáticamente. |
| tags and labels | El teléfono ya no pertenece a este cliente. · Aceptado por el proveedor · Entregado · Leído (timestamp labels) |
| controls | Actualizar historial · Cargar más · Actualizar · Abrir en revisión · Reintentar |

Reused from `messageLabels.ts`: Este mensaje ya no está disponible. · Tu sesión terminó. Vuelve a iniciar sesión. · No se pudo actualizar el mensaje. Intenta de nuevo. · Cargando mensaje… · the timeline sentences.

## Rendered check

A throwaway harness page (Vite on port 5199, `/api` fulfilled by Playwright from the same fixtures, deleted afterwards) rendered the component in headless Chromium at 360 px and 1280 px. Screenshots in `issue-148-evidence/`:

| File | State |
| --- | --- |
| `collapsed-list-360.png`, `collapsed-list-1280.png` | fifteen collapsed rows covering every outcome, with **Cargar más** |
| `open-failed-row-360.png`, `open-failed-row-1280.png` | the `INVALID_RECIPIENT` row open: category, guidance, no automatic resend, masked phone, body, **Abrir en revisión**, timeline |
| `uncertain-row-360.png`, `uncertain-row-1280.png` | the frozen `SENDING` row open with its one **Actualizar** and the uncertain outcome block |

At both widths `scrollWidth` equalled `clientWidth` (no horizontal scroll) and the console carried no error or warning. Empty and error states are covered by component tests only. The check led to one change: the row hover colour was a brand tinted green that read as success on a failed or uncertain row, so it now uses the neutral inset surface.

## Decisions taken inside the spec

- Controls that are "disabled while in flight" use `aria-disabled` with the click ignored, the same choice the shared `Button` makes for its loading state, so keyboard focus is not dropped. Tests assert `aria-disabled="true"`.
- The loading sentence and the announcements share the one `role="status"` region; the announcement node is keyed by a sequence number so a repeated sentence is re inserted and announced again.
- Focus never falls to the document body: the component remembers which read the focused control started. When that control leaves with the reply (**Reintentar** unmounts with its alert, **Cargar más** with the last page), focus moves to **Cargar más** if it is still there, otherwise to the history heading (`tabindex="-1"`). An unfocused control moves nothing.
- A row marked not found keeps its header button in the DOM with `aria-disabled` and `aria-expanded="false"`, so the notice stays reachable and nothing moves under the keyboard.
- A row refresh belongs to the list: the open row receives the fresh detail through an imperative `applyRefresh` handle, applies it under the version rule, then reads its events again. An own detail read of the open row that is still in flight is not cancelled; it lands under the same rule, so the server's version decides whichever reply arrives first (changed after the 2026-10-21 review, which found the earlier arrival order rule could drop a newer reply).
- The open row takes the list row's `version` as a prop. When **Actualizar historial** raises it above the detail the panel holds, the row reads detail and events again, so the header badge and the open outcome block never disagree (added after the review).
- A list refresh that replaces the cursor (no later page loaded) also clears a held **Cargar más** failure, since that failure belonged to the old cursor. Once a later page was loaded the cursor and the failure both stay, so the AC-9 hint "usa Actualizar historial" cannot heal a bad cursor from a later page; that is a tension inside spec 0012 between AC-7 and AC-9, left for `/architect`.
- The list accepts any `status` (AC-3) but the detail read reuses the strict #147 narrowing, so a row with a status the contract does not know renders raw and opens as the row error state. The raw category branch of the guidance table is defensive only; ADR 0024 lands enum additions with the TypeScript contract in the same PR, so neither path is reachable in a consistent deployment.
- A 403 on an events read shows the permission sentence in place of the timeline; a 403 on a **Cargar más** or **Actualizar historial** shows it in that alert.
- An empty history is the `ready` state with no rows, so **Actualizar historial** is available there and a new message appears through it.
- The row **Actualizar** announcement also compares `contactRemoved`: a flipped flag is announced as "El teléfono ya no pertenece a este cliente." or "El teléfono vuelve a pertenecer a este cliente.", after the status sentence when both changed; "Sin cambios" is said only when nothing the row shows changed (fourth review round, 2026-10-22).
- The permission sentence of a forbidden first page is `role="alert"`: nothing takes focus and the status region empties, so the denial has to announce itself (fourth review round).
- A row **Actualizar** remembers whether it had focus when clicked; when its reply removes it (an uncertain row resolved, a 404) focus moves to that row's toggle, which stays in the DOM even when not found (fourth review round).
- **Actualizar historial** keeps the held rows and cursor only while the refreshed first page shares at least one row with them. A full page (one with a `nextCursor`) that shares none means more than a page of messages arrived since the last read; the held rows would sit below a gap the old cursor can never fill, so the page replaces the list, its cursor replaces the held one, and an open row that is no longer listed closes (third review round, 2026-10-22). This also resolves the AC-7/AC-9 tension noted below for that case.
- `narrowDetail` now requires string `createdAt` and `updatedAt`, since a refreshed detail is copied into the list row and a missing timestamp would blank the row's date (third review round).
- The permission sentence inside an open row (detail or events 403) is `role="alert"`, like the row error, because focus stays on the toggle while the panel loads (third review round).
- The announcement after a row **Actualizar** compares `status` and `outcomeUnknown`, not the rendered label, because `PENDING_APPROVAL` and `APPROVED` share "Aún no enviado". When the label stays the same the secondary line is appended ("El estado cambió: ahora Aún no enviado. Aprobado, sin enviar."), so the change is still heard (second review round, 2026-10-21).
- A failed own detail read of the open row never replaces a detail the panel already shows (a row refresh that landed first); the 404 and 401 paths are unchanged. The version mismatch re-read follows the same rule, so a failed re-read leaves the panel on the older detail instead of an error (second review round).
- Every list and row read forwards the mount abort through a listener that is removed when the read settles, instead of one `once` listener per read that stayed on the mount signal for the life of the component (second review round).
- The open row's reads depend only on the message id. A parent re-render with new callback identities does not read again (found by the row retry test, which saw three detail reads instead of two, and fixed before the build landed).

## Caller contract

- `DeliveryHistory` reads `tenantId`, `customerId`, `role` and `onOpenMessage` through props and remounts on a change of tenant or customer. It renders nothing when `httpClient.getTenantId()` is `null` or differs from `tenantId`, or when the role is missing. The host passes `tenantId` from the active workspace, so a workspace switch re-renders it.
- `canReadMessages` is feature local in `utils/permissions.ts` beside `canActOnMessages`; the composition work moves both into `TenantContext`.

## Not covered here

- No end to end browser check against the real backend; it belongs to the composition slice that mounts the component and to integrated acceptance (#131).
- `@testing-library/user-event` is not installed, so keyboard tests assert native buttons, focus targets and `aria-expanded`, not real Tab order.
- `indexedDB` is absent from jsdom; no code path touches it.
- Two status vocabularies now live in `features/messages/` (the #147 "Enviado" and this "Aceptado por el proveedor"); reconciling them is a spec 0012 follow up.
