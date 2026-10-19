// Display-only formatting for draft evidence and warnings (Issue #194).
//
// The backend requires each evidence value to equal the authoritative field verbatim
// (ADR 0018 `Label: Value`), so the model echoes raw machine values such as
// `2026-01-10T14:00:00Z` or `OVERDUE`. These helpers only change what the operator
// reads; the draft sent or validated is never touched. Values arrive from the network,
// so anything unrecognized is returned unchanged. The panel keeps the raw values behind
// an explicit disclosure instead of a `title` or hidden text, which screen readers would
// announce unasked.

import { followUpStatusLabel, followUpStatusPhrase } from '@/features/followups/utils/aiLabels';
import {
  formatCalendarDate,
  formatInstantDate,
  ISO_DATE_SOURCE,
  ISO_INSTANT_SOURCE
} from '@/features/followups/utils/dateUtils';

/**
 * Evidence labels whose values are formatted, as `DraftGroundingContext.sourceForLabel`
 * resolves them (pinned by `contracts/ai/v1/draft-evidence.json`).
 */
export const FORMATTED_EVIDENCE_LABELS = {
  'fecha de compra': 'PURCHASE_DATE',
  'estado de seguimiento': 'FOLLOW_UP_STATUS'
} as const;

type FormattedEvidenceKind = (typeof FORMATTED_EVIDENCE_LABELS)[keyof typeof FORMATTED_EVIDENCE_LABELS];

// Mirrors DraftSafetyValidator.FORMAT_CHARACTER_PATTERN: invisible characters the backend drops
// before comparing a cited value with the authoritative field.
const FORMAT_CHARACTERS =
  /[\p{Cf}­͏؜ᅟ-ᅠ឴-឵᠋-᠏⁥ㅤ︀-️ﾠ￰-￸\u{1BCA0}-\u{1BCA3}\u{1D173}-\u{1D17A}\u{E0000}-\u{E0FFF}]/gu;

// Tokens inside free text are bounded by Unicode-aware lookarounds (`\b` treats `Ñ` as a
// boundary in `DUEÑO`), and a hyphen or slash joined to a word (`DUE-DATE`) is not a boundary.
const BEFORE_TOKEN = '(?<![\\p{L}\\p{N}_]|[\\p{L}\\p{N}_][-/])';
const AFTER_TOKEN = '(?![\\p{L}\\p{N}_]|[-/][\\p{L}\\p{N}_])';
const EMBEDDED_INSTANT_PATTERN = new RegExp(`${BEFORE_TOKEN}${ISO_INSTANT_SOURCE}${AFTER_TOKEN}`, 'gu');
const EMBEDDED_DATE_PATTERN = new RegExp(`${BEFORE_TOKEN}${ISO_DATE_SOURCE}(?![Tt]\\d)${AFTER_TOKEN}`, 'gu');
const EMBEDDED_STATUS_PATTERN = new RegExp(`${BEFORE_TOKEN}(?:OVERDUE|DUE)${AFTER_TOKEN}`, 'gu');

/** Canonicalizes a cited value the way the backend does before its exact match (NFKC, no format characters). */
function canonicalValue(value: string): string {
  return value.normalize('NFKC').replace(FORMAT_CHARACTERS, '').normalize('NFKC').trim();
}

function formattedEvidenceKind(label: string): FormattedEvidenceKind | undefined {
  const key = label.toLowerCase();
  return Object.prototype.hasOwnProperty.call(FORMATTED_EVIDENCE_LABELS, key)
    ? FORMATTED_EVIDENCE_LABELS[key as keyof typeof FORMATTED_EVIDENCE_LABELS]
    : undefined;
}

function formatValue(kind: FormattedEvidenceKind, value: string, timeZone: string | null): string | undefined {
  const canonical = canonicalValue(value);
  switch (kind) {
    case 'PURCHASE_DATE':
      return formatInstantDate(canonical, timeZone ?? undefined) ?? undefined;
    case 'FOLLOW_UP_STATUS':
      return followUpStatusLabel(canonical.toUpperCase());
  }
}

/**
 * Formats a `Label: Value` evidence entry for known labels. Splits on the first colon
 * only (ISO times contain colons) and matches label and value case-insensitively, as the
 * backend validator does.
 */
export function formatEvidenceEntry(entry: string, timeZone: string | null): string {
  const separator = entry.indexOf(':');
  if (separator < 0) {
    return entry;
  }
  const label = entry.slice(0, separator).trim();
  const kind = formattedEvidenceKind(label);
  const formattedValue = kind === undefined ? undefined : formatValue(kind, entry.slice(separator + 1), timeZone);
  return formattedValue === undefined ? entry : `${label}: ${formattedValue}`;
}

/**
 * Best-effort formatting for free-text warnings: replaces embedded ISO instants, ISO
 * calendar dates and upper-case follow-up status tokens (with gender-neutral wording),
 * leaving the rest of the text untouched.
 */
export function formatWarningText(warning: string, timeZone: string | null): string {
  return warning
    .replace(EMBEDDED_INSTANT_PATTERN, (match) => formatInstantDate(match, timeZone ?? undefined) ?? match)
    .replace(EMBEDDED_DATE_PATTERN, (match) => formatCalendarDate(match) ?? match)
    .replace(EMBEDDED_STATUS_PATTERN, (match) => followUpStatusPhrase(match) ?? match);
}
