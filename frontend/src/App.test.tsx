import React from 'react';
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { App } from './App';

describe('App routing transitions', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('renders LoginView when session is unauthenticated', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(null, { status: 401 });
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    expect(screen.getByRole('status')).toHaveTextContent('Cargando Dokene...');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Iniciar sesión con OIDC/i })).toBeInTheDocument();
    });
  });

  it('renders NoMembershipsView when authenticated with 0 workspaces', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: 'id-123',
            csrfToken: 'csrf-123',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url === '/api/tenants') {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('Sin espacios de trabajo')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Crear espacio de trabajo/i })).toBeInTheDocument();
    });
  });

  it('renders AppShell when authenticated with at least one workspace', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: 'id-123',
            csrfToken: 'csrf-123',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url === '/api/tenants') {
        return new Response(
          JSON.stringify([
            { tenantId: 't-1', displayName: 'Café & Taller Artesano', role: 'TENANT_ADMIN' },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Seguimientos');
      expect(screen.getAllByText(/Café & Taller Artesano/i).length).toBeGreaterThan(0);
    });
  });

  it('renders connection error view when session check fails with 500 and recovers on retry', async () => {
    let callCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        callCount++;
        if (callCount === 1) {
          return new Response('Server Error', { status: 500, statusText: 'Internal Server Error' });
        }
        return new Response(null, { status: 401 });
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('Error de conexión')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Reintentar/i })).toBeInTheDocument();
    });

    await act(async () => {
      screen.getByRole('button', { name: /Reintentar/i }).click();
    });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Iniciar sesión con OIDC/i })).toBeInTheDocument();
    });
  });

  it('resets customer profile view synchronously upon workspace switch', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      const urlStr = String(url);
      if (urlStr === '/api/session') {
        return new Response(
          JSON.stringify({ authenticated: true, identityId: 'id-123', csrfToken: 'csrf-123' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr === '/api/tenants') {
        return new Response(
          JSON.stringify([
            { tenantId: 't-1', displayName: 'Workspace 1', role: 'TENANT_ADMIN' },
            { tenantId: 't-2', displayName: 'Workspace 2', role: 'TENANT_ADMIN' }
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr.includes('/api/customers')) {
        return new Response(
          JSON.stringify({
            customer: {
              id: 'c-1',
              displayName: 'Cliente Uno',
              phones: [],
              status: 'ACTIVE',
              version: 1,
              createdAt: '2025-01-01T00:00:00Z',
              updatedAt: '2025-01-01T00:00:00Z',
              archivedAt: null,
              notes: null
            },
            customers: [
              {
                id: 'c-1',
                displayName: 'Cliente Uno',
                phones: [],
                status: 'ACTIVE',
                version: 1,
                createdAt: '2025-01-01T00:00:00Z',
                updatedAt: '2025-01-01T00:00:00Z',
                archivedAt: null,
                notes: null
              }
            ],
            purchases: [],
            policy: { customerId: 'c-1', version: 1, doNotContact: false, consents: [] },
            nextCursor: null
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getAllByText(/Workspace 1/i).length).toBeGreaterThan(0);
    });

    // Navigate to Clientes tab
    const clientesTab = screen.getByRole('button', { name: /Clientes/i });
    fireEvent.click(clientesTab);

    await waitFor(() => {
      expect(screen.getByText('Cliente Uno')).toBeInTheDocument();
    });

    // Select customer to open CustomerProfile
    fireEvent.click(screen.getByText('Cliente Uno'));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Volver a Clientes/i })).toBeInTheDocument();
    });

    // Open workspace selector and switch to Workspace 2
    fireEvent.click(screen.getByRole('button', { name: /Seleccionar espacio de trabajo/i }));
    fireEvent.click(screen.getByText('Workspace 2'));

    // Customer profile is immediately cleared
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /Volver a Clientes/i })).not.toBeInTheDocument();
    });
  });

  it('navigates from FollowUpWorkbench to CustomerProfile when clicking Ver cliente', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      const urlStr = String(url);
      if (urlStr === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: 'id-123',
            csrfToken: 'csrf-123',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr === '/api/tenants') {
        return new Response(
          JSON.stringify([
            { tenantId: 't-1', displayName: 'Café Artesano', role: 'TENANT_ADMIN' },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr.startsWith('/api/follow-up-queue')) {
        return new Response(
          JSON.stringify({
            items: [
              {
                customerId: 'cust-wf-1',
                displayName: 'Valentina Morales',
                primaryPhone: '+56984521190',
                status: 'DUE',
                reasons: ['DUE_TODAY'],
                dueDate: '2026-09-15',
                timingSource: 'LAST_PURCHASE',
                policyVersion: 1,
                effectiveCadenceDays: 30,
                lastPurchaseAt: null,
                lastManualFollowUpDate: null,
                lastDismissedDate: null,
                evaluatedAt: '2026-09-15T12:00:00Z',
              },
            ],
            nextCursor: null,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr.startsWith('/api/customers/cust-wf-1/purchases')) {
        return new Response(
          JSON.stringify({ purchases: [], nextCursor: null }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (urlStr === '/api/customers/cust-wf-1') {
        return new Response(
          JSON.stringify({
            id: 'cust-wf-1',
            displayName: 'Valentina Morales',
            notes: null,
            phones: [{ id: 'p-1', e164: '+56984521190', primary: true }],
            status: 'ACTIVE',
            version: 1,
            createdAt: '2026-09-01T00:00:00Z',
            updatedAt: '2026-09-01T00:00:00Z',
            archivedAt: null,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json', ETag: '"1"' } }
        );
      }
      if (urlStr === '/api/customers/cust-wf-1/contact-policy') {
        return new Response(
          JSON.stringify({
            customerId: 'cust-wf-1',
            version: 1,
            doNotContact: false,
            doNotContactSource: null,
            doNotContactChangedAt: null,
            consents: [
              {
                contactId: 'p-1',
                channel: 'WHATSAPP',
                status: 'GRANTED',
                source: 'CUSTOMER_VERBAL',
                changedAt: '2026-09-01T00:00:00Z',
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json', ETag: '"1"' } }
        );
      }
      if (urlStr === '/api/customers/cust-wf-1/contact-eligibility') {
        return new Response(
          JSON.stringify({ eligible: true, reasons: [] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(<App />);

    // Workbench loads first on seguimientos tab
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Seguimientos');
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    });

    // Click "Ver cliente" in the detail panel
    const verClienteBtn = await screen.findByRole('button', { name: /Ver cliente/i });
    fireEvent.click(verClienteBtn);

    // Profile view is now rendered with "Volver a Clientes"
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Volver a Clientes/i })).toBeInTheDocument();
    });
  });
});
