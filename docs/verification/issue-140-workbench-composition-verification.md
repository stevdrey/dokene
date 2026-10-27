# Issue #140 workbench composition: verification record

Status: the second #140 PR. It mounts the reviewed Phase 3 components in the app: the WhatsApp integration panel (#146), message review and approval (#147) and delivery history (#148). The real-stack run, the #149 report and the release certification follow in a later PR.

## Environment

- **Run:** 2026-10-26, branch `140-compose-phase-3-workbench`, based on `9c853df`.
- **Toolchain:** the frontend's npm scripts (Vitest 5 on jsdom, TypeScript build, Vite build).
- **No backend, no network:** messaging routes are answered by `FakeServer` (`features/messages/__tests__/messageTestServer.ts`) from the frozen `contracts/messaging/v1` fixtures. Follow-up and customer reads are `vi.spyOn` stubs.

## What changed

- **Settings tab:** the placeholder is replaced by `IntegrationStatusPanel` for the active workspace. Its management controls stay gated by the panel's own OWNER/ADMIN check.
- **AI draft hand-off:** the draft editor gains "Revisar y enviar" when the host passes `onReviewDraft`. The follow-up detail passes it only for a role that may submit messages (`canActOnMessages`).
  - The button hands the current text, edits included, with the draft's action and template intent to message review. It is disabled for blank text or more than 1000 code points.
  - It never calls the network itself. The editor's note "Edición local: no se guarda ni se envía" stays true: nothing is saved until the explicit submit in review, and nothing is sent until approval and a separate send.
- **Follow-up detail:** a new `FollowUpMessaging` section, placed below the assistant, holds the review area and the customer's delivery history.
  - **Recipient:** every hand-off reads the customer's primary phone again (`customerApi.getCustomer`). That phone's id is the `contactId` the submit binds. Compose waits for that hand-off's own lookup, so neither the queue item nor an earlier hand-off can supply a stale phone. Without a primary phone it explains why and offers no compose. A failed read offers a retry.
  - **Compose:** `MessageReview` in compose mode with `origin: 'AI_DRAFT'` and the policy version **the draft was written for**, never the queue item's current one. It is keyed per hand-off, with a number that is never reused. A `STALE_VERSION` refusal asks the workbench to refresh the queue, and the compose stays stale. Only a new draft, written for the new version and handed off again, can be submitted.
  - **History:** `DeliveryHistory` for the customer, remounted after a submit so the new message is listed. "Abrir en revisión" opens that message in `MessageReview` review mode.
  - Dispositions (record, snooze, dismiss), the phone copy button and the assistant are unchanged.
- **Permissions:** `canActOnMessages` and `canReadMessages` moved from `features/messages/utils/permissions.ts` into `features/tenants/TenantContext.tsx`, beside `canWriteCustomer`. The two workbench test files now mock `useTenant` over the real module (`importOriginal`), as the customer tests already did.
- **Docs:** the caller-contract notes of the #147 and #148 records are brought up to date.

## Automated coverage

### `FollowUpWorkbench.messaging.test.tsx` (8 tests)

| Acceptance item | Test |
| --- | --- |
| No generated draft sends by itself: no message request before hand-off and confirm. The submit binds the primary phone's `contactId`, `origin: AI_DRAFT`, the draft's action, intent and body, and `If-Match` = the queue item's policy version. The history reads again after the submit. Approval sends the shown preview's `templateRevision` | `hands the AI draft to review, submits it against the policy version and approves the shown preview` |
| Operator edits are what reaches review; copying stays available | `hands off the operator edits, not the original draft, and keeps copying available` |
| A viewer gets the read-only history and no hand-off or message actions | `shows a viewer the history but no hand-off or message actions` |
| No compose without a primary phone | `refuses to compose for a customer without a primary phone` |
| Drift: a `STALE_VERSION` submit refreshes the queue. The handed-off draft is not rebound to the newer version, and submit stays disabled | `refreshes the queue on a moved policy and keeps the handed-off draft stale until a new one is written` |
| A second hand-off reads the primary phone again and binds the new contact | `reads the primary phone again for every hand-off` |
| Opt-out: `NO_CONTACT_CONSENT` shows an alert, and no approval or send request follows | `stops at an opt-out refusal without any further message request` |
| History reopens a message in review | `reopens a message from the delivery history in review` |

Every test fails on any request the fake server does not expect.

### `AiAssistantPanel.test.tsx` (1 new test)

`hands the edited draft to message review only when the host offers it, without any request`: disabled for blank text; hands off the edited text with the draft's action and intent; no fetch. The existing `never offers a way to approve or send the message` still passes, because without `onReviewDraft` the panel offers no hand-off.

Duplicate clicks, uncertain outcomes, approve and send ordering, and polling are proven on the components themselves (#147 `MessageActions.test.tsx`, `useReviewActions.test.tsx`; #148 `DeliveryHistory*.test.tsx`). The composition only mounts them.

## Results

| Command (working directory) | Result |
| --- | --- |
| `npx vitest run` (`frontend/`) | **Passed** (after the review fixes): 45 files, 1093 tests |
| `npx tsc -b` (`frontend/`) | **Passed** |
| `npm run build` (`frontend/`) | **Passed**. Vite still prints its chunk-size warning (>500 kB); this PR does not change the bundling |

A Codex review found two issues, and both are fixed:
- the compose took the queue item's newer policy version after a stale refusal, which re-enabled submitting an outdated draft (P1);
- a repeated hand-off reused the earlier phone lookup (P2).

With the old version binding put back temporarily, the stale test fails, so it guards the fix.

The first run of the new file had one failure, and the bug was in the test: it asked a viewer to generate a draft, which the assistant correctly refuses for that role. The test now checks the viewer's history without a draft.

## Not covered here

- **Real stack and live providers:** no real-stack browser run and no live Meta or OpenAI call. Both are in the #140 certification PR, on the recorded candidate SHA.
- **No manual compose:** there is no way to start a message without an AI draft in this PR.
- **History after a send:** the history refreshes itself after a submit, not after a send. Its row and list refresh controls cover a send made from review.
- **No resolution action:** there is no UI to resolve an uncertain outcome (T12). The route is added in a separate backend PR; its screen is scope item 10 and still needs a design.
