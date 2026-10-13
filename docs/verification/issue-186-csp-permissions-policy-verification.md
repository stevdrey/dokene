# Issue #186 — Content-Security-Policy and Permissions-Policy for the SPA and BFF

## Change
nginx (SPA and proxied BFF routes) and Spring Security (BFF) now send a restrictive `Content-Security-Policy` and a
`Permissions-Policy`. Fonts are self-hosted (Inter v4.1, OFL), the Google Fonts links are gone, and the four inline
`<style>` blocks in components moved to bundled CSS files because `style-src 'self'` blocks them. Rationale and
policies: [ADR 0023](../adr/0023-spa-and-bff-response-security-headers.md).

## Evidence
Code under test: commit `9664ac2` (clean tree), based on `main` at `d9e55da`. Synthetic QA data only.

- **Automated, backend** (`backend/`, JDK 26 toolchain): `./gradlew check` — passed, including the new
  `BffResponseSecurityHeadersIntegrationTest` (CSP, Permissions-Policy, nosniff and frame denial on an
  unauthenticated `/api/session` 401).
- **Automated, frontend** (`frontend/`): `npm test` — 366/366 passed in the final run; `npm run build` — passed, and
  `dist/index.html` contains no inline script/style and no external host. `selfHostedAssets.test.ts` fails if
  `index.html` or `tokens.css` references an external host, if `index.html` has inline script/style, or if any
  component renders a `<style>` element. One earlier full run had 5 intermittent failures under heavy machine load;
  the identical suite passed on rerun (not investigated further).
- **nginx headers** — `issue-186-evidence/nginx-headers.txt`: image built from the commit above and run with `curl -I`
  on `/`, a hashed JS and CSS asset, the font, `/api/session` (401) and `/oauth2/authorization/dokene` (302). SPA
  locations carry the SPA CSP, BFF locations carry `default-src 'none'; …`, every response carries
  `Permissions-Policy`, and each header appears exactly once. Limitation: the BFF behind this test instance was the
  QA stack's backend (an older commit without the Spring headers), so the BFF values in this file came from nginx;
  the Spring side is covered by the integration test above.
- **Manual, built-in browser, SPA served by nginx with the CSP, QA stack on `localhost:5173`** (the stack's own
  frontend container was stopped for the test and restored afterwards):
  - First load of the pre-fix build reported `Applying inline style violates … style-src 'self'` (the `<style>`
    blocks). After moving them to CSS files the violation no longer appeared on reload; the served bundle has no
    `@keyframes spin` and no `<style>`.
  - OIDC login with the seed `testuser` through Keycloak returned to the SPA; the authenticated shell rendered with
    the sidebar/header layout, Material Symbols icons and Inter (`document.fonts`: both `loaded`).
  - A `securitypolicyviolation` listener stayed empty while visiting Seguimientos, Clientes, Configuración and the
    workspace selector. Console showed only the expected 401 from the pre-login `/api/session` probe.
  - Logout returned to the login screen with no CSP messages. `form-action 'self'` did not interfere with the OIDC
    top-level navigations.
- **Not exercised:** the AI assistance panel (the QA stack uses the live OpenAI provider and budget). It renders AI
  output as plain text and uses no new resource types.
