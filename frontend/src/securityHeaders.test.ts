import baseSnippet from '../../infra/docker/frontend/security-headers-base.conf?raw';
import spaSnippet from '../../infra/docker/frontend/security-headers-spa.conf?raw';
import { describe, expect, it } from 'vitest';
import { spaSecurityHeaders } from '../securityHeaders';

// Issue #186: the nginx snippets are the deployed source of truth for the SPA response headers; `vite preview` must
// serve the same ones, and the policy must stay free of unsafe keywords and third-party hosts.
const nginxHeaders = Object.fromEntries(
  [...(baseSnippet + spaSnippet).matchAll(/^add_header\s+(\S+)\s+"([^"]*)"/gm)].map(([, name, value]) => [name, value])
);

describe('SPA security headers', () => {
  it('vite preview headers match the nginx snippets', () => {
    expect(spaSecurityHeaders).toEqual(nginxHeaders);
  });

  it('the CSP allows only same-origin, none and data: sources', () => {
    const csp = nginxHeaders['Content-Security-Policy'];
    expect(csp).toBeDefined();
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval/);
    for (const directive of csp.split(';').map((part) => part.trim())) {
      const [name, ...sources] = directive.split(/\s+/);
      expect(sources.length, name).toBeGreaterThan(0);
      for (const source of sources) {
        expect(source, `${name} source`).toMatch(/^('self'|'none'|data:)$/);
      }
    }
  });

  it('does not reference the obsolete interest-cohort feature', () => {
    expect(nginxHeaders['Permissions-Policy']).not.toContain('interest-cohort');
  });
});
