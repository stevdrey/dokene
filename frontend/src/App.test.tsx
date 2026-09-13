import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
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
});
