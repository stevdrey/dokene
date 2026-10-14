# Issue #192 — Long customer text stays inside follow-up and profile cards

## Change

Customer names, notes and purchase descriptions remain fully readable through wrapping, including strings with no
spaces. `overflow-wrap: anywhere` and shrinkable flex/grid children prevent user-entered text from setting the page's
intrinsic width. Purchase rows, profile actions and narrow detail headings can wrap while retaining status labels and
44 px interactive targets. The profile's inner summary grid now allows a column narrower than 200 px.

The queue card's existing two-line clamp from #191 remains unchanged. No API, permission, validation, persistence,
security-header or dependency changes are included. No page-level horizontal clipping was added.

## Code and automated checks

Base: `a324dffb511fbe867ac63711644c68377577e357`. Branch:
`192-uilayout-long-unbroken-names-notes-and-purchase-descriptions-overflow-the-follow-up-detail-and-the-customer-profile`.
All implementation and verification used a separate managed worktree. The main checkout and shared Docker stack
were not changed or restarted.

- Targeted component tests: 3 files / 67 tests passed. Added full-text and CSS regression coverage for an unbroken
  80-character name and 200-character notes/descriptions; detail scenarios include 320, 390 and 1440 px.
- Final `npm test`: 25 files / 438 tests passed, with workspace-local dependencies installed from the lockfile using
  `npm ci --ignore-scripts --offline`.
- Final `npm run build`: TypeScript and Vite 8.3.3 production build passed.
- `git diff --check`: passed.
- Vite emitted a config-loader advisory about an extensionless import in its configuration and a bundle-size advisory
  for the main JavaScript chunk. Neither blocked the build.

jsdom checks preservation of the complete text and computed wrapping/shrinking rules; it does not calculate browser
layout. Pixel-level acceptance is covered by the browser measurements below.

## Browser method and results

Browsers: Chrome 155.0.8059.79 (headless CDP) and Firefox 157.0.1 (headless WebDriver BiDi), each with a fresh temporary
profile. The worktree frontend was served on `127.0.0.1:5192`, independently of the shared stack.

A temporary local fixture rendered the actual `App`, authenticated shell, follow-up workbench and customer profile.
It replaced the session/tenant and customer/follow-up API methods with synthetic responses in browser memory:
`N` repeated 80 times for the name, `Y` repeated 200 times for notes, `X` repeated 200 times for the valid purchase,
and a second normal-word voided purchase. No backend or AI-provider request was needed. The fixture and local
browser harness were not shipped as application code.

At each width, a fresh page opened the follow-up detail and then navigated through its profile button. A second pass
loaded at 1440 px and resized each view through 820, 768, 390, 320 and back to 1440 px. Fonts finished loading before
measurement. Each pass checked:

- `document.documentElement.scrollWidth <= document.documentElement.clientWidth`;
- name, notes, descriptions and the business-status text lie inside their containing cards, and their own scroll
  widths do not exceed their client widths (1 px tolerance for element rounding);
- every rendered detail/profile button stays within the document viewport and remains at least 44 × 44 px.

All **40 view/width/load-mode combinations passed**. For both views and both load modes:

| Viewport width | Chrome document client / scroll width | Firefox document client / scroll width |
| --- | --- | --- |
| 320 | 305 / 305 | 308 / 308 |
| 390 | 375 / 375 | 378 / 378 |
| 768 | 753 / 753 | 756 / 756 |
| 820 | 805 / 805 | 808 / 808 |
| 1440 | 1425 / 1425 | 1428 / 1428 |

The difference from viewport width is the vertical scrollbar. Complete per-element measurements and button bounds
are in [Chrome measurements](issue-192-evidence/chrome-measurements.json) and
[Firefox measurements](issue-192-evidence/firefox-measurements.json).

Screenshots, inspected visually:

- Chrome: [390 detail](issue-192-evidence/chrome-390-detail.png), [390 profile](issue-192-evidence/chrome-390-profile.png),
  [1440 detail](issue-192-evidence/chrome-1440-detail.png), [1440 profile](issue-192-evidence/chrome-1440-profile.png).
- Firefox: [390 detail](issue-192-evidence/firefox-390-detail.png), [390 profile](issue-192-evidence/firefox-390-profile.png),
  [1440 detail](issue-192-evidence/firefox-1440-detail.png), [1440 profile](issue-192-evidence/firefox-1440-profile.png).

## Limits

This verifies real browser layout with synthetic API responses, not a live authenticated backend journey.
No business mutations, AI generation, screen-reader session, Safari or physical mobile-device run was performed.
Widths between the sampled breakpoints were not individually measured. Existing component tests continue covering
ordinary names, purchases, loading/error states and role restrictions. No backend verification was necessary for
these presentation-only changes.
