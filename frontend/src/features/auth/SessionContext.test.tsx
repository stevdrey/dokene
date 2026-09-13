import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { SessionProvider, useSession } from './SessionContext';
import { apiClient } from '../../api/apiClient';

const TestSessionConsumer: React.FC = () => {
  const { status, identityId, wasExpired, logout } = useSession();
  return (
    <div>
      <div data-testid="status">{status}</div>
      <div data-testid="identityId">{identityId || 'none'}</div>
      <div data-testid="wasExpired">{wasExpired ? 'yes' : 'no'}</div>
      <button onClick={() => logout()}>Cerrar sesión</button>
    </div>
  );
};

describe('SessionContext', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('transitions to unauthenticated when /api/session returns 401', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(null, { status: 401, statusText: 'Unauthorized' });
      }
      return new Response(null, { status: 404 });
    });

    render(
      <SessionProvider>
        <TestSessionConsumer />
      </SessionProvider>
    );

    expect(screen.getByTestId('status')).toHaveTextContent('loading');

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated');
    });
    expect(screen.getByTestId('identityId')).toHaveTextContent('none');
    expect(screen.getByTestId('wasExpired')).toHaveTextContent('no');
  });

  it('transitions to authenticated when /api/session returns active session', async () => {
    const mockIdentity = '3fa85f64-5717-4562-b3fc-2c963f66afa6';
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: mockIdentity,
            csrfToken: 'mock-csrf-token',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(null, { status: 404 });
    });

    render(
      <SessionProvider>
        <TestSessionConsumer />
      </SessionProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    expect(screen.getByTestId('identityId')).toHaveTextContent(mockIdentity);
    expect(screen.getByTestId('wasExpired')).toHaveTextContent('no');
  });

  it('handles expired session triggered by API 401 response', async () => {
    const mockIdentity = '3fa85f64-5717-4562-b3fc-2c963f66afa6';
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL) => {
      if (url === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: mockIdentity,
            csrfToken: 'mock-csrf-token',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url === '/api/tenants') {
        return new Response(null, { status: 401, statusText: 'Session Expired' });
      }
      return new Response(null, { status: 404 });
    });

    render(
      <SessionProvider>
        <TestSessionConsumer />
      </SessionProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });

    // Simulating an authenticated API call returning 401
    await act(async () => {
      try {
        await apiClient.get('/api/tenants');
      } catch {
        // expected error
      }
    });

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated');
      expect(screen.getByTestId('wasExpired')).toHaveTextContent('yes');
    });
  });

  it('calls /logout and clears session on logout', async () => {
    const mockIdentity = '3fa85f64-5717-4562-b3fc-2c963f66afa6';
    let logoutCalled = false;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (url === '/api/session') {
        return new Response(
          JSON.stringify({
            authenticated: true,
            identityId: mockIdentity,
            csrfToken: 'csrf-123',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url === '/logout') {
        logoutCalled = true;
        const headers = init?.headers as Headers;
        expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-123');
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });

    render(
      <SessionProvider>
        <TestSessionConsumer />
      </SessionProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });

    await act(async () => {
      screen.getByText('Cerrar sesión').click();
    });

    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated');
    });
    expect(logoutCalled).toBe(true);
    expect(screen.getByTestId('identityId')).toHaveTextContent('none');
  });
});
