// ABOUTME: Unit tests for the shared strong numeric ETag parser (spec 0004 AC-2, AC-7).
// ABOUTME: Pins that only a quoted non negative safe integer becomes a version; everything else is null.

import { describe, it, expect } from 'vitest';
import { parseStrongNumericEtag } from '../etag';

describe('parseStrongNumericEtag', () => {
  it.each([
    ['"0"', 0],
    ['"1"', 1],
    ['"42"', 42],
    ['"9007199254740991"', Number.MAX_SAFE_INTEGER]
  ])('reads %s as version %i', (etag, expected) => {
    expect(parseStrongNumericEtag(etag)).toBe(expected);
  });

  it('returns null when the header is missing', () => {
    expect(parseStrongNumericEtag(null)).toBeNull();
  });

  it.each([
    ['a weak ETag', 'W/"7"'],
    ['an unquoted number', '7'],
    ['a leading zero', '"007"'],
    ['a negative number', '"-1"'],
    ['a decimal', '"1.5"'],
    ['a non numeric tag', '"abc"'],
    ['an empty string', ''],
    ['empty quotes', '""'],
    ['surrounding whitespace', ' "7" ']
  ])('returns null for %s', (_label, etag) => {
    expect(parseStrongNumericEtag(etag)).toBeNull();
  });

  it('returns null for an integer beyond the safe range so a version is never rounded', () => {
    expect(parseStrongNumericEtag('"9007199254740993"')).toBeNull();
  });
});
