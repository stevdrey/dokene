// ABOUTME: Display formatting for the message review component: masked phones and es-CL date times.
// ABOUTME: Mirrors the customers feature's toLocale* usage so dates read the same across the shell.
const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
};

/** Confirm groups restate the recipient next to an action, so they show only the last four digits. */
export function maskPhone(e164: string): string {
  const digits = e164.replace(/\D/g, '');
  return `•••• ${digits.slice(-4)}`;
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('es-CL', DATE_TIME_OPTIONS);
}
