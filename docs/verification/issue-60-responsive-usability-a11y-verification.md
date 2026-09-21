# Issue #60 Manual QA Verification: Responsive Usability, Accessibility, and Recovery States

Date: 2026-09-20  
Tester: Antigravity QA Agent  
Commit Tested: `c6570a7862788e0015fbe8766157833a6998ff5a` (branch `main` / `60-manual-qa-uiux-evaluate-responsive-usability-accessibility-and-recovery-states`)  
Environment:
- OS: Linux x86_64
- Java: OpenJDK Temurin `26.0.2.1`
- Spring Boot: `4.1.1` (Modular Monolith, port `8080`)
- Database: PostgreSQL 17 (`postgres:17-alpine`) with Flyway migrations V1–V12 and PostgreSQL RLS
- OIDC Identity Provider: Keycloak `26.7.3` (`dokene-keycloak:local`, port `8081`)
- Frontend: Vite `6.4.3` / React `19.3.0` (port `5173`)
- Browser: Google Chrome 153.0.0.0 via Chrome DevTools Protocol MCP
- Test Workspace: `QA Café Norte` (`f1da89a4-580a-4b4e-baa7-2ba9b45a9b3e`)
- Authenticated Identities:
  - `testuser` (`OWNER`, identity `06b430c6-228a-435d-82b5-a26f0ca265d6`)
  - `testoperator` (`OPERATOR`, identity `30522151-0f35-4290-aeeb-96351a3b6b57`)
  - `testviewer` (`VIEWER`, identity `9c82a99b-5546-434b-a9bb-768547fe3e06`)

---

## 1. Executive Summary

This manual exploratory QA pass completes all evaluation areas and acceptance criteria for [Issue #60](https://github.com/stevdrey/dokene/issues/60). Testing evaluated the real, live local Dokene frontend and BFF stack as a human operator would use it across desktop, tablet, and mobile viewports, keyboard-driven navigation, browser zoom, accessible semantics, content boundaries, loading, empty, and failure recovery states.

### Key Observations & Verification Results:

1. **Responsive Composition & Layout (Area 1)**:
   - **Desktop (1440×900)**: Clean rendering of the 232px sidebar (`desktop-sidebar`), 56px header (`desktop-header`), and two-column follow-up workbench and customer profiles. Zero horizontal page overflow (`scrollWidth: 1420px <= innerWidth: 1420px`).
   - **Tablet Landscape (1024×768)**: Fluid reflow of both `FollowUpWorkbench` and `CustomerList`. Zero horizontal overflow (`scrollWidth: 1024px <= innerWidth: 1024px`).
   - **Tablet Portrait (768×1024)**: Uncovered Defect #1 ([Issue #82](https://github.com/stevdrey/dokene/issues/82)): `desktop-main` flex item defaults to `min-width: auto`, forcing 545px content width alongside 232px sidebar, causing `document.documentElement.scrollWidth: 777px` (9px horizontal overflow).
   - **Mobile Standard (390×844)**: Responsive shell transition functions cleanly. Desktop sidebar/header hide (`display: none`), while translucent mint `mobile-header` (`rgba(231, 255, 246, 0.9)`) and `mobile-bottom-nav` activate. Follow-up list and customer profile collapse into single-column mobile views with zero horizontal overflow (`scrollWidth: 390px = innerWidth: 390px`).
   - **Narrow Mobile (320×568)**: Follow-up workbench and profile reflow cleanly without overflow (`scrollWidth: 320px`). However, in `CustomerList`, uncovered Defect #2 ([Issue #83](https://github.com/stevdrey/dokene/issues/83)): `flex: 1 1 260px` container without `min-width: 0` causes `document.documentElement.scrollWidth: 497px` (177px overflow).
2. **Zoom & Text Scaling (Area 2)**:
   - Browser zoom at 200% evaluated on `CustomerFormModal` and primary screens. Elements reflow cleanly, modal body remains scrollable (`canScrollModal: true`), action buttons remain reachable, and no error texts are clipped.
3. **Keyboard-Only Operation (Area 3)**:
   - Complete end-to-end operator journey performed without mouse: navigation between sidebar items, tab switching (`Seguimientos`, `Clientes`), opening and operating `CustomerFormModal`, and sign-out.
   - Visible focus ring is consistently enforced on all active controls (`outline: rgb(15, 118, 110) solid 2px; outline-offset: 2px`).
   - Focus trap in `Modal.tsx` strictly confines focus inside dialog controls during `Tab` cycles (`allInsideDialog: true`).
   - Escape key cleanly dismisses modals and returns focus to the trigger button without losing focus context.
4. **Accessible Semantics (Area 4)**:
   - Landmark elements (`<main>`, `<aside aria-label="...">`, `<header>`, `<nav>`) provide clear structural navigation.
   - Form controls have associated labels (`htmlFor`).
   - Field errors are explicitly linked via `aria-describedby` (`phone-error-0`) and expose `aria-invalid="true"`, preventing reliance on color alone.
   - Loading states use `role="status"` and `aria-live="polite"`; error alerts use `role="alert"`.
5. **Visual/Readability Review (Area 5)**:
   - Evaluated `:root` design tokens against Issues #37–#39 design specifications:
     - Brand: `#005c55`
     - Primary: `#0f766e`, Hover: `#115e59`, Text: `#ffffff`
     - Surfaces: Canvas Desktop `#ffffff`, Canvas Mobile `#e7fff6`, Surface Selected `#e6f4f1`
     - Warnings: Background `#fef3c7`, Text `#92400e`
     - Errors: Background `#fef3f2`, Text `#b42318`
     - Dimensions: Sidebar `232px`, Header `56px`, Bottom Nav `64px`
   - Spanish copy is consistent, concise, and natural across Latin American conventions.
6. **Long & Unusual Content (Area 6)**:
   - Injected customer with extreme Unicode and accents: `María José ☕ Del Valle y Solís de los Andes Ñandú`. Rendered across cards, headers, and profiles without layout clipping or encoding defects.
   - Maximum 2000-character customer notes and 500-character purchase descriptions stored and rendered cleanly.
7. **Loading, Empty & Recovery States (Area 7)**:
   - Spinners for initial load and workspace switches expose accessible text.
   - Empty search queries render helpful empty state (`No se encontraron clientes. Prueba ajustando los filtros de búsqueda o registra un nuevo cliente.`).
   - Empty follow-up queue renders positive feedback (`No hay seguimientos pendientes. ¡Todo al día!`).
   - Duplicate phone validation cleanly displays localized 409 Conflict feedback without data loss.
8. **Browser Navigation & Refresh (Area 8)**:
   - Deep route refresh (F5) maintains session and workspace context without white screen or navigation loops.
9. **Pointer & Touch Ergonomics (Area 9)**:
   - Evaluated interactive targets across shell and customer list. 100% of tested buttons, selects, inputs, and nav targets meet or exceed $44 \times 44\text{ px}$ (`smallTargetsCount: 0`).

---

## 2. Screen × Viewport × Interaction Matrix

| Screen / Feature | 1440px Desktop | 1024px Tablet | 768px Tablet | 390px Mobile | 320px Mobile | Keyboard Only | Zoom 200% | Accessible Semantics | Overall Status |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **LoginView** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **NoMembershipsView** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **AppShell (Desktop)** | PASS | PASS | FAIL *(#82)* | N/A | N/A | PASS | PASS | PASS | **FAIL** *(#82)* |
| **AppShell (Mobile)** | N/A | N/A | N/A | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **WorkspaceSelector** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **FollowUpWorkbench (List)** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **FollowUpWorkbench (Detail)** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **CustomerList** | PASS | PASS | FAIL *(#82)* | PASS | FAIL *(#83)* | PASS | PASS | PASS | **FAIL** *(#82, #83)* |
| **CustomerProfile** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **CustomerFormModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **PurchaseModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **VoidPurchaseModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **SnoozeModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **DismissModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **ManualFollowUpModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **ArchiveModal** | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **PASS** |
| **Configuración (Ajustes)** | — | — | — | — | — | — | — | — | **BLOCKED** *(Placeholder)* |
| **Outbound WhatsApp Automation** | — | — | — | — | — | — | — | — | **NOT APPLICABLE** *(Out of scope)* |

---

## 3. Defects Filed

| Issue | Title | Severity / Type | Affected Viewports | Root Cause |
| :---: | :--- | :---: | :---: | :--- |
| **[#82](https://github.com/stevdrey/dokene/issues/82)** | `[UI/Layout] Horizontal overflow on 768px tablet portrait viewport caused by desktop-main default min-width: auto` | Medium / Usability | 768×1024 | `.desktop-main` flex item in `AppShell.tsx` lacks `min-width: 0`, forcing 545px intrinsic width alongside 232px sidebar (total 777px > 768px). |
| **[#83](https://github.com/stevdrey/dokene/issues/83)** | `[UI/Layout] Horizontal page overflow on narrow mobile viewports (320px) caused by CustomerList phone search flex-basis` | Medium / Responsiveness | 320×568 | In `CustomerList.tsx`, phone search container specifies `flex: 1 1 260px` without `min-width: 0`, exceeding 256px available width and expanding `scrollWidth` to 497px. |

---

## 4. Areas Skipped (Automated Coverage Non-Duplication)

In accordance with Issue #60's non-duplication requirement:
- **Component unit assertions**: Vitest suites in `frontend/src/features/**/__tests__` already authoritatively verify isolated component mounting, prop rendering, and pure unit mocks.
- **Phone number regional parsing algorithms**: Covered comprehensively by `phoneValidation.test.ts` and `CustomerFormModal.test.tsx`.
- **Backend authorization & idempotency contracts**: Covered by integration tests and verification suites for Issues #58 and #59.

---

## 5. Visual Evidence Captured

All captured evidence is stored under `docs/verification/issue-60-evidence/`:

1. `01-desktop-1440-followups-workbench.png` — Full desktop view (1440×900) showing 232px sidebar, header, and follow-up workbench layout.
2. `02-mobile-390-followups-list.png` — Mobile view (390×844) displaying mobile mint canvas, mobile header, and fixed bottom navigation.
3. `03-mobile-390-followup-detail.png` — Mobile view (390×844) demonstrating single-column stacked customer profile.
4. `04-tablet-1024-workbench-reflow.png` — Intermediate tablet landscape (1024×768) demonstrating fluid reflow without horizontal overflow.
5. `05-tablet-768-narrow-breakpoint.png` — Tablet portrait (768×1024) documenting the 9px horizontal overflow defect ([#82](https://github.com/stevdrey/dokene/issues/82)).
6. `06-narrow-320-horizontal-overflow-defect.png` — Narrow mobile (320×568) documenting the search bar overflow defect ([#83](https://github.com/stevdrey/dokene/issues/83)).
7. `07-zoom-200-customer-modal.png` — Browser zoom at 200% on `CustomerFormModal` showing readable reflow and accessible scrollable body.
8. `08-keyboard-nav-focus-rings.png` — Active keyboard navigation showing visible 2px teal focus ring (`#0f766e`) with 2px offset.
9. `09-a11y-tree-and-error-associations.png` — Accessible error state showing `aria-invalid="true"` and `aria-describedby="phone-error-0"`.
10. `10-long-unicode-content-resilience.png` — Customer profile with emoji and accented name (`María José ☕ Del Valle...`) and 2000 chars notes.
11. `11-empty-states-and-recovery.png` — Customer list empty search result showing friendly, actionable user recovery message.
