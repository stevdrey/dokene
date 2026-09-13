import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { NoMembershipsView } from './NoMembershipsView';
import { useTenant } from './TenantContext';
import { useSession } from '../auth/SessionContext';

vi.mock('./TenantContext', () => ({
  useTenant: vi.fn(),
}));

vi.mock('../auth/SessionContext', () => ({
  useSession: vi.fn(),
}));

describe('NoMembershipsView', () => {
  const mockProvisionWorkspace = vi.fn();
  const mockLogout = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.mocked(useTenant).mockReturnValue({
      status: 'no-memberships',
      workspaces: [],
      activeWorkspace: null,
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: mockProvisionWorkspace,
    });
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'user-1',
      csrfToken: 'csrf-1',
      wasExpired: false,
      error: null,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: mockLogout,
    });
  });

  it('renders NoMembershipsView content and logout button', () => {
    render(<NoMembershipsView />);
    expect(screen.getByText('Sin espacios de trabajo')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Crear espacio de trabajo/i })).toBeInTheDocument();

    screen.getByRole('button', { name: /Cerrar sesión/i }).click();
    expect(mockLogout).toHaveBeenCalledTimes(1);
  });

  it('preserves idempotency key across retries after a failed provisioning attempt', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new Error('Network error'));
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    fireEvent.change(input, { target: { value: 'Mi Panadería' } });

    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // First attempt (fails)
    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstKey = mockProvisionWorkspace.mock.calls[0][1];
    expect(firstKey).toBeDefined();
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Mi Panadería', firstKey);
    expect(screen.getByText('Network error')).toBeInTheDocument();

    // Second attempt (retry)
    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-new',
      displayName: 'Mi Panadería',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const secondKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(secondKey).toBe(firstKey);
  });
});
