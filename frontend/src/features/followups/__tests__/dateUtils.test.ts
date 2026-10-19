import { describe, it, expect, vi, afterEach } from 'vitest';
import { formatCalendarDate, formatInstantDate } from '@/features/followups/utils/dateUtils';

describe('formatInstantDate', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('formats in the given time zone', () => {
    expect(formatInstantDate('2026-01-11T02:00:00Z', 'America/Santiago')).toBe('10 ene 2026');
    expect(formatInstantDate('2026-01-11T02:00:00Z', 'UTC')).toBe('11 ene 2026');
  });

  it('accepts fractional seconds, offsets and lower-case separators', () => {
    expect(formatInstantDate('2026-01-10T14:00:00.123456Z', 'UTC')).toBe('10 ene 2026');
    expect(formatInstantDate('2026-01-10T14:00+03:00', 'UTC')).toBe('10 ene 2026');
    expect(formatInstantDate('2026-01-10t14:00:00z', 'UTC')).toBe('10 ene 2026');
  });

  it.each(['2026-02-30T10:00:00Z', '2026-01-10T24:00:00Z', '2026-01-10T10:60:00Z', '2026-01-10', 'ayer'])(
    'returns null for %s',
    (value) => {
      expect(formatInstantDate(value, 'UTC')).toBeNull();
    }
  );

  it('falls back to the browser zone for an unknown time zone', () => {
    expect(formatInstantDate('2026-01-10T14:00:00Z', 'Not/AZone')).toMatch(/^\d{1,2} ene 2026$/);
  });

  it('reuses one formatter per time zone', () => {
    const spy = vi.spyOn(Intl, 'DateTimeFormat');
    formatInstantDate('2026-01-10T14:00:00Z', 'Asia/Tokyo');
    formatInstantDate('2026-03-10T14:00:00Z', 'Asia/Tokyo');
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe('formatCalendarDate', () => {
  it('formats a calendar date without shifting the day', () => {
    expect(formatCalendarDate('2026-10-17')).toBe('17 oct 2026');
  });

  it.each(['2026-02-30', '2026-13-01', '2026-10-17T10:00:00Z'])('returns null for %s', (value) => {
    expect(formatCalendarDate(value)).toBeNull();
  });
});
