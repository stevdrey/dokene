# Issue #193 — Responsive assistant state preservation

Verified on 2026-10-14 in a separate worktree on branch
`193-uistate-crossing-the-1024px-breakpoint-discards-the-ai-result-and-the-operators-edited-draft`.

## Change

The workbench renders one tenant/customer-keyed detail tree at all viewport widths.
Its layout changes without unmounting the assistant. The layout is tracked with a
`matchMedia('(max-width: 1023.98px)')` change listener, so state updates only happen when the
breakpoint is crossed. Queue loading no longer depends on the layout: desktop auto-selection
lives only in the filtered-selection sync effect (a layout effect, so an applied queue never
paints without its detail), and crossing 1024px no longer retriggers the queue-loading effect.
The mobile queue is hidden with `hidden` and an explicit CSS override of its flex display; its
cards are memoized with a stable selection handler so they do not re-render while hidden.
On mobile, opening a customer (or hiding a focused queue by narrowing the viewport) moves
focus to the labelled detail region, and returning to the list restores focus to that
customer's card.
Customer/workspace changes and returning to the mobile list still discard local state.
ADR 0020 now documents resize and rotation preservation and the mobile focus behavior.

## Automated checks

With dependencies installed by `npm ci --prefer-offline --no-audit --no-fund`:

- `npm test`: 25 files, 448 tests passed.
- `npm run build`: TypeScript and Vite build passed.
- `git diff --check`: passed.

Ten new workbench tests cover desktop/mobile starting widths, editor identity and
edited text preservation, unchanged queue/purchase/AI request counts, pending
recommendation and draft requests remaining un-aborted and displaying their results,
discard on mobile back, customer change and workspace change (asserted after the
new workspace's queue reload settles), first-customer auto-selection when a mobile list
without a selection widens to desktop, and mobile focus moving into the detail and back
to the card. Tests emulate `matchMedia` from `window.innerWidth` in `src/setupTests.ts`.

The browser checks below predate the review follow-up (matchMedia tracking, focus
management and memoized cards) and were not repeated; that follow-up is covered by the
automated checks above.

The build reports existing Vite configuration and bundle-size warnings; neither blocks it.

## Browser checks

Chrome (installed executable) and Firefox 153.0 (Playwright build installed temporarily
under `/tmp`) passed a headless browser check with the actual workbench/detail/assistant
components and CSS. A temporary Vite harness supplied a synthetic workspace and replaced
queue, purchase, recommendation and draft API methods with synthetic fixtures from the tests.
No Docker stack, backend or live AI provider was used. The harness was removed afterward.

After generating and editing a draft, both browsers traversed
`1440 → 1100 → 1023 → 1024 → 1440 → 768`, with a 900px viewport height.
The edited text survived every resize. Queue, purchase, recommendation and draft calls
remained at one each. The queue used two columns at desktop widths and was invisible
below 1024px while the detail was selected. Returning to the list and selecting the
customer again correctly showed the initial recommendation action.

Screenshots were inspected for the desktop split layout and mobile detail layout:

- [Chrome desktop, 1440px](issue-193-evidence/chrome-1440.png)
- [Firefox mobile detail, 1023px](issue-193-evidence/firefox-1023.png)
