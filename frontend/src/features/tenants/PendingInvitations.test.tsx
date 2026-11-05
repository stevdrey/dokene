import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { PendingInvitations } from './PendingInvitations';
import { TenantProvider, useTenant } from './TenantContext';
import { SessionContextValue, useSession } from '../auth/SessionContext';
import { apiClient } from '../../api/apiClient';

vi.mock('../auth/SessionContext', () => ({ useSession: vi.fn() }));

vi.mock('./TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./TenantContext')>();
  return { ...actual, useTenant: vi.fn() };
});

const TENANT_ID = '11111111-1111-4111-8111-111111111111';
const invitation = {
  tenantId: TENANT_ID,
  displayName: 'Panadería Central',
  role: 'OPERATOR',
  invitedAt: '2026-11-04T10:00:00Z',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('PendingInvitations', () => {
  const refreshWorkspaces = vi.fn();

  beforeEach(() => {
    refreshWorkspaces.mockReset().mockResolvedValue(undefined);
    vi.mocked(useTenant).mockReturnValue({
      status: 'no-memberships',
      workspaces: [],
      activeWorkspace: null,
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces,
      provisionWorkspace: vi.fn(),
    });
    apiClient.setCsrfToken('csrf-1');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when there is no pending invitation', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json([]));
    const { container } = render(<PendingInvitations />);
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith('/api/tenants/invitations', expect.anything()));
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('lists a pending invitation with its workspace id and accepts it without a tenant header', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants/invitations') return json([invitation]);
      if (url === `/api/tenants/${TENANT_ID}/invitation/acceptance`) {
        return json({ tenantId: TENANT_ID, displayName: invitation.displayName, role: 'OPERATOR' });
      }
      return new Response(null, { status: 404 });
    });

    render(<PendingInvitations />);

    expect(await screen.findByText('Panadería Central')).toBeInTheDocument();
    expect(screen.getByText(`ID: ${TENANT_ID}`)).toBeInTheDocument();
    expect(screen.getByText(/Operador\(a\)/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Aceptar invitación a Panadería Central' }));

    await waitFor(() => expect(refreshWorkspaces).toHaveBeenCalledWith({ background: true }));
    const acceptCall = fetchSpy.mock.calls.find(([url]) => url === `/api/tenants/${TENANT_ID}/invitation/acceptance`);
    expect(acceptCall).toBeDefined();
    const init = acceptCall![1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(init.method).toBe('POST');
    expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-1');
    expect(headers.has('X-Tenant-Id')).toBe(false);
    await waitFor(() => expect(screen.queryByText('Panadería Central')).not.toBeInTheDocument());
  });

  it('explains when the invitation is no longer available', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants/invitations') return json([invitation]);
      return new Response(null, { status: 404 });
    });

    render(<PendingInvitations />);
    fireEvent.click(await screen.findByRole('button', { name: 'Aceptar invitación a Panadería Central' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Esta invitación ya no está disponible.');
    expect(refreshWorkspaces).not.toHaveBeenCalled();
    expect(screen.queryByText('Panadería Central')).not.toBeInTheDocument();
  });

  it('keeps the invitation and offers a retry when acceptance fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants/invitations') return json([invitation]);
      return new Response(null, { status: 503 });
    });

    render(<PendingInvitations />);
    fireEvent.click(await screen.findByRole('button', { name: 'Aceptar invitación a Panadería Central' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo aceptar la invitación.');
    expect(screen.getByText('Panadería Central')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Aceptar invitación a Panadería Central' })).toBeEnabled();
  });

  it('reports a failed load without blocking the page', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(null, { status: 500 }));
    render(<PendingInvitations />);

    expect(await screen.findByRole('status')).toHaveTextContent('No se pudieron cargar tus invitaciones.');
    fetchSpy.mockResolvedValueOnce(json([invitation]));
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Panadería Central')).toBeInTheDocument();
  });

  it('refreshes the workspaces in the background, never passing through loading or error', async () => {
    const actual = await vi.importActual<typeof import('./TenantContext')>('./TenantContext');
    vi.mocked(useTenant).mockImplementation(actual.useTenant);
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'id-1',
      csrfToken: 'csrf-1',
      wasSessionEnded: false,
      error: null,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: vi.fn(),
    } as SessionContextValue);
    const first = [{ tenantId: 'tenant-1', displayName: 'Café Artesano', role: 'OWNER' }];
    const second = [...first, { tenantId: TENANT_ID, displayName: invitation.displayName, role: 'OPERATOR' }];
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(first))
      .mockResolvedValueOnce(json(second))
      .mockResolvedValueOnce(new Response(null, { status: 503 }));
    const statuses: string[] = [];
    const Probe: React.FC = () => {
      const { status, workspaces, refreshWorkspaces: refresh } = useTenant();
      statuses.push(status);
      return (
        <div>
          <div data-testid="bg-status">{status}</div>
          <div data-testid="bg-count">{workspaces.length}</div>
          <button onClick={() => void refresh({ background: true })}>Background</button>
        </div>
      );
    };

    render(
      <TenantProvider>
        <Probe />
      </TenantProvider>
    );
    await waitFor(() => expect(screen.getByTestId('bg-status')).toHaveTextContent('ready'));
    statuses.length = 0;

    fireEvent.click(screen.getByRole('button', { name: 'Background' }));
    await waitFor(() => expect(screen.getByTestId('bg-count')).toHaveTextContent('2'));
    fireEvent.click(screen.getByRole('button', { name: 'Background' }));
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(3));

    expect(statuses).not.toContain('loading');
    expect(statuses).not.toContain('error');
    expect(screen.getByTestId('bg-status')).toHaveTextContent('ready');
    expect(screen.getByTestId('bg-count')).toHaveTextContent('2');
  });
});
