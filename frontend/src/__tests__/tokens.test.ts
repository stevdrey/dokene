// ABOUTME: Guards the global CSS rule the component tests cannot see: the skip link stays above the viewport until it
// ABOUTME: is focused (#415). tokens.css is applied to jsdom (vite test.css.include) and read back as computed style.
import '../tokens.css';
import { describe, expect, it } from 'vitest';

describe('tokens.css skip link', () => {
  it('hides the skip link above the viewport until it is focused, then brings it on screen', () => {
    const link = document.createElement('a');
    link.className = 'skip-link';
    link.href = '#contenido-principal';
    link.textContent = 'Saltar al contenido';
    document.body.append(link);
    try {
      expect(getComputedStyle(link).position).toBe('absolute');
      expect(getComputedStyle(link).top).toBe('-100px');
      link.focus();
      expect(link).toHaveFocus();
      expect(getComputedStyle(link).top).not.toBe('-100px');
    } finally {
      link.remove();
    }
  });
});
