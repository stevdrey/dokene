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
- `frontend/src/features/followups/__tests__/FollowUpWorkbench.test.tsx` (`contact row reflow`): at a 320 px window
  the test opens a customer with a phone, checks that the mobile detail rendered ("Volver a Seguimientos"), and asserts
  the computed styles that keep the row inside the viewport. The row holding the phone, the "WhatsApp directo" chip
  and the copy button must have `flex-wrap: wrap` and `max-width: 100%`. Its ancestors `.follow-up-detail__name-block`
  and `.follow-up-detail` must have `min-width: 0`, since `max-width: 100%` only bounds the row when they can shrink.
  Vitest loads the real CSS. jsdom does not compute layout, so the pixel results come from the browser runs below.
  Mutation checks: setting `flex-wrap: nowrap` on the row, or `min-width: auto` on either ancestor, makes the test
  fail.

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
`issue-196-evidence/firefox-320-contact-pill.png`. In this first run the 400 % zoom case is a 320 × 256 CSS px
viewport at device pixel ratio 1; the run below repeats it at ratio 4.
The measuring scripts are a local QA harness and are not versioned (same as #191).

### AI panel, draft editor and dialogs (the states the Issue also reported)
This uses the #122 UX-02 flow on the same stack and data: select "Dormant Té QA", then Obtener recomendación (real
OpenAI response), Generar borrador, scroll to the draft editor, open the Posponer dialog, close it with Escape, and
open the Descartar dialog. Each stage is measured with the #122 `MEASURE` helper, with the document scroll width
compared with `clientWidth`, and run at 320 × 568 and at 320 × 256 with device pixel ratio 4, which reproduces a
1280 × 1024 window at 400 % zoom.

| Browser | Size | Stages | Document scroll width / client width | Elements beyond the viewport |
| --- | --- | --- | --- | --- |
| Chrome 155 | 320 × 568 | detail, recommendation, draft, editor, Posponer, Descartar | 305 / 305 at every stage | 0 |
| Chrome 155 | 320 × 256, DPR 4 | same six stages | 305 / 305 at every stage | 0 |
| Firefox 158 | 320 × 568 | same six stages | 308 / 308 at every stage | 0 |
| Firefox 158 | 320 × 256, DPR 4 | same six stages | 308 / 308 at every stage | 0 |

The contact row keeps the same box at every stage (Chrome x 41–264, Firefox x 41–267, two lines). Raw output is in
`issue-196-evidence/chrome-320-ai-dialogs-measurements.txt` and
`issue-196-evidence/firefox-320-ai-dialogs-measurements.txt`. Screenshots:
`chrome-320x568-4-editor.png`, `chrome-320x568-5-dialog-posponer.png`, `firefox-320x568-2-recommendation.png` and
`firefox-320x568-6-dialog-descartar.png`.

## Not exercised
Safari and real screen readers. Browser zoom is reproduced with device pixel ratio 4 at 320 × 256 CSS px, not with
the browser's own zoom control. The sticky header at very small heights noted in the Issue is not a defect and is not
addressed here.
