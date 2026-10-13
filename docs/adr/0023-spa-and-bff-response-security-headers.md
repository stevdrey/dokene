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
  frame-ancestors 'none'`. Spring Security sets it (`TenantSecurityConfiguration`) so it applies with or without nginx;
  nginx hides the upstream copies and sets identical values so no header is sent twice.
- Every response also carries `Permissions-Policy` denying camera, microphone, geolocation, payment, USB and FLoC.
- nginx headers live in shared snippets included by each location, because `add_header` is not inherited into a block
  that defines its own.

## Consequences

- A future `dangerouslySetInnerHTML`, injected inline script or third-party script is blocked by the browser even if
  escaping fails. Adding a third-party origin (analytics, CDN, fonts) requires amending this ADR.
- `form-action 'self'` applies to the SPA only; OIDC login and logout use top-level navigations to the BFF.
- Production edges must replicate these headers (see `infra/docker/README.md`); the BFF headers already apply there.
