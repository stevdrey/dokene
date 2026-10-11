// ABOUTME: Unit tests for the Mensajes label table, badge variants and refusal sentences.
// ABOUTME: Pins the Spanish copy and the raw name fallback for unknown backend values.

import { describe, it, expect } from 'vitest';
import {
  actorKindLabel,
  attemptOutcomeLabel,
  changedSentence,
  errorCodeSentence,
  eventTypeLabel,
  failureCategoryLabel,
  originLabel,
  rateLimitedSentence,
  statusBadgeVariant,
  statusLabel
} from '../utils/messageLabels';
import { filterByKey, MESSAGE_FILTERS } from '../utils/filters';
import { formatDate, formatDateTime, maskPhone } from '../utils/format';

describe('messageLabels', () => {
  it.each([
    ['PENDING_APPROVAL', 'Pendiente de aprobación', 'warning'],
    ['APPROVED', 'Aprobado', 'neutral'],
    ['QUEUED', 'En cola', 'warning'],
    ['SENDING', 'Enviando', 'warning'],
    ['SENT', 'Enviado', 'success'],
    ['DELIVERED', 'Entregado', 'success'],
    ['READ', 'Leído', 'success'],
    ['REJECTED', 'Rechazado', 'archived'],
    ['CANCELLED', 'Cancelado', 'archived'],
    ['FAILED', 'Fallido', 'error']
  ])('labels status %s', (status, label, variant) => {
    expect(statusLabel(status)).toBe(label);
    expect(statusBadgeVariant(status)).toBe(variant);
  });

  it('falls back to the raw name and a neutral badge for an unknown status', () => {
    expect(statusLabel('ARCHIVED_LATER')).toBe('ARCHIVED_LATER');
    expect(statusBadgeVariant('ARCHIVED_LATER')).toBe('neutral');
  });

  it('labels failure categories, outcomes, event types and actors with fallbacks', () => {
    expect(failureCategoryLabel('INVALID_RECIPIENT')).toBe('Destinatario inválido');
    expect(failureCategoryLabel('SOMETHING')).toBe('SOMETHING');
    expect(attemptOutcomeLabel('FAILED_TRANSIENT')).toBe('Falló temporalmente');
    expect(attemptOutcomeLabel('NEW_OUTCOME')).toBe('NEW_OUTCOME');
    expect(eventTypeLabel('SEND_OUTCOME_UNKNOWN')).toBe('Resultado del envío desconocido');
    expect(eventTypeLabel('NEW_EVENT')).toBe('NEW_EVENT');
    expect(actorKindLabel('PROVIDER')).toBe('Proveedor');
    expect(actorKindLabel('ROBOT')).toBe('ROBOT');
    expect(originLabel('AI_DRAFT')).toBe('IA');
    expect(originLabel('MANUAL')).toBe('Manual');
  });

  it('maps every refusal code the spec lists and falls back with the code in parentheses', () => {
    expect(errorCodeSentence('MESSAGE_ALREADY_OPEN')).toBe('Ya hay un mensaje abierto para este cliente.');
    expect(errorCodeSentence('PROVIDER_UNAVAILABLE')).toBe('El proveedor de mensajería no está disponible.');
    expect(errorCodeSentence('IDEMPOTENCY_KEY_REUSED')).toBe(
      'Esta acción ya se registró con otros datos. Actualiza e intenta de nuevo.'
    );
    expect(errorCodeSentence('SOMETHING_ELSE')).toBe('No se pudo completar la acción (SOMETHING_ELSE).');
    expect(changedSentence('REJECTED')).toBe('Este mensaje cambió: ahora está Rechazado.');
    expect(rateLimitedSentence(7)).toBe('Se alcanzó el límite de envíos por minuto. Espera 7 s.');
  });
});

describe('filters', () => {
  it('opens on Pendientes and maps each chip to its statuses', () => {
    expect(MESSAGE_FILTERS.map((f) => f.label)).toEqual([
      'Pendientes',
      'Aprobados',
      'Enviados',
      'Fallidos',
      'Rechazados y cancelados',
      'Todos'
    ]);
    expect(filterByKey('sent').statuses).toEqual(['QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ']);
    expect(filterByKey('closed').statuses).toEqual(['REJECTED', 'CANCELLED']);
    expect(filterByKey('all').statuses).toEqual([]);
  });
});

describe('format', () => {
  it('masks a phone to its last four digits', () => {
    expect(maskPhone('+56984521190')).toBe('•••• 1190');
  });

  it('formats dates in es-CL and leaves an unparsable value as is', () => {
    expect(formatDate('2026-10-10T14:05:00Z')).toMatch(/2026/);
    expect(formatDateTime('2026-10-10T14:05:00Z')).toMatch(/\d{2}:\d{2}/);
    expect(formatDate('not-a-date')).toBe('not-a-date');
  });
});
