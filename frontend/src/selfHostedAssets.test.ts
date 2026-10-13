import indexHtml from '../index.html?raw';
import tokensCss from './tokens.css?raw';
import { describe, expect, it } from 'vitest';

// Issue #186: the SPA is served with `default-src 'self'`; a third-party host in the entry HTML or the global
// stylesheet would be blocked by the Content-Security-Policy (and widens the trust surface), so assets stay self-hosted.
const EXTERNAL_HOST = /(?:https?:)?\/\/(?!localhost)[a-z0-9.-]+\.[a-z]{2,}/i;
// Anything but a root-relative path, a data: URI or a fragment is a relative/bare-host reference we do not expect.
const NON_LOCAL_CSS_URL = /url\(\s*['"]?(?!\/(?!\/)|data:|#)/i;
const stripComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

describe('self-hosted assets', () => {
  it.each([
    ['index.html', indexHtml],
    ['tokens.css', tokensCss]
  ])('%s references no external hosts', (_name, content) => {
    expect(content).not.toMatch(EXTERNAL_HOST);
  });

  it('tokens.css has no @import and only root-relative or data: url() references', () => {
    const css = stripComments(tokensCss);
    expect(css).not.toMatch(/@import/i);
    expect(css).not.toMatch(NON_LOCAL_CSS_URL);
  });

  it('index.html has no inline scripts or styles that the CSP would block', () => {
    expect(indexHtml).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/i);
    expect(indexHtml).not.toMatch(/<style[\s>]/i);
    expect(indexHtml).not.toMatch(/\sstyle=/i);
  });

  it('source files render no <style> elements (blocked by style-src; use bundled CSS files)', () => {
    const sources = import.meta.glob(['./**/*.{ts,tsx,jsx}', '!./**/*.test.*'], {
      query: '?raw',
      import: 'default',
      eager: true
    });
    const offenders = Object.entries(sources)
      .filter(([, source]) => /<style[\s>]/.test(stripComments(source as string)))
      .map(([file]) => file);
    expect(Object.keys(sources).length).toBeGreaterThan(10);
    expect(offenders).toEqual([]);
  });
});
