// Display-only formatting for draft evidence and warnings (Issue #194).
//
// The backend requires each evidence value to equal the authoritative field verbatim
// (ADR 0018 `Label: Value`), so the model echoes raw machine values such as
// `2026-01-10T14:00:00Z` or `OVERDUE`. These helpers only change what the operator
// reads; the draft sent or validated is never touched. Values arrive from the network,
// so anything unrecognized is returned unchanged.

export interface FormattedDraftText {
  text: string;
  raw: string;
  formatted: boolean;
}

const FOLLOW_UP_STATUS_LABELS: Record<'DUE' | 'OVERDUE', string> = {
  DUE: 'Pendiente',
  OVERDUE: 'Vencido'
};

const ISO_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})$/;
const EMBEDDED_ISO_INSTANT_PATTERN = /\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})/g;
const EMBEDDED_STATUS_PATTERN = /\b(?:OVERDUE|DUE)\b/g;

function followUpStatusLabel(value: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(FOLLOW_UP_STATUS_LABELS, value)
    ? FOLLOW_UP_STATUS_LABELS[value as keyof typeof FOLLOW_UP_STATUS_LABELS]
    : undefined;
}

function formatInstant(value: string, timeZone: string | null): string | undefined {
  if (!ISO_INSTANT_PATTERN.test(value)) {
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
export function formatEvidenceEntry(entry: string, timeZone: string | null): FormattedDraftText {
  const separator = entry.indexOf(':');
  if (separator < 0) {
    return { text: entry, raw: entry, formatted: false };
  }
  const label = entry.slice(0, separator).trim();
  const value = entry.slice(separator + 1).trim();
  const formattedValue = formatValue(label, value, timeZone);
  if (formattedValue === undefined) {
    return { text: entry, raw: entry, formatted: false };
  }
  return { text: `${label}: ${formattedValue}`, raw: entry, formatted: true };
}

/**
 * Best-effort formatting for free-text warnings: replaces embedded ISO instants and
 * upper-case follow-up status tokens, leaving the rest of the text untouched.
 */
export function formatWarningText(warning: string, timeZone: string | null): FormattedDraftText {
  const text = warning
    .replace(EMBEDDED_ISO_INSTANT_PATTERN, (match) => formatInstant(match, timeZone) ?? match)
    .replace(EMBEDDED_STATUS_PATTERN, (match) => followUpStatusLabel(match) ?? match);
  return { text, raw: warning, formatted: text !== warning };
}
