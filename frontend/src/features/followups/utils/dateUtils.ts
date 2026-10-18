/**
 * Centralized date utilities for follow-up workbench and dispositions.
 * Ensures consistent date evaluation respecting the tenant's configured time zone.
 */

/**
 * Returns a calendar date (YYYY-MM-DD) evaluated in the given time zone,
 * optionally offset by a number of days ahead.
 */
export function getCalendarDateInTimeZone(daysAhead = 0, timeZone?: string): string | null {
  if (!timeZone) {
    return null;
  }
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
  const todayDateStr = formatter.format(new Date());
  if (daysAhead === 0) {
    return todayDateStr;
  }
  const [year, month, day] = todayDateStr.split('-').map(Number);
  const target = new Date(Date.UTC(year, month - 1, day + daysAhead));
  return target.toISOString().slice(0, 10);
}

/**
 * Calculates calendar days of difference between a due date (YYYY-MM-DD)
 * and the current date in the specified time zone.
 * Returns null if the date is not in the past, invalid, or timeZone is not provided.
 */
export function computeDaysOverdueInTimeZone(dueDateStr: string, timeZone?: string): number | null {
  if (!timeZone) {
    return null;
  }
  try {
    const todayStr = getCalendarDateInTimeZone(0, timeZone);
    if (!todayStr || dueDateStr >= todayStr) {
      return null;
    }
    const [dueYear, dueMonth, dueDay] = dueDateStr.split('-').map(Number);
    const [todayYear, todayMonth, todayDay] = todayStr.split('-').map(Number);

    const dueUtc = Date.UTC(dueYear, dueMonth - 1, dueDay);
    const todayUtc = Date.UTC(todayYear, todayMonth - 1, todayDay);

    const diffDays = Math.floor((todayUtc - dueUtc) / (1000 * 60 * 60 * 24));
    return diffDays > 0 ? diffDays : null;
  } catch {
    return null;
  }
}

const SHORT_DATE_OPTIONS: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' };
const shortDateFormatters = new Map<string, Intl.DateTimeFormat>();

/** Cached `es-CL` short-date formatter; an unknown time zone falls back to the browser zone. */
function shortDateFormatter(timeZone?: string): Intl.DateTimeFormat {
  const key = timeZone ?? '';
  let formatter = shortDateFormatters.get(key);
  if (formatter === undefined) {
    try {
      formatter = new Intl.DateTimeFormat('es-CL', timeZone ? { ...SHORT_DATE_OPTIONS, timeZone } : SHORT_DATE_OPTIONS);
    } catch {
      formatter = shortDateFormatter();
    }
    shortDateFormatters.set(key, formatter);
  }
  return formatter;
}

/** ISO-8601 calendar date (`YYYY-MM-DD`) pattern source, shared with the embedded-text matchers. */
export const ISO_DATE_SOURCE = '\\d{4}-\\d{2}-\\d{2}';
/** ISO-8601 instant pattern source (`T`/`Z` in any case, as the backend compares values case-insensitively). */
export const ISO_INSTANT_SOURCE = `${ISO_DATE_SOURCE}[Tt]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:[Zz]|[+-]\\d{2}:\\d{2})`;

const ISO_DATE_PARTS = /^(\d{4})-(\d{2})-(\d{2})$/;
const ISO_INSTANT_PARTS =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-](\d{2}):(\d{2}))$/;

// `Date` silently normalizes calendar-invalid values (`2026-02-30` becomes March 2,
// `T24:00` the next day), so the components are checked before parsing.
function utcCalendarDay(year: string, month: string, day: string): Date | null {
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  return date.getUTCFullYear() === Number(year) &&
    date.getUTCMonth() === Number(month) - 1 &&
    date.getUTCDate() === Number(day)
    ? date
    : null;
}

/**
 * Formats an ISO-8601 instant as a Spanish short date (`10 ene 2026`) in the given time zone.
 * Returns null for anything that is not a calendar-valid instant.
 */
export function formatInstantDate(value: string, timeZone?: string): string | null {
  const normalized = value.toUpperCase();
  const match = ISO_INSTANT_PARTS.exec(normalized);
  if (match === null) {
    return null;
  }
  const [, year, month, day, hour, minute, second = '0', offsetHour = '0', offsetMinute = '0'] = match;
  if (
    utcCalendarDay(year, month, day) === null ||
    Number(hour) >= 24 ||
    Number(minute) >= 60 ||
    Number(second) >= 60 ||
    Number(offsetHour) >= 24 ||
    Number(offsetMinute) >= 60
  ) {
    return null;
  }
  return shortDateFormatter(timeZone).format(new Date(normalized));
}

/**
 * Formats a calendar date (`YYYY-MM-DD`) as a Spanish short date without any time-zone shift.
 * Returns null for anything that is not a valid calendar date.
 */
export function formatCalendarDate(value: string): string | null {
  const match = ISO_DATE_PARTS.exec(value);
  const date = match === null ? null : utcCalendarDay(match[1], match[2], match[3]);
  return date === null ? null : shortDateFormatter('UTC').format(date);
}
