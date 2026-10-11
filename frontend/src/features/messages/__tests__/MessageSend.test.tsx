// ABOUTME: Component tests for sending: polling to DELIVERED, exhaustion, frozen, 429 and refusals.
// ABOUTME: Uses fake timers so the 2 s tick and 20 s budget are checked exactly (spec 0004 AC-5).

import React, { useState } from 'react';
import { render, screen, fireEvent, within, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MessagesRoot } from '../components/MessagesRoot';
import { useTenant } from '@/features/tenants/TenantContext';
import type { MessageDetail, MessageEvent, MessageListItem } from '../types';
import { approvedEvent, installFetchRouter, pendingDetail, pendingItem, submittedEvent } from './fixtures';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return { ...actual, useTenant: vi.fn() };
});

const approvedItem: MessageListItem = { ...pendingItem, status: 'APPROVED', version: 2 };
const approvedDetail: MessageDetail = { ...pendingDetail, status: 'APPROVED', version: 2 };
const sentEvent: MessageEvent = { ...approvedEvent, sequenceNumber: 3, eventType: 'SEND_REQUESTED', statusFrom: 'APPROVED', statusTo: 'QUEUED' };
const deliveredEvent: MessageEvent = {
  ...approvedEvent,
  sequenceNumber: 5,
  eventType: 'DELIVERY_UPDATED',
  statusFrom: 'SENT',
  statusTo: 'DELIVERED',
  actorKind: 'PROVIDER'
};

const attempt = { attemptNumber: 1, startedAt: '2026-10-10T16:00:00Z', completedAt: '2026-10-10T16:00:01Z', outcome: 'ACCEPTED', failureCategory: null };
const sentDetail: MessageDetail = { ...approvedDetail, status: 'SENT', version: 4, attempts: [attempt], lastAttempt: attempt, sentAt: '2026-10-10T16:00:01Z' };
const deliveredDetail: MessageDetail = { ...sentDetail, status: 'DELIVERED', version: 5, deliveredAt: '2026-10-10T16:00:04Z' };

const Harness: React.FC<{ initialSelected?: string | null }> = ({ initialSelected = 'msg-1' }) => {
  const [selected, setSelected] = useState<string | null>(initialSelected);
  return <MessagesRoot selectedMessageId={selected} onSelectMessage={setSelected} customerFilter={null} onClearCustomerFilter={() => undefined} />;
};

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

async function openSendConfirm() {
  await flush();
  await flush();
  fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
  return screen.getByRole('group', { name: 'Confirmar envío' });
}

describe('sending a message', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [],
      error: null,
      activeWorkspace: { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'OWNER' },
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    } as unknown as ReturnType<typeof useTenant>);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('sends with If-Match and a key, polls every 2 s and refetches events when the version changes', async () => {
    let detail: MessageDetail = approvedDetail;
    let events: MessageEvent[] = [submittedEvent, approvedEvent];
    let pollCount = 0;
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': () => {
        pollCount += 1;
        // Second poll tick: the local provider has delivered.
        if (pollCount >= 3) {
          detail = deliveredDetail;
          events = [submittedEvent, approvedEvent, sentEvent, deliveredEvent];
        }
        return { body: detail, etag: `"${detail.version}"` };
      },
      'GET /api/messages/msg-1/events': () => ({ body: { events } }),
      'POST /api/messages/msg-1/sends': () => {
        detail = sentDetail;
        events = [submittedEvent, approvedEvent, sentEvent];
        return { status: 201, body: sentDetail, etag: '"4"' };
      }
    });

    render(<Harness />);
    const group = await openSendConfirm();
    expect(group).toHaveTextContent('¿Enviar este mensaje a •••• 1190?');
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();

    const send = router.callsTo('POST /api/messages/msg-1/sends')[0];
    expect(send.headers['If-Match']).toBe('"2"');
    expect(send.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(send.body).toBeUndefined();
    expect(screen.getAllByText('Enviado').length).toBeGreaterThan(0);
    expect(screen.getByText('Siguiendo el envío...')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Actualizar' })).toBeDisabled();
    const detailCallsAfterSend = router.callsTo('GET /api/messages/msg-1').length;
    const eventCallsAfterSend = router.callsTo('GET /api/messages/msg-1/events').length;

    // First tick at 2 s: same version, no events refetch.
    await advance(1999);
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(detailCallsAfterSend);
    await advance(1);
    await flush();
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(detailCallsAfterSend + 1);
    expect(router.callsTo('GET /api/messages/msg-1/events')).toHaveLength(eventCallsAfterSend);

    // Second tick at 4 s: version 5, events refetched, polling stops on the terminal status.
    await advance(2000);
    await flush();
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(detailCallsAfterSend + 2);
    expect(router.callsTo('GET /api/messages/msg-1/events')).toHaveLength(eventCallsAfterSend + 1);
    expect(screen.getAllByText('Entregado').length).toBeGreaterThan(0);
    expect(screen.getByText('Proveedor · Enviado → Entregado')).toBeInTheDocument();
    expect(screen.queryByText('Siguiendo el envío...')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actualizar' })).not.toBeInTheDocument();

    await advance(10000);
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(detailCallsAfterSend + 2);

    // The list row was patched in place without a list refetch.
    const row = screen.getByRole('button', { name: /Valentina Morales Gómez/ });
    expect(within(row).getByText('Entregado')).toBeInTheDocument();
    expect(router.callsTo('GET /api/messages')).toHaveLength(1);
  });

  it('stops after the 20 s budget and leaves Actualizar enabled', async () => {
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': () => ({ body: sentDetail, etag: '"4"' }),
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent, sentEvent] } },
      'POST /api/messages/msg-1/sends': { status: 201, body: sentDetail, etag: '"4"' }
    });
    // Start approved so Enviar is offered.
    router.set('GET /api/messages/msg-1', { body: approvedDetail, etag: '"2"' });

    render(<Harness />);
    const group = await openSendConfirm();
    router.set('GET /api/messages/msg-1', { body: sentDetail, etag: '"4"' });
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();
    const before = router.callsTo('GET /api/messages/msg-1').length;

    for (let i = 0; i < 10; i += 1) {
      await advance(2000);
      await flush();
    }
    const ticks = router.callsTo('GET /api/messages/msg-1').length - before;
    expect(ticks).toBeGreaterThanOrEqual(9);
    expect(ticks).toBeLessThanOrEqual(10);
    expect(screen.queryByText('Siguiendo el envío...')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Actualizar' })).toBeEnabled();

    await advance(6000);
    expect(router.callsTo('GET /api/messages/msg-1').length - before).toBe(ticks);
  });

  it('shows the frozen notice and does not poll when the send lands SENDING with outcomeUnknown', async () => {
    const frozenAttempt = { ...attempt, completedAt: null, outcome: 'OUTCOME_UNKNOWN' };
    const frozen: MessageDetail = { ...approvedDetail, status: 'SENDING', outcomeUnknown: true, version: 4, attempts: [frozenAttempt], lastAttempt: frozenAttempt };
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: approvedDetail, etag: '"2"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent] } },
      'POST /api/messages/msg-1/sends': { status: 201, body: frozen, etag: '"4"' }
    });
    render(<Harness />);
    const group = await openSendConfirm();
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();
    const before = router.callsTo('GET /api/messages/msg-1').length;

    expect(screen.getByRole('alert')).toHaveTextContent('El proveedor no confirmó el envío. Un administrador debe resolverlo.');
    expect(screen.getByRole('button', { name: 'Actualizar' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /^(Enviar|Cancelar|Aprobar|Rechazar)$/ })).not.toBeInTheDocument();
    await advance(6000);
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(before);
  });

  it('holds a tenant wide countdown on 429, keeps the key and re enables after Retry-After', async () => {
    const otherItem: MessageListItem = { ...approvedItem, messageId: 'msg-2', customerDisplayName: 'Carlos Soto Arancibia' };
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem, otherItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: approvedDetail, etag: '"2"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent] } },
      'GET /api/messages/msg-2': { body: { ...approvedDetail, messageId: 'msg-2', customerDisplayName: 'Carlos Soto Arancibia' }, etag: '"2"' },
      'GET /api/messages/msg-2/events': { body: { events: [] } },
      'POST /api/messages/msg-1/sends': { status: 429, body: { status: 429, code: 'RATE_LIMITED' }, headers: { 'Retry-After': '7' } }
    });
    render(<Harness />);
    const group = await openSendConfirm();
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();

    expect(screen.getByRole('alert')).toHaveTextContent('Se alcanzó el límite de envíos por minuto. Espera 7 s.');
    const confirm = within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' });
    expect(confirm).toBeDisabled();
    await advance(3000);
    expect(screen.getByRole('alert')).toHaveTextContent('Espera 4 s.');

    // Enviar on another message of the tenant is disabled for the same period.
    fireEvent.click(screen.getByRole('button', { name: /Carlos Soto Arancibia/ }));
    await flush();
    await flush();
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Carlos Soto Arancibia');
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();
    expect(screen.getByText(/Envíos en pausa por \d s\./)).toBeInTheDocument();
    await advance(4000);
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeEnabled();

    // Back on the first message a retry of the same intent reuses the key.
    fireEvent.click(screen.getByRole('button', { name: /Valentina Morales Gómez/ }));
    await flush();
    await flush();
    router.set('POST /api/messages/msg-1/sends', () => ({
      status: 429,
      body: { status: 429, code: 'RATE_LIMITED' },
      headers: { 'Retry-After': '2' }
    }));
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();
    await advance(2000);
    router.set('POST /api/messages/msg-1/sends', { status: 201, body: sentDetail, etag: '"4"' });
    fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();
    const sends = router.callsTo('POST /api/messages/msg-1/sends');
    expect(sends).toHaveLength(3);
    expect(sends[2].headers['Idempotency-Key']).toBe(sends[1].headers['Idempotency-Key']);
    expect(sends[1].headers['Idempotency-Key']).not.toBe(sends[0].headers['Idempotency-Key']);
  });

  it('renders a transient failure through the outcome card and uses a new key for the next Enviar', async () => {
    const failedAttempt = { ...attempt, outcome: 'FAILED_TRANSIENT', failureCategory: 'PROVIDER_UNAVAILABLE' };
    const backToApproved: MessageDetail = { ...approvedDetail, version: 4, attempts: [failedAttempt], lastAttempt: failedAttempt };
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: approvedDetail, etag: '"2"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent] } },
      'POST /api/messages/msg-1/sends': { status: 201, body: backToApproved, etag: '"4"' }
    });
    render(<Harness />);
    const group = await openSendConfirm();
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();

    const outcome = screen.getByRole('region', { name: 'Resultado del envío' });
    expect(within(outcome).getByText('Falló temporalmente')).toBeInTheDocument();
    expect(within(outcome).getByText('Proveedor no disponible')).toBeInTheDocument();
    expect(screen.queryByText('Siguiendo el envío...')).not.toBeInTheDocument();

    router.set('POST /api/messages/msg-1/sends', { status: 201, body: sentDetail, etag: '"5"' });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' }));
    await flush();
    await flush();
    const sends = router.callsTo('POST /api/messages/msg-1/sends');
    expect(sends).toHaveLength(2);
    expect(sends[1].headers['If-Match']).toBe('"4"');
    expect(sends[1].headers['Idempotency-Key']).not.toBe(sends[0].headers['Idempotency-Key']);
  });

  it('shows the network sentence, keeps the key, and maps refusal codes to their sentences', async () => {
    let mode: 'network' | 'policy' | 'unknown' = 'network';
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: approvedDetail, etag: '"2"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent] } },
      'POST /api/messages/msg-1/sends': () => {
        if (mode === 'network') throw new TypeError('Failed to fetch');
        if (mode === 'policy') return { status: 409, body: { status: 409, code: 'NO_CONTACT_CONSENT' } };
        return { status: 409, body: { status: 409, code: 'BRAND_NEW_CODE' } };
      }
    });
    render(<Harness />);
    const group = await openSendConfirm();
    const confirm = () => fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' }));
    expect(group).toBeInTheDocument();
    confirm();
    await flush();
    await flush();
    expect(screen.getByRole('alert')).toHaveTextContent('No se pudo conectar. Intenta de nuevo.');

    mode = 'policy';
    confirm();
    await flush();
    await flush();
    expect(screen.getByRole('alert')).toHaveTextContent('El cliente no ha dado consentimiento para este canal.');

    mode = 'unknown';
    confirm();
    await flush();
    await flush();
    expect(screen.getByRole('alert')).toHaveTextContent('No se pudo completar la acción (BRAND_NEW_CODE).');

    const sends = router.callsTo('POST /api/messages/msg-1/sends');
    expect(sends).toHaveLength(3);
    expect(new Set(sends.map((call) => call.headers['Idempotency-Key'])).size).toBe(1);
    // The detail is unchanged by refusals.
    expect(screen.getAllByText('Aprobado').length).toBeGreaterThan(0);
  });
});
