# ADR 0023: SPA and BFF response security headers

## Status

Accepted

## Context

QA suite #121 (SEC-07) found no exploitable XSS, but React's output escaping was the only layer protecting the operator
UI (security invariant 22). Neither the nginx entry point nor the Spring BFF sent a `Content-Security-Policy` or a
`Permissions-Policy`. ADR 0022 had deferred a CSP because the SPA loaded Inter from Google Fonts.

## Decision

- All static SPA responses (`/`, `/assets/*`) carry `Content-Security-Policy: default-src 'self'; script-src 'self';
  style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none';
  form-action 'self'; frame-ancestors 'none'`. No `'unsafe-inline'` or `'unsafe-eval'`; React applies `style={...}`
  through the CSSOM, which `style-src 'self'` does not block.
- Components must not render `<style>` elements (blocked by `style-src 'self'`); layout rules live in CSS files bundled by
  Vite. A frontend test enforces this.
- Fonts are self-hosted (`frontend/public/fonts/`); the SPA references no third-party host. A frontend test fails if
  `index.html` or `tokens.css` reintroduces one.
- BFF responses (`/api`, `/oauth2`, `/login`, `/logout`) carry `default-src 'none'; base-uri 'none'; form-action 'none';
  frame-ancestors 'none'`, plus nosniff, frame denial and referrer policy. Spring Security is the single owner of these
  headers (`TenantSecurityConfiguration`); nginx passes them through untouched and adds none of its own on those routes.
- Every response also carries `Permissions-Policy` denying camera, microphone, geolocation, payment and USB.
- SPA headers live in nginx snippets included by each static location, because `add_header` is not inherited into a
  block that defines its own. `scripts/tests/nginx-headers-smoke.sh` (run in the Frontend CI workflow) builds the image
  and asserts one CSP and one Permissions-Policy per static response, same-origin-only sources and pass-through on BFF
  routes. `vite preview` serves the production build with identical headers (`frontend/securityHeaders.ts`, compared
  header by header with the snippets by `securityHeaders.test.ts`); `npm run dev` cannot run under this CSP (HMR needs
  inline scripts), so check CSP violations with `npm run build && npm run preview`.
- Fonts are unhashed and cached for 30 days; changing a font means renaming the file.

## Consequences

- A future `dangerouslySetInnerHTML`, injected inline script or third-party script is blocked by the browser even if
  escaping fails. Adding a third-party origin (analytics, CDN, fonts) requires amending this ADR.
- `form-action 'self'` applies to the SPA only; OIDC login and logout use top-level navigations to the BFF.
- Production edges must replicate these headers (see `infra/docker/README.md`); the BFF headers already apply there.
