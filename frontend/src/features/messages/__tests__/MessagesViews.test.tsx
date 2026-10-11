// ABOUTME: Component tests for the Mensajes views: filters, paging, customer chip, mobile and tenant switch.
// ABOUTME: Covers spec 0004 task 5 (AC-1, AC-2, AC-9, AC-10) against the fetch router.

import React, { useState } from 'react';
import { render, screen, fireEvent, within, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MessagesRoot } from '../components/MessagesRoot';
import { useTenant } from '@/features/tenants/TenantContext';
import type { MessageDetail, MessageListItem } from '../types';
import { approvedEvent, installFetchRouter, pendingDetail, pendingItem, submittedEvent } from './fixtures';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return { ...actual, useTenant: vi.fn() };
});

function mockTenant(tenantId: string, role = 'OWNER') {
  vi.mocked(useTenant).mockReturnValue({
    status: 'ready',
    workspaces: [],
    error: null,
    activeWorkspace: { tenantId, displayName: `Espacio ${tenantId}`, role },
    switchWorkspace: vi.fn(),
    refreshWorkspaces: vi.fn(),
    provisionWorkspace: vi.fn()
  } as unknown as ReturnType<typeof useTenant>);
}

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
}

const secondItem: MessageListItem = { ...pendingItem, messageId: 'msg-2', customerDisplayName: 'Carlos Soto Arancibia' };
const thirdItem: MessageListItem = { ...pendingItem, messageId: 'msg-3', customerDisplayName: 'Ana Pérez Rojas', status: 'APPROVED' };

const Harness: React.FC<{
  initialSelected?: string | null;
  initialFilter?: { customerId: string; customerDisplayName: string } | null;
}> = ({ initialSelected = null, initialFilter = null }) => {
  const [selected, setSelected] = useState<string | null>(initialSelected);
  const [filter, setFilter] = useState(initialFilter);
  return (
    <MessagesRoot selectedMessageId={selected} onSelectMessage={setSelected} customerFilter={filter} onClearCustomerFilter={() => setFilter(null)} />
  );
};

describe('Mensajes views', () => {
  beforeEach(() => {
    setViewportWidth(1200);
    mockTenant('tenant-1');
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('switches filter chips with aria-pressed and queries the mapped statuses', async () => {
    const router = installFetchRouter({
      'GET /api/messages': (_init, url) => {
        const statuses = url.searchParams.getAll('status');
        if (statuses.length === 0) return { body: { items: [pendingItem, thirdItem], nextCursor: null } };
        if (statuses.includes('APPROVED')) return { body: { items: [thirdItem], nextCursor: null } };
        if (statuses.includes('FAILED')) return { body: { items: [], nextCursor: null } };
        return { body: { items: [pendingItem], nextCursor: null } };
      }
    });
    render(<Harness />);
    await screen.findByRole('button', { name: /Valentina Morales Gómez/ });

    fireEvent.click(screen.getByRole('button', { name: 'Enviados' }));
    await waitFor(() => expect(router.callsTo('GET /api/messages')).toHaveLength(2));
    expect(router.callsTo('GET /api/messages')[1].url.searchParams.getAll('status')).toEqual([
      'QUEUED',
      'SENDING',
      'SENT',
      'DELIVERED',
      'READ'
    ]);
    expect(screen.getByRole('button', { name: 'Enviados' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Pendientes' })).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(screen.getByRole('button', { name: 'Fallidos' }));
    expect(await screen.findByText('No hay mensajes en esta vista.')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Rechazados y cancelados' }));
    await waitFor(() => expect(router.callsTo('GET /api/messages')).toHaveLength(4));
    expect(router.callsTo('GET /api/messages')[3].url.searchParams.getAll('status')).toEqual(['REJECTED', 'CANCELLED']);

    fireEvent.click(screen.getByRole('button', { name: 'Todos' }));
    await screen.findByRole('button', { name: /Ana Pérez Rojas/ });
    expect(router.callsTo('GET /api/messages')[4].url.searchParams.getAll('status')).toEqual([]);
    expect(screen.getByRole('button', { name: /Valentina Morales Gómez/ })).toBeInTheDocument();
  });

  it('appends the next page with Cargar más and hides it when the cursor is null', async () => {
    const router = installFetchRouter({
      'GET /api/messages': (_init, url) =>
        url.searchParams.get('cursor') === 'c2'
          ? { body: { items: [secondItem], nextCursor: null } }
          : { body: { items: [pendingItem], nextCursor: 'c2' } }
    });
    render(<Harness />);
    await screen.findByRole('button', { name: /Valentina Morales Gómez/ });
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más' }));
    await screen.findByRole('button', { name: /Carlos Soto Arancibia/ });
    expect(screen.getByRole('button', { name: /Valentina Morales Gómez/ })).toBeInTheDocument();
    expect(router.callsTo('GET /api/messages')[1].url.searchParams.get('cursor')).toBe('c2');
    expect(screen.queryByRole('button', { name: 'Cargar más' })).not.toBeInTheDocument();
  });

  it('shows the list error state with Reintentar', async () => {
    let fail = true;
    installFetchRouter({
      'GET /api/messages': () => (fail ? { status: 500, body: { status: 500, code: 'INTERNAL' } } : { body: { items: [pendingItem], nextCursor: null } })
    });
    render(<Harness />);
    const alert = await screen.findByRole('alert');
    fail = false;
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('button', { name: /Valentina Morales Gómez/ });
  });

  it('applies the customer filter from the panel link on Todos and clears it with the chip', async () => {
    const router = installFetchRouter({
      'GET /api/messages': (_init, url) =>
        url.searchParams.get('customerId') === 'cust-1'
          ? { body: { items: [pendingItem, thirdItem], nextCursor: null } }
          : { body: { items: [pendingItem, secondItem], nextCursor: null } }
    });
    render(<Harness initialFilter={{ customerId: 'cust-1', customerDisplayName: 'Valentina Morales Gómez' }} />);
    await screen.findByRole('button', { name: /Ana Pérez Rojas/ });
    const first = router.callsTo('GET /api/messages')[0];
    expect(first.url.searchParams.get('customerId')).toBe('cust-1');
    expect(first.url.searchParams.getAll('status')).toEqual([]);
    expect(screen.getByRole('button', { name: 'Todos' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Cliente: Valentina Morales Gómez')).toBeInTheDocument();

    // Chip changes keep the customer filter.
    fireEvent.click(screen.getByRole('button', { name: 'Pendientes' }));
    await waitFor(() => expect(router.callsTo('GET /api/messages')).toHaveLength(2));
    expect(router.callsTo('GET /api/messages')[1].url.searchParams.get('customerId')).toBe('cust-1');

    fireEvent.click(screen.getByRole('button', { name: 'Quitar filtro de cliente Valentina Morales Gómez' }));
    await screen.findByRole('button', { name: /Carlos Soto Arancibia/ });
    expect(router.callsTo('GET /api/messages')[2].url.searchParams.get('customerId')).toBeNull();
    expect(screen.queryByText(/Cliente:/)).not.toBeInTheDocument();
  });

  it('replaces the list with the detail below 768px, focuses the heading and returns focus on Volver', async () => {
    setViewportWidth(500);
    installFetchRouter({
      'GET /api/messages': { body: { items: [pendingItem], nextCursor: null } },
      'GET /api/messages/msg-1': { body: pendingDetail, etag: '"1"' },
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent] } }
    });
    render(<Harness />);
    const row = await screen.findByRole('button', { name: /Valentina Morales Gómez/ });
    fireEvent.click(row);

    const heading = await screen.findByRole('heading', { level: 2, name: 'Valentina Morales Gómez' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(screen.queryByRole('button', { name: 'Pendientes' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Volver' }));
    const rowAgain = await screen.findByRole('button', { name: /Valentina Morales Gómez/ });
    await waitFor(() => expect(rowAgain).toHaveFocus());
    expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
  });

  it('resets to the new tenant on workspace switch, aborting the active poll and never showing the old rows', async () => {
    vi.useFakeTimers();
    const approvedItem: MessageListItem = { ...pendingItem, status: 'APPROVED', version: 2 };
    const approvedDetail: MessageDetail = { ...pendingDetail, status: 'APPROVED', version: 2 };
    const sentDetail: MessageDetail = { ...approvedDetail, status: 'SENT', version: 4 };
    const tenantBItem: MessageListItem = { ...pendingItem, messageId: 'msg-b', customerDisplayName: 'Beatriz Núñez (B)' };
    const router = installFetchRouter({
      'GET /api/messages': (init) =>
        (init?.headers as Record<string, string> | undefined)?.['X-Tenant-Id'] === 'tenant-2' || currentTenant === 'tenant-2'
          ? { body: { items: [tenantBItem], nextCursor: null } }
          : { body: { items: [approvedItem], nextCursor: null } },
      'GET /api/messages/msg-1': () => ({ body: sentDetail, etag: '"4"' }),
      'GET /api/messages/msg-1/events': { body: { events: [submittedEvent, approvedEvent] } },
      'POST /api/messages/msg-1/sends': { status: 201, body: sentDetail, etag: '"4"' }
    });
    let currentTenant = 'tenant-1';
    router.set('GET /api/messages/msg-1', { body: approvedDetail, etag: '"2"' });

    // Mirrors App: the selection is stamped with its tenant and ignored once the workspace changes.
    const Switcher: React.FC<{ tenantId: string }> = ({ tenantId }) => {
      const [selected, setSelected] = useState<{ tenantId: string; messageId: string } | null>({ tenantId: 'tenant-1', messageId: 'msg-1' });
      const selectedMessageId = selected && selected.tenantId === tenantId ? selected.messageId : null;
      return (
        <MessagesRoot
          key={tenantId}
          selectedMessageId={selectedMessageId}
          onSelectMessage={(messageId) => setSelected(messageId ? { tenantId, messageId } : null)}
          customerFilter={null}
          onClearCustomerFilter={() => undefined}
        />
      );
    };
    const view = render(<Switcher tenantId="tenant-1" />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Valentina Morales Gómez');

    router.set('GET /api/messages/msg-1', { body: sentDetail, etag: '"4"' });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar envío' })).getByRole('button', { name: 'Confirmar envío' }));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByText('Siguiendo el envío...')).toBeInTheDocument();
    const detailCallsBefore = router.callsTo('GET /api/messages/msg-1').length;

    // Switch workspace: App remounts the root through its key.
    currentTenant = 'tenant-2';
    mockTenant('tenant-2');
    view.rerender(<Switcher tenantId="tenant-2" />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.getByRole('button', { name: 'Pendientes' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Beatriz Núñez/ })).toBeInTheDocument();
    expect(screen.queryByText('Valentina Morales Gómez')).not.toBeInTheDocument();
    expect(screen.queryByText('Siguiendo el envío...')).not.toBeInTheDocument();

    // The old tenant's poll never fires again.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(router.callsTo('GET /api/messages/msg-1').length).toBeLessThanOrEqual(detailCallsBefore + 1);
    expect(router.callsTo('GET /api/messages')).toHaveLength(2);
  });
});
