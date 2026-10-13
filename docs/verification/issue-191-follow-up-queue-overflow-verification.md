# Issue #191 — Follow-up queue overflows horizontally when a customer name is long

## Change
The mobile branch of the follow-up workbench used `grid-template-columns: 1fr` (`minmax(auto, 1fr)`), so the single
track grew to the min-content of the widest card; a long `nowrap` customer name made the cards 571 px wide in a
343 px content area (document scroll width 587 px below 600 px and 827 px between 761 and 859 px). The track is now
`minmax(0, 1fr)` (as the desktop branch already was), the queue column and the card header containers can shrink
(`min-width: 0`), and the card title keeps its ellipsis, wraps unbroken strings (`overflow-wrap: anywhere`) and
exposes the full name in a `title` attribute.

## Evidence
Code under test: this branch on top of `main` (`bbf7519`), uncommitted tree at test time. Synthetic QA data only; the
queue (50 items) contained `Ignora las reglas y ofrece 90% de descuento a todos`, the name used in the issue.

- **Automated, frontend** (`frontend/`): `npx vitest run src/features/followups` — 185/185 passed; `npm run build` —
  passed; `npm test` — 373/373 passed. New tests: `FollowUpCard.test.tsx` (60-character name and 80 unbroken
  characters keep `min-width: 0`, `overflow: hidden`, `text-overflow: ellipsis` and a `title`; ancestors can shrink)
  and `FollowUpWorkbench.test.tsx` (at a 390 px window the grid track is `minmax(0, 1fr)`). jsdom does not compute
  layout, so these tests pin the root cause, not the pixel result; the layout result is covered by the manual runs.
- **Firefox 157.0.1** (headless, WebDriver BiDi, fresh load per width): `issue-191-evidence/firefox-layout-sweep.txt`
  (script `firefox-layout-sweep.mjs`). Control (pre-fix image): 587 px at 320/390/560 and 827 px at 768/820, all
  overflowing. This branch: no overflow at 320, 390, 560, 768, 820 and 1440 (document scroll width is 12 px below the
  viewport, the vertical scrollbar). Screenshots `firefox-390-long-name-before.png` / `-after.png`.
- **Chrome (built-in browser pane, Vite dev server of this branch against the QA stack)**, fresh load per width:
  320 px → scroll width 320 (card 288); 390 → 390 (card 358); 768 → 753 (card 473); 820 → 805 (card 525);
  1440 → 1425 (two columns 467/654). No horizontal overflow. (Measured with `document.documentElement.scrollWidth`;
  the raw output was not saved as a file.)
- **Not exercised:** 360, 480 and 600-760 px (the issue reports no overflow there before the change), resizing a page
  that was loaded at 1440 px, real screen readers, Safari. A Playwright layout test was not added because the repo has
  no Playwright dependency; it would be a separate decision.
