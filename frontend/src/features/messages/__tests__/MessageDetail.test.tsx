// ABOUTME: Component tests for the message detail: reject, cancel, timeline, outcome card and 404.
// ABOUTME: Covers spec 0004 tasks 2 (decide and history) against the fetch router.

import React, { useState } from 'react';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MessagesRoot } from '../components/MessagesRoot';
import { useTenant } from '@/features/tenants/TenantContext';
import type { MessageDetail, MessageEvent } from '../types';
import { approvedEvent, installFetchRouter, pendingDetail, pendingItem, submittedEvent } from './fixtures';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return { ...actual, useTenant: vi.fn() };
});

const Harness: React.FC<{ onSelectionChange?: (id: string | null) => void }> = ({ onSelectionChange }) => {
  const [selected, setSelected] = useState<string | null>('msg-1');
  return (
    <MessagesRoot
      selectedMessageId={selected}
      onSelectMessage={(id) => {
        setSelected(id);
        onSelectionChange?.(id);
      }}
      customerFilter={null}
      onClearCustomerFilter={() => undefined}
    />
  );
};

const listRoute = { 'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } } };

describe('MessageDetailPanel', () => {
  beforeEach(() => {
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [],
      error: null,
      activeWorkspace: { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'OPERATOR' },
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    } as unknown as ReturnType<typeof useTenant>);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('rejects with a counted note and attaches the note to the REJECTED event', async () => {
    const rejectedEvent: MessageEvent = { ...approvedEvent, eventType: 'REJECTED', statusTo: 'REJECTED' };
    let events = [submittedEvent];
    const router = installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': () => ({ body: { events } }),
      'POST /api/messages/msg-1/approvals': () => {
        events = [submittedEvent, rejectedEvent];
        return {
          status: 201,
          etag: '"2"',
          body: {
            ...pendingDetail,
            status: 'REJECTED',
            version: 2,
            approval: { decision: 'REJECT', note: 'Tono muy informal', decidedAt: '2026-10-10T15:00:00Z', decidedByMembershipId: 'mem-2' }
          }
        };
      }
    });

    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    fireEvent.click(screen.getByRole('button', { name: 'Rechazar' }));
    const group = screen.getByRole('group', { name: 'Confirmar rechazo' });
    const note = within(group).getByLabelText('Nota (opcional)');
    expect(note).toHaveAttribute('maxlength', '500');
    fireEvent.change(note, { target: { value: 'Tono muy informal' } });
    expect(within(group).getByText('17/500')).toBeInTheDocument();
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar rechazo' }));

    await waitFor(() => expect(screen.getAllByText('Rechazado').length).toBeGreaterThan(0));
    expect(router.callsTo('POST /api/messages/msg-1/approvals')[0].body).toEqual({
      decision: 'REJECT',
      note: 'Tono muy informal'
    });
    expect(await screen.findByText('Miembro · Pendiente de aprobación → Rechazado')).toBeInTheDocument();
    expect(screen.getByText('Tono muy informal', { selector: 'blockquote' })).toBeInTheDocument();
    // Terminal: no controls, no Actualizar.
    expect(screen.queryByRole('button', { name: /Aprobar|Rechazar|Cancelar|Enviar|Actualizar/ })).not.toBeInTheDocument();
  });

  it('omits an empty note on reject', async () => {
    const router = installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } },
      'POST /api/messages/msg-1/approvals': { status: 201, etag: '"2"', body: { ...pendingDetail, status: 'REJECTED', version: 2 } }
    });
    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    fireEvent.click(screen.getByRole('button', { name: 'Rechazar' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar rechazo' }));
    await waitFor(() => expect(router.callsTo('POST /api/messages/msg-1/approvals')).toHaveLength(1));
    expect(router.callsTo('POST /api/messages/msg-1/approvals')[0].body).toEqual({ decision: 'REJECT' });
  });

  it('cancels an approved message and attaches the cancellation note to the CANCELLED event', async () => {
    const approvedDetail: MessageDetail = { ...pendingDetail, status: 'APPROVED', version: 2 };
    const cancelledEvent: MessageEvent = {
      ...approvedEvent,
      sequenceNumber: 3,
      eventType: 'CANCELLED',
      statusFrom: 'APPROVED',
      statusTo: 'CANCELLED'
    };
    let events = [submittedEvent, approvedEvent];
    const router = installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: approvedDetail, etag: '"2"' },
      'GET /api/messages/msg-1/events': () => ({ body: { events } }),
      'POST /api/messages/msg-1/cancellations': () => {
        events = [submittedEvent, approvedEvent, cancelledEvent];
        return {
          status: 201,
          etag: '"3"',
          body: {
            ...approvedDetail,
            status: 'CANCELLED',
            version: 3,
            cancellation: { statusFrom: 'APPROVED', note: 'Cliente llamó', cancelledAt: '2026-10-10T16:00:00Z', cancelledByMembershipId: 'mem-1' }
          }
        };
      }
    });

    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    const group = screen.getByRole('group', { name: 'Confirmar cancelación' });
    fireEvent.change(within(group).getByLabelText('Nota (opcional)'), { target: { value: 'Cliente llamó' } });
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar cancelación' }));

    await waitFor(() => expect(screen.getAllByText('Cancelado').length).toBeGreaterThan(0));
    const call = router.callsTo('POST /api/messages/msg-1/cancellations')[0];
    expect(call.headers['If-Match']).toBe('"2"');
    expect(call.body).toEqual({ note: 'Cliente llamó' });
    expect(await screen.findByText('Miembro · Aprobado → Cancelado')).toBeInTheDocument();
    expect(screen.getByText('Cliente llamó', { selector: 'blockquote' })).toBeInTheDocument();
  });

  it('renders the timeline rules: null from, equal from and to, hidden unapplied rows, failure inline', async () => {
    const failedDetail: MessageDetail = {
      ...pendingDetail,
      status: 'FAILED',
      version: 5,
      attempts: [
        { attemptNumber: 1, startedAt: '2026-10-10T16:00:00Z', completedAt: '2026-10-10T16:00:01Z', outcome: 'FAILED_PERMANENT', failureCategory: 'INVALID_RECIPIENT' }
      ],
      lastAttempt: { attemptNumber: 1, startedAt: '2026-10-10T16:00:00Z', completedAt: '2026-10-10T16:00:01Z', outcome: 'FAILED_PERMANENT', failureCategory: 'INVALID_RECIPIENT' },
      sentAt: null
    };
    const events: MessageEvent[] = [
      submittedEvent,
      approvedEvent,
      { ...approvedEvent, sequenceNumber: 3, eventType: 'SEND_REQUESTED', statusFrom: 'APPROVED', statusTo: 'QUEUED' },
      { ...approvedEvent, sequenceNumber: 4, eventType: 'SEND_ATTEMPT_STARTED', statusFrom: 'SENDING', statusTo: 'SENDING', actorKind: 'SYSTEM', attemptNumber: 1 },
      { ...approvedEvent, sequenceNumber: 5, eventType: 'SEND_FAILED', statusFrom: 'SENDING', statusTo: 'FAILED', actorKind: 'PROVIDER', failureCategory: 'INVALID_RECIPIENT', attemptNumber: 1 },
      { ...approvedEvent, sequenceNumber: 6, eventType: 'DELIVERY_UPDATED', statusFrom: 'FAILED', statusTo: 'DELIVERED', actorKind: 'PROVIDER', applied: false }
    ];
    installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: { ...failedDetail, providerMessageId: 'wamid.SECRET' }, etag: '"5"' },
      // Out of order on purpose: the UI sorts by sequenceNumber.
      'GET /api/messages/msg-1/events': { body: { events: [...events].reverse() } }
    });

    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    const timeline = await screen.findByRole('list', { name: 'Historial del mensaje' });
    const rows = within(timeline).getAllByRole('listitem').map((li) => li.textContent ?? '');
    expect(rows).toHaveLength(5);
    expect(rows[0]).toContain('Miembro · Enviado a aprobación');
    expect(rows[1]).toContain('Miembro · Pendiente de aprobación → Aprobado');
    expect(rows[2]).toContain('Miembro · Aprobado → En cola');
    expect(rows[3]).toContain('Sistema · Intento de envío iniciado');
    expect(rows[4]).toContain('Proveedor · Enviando → Fallido');
    expect(rows[4]).toContain('Destinatario inválido');
    expect(screen.queryByText(/Estado de entrega actualizado/)).not.toBeInTheDocument();

    // Outcome card from the last attempt; providerMessageId never rendered.
    const outcome = screen.getByRole('region', { name: 'Resultado del envío' });
    expect(within(outcome).getByText('Falló definitivamente')).toBeInTheDocument();
    expect(within(outcome).getByText('Destinatario inválido')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('wamid.SECRET');
  });

  it('shows the frozen notice with only Actualizar on SENDING with outcomeUnknown', async () => {
    const frozen: MessageDetail = {
      ...pendingDetail,
      status: 'SENDING',
      outcomeUnknown: true,
      version: 4,
      attempts: [{ attemptNumber: 1, startedAt: '2026-10-10T16:00:00Z', completedAt: null, outcome: 'OUTCOME_UNKNOWN', failureCategory: null }],
      lastAttempt: { attemptNumber: 1, startedAt: '2026-10-10T16:00:00Z', completedAt: null, outcome: 'OUTCOME_UNKNOWN', failureCategory: null }
    };
    const router = installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: frozen, etag: '"4"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } }
    });
    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    expect(screen.getByRole('alert')).toHaveTextContent('El proveedor no confirmó el envío. Un administrador debe resolverlo.');
    expect(screen.getByRole('button', { name: 'Actualizar' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Aprobar|Rechazar|Cancelar|Enviar$/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Actualizar' }));
    await waitFor(() => expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(2));
    expect(router.callsTo('GET /api/messages/msg-1/events')).toHaveLength(2);
  });

  it('clears the selection and shows the gone sentence on a 404 mid action', async () => {
    const onSelectionChange = vi.fn();
    installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } },
      'POST /api/messages/msg-1/approvals': { status: 404, body: { status: 404, code: 'NOT_FOUND' } }
    });
    render(<Harness onSelectionChange={onSelectionChange} />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Este mensaje ya no está disponible.');
    expect(onSelectionChange).toHaveBeenCalledWith(null);
    expect(screen.queryByRole('heading', { level: 2, name: 'Valentina Morales Gómez' })).not.toBeInTheDocument();
  });

  it('keeps the detail and offers Reintentar when the events request fails', async () => {
    let eventsFail = true;
    const router = installFetchRouter({
      ...listRoute,
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': () =>
        eventsFail ? { status: 500, body: { status: 500, code: 'INTERNAL' } } : { body: { events: [submittedEvent] } }
    });
    render(<Harness />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No se pudo cargar el historial del mensaje.');
    expect(screen.getByText(pendingDetail.body)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Aprobar' })).toBeInTheDocument();

    eventsFail = false;
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Miembro · Enviado a aprobación')).toBeInTheDocument();
    expect(router.callsTo('GET /api/messages/msg-1')).toHaveLength(1);
  });

  it('drops a late response for a previous selection', async () => {
    const slowDetail = { ...pendingDetail, messageId: 'msg-2', customerDisplayName: 'Carlos Soto Arancibia' };
    let releaseSlow: () => void = () => undefined;
    const slow = new Promise<void>((resolve) => {
      releaseSlow = resolve;
    });
    const items = [pendingItem, { ...pendingItem, messageId: 'msg-2', customerDisplayName: 'Carlos Soto Arancibia' }];
    installFetchRouter({
      'GET /api/messages': { body: { items, nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } },
      'GET /api/messages/msg-2': { body: slowDetail, etag: '"1"', delay: slow },
      'GET /api/messages/msg-2/events': { body: { events: [] }, delay: slow }
    });

    const Switcher: React.FC = () => {
      const [selected, setSelected] = useState<string | null>('msg-2');
      return (
        <MessagesRoot selectedMessageId={selected} onSelectMessage={setSelected} customerFilter={null} onClearCustomerFilter={() => undefined} />
      );
    };
    render(<Switcher />);
    expect(await screen.findByText('Cargando mensaje...')).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: /Valentina Morales Gómez/ }));
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    releaseSlow();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Valentina Morales Gómez');
    expect(screen.queryByRole('heading', { level: 2, name: 'Carlos Soto Arancibia' })).not.toBeInTheDocument();
  });
});
