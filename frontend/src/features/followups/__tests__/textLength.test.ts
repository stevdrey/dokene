import { describe, it, expect } from 'vitest';
import { countCodePoints, truncateToCodePoints } from '../utils/textLength';

describe('textLength', () => {
  it('counts code points, not UTF-16 units', () => {
    expect(countCodePoints('')).toBe(0);
    expect(countCodePoints('abc')).toBe(3);
    expect(countCodePoints('😀')).toBe(1);
    expect(countCodePoints('a😀b')).toBe(3);
    expect('😀'.length).toBe(2);
  });

  it('returns the text unchanged when it fits', () => {
    expect(truncateToCodePoints('hola', 10)).toBe('hola');
    expect(truncateToCodePoints('😀😀', 2)).toBe('😀😀');
  });

  it('truncates by code point without splitting a surrogate pair', () => {
    expect(truncateToCodePoints('ab😀cd', 3)).toBe('ab😀');
    expect(truncateToCodePoints('ab😀cd', 2)).toBe('ab');
    const cut = truncateToCodePoints('😀'.repeat(5), 3);
    expect(cut).toBe('😀😀😀');
    expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(cut)).toBe(false);
  });

  it('handles a zero or negative limit', () => {
    expect(truncateToCodePoints('abc', 0)).toBe('');
    expect(truncateToCodePoints('abc', -1)).toBe('');
  });
});
