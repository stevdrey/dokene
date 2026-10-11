// ABOUTME: Display formatting for the Mensajes feature: masked phones and es-CL dates.
// ABOUTME: Mirrors the customers feature's toLocale* usage so dates read the same across the shell.

const DATE_OPTIONS: Intl.DateTimeFormatOptions = { day: '2-digit', month: 'short', year: 'numeric' };
const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
};

/** The list is a wide surface, so it shows only the last four digits of the E.164 number. */
export function maskPhone(e164: string): string {
  const digits = e164.replace(/\D/g, '');
  return `•••• ${digits.slice(-4)}`;
}

export function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString('es-CL', DATE_OPTIONS);
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('es-CL', DATE_TIME_OPTIONS);
}
