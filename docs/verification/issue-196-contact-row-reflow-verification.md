# Issue #196 — Customer contact row overflows by 7 px at 320 px in the follow-up detail

## Finding
QA suite #122 measured the overflow on `a2baddb`: the contact row of the follow-up detail (chat icon, phone number,
"WhatsApp directo" chip, copy button) was a 286 px non-wrapping row starting at x=41, so the document was 327 px wide
at a 320 px viewport (WCAG 1.4.10 Reflow).

That SHA predates PR #199 (Issue #191, `ca8cf85`), which already turned the row into
`.follow-up-detail__contact-pill` in `frontend/src/features/followups/components/FollowUpLayout.css`
(`display: inline-flex; flex-wrap: wrap; max-width: 100%`) after measuring the same row overflowing by 4 px at
320 px. PR #211 (Issue #192) additionally made `.follow-up-detail` shrinkable (`min-width: 0`). No further CSS
change is needed; this change only adds a regression test, because nothing covered the contact row.

## Change
- `frontend/src/features/followups/__tests__/FollowUpWorkbench.test.tsx`: at a 320 px window, open a customer with a
  phone and assert that the row holding the phone, the "WhatsApp directo" chip and the copy button has computed
  `flex-wrap: wrap` and `max-width: 100%` (Vitest loads the real CSS; jsdom does not compute layout, so the pixel
  result comes from the browser runs below). Mutation check: with `flex-wrap: nowrap` on the pill the test fails.

## Evidence
Code under test: `main` at `da58e20` (frontend served by the Vite dev server from this branch), backend of the QA
stack (`../dokene-qa-run`, image built from `a2baddb`; this change is frontend-only). Synthetic QA data only.
Procedure, fresh load per size: log in with the seed operator, open Seguimientos, search "Dormant", select
"Dormant Té QA", then compare `document.documentElement.scrollWidth` with `innerWidth`, measure the row's
bounding box and list every non-fixed element whose right edge exceeds the viewport. At 320 × 568 the copy button is
also clicked, so the "¡Copiado!" confirmation joins the row.

| Browser | Size | Document scroll width | Contact row | Overflowing elements |
| --- | --- | --- | --- | --- |
| Chrome 155 | 320 × 568 | 305 (vertical scrollbar) | x 41–264, 223 px wide, 2 lines | 0 |
| Chrome 155 | 320 × 568, after copy | 305 | 3 lines | 0 |
| Chrome 155 | 320 × 256 (1280 px at 400 %) | 305 | x 41–264 | 0 |
| Chrome 155 | 360 / 390 | 345 / 375 | 263 / 293 px | 0 |
| Firefox 158 | 320 × 568 | 308 (vertical scrollbar) | x 41–267, 226 px wide, 2 lines | 0 |
| Firefox 158 | 320 × 568, after copy | 308 | 3 lines | 0 |
| Firefox 158 | 320 × 256 (1280 px at 400 %) | 308 | x 41–267 | 0 |
| Firefox 158 | 360 / 390 | 348 / 378 | 266 / 296 px | 0 |

Raw output: `issue-196-evidence/chrome-320-measurements.txt`, `issue-196-evidence/firefox-320-measurements.txt`.
Screenshots at 320 px: `issue-196-evidence/chrome-320-contact-pill.png`,
`issue-196-evidence/firefox-320-contact-pill.png`. The 400 % zoom case is emulated as a 320 × 256 CSS px viewport.
The measuring scripts are a local QA harness and are not versioned (same as #191).

## Not exercised
The AI panel, the draft editor and the dialogs open at 320 px (the contact row is not inside them and no longer
overflows, so they cannot reintroduce this row's overflow; their own reflow is out of scope), real zoom instead of
viewport emulation, Safari. The sticky header at very small heights noted in the issue is not a defect and is not
addressed here.
