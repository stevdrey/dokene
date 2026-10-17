// Display-only formatting for draft evidence and warnings (Issue #194).
//
// The backend requires each evidence value to equal the authoritative field verbatim
// (ADR 0018 `Label: Value`), so the model echoes raw machine values such as
// `2026-01-10T14:00:00Z` or `OVERDUE`. These helpers only change what the operator
// reads; the draft sent or validated is never touched. Values arrive from the network,
// so anything unrecognized is returned unchanged. The raw value is not kept in a `title`
// or hidden text: either would expose it to screen readers, which is what #194 avoids.

const FOLLOW_UP_STATUS_LABELS: Record<'DUE' | 'OVERDUE', string> = {
  DUE: 'Pendiente',
  OVERDUE: 'Vencido'
};

const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-](\d{2}):(\d{2}))$/;
// `\b` treats accented letters as non-word characters (e.g. `DUEÑO`), so tokens are
// bounded by Unicode-aware lookarounds instead.
const EMBEDDED_ISO_INSTANT_PATTERN =
  /(?<![\p{L}\p{N}_])\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})(?![\p{L}\p{N}_])/gu;
const EMBEDDED_STATUS_PATTERN = /(?<![\p{L}\p{N}_])(?:OVERDUE|DUE)(?![\p{L}\p{N}_])/gu;

function followUpStatusLabel(value: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(FOLLOW_UP_STATUS_LABELS, value)
    ? FOLLOW_UP_STATUS_LABELS[value as keyof typeof FOLLOW_UP_STATUS_LABELS]
    : undefined;
}

// `Date` silently normalizes calendar-invalid values (`2026-02-30` becomes March 2,
// `T24:00` the next day), so the components are checked before parsing.
function isValidInstantParts(match: RegExpExecArray): boolean {
  const [, year, month, day, hour, minute, second = '0', offsetHour = '0', offsetMinute = '0'] = match;
  const calendarDay = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  return (
    calendarDay.getUTCFullYear() === Number(year) &&
    calendarDay.getUTCMonth() === Number(month) - 1 &&
    calendarDay.getUTCDate() === Number(day) &&
    Number(hour) < 24 &&
    Number(minute) < 60 &&
    Number(second) < 60 &&
    Number(offsetHour) < 24 &&
    Number(offsetMinute) < 60
  );
}

function formatInstant(value: string, timeZone: string | null): string | undefined {
  const match = ISO_INSTANT_PATTERN.exec(value);
  if (match === null || !isValidInstantParts(match)) {
    return undefined;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return undefined;
  }
  const options: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' };
  if (timeZone) {
    try {
      return new Intl.DateTimeFormat('es-CL', { ...options, timeZone }).format(date);
    } catch {
      // Unknown time zone: fall back to the browser's zone below.
    }
  }
  return new Intl.DateTimeFormat('es-CL', options).format(date);
}

function formatValue(label: string, value: string, timeZone: string | null): string | undefined {
  switch (label.toLowerCase()) {
    case 'fecha de compra':
      return formatInstant(value, timeZone);
    case 'estado de seguimiento':
      return followUpStatusLabel(value);
    default:
      return undefined;
  }
}

/**
 * Formats a `Label: Value` evidence entry for known labels. Splits on the first colon
 * only (ISO times contain colons) and matches the label case-insensitively, as the
 * backend validator does.
 */
export function formatEvidenceEntry(entry: string, timeZone: string | null): string {
  const separator = entry.indexOf(':');
  if (separator < 0) {
    return entry;
  }
  const label = entry.slice(0, separator).trim();
  const value = entry.slice(separator + 1).trim();
  const formattedValue = formatValue(label, value, timeZone);
  return formattedValue === undefined ? entry : `${label}: ${formattedValue}`;
}

/**
 * Best-effort formatting for free-text warnings: replaces embedded ISO instants and
 * upper-case follow-up status tokens, leaving the rest of the text untouched.
 */
export function formatWarningText(warning: string, timeZone: string | null): string {
  return warning
    .replace(EMBEDDED_ISO_INSTANT_PATTERN, (match) => formatInstant(match, timeZone) ?? match)
    .replace(EMBEDDED_STATUS_PATTERN, (match) => followUpStatusLabel(match) ?? match);
}
