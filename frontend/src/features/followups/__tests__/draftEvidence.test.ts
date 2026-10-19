import fixtureJson from '../../../../../backend/src/test/resources/contracts/ai/v1/draft-evidence.json?raw';
import { describe, it, expect } from 'vitest';
import {
  FORMATTED_EVIDENCE_LABELS,
  formatEvidenceEntry,
  formatWarningText
} from '@/features/followups/utils/draftEvidence';
import { DRAFT_FOLLOW_UP_STATUSES } from '@/features/followups/utils/aiLabels';

const TZ = 'America/Santiago';

describe('draft evidence contract fixture', () => {
  const fixture = JSON.parse(fixtureJson) as { formattedLabels: Record<string, string>; followUpStatuses: string[] };

  it('formats exactly the labels the backend resolves to dates and statuses', () => {
    expect(FORMATTED_EVIDENCE_LABELS).toEqual(fixture.formattedLabels);
  });

  it('knows exactly the follow-up statuses a draft can cite', () => {
    expect([...DRAFT_FOLLOW_UP_STATUSES]).toEqual(fixture.followUpStatuses);
  });
});

describe('formatEvidenceEntry', () => {
  it('formats the purchase date instant as a Spanish calendar date', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-10T14:00:00Z', TZ)).toBe('Fecha de compra: 10 ene 2026');
  });

  it('uses the tenant time zone for the calendar day of a late UTC instant', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-11T02:00:00Z', TZ)).toBe('Fecha de compra: 10 ene 2026');
  });

  it('falls back to the default time zone when the tenant zone is invalid', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-10T14:00:00Z', 'Not/AZone')).toMatch(
      /^Fecha de compra: \d{1,2} ene 2026$/
    );
  });

  it('matches labels case-insensitively and keeps the original label text', () => {
    expect(formatEvidenceEntry('FECHA DE COMPRA: 2026-01-10T14:00:00Z', TZ)).toBe('FECHA DE COMPRA: 10 ene 2026');
    expect(formatEvidenceEntry('estado de seguimiento: OVERDUE', TZ)).toBe('estado de seguimiento: Vencido');
  });

  it('maps follow-up statuses to Spanish labels', () => {
    expect(formatEvidenceEntry('Estado de seguimiento: OVERDUE', TZ)).toBe('Estado de seguimiento: Vencido');
    expect(formatEvidenceEntry('Estado de seguimiento: DUE', TZ)).toBe('Estado de seguimiento: Pendiente');
  });

  it('formats value variants the backend accepts after canonicalization', () => {
    expect(formatEvidenceEntry('Estado de seguimiento: overdue', TZ)).toBe('Estado de seguimiento: Vencido');
    expect(formatEvidenceEntry('Estado de seguimiento: OVER​DUE', TZ)).toBe('Estado de seguimiento: Vencido');
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-10t14:00:00z', TZ)).toBe('Fecha de compra: 10 ene 2026');
    expect(formatEvidenceEntry('Fecha de compra: ２026-01-10T14:00:00Z', TZ)).toBe('Fecha de compra: 10 ene 2026');
  });

  it.each([
    'Estado de seguimiento: SNOOZED',
    'Fecha de compra: ayer',
    'Fecha de compra: 2026-13-45T99:00:00Z',
    'Fecha de compra: 2026-02-30T10:00:00Z',
    'Fecha de compra: 2026-01-10T24:00:00Z',
    'Compra reciente: Café molido',
    'Sin separador'
  ])('leaves %s unchanged', (entry) => {
    expect(formatEvidenceEntry(entry, TZ)).toBe(entry);
  });
});

describe('formatWarningText', () => {
  it('replaces embedded instants and status tokens with gender-neutral wording', () => {
    expect(formatWarningText('La compra del 2026-01-10T14:00:00Z está OVERDUE.', TZ)).toBe(
      'La compra del 10 ene 2026 está con retraso.'
    );
    expect(formatWarningText('El seguimiento sigue DUE hoy.', TZ)).toBe('El seguimiento sigue pendiente hoy.');
  });

  it('formats embedded calendar dates without shifting the day', () => {
    expect(formatWarningText('Seguimiento vencido desde 2026-10-17.', TZ)).toBe('Seguimiento vencido desde 17 oct 2026.');
  });

  it('leaves calendar-invalid instants and dates unchanged instead of normalizing them', () => {
    const warning = 'Revisa 2026-02-30T10:00:00Z, 2026-01-10T24:00:00Z y 2026-02-30.';
    expect(formatWarningText(warning, TZ)).toBe(warning);
  });

  it('does not touch status letters inside other words or joined tokens', () => {
    const warning = 'Revisa DUE_DATE, DUE-DATE, OVERDUE/x, PRE-DUE, PROCEDURE y al DUEÑO del local.';
    expect(formatWarningText(warning, TZ)).toBe(warning);
  });

  it('leaves plain warnings unchanged', () => {
    expect(formatWarningText('Verifica el stock antes de ofrecer.', TZ)).toBe('Verifica el stock antes de ofrecer.');
  });
});
