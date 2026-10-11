// ABOUTME: Component tests for the Mensajes tab root: list, detail, approve and their refusals.
// ABOUTME: Runs against a fetch router so the real API module and ETag handling are exercised.

import React, { useState } from 'react';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MessagesRoot } from '../components/MessagesRoot';
import { useTenant } from '@/features/tenants/TenantContext';
import {
  approvedEvent,
  installFetchRouter,
  pendingDetail,
  pendingItem,
  submittedEvent
} from './fixtures';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return { ...actual, useTenant: vi.fn() };
});

function mockRole(role: string) {
  vi.mocked(useTenant).mockReturnValue({
    status: 'ready',
    workspaces: [],
    error: null,
    activeWorkspace: { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role },
    switchWorkspace: vi.fn(),
    refreshWorkspaces: vi.fn(),
    provisionWorkspace: vi.fn()
  } as unknown as ReturnType<typeof useTenant>);
}

/** Mirrors App: the selection is held above the feature root. */
const Harness: React.FC<{ initialSelected?: string | null }> = ({ initialSelected = null }) => {
  const [selected, setSelected] = useState<string | null>(initialSelected);
  const [filter, setFilter] = useState<{ customerId: string; customerDisplayName: string } | null>(null);
  return (
    <MessagesRoot
      selectedMessageId={selected}
      onSelectMessage={setSelected}
      customerFilter={filter}
      onClearCustomerFilter={() => setFilter(null)}
    />
  );
};

describe('MessagesRoot', () => {
  beforeEach(() => {
    mockRole('OWNER');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('opens on Pendientes, shows a pending row, opens the detail and approves it', async () => {
    const approvedDetail = { ...pendingDetail, status: 'APPROVED', version: 2, approval: { decision: 'APPROVE', note: null, decidedAt: '2026-10-10T15:00:00Z', decidedByMembershipId: 'mem-2' } };
    let eventsBody = { events: [submittedEvent] };
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': () => ({ body: eventsBody }),
      'POST /api/messages/msg-1/approvals': () => {
        eventsBody = { events: [submittedEvent, approvedEvent] };
        return { status: 201, body: approvedDetail, etag: '"2"' };
      }
    });

    render(<Harness />);

    // AC-1: the list opens on Pendientes with status=PENDING_APPROVAL and limit=50.
    const pendingChip = screen.getByRole('button', { name: 'Pendientes' });
    expect(pendingChip).toHaveAttribute('aria-pressed', 'true');
    const row = await screen.findByRole('button', { name: /Valentina Morales Gómez/ });
    const listCall = router.callsTo('GET /api/messages')[0];
    expect(listCall.url.searchParams.getAll('status')).toEqual(['PENDING_APPROVAL']);
    expect(listCall.url.searchParams.get('limit')).toBe('50');
    expect(within(row).getByText('Pendiente de aprobación')).toBeInTheDocument();
    expect(within(row).getByText('•••• 1190')).toBeInTheDocument();
    expect(within(row).getByText('IA')).toBeInTheDocument();
    expect(row).not.toHaveAttribute('aria-current');

    // AC-2: selecting opens the detail with body and timeline; the row carries aria-current.
    fireEvent.click(row);
    expect(await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' })).toBeInTheDocument();
    expect(screen.getByText(pendingDetail.body)).toBeInTheDocument();
    expect(screen.getByText('+56984521190')).toBeInTheDocument();
    expect(await screen.findByText('Miembro · Enviado a aprobación')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Valentina Morales Gómez/ })).toHaveAttribute('aria-current', 'true');

    // AC-3: approve behind a confirm group, with If-Match and Idempotency-Key.
    fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
    const group = screen.getByRole('group', { name: 'Confirmar aprobación' });
    fireEvent.click(within(group).getByRole('button', { name: 'Confirmar aprobación' }));

    await waitFor(() => {
      expect(screen.getAllByText('Aprobado').length).toBeGreaterThan(0);
    });
    const approveCall = router.callsTo('POST /api/messages/msg-1/approvals')[0];
    expect(approveCall.headers['If-Match']).toBe('"1"');
    expect(approveCall.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(approveCall.body).toEqual({ decision: 'APPROVE' });

    // The events are refetched, the list row is patched in place without a list refetch.
    expect(await screen.findByText('Miembro · Pendiente de aprobación → Aprobado')).toBeInTheDocument();
    expect(router.callsTo('GET /api/messages')).toHaveLength(1);
    const patchedRow = screen.getByRole('button', { name: /Valentina Morales Gómez/ });
    expect(within(patchedRow).getByText('Aprobado')).toBeInTheDocument();

    // Approved offers Enviar and Cancelar, not Aprobar.
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Aprobar' })).not.toBeInTheDocument();
  });

  it('reloads and names the new status on a 409 STALE_VERSION without retrying', async () => {
    let detailBody = pendingDetail;
    let detailEtag = '"1"';
    const router = installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': () => ({ body: detailBody, etag: detailEtag }),
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } },
      'POST /api/messages/msg-1/approvals': () => {
        detailBody = { ...pendingDetail, status: 'REJECTED', version: 2 };
        detailEtag = '"2"';
        return { status: 409, body: { type: 'about:blank', title: 'Conflict', status: 409, code: 'STALE_VERSION' } };
      }
    });

    render(<Harness initialSelected="msg-1" />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Este mensaje cambió: ahora está Rechazado.');
    expect(router.callsTo('POST /api/messages/msg-1/approvals')).toHaveLength(1);
    expect(router.callsTo('GET /api/messages/msg-1').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole('button', { name: 'Aprobar' })).not.toBeInTheDocument();
  });

  it('shows the forbidden sentence on 403 and leaves the detail unchanged', async () => {
    installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } },
      'POST /api/messages/msg-1/approvals': { status: 403 }
    });

    render(<Harness initialSelected="msg-1" />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No tienes permiso para esta acción.');
    expect(screen.getAllByText('Pendiente de aprobación').length).toBeGreaterThan(0);
    expect(screen.getByText(pendingDetail.body)).toBeInTheDocument();
  });

  it('treats a detail without a strong ETag as an error, never version 0', async () => {
    installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } }
    });

    render(<Harness initialSelected="msg-1" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('La respuesta del servidor no tiene el formato esperado.');
    expect(screen.queryByRole('button', { name: 'Aprobar' })).not.toBeInTheDocument();
  });

  it('shows a read only line and no action controls for a VIEWER', async () => {
    mockRole('VIEWER');
    installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } }
    });

    render(<Harness initialSelected="msg-1" />);
    await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
    for (const name of ['Aprobar', 'Rechazar', 'Cancelar', 'Enviar']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });
});
