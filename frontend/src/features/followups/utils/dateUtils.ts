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
