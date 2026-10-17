import { describe, it, expect } from 'vitest';
import { formatEvidenceEntry, formatWarningText } from '@/features/followups/utils/draftEvidence';

const TZ = 'America/Santiago';

describe('formatEvidenceEntry', () => {
  it('formats the purchase date instant as a Spanish calendar date', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-10T14:00:00Z', TZ)).toEqual({
      text: 'Fecha de compra: 10 ene 2026',
      raw: 'Fecha de compra: 2026-01-10T14:00:00Z',
      formatted: true
    });
  });

  it('uses the tenant time zone for the calendar day of a late UTC instant', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-11T02:00:00Z', TZ).text).toBe(
      'Fecha de compra: 10 ene 2026'
    );
  });

  it('falls back to the default time zone when the tenant zone is invalid', () => {
    expect(formatEvidenceEntry('Fecha de compra: 2026-01-10T14:00:00Z', 'Not/AZone').formatted).toBe(true);
  });

  it('matches labels case-insensitively and keeps the original label text', () => {
    expect(formatEvidenceEntry('FECHA DE COMPRA: 2026-01-10T14:00:00Z', TZ).text).toBe(
      'FECHA DE COMPRA: 10 ene 2026'
    );
    expect(formatEvidenceEntry('estado de seguimiento: OVERDUE', TZ).text).toBe('estado de seguimiento: Vencido');
  });

  it('maps follow-up statuses to Spanish labels', () => {
    expect(formatEvidenceEntry('Estado de seguimiento: OVERDUE', TZ).text).toBe('Estado de seguimiento: Vencido');
    expect(formatEvidenceEntry('Estado de seguimiento: DUE', TZ).text).toBe('Estado de seguimiento: Pendiente');
  });

  it.each([
    'Estado de seguimiento: SNOOZED',
    'Fecha de compra: ayer',
    'Fecha de compra: 2026-13-45T99:00:00Z',
    'Compra reciente: Café molido',
    'Sin separador'
  ])('leaves %s unchanged', (entry) => {
    expect(formatEvidenceEntry(entry, TZ)).toEqual({ text: entry, raw: entry, formatted: false });
  });
});

describe('formatWarningText', () => {
  it('replaces embedded instants and status tokens', () => {
    expect(formatWarningText('La compra del 2026-01-10T14:00:00Z está OVERDUE.', TZ)).toEqual({
      text: 'La compra del 10 ene 2026 está Vencido.',
      raw: 'La compra del 2026-01-10T14:00:00Z está OVERDUE.',
      formatted: true
    });
  });

  it('does not touch status words embedded in other words', () => {
    expect(formatWarningText('Revisa el campo DUE_DATE y PROCEDURE.', TZ).formatted).toBe(false);
  });

  it('leaves plain warnings unchanged', () => {
    expect(formatWarningText('Verifica el stock antes de ofrecer.', TZ)).toEqual({
      text: 'Verifica el stock antes de ofrecer.',
      raw: 'Verifica el stock antes de ofrecer.',
      formatted: false
    });
  });
});
