// ABOUTME: Tests the delivery history tables of spec 0012: the outcome map, the failure guidance, the version rule and read access.
// ABOUTME: Proves provider acceptance never reads as delivered and that no sentence in the tables offers a resend.
import { describe, expect, it } from 'vitest';
import { FAILURE_CATEGORIES, MESSAGE_STATUSES, type MessageListItem } from '../types';
import {
  HISTORY_COPY,
  deliveryOutcome,
  failureGuidance,
  loadedMoreSentence,
  outcomeChangedSentence
} from '../utils/deliveryOutcome';
import { listItemFromDetail, mergeItem } from '../utils/mergeItem';
import { canReadMessages } from '../utils/permissions';
import { details } from './messageTestServer';

// covers: AC-3
describe('deliveryOutcome', () => {
  const table: [string, boolean, string, string, string | null, string][] = [
    ['PENDING_APPROVAL', false, 'notSent', 'Aún no enviado', 'Pendiente de aprobación', 'warning'],
    ['APPROVED', false, 'notSent', 'Aún no enviado', 'Aprobado, sin enviar', 'neutral'],
    ['QUEUED', false, 'queued', 'En cola', 'Esperando el envío al proveedor', 'warning'],
    ['SENDING', false, 'sending', 'Enviando al proveedor', 'Esperando la respuesta del proveedor', 'warning'],
    [
      'SENDING',
      true,
      'uncertain',
      'Resultado sin confirmar',
      'El proveedor no confirmó el envío. No lo reenvíes; actualiza o pide a un administrador que lo resuelva.',
      'error'
    ],
    ['SENT', false, 'accepted', 'Aceptado por el proveedor', 'Aún sin confirmación de entrega', 'neutral'],
    ['DELIVERED', false, 'delivered', 'Entregado', 'Entregado al teléfono del cliente', 'success'],
    ['READ', false, 'read', 'Leído', 'El cliente abrió el mensaje', 'success'],
    ['FAILED', false, 'failed', 'Fallido', 'No se entregó al cliente.', 'error'],
    ['CANCELLED', false, 'cancelled', 'Cancelado', 'No se enviará al cliente', 'archived'],
    ['REJECTED', false, 'rejected', 'Rechazado', 'No se enviará al cliente', 'archived']
  ];

  it.each(table)('reads %s (outcomeUnknown %s) as %s', (status, unknown, outcome, label, secondary, variant) => {
    expect(deliveryOutcome(status, unknown)).toEqual({ outcome, label, secondary, variant });
  });

  it('gives every contract status its own label', () => {
    const labels = MESSAGE_STATUSES.map((status) => deliveryOutcome(status, false).label);
    expect(new Set(labels).size).toBe(MESSAGE_STATUSES.length - 1);
    expect(deliveryOutcome('PENDING_APPROVAL', false).label).toBe(deliveryOutcome('APPROVED', false).label);
  });

  it('ignores outcomeUnknown on any status other than SENDING', () => {
    for (const status of MESSAGE_STATUSES.filter((value) => value !== 'SENDING')) {
      expect(deliveryOutcome(status, true)).toEqual(deliveryOutcome(status, false));
    }
  });

  it('never lets provider acceptance read as delivered or read, nor as a success', () => {
    const accepted = deliveryOutcome('SENT', false);
    const text = `${accepted.label} ${accepted.secondary}`;
    expect(text).not.toMatch(/Entregado|Leído/);
    expect(accepted.variant).not.toBe('success');
  });

  it('renders an unknown status raw with a neutral badge and no secondary line', () => {
    expect(deliveryOutcome('ARCHIVED_LATER', false)).toEqual({
      outcome: 'unknown',
      label: 'ARCHIVED_LATER',
      secondary: null,
      variant: 'neutral'
    });
  });
});

// covers: AC-5
describe('failureGuidance', () => {
  it.each(FAILURE_CATEGORIES)('has a sentence for %s', (category) => {
    expect(failureGuidance(category)).toMatch(/\.$/);
  });

  it('gives every known category its own sentence', () => {
    expect(new Set(FAILURE_CATEGORIES.map(failureGuidance)).size).toBe(FAILURE_CATEGORIES.length);
  });

  it('uses the UNKNOWN guidance for null and for an unlisted category', () => {
    expect(failureGuidance(null)).toBe(failureGuidance('UNKNOWN'));
    expect(failureGuidance('SOMETHING_NEW')).toBe(failureGuidance('UNKNOWN'));
  });

  it('never offers a resend in any sentence of the tables', () => {
    const sentences = [
      ...FAILURE_CATEGORIES.map(failureGuidance),
      ...MESSAGE_STATUSES.flatMap((status) => [true, false].map((unknown) => deliveryOutcome(status, unknown))).flatMap(
        (outcome) => [outcome.label, outcome.secondary ?? '']
      ),
      ...Object.values(HISTORY_COPY),
      outcomeChangedSentence('Fallido'),
      loadedMoreSentence(3)
    ];
    for (const sentence of sentences) {
      expect(sentence).not.toMatch(/Reenviar|Reintentar envío/);
    }
  });

  it('builds the announcement sentences', () => {
    expect(outcomeChangedSentence('Entregado')).toBe('El estado cambió: ahora Entregado.');
    expect(loadedMoreSentence(4)).toBe('Se cargaron 4 mensajes más.');
  });
});

// covers: AC-8
describe('mergeItem', () => {
  const held: MessageListItem = listItemFromDetail(details.sent);

  it('replaces the held item with a higher version', () => {
    const incoming = { ...held, status: 'DELIVERED' as const, version: held.version + 1 };
    expect(mergeItem(held, incoming)).toBe(incoming);
  });

  it('keeps the held item on an equal version and returns the same reference', () => {
    const incoming = { ...held, status: 'DELIVERED' as const, updatedAt: '2026-10-14T00:00:00Z' };
    expect(mergeItem(held, incoming)).toBe(held);
  });

  it('takes contactRemoved from an equal version and nothing else', () => {
    const incoming = { ...held, status: 'DELIVERED' as const, contactRemoved: true };
    const merged = mergeItem(held, incoming);
    expect(merged).toEqual({ ...held, contactRemoved: true });
    expect(merged).not.toBe(held);
  });

  it('drops a lower version even when it carries contactRemoved', () => {
    const incoming = { ...held, contactRemoved: true, status: 'FAILED' as const, version: held.version - 1 };
    expect(mergeItem(held, incoming)).toBe(held);
  });

  it('copies only the list members of a detail', () => {
    const item = listItemFromDetail(details.delivered);
    expect(Object.keys(item).sort()).toEqual(
      [
        'action',
        'contactRemoved',
        'createdAt',
        'customerId',
        'messageId',
        'origin',
        'outcomeUnknown',
        'status',
        'templateIntent',
        'updatedAt',
        'version'
      ].sort()
    );
    expect(item).not.toHaveProperty('providerMessageId');
    expect(item).not.toHaveProperty('body');
  });
});

// covers: AC-1
describe('canReadMessages', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER', 'TENANT_VIEWER', 'SOMETHING_ELSE'])('lets %s read', (role) => {
    expect(canReadMessages(role)).toBe(true);
  });

  it.each([[''], [null], [undefined]])('hides the history for role %s', (role) => {
    expect(canReadMessages(role)).toBe(false);
  });
});
