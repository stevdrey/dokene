// Response headers `vite preview` adds to the production build so CSP violations can be checked on a host-run build
// (`npm run build && npm run preview`). Vite dev cannot run under this CSP (HMR needs inline scripts), and the
// containerized SPA gets its headers from infra/docker/frontend/security-headers-*.conf. The two must stay identical:
// src/securityHeaders.test.ts compares them header by header.
export const spaSecurityHeaders: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
};
