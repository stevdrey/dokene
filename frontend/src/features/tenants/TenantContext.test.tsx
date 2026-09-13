import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TenantProvider, useTenant } from './TenantContext';
import { SessionContextValue, useSession } from '../auth/SessionContext';
import { apiClient, AbortedTenantRequestError } from '../../api/apiClient';

// Mock useSession
vi.mock('../auth/SessionContext', () => ({
  useSession: vi.fn(),
}));

const TestTenantConsumer: React.FC = () => {
  const { status, workspaces, activeWorkspace, switchWorkspace, provisionWorkspace, error } = useTenant();
  return (
    <div>
      <div data-testid="status">{status}</div>
      <div data-testid="activeWorkspace">{activeWorkspace ? activeWorkspace.displayName : 'none'}</div>
      <div data-testid="activeTenantId">{activeWorkspace ? activeWorkspace.tenantId : 'none'}</div>
      <div data-testid="workspacesCount">{workspaces.length}</div>
      {error && <div data-testid="error">{error}</div>}
      <button onClick={() => switchWorkspace('tenant-2')}>Cambiar a Tenant 2</button>
      <button onClick={() => provisionWorkspace('Nuevo Negocio', 'idem-123')}>Crear Negocio</button>
    </div>
  );
};

describe('TenantContext', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
    apiClient.setCurrentTenantId(null);
  });

  it('discovers authorized workspaces and selects the first one', async () => {
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'id-1',
      csrfToken: 'csrf-1',
      wasExpired: false,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: vi.fn(),
    } as SessionContextValue);

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants') {
        return new Response(
          JSON.stringify([
            { tenantId: 'tenant-1', displayName: 'Café Artesano', role: 'TENANT_ADMIN' },
            { tenantId: 'tenant-2', displayName: 'Taller Mecánico', role: 'TENANT_OPERATOR' },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(
      <TenantProvider>
        <TestTenantConsumer />
      </TenantProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('ready');
    });

    expect(screen.getByTestId('workspacesCount')).toHaveTextContent('2');
    expect(screen.getByTestId('activeWorkspace')).toHaveTextContent('Café Artesano');
    expect(screen.getByTestId('activeTenantId')).toHaveTextContent('tenant-1');
    expect(apiClient.getCurrentTenantId()).toBe('tenant-1');
  });

  it('sets status to no-memberships when /api/tenants returns empty array', async () => {
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'id-1',
      csrfToken: 'csrf-1',
      wasExpired: false,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: vi.fn(),
    } as SessionContextValue);

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants') {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(null, { status: 404 });
    });

    render(
      <TenantProvider>
        <TestTenantConsumer />
      </TenantProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('no-memberships');
    });
    expect(screen.getByTestId('activeWorkspace')).toHaveTextContent('none');
    expect(apiClient.getCurrentTenantId()).toBeNull();
  });

  it('switches workspaces, updates active workspace and current tenant ID', async () => {
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'id-1',
      csrfToken: 'csrf-1',
      wasExpired: false,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: vi.fn(),
    } as SessionContextValue);

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/tenants') {
        return new Response(
          JSON.stringify([
            { tenantId: 'tenant-1', displayName: 'Café Artesano', role: 'TENANT_ADMIN' },
            { tenantId: 'tenant-2', displayName: 'Taller Mecánico', role: 'TENANT_OPERATOR' },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(
      <TenantProvider>
        <TestTenantConsumer />
      </TenantProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('activeTenantId')).toHaveTextContent('tenant-1');
    });

    await act(async () => {
      screen.getByText('Cambiar a Tenant 2').click();
    });

    await waitFor(() => {
      expect(screen.getByTestId('activeTenantId')).toHaveTextContent('tenant-2');
      expect(screen.getByTestId('activeWorkspace')).toHaveTextContent('Taller Mecánico');
    });

    expect(apiClient.getCurrentTenantId()).toBe('tenant-2');
  });

  it('provisions a new workspace with idempotency key and selects it', async () => {
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'id-1',
      csrfToken: 'csrf-1',
      wasExpired: false,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: vi.fn(),
    } as SessionContextValue);

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (url === '/api/tenants' && (!init || init.method === 'GET')) {
        return new Response(
          JSON.stringify([{ tenantId: 'tenant-1', displayName: 'Café Artesano', role: 'TENANT_ADMIN' }]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url === '/api/tenants' && init?.method === 'POST') {
        return new Response(
          JSON.stringify({
            tenantId: 'tenant-new',
            displayName: 'Nuevo Negocio',
            role: 'TENANT_ADMIN',
          }),
          { status: 201, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(
      <TenantProvider>
        <TestTenantConsumer />
      </TenantProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('activeTenantId')).toHaveTextContent('tenant-1');
    });

    await act(async () => {
      screen.getByText('Crear Negocio').click();
    });

    await waitFor(() => {
      expect(screen.getByTestId('activeTenantId')).toHaveTextContent('tenant-new');
      expect(screen.getByTestId('activeWorkspace')).toHaveTextContent('Nuevo Negocio');
    });
    expect(apiClient.getCurrentTenantId()).toBe('tenant-new');
  });

  it('prevents late responses from previous tenant during workspace switching race condition', async () => {
    // 1. Initial setup with active tenant-1
    apiClient.setCurrentTenantId('tenant-1');

    let resolveSlowRequest: (res: Response) => void;
    const slowResponsePromise = new Promise<Response>((resolve) => {
      resolveSlowRequest = resolve;
    });

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (url === '/api/customers') {
        const headers = init?.headers as Headers;
        expect(headers.get('X-Tenant-Id')).toBe('tenant-1');
        return slowResponsePromise;
      }
      return new Response(null, { status: 404 });
    });

    // 2. Dispatch slow request for tenant-1
    const requestPromise = apiClient.get('/api/customers', { tenantScoped: true });

    // 3. User switches to tenant-2 while request is in flight
    apiClient.setCurrentTenantId('tenant-2');

    // 4. Slow request for tenant-1 now completes
    resolveSlowRequest!(
      new Response(JSON.stringify([{ id: 'customer-from-tenant-1', name: 'Secret Customer' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    // 5. Verify the request is rejected with AbortedTenantRequestError and not returned to client
    await expect(requestPromise).rejects.toThrow(AbortedTenantRequestError);
  });
});
