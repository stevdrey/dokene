import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { NoMembershipsView } from './NoMembershipsView';
import { useTenant } from './TenantContext';
import { useSession } from '../auth/SessionContext';
import { ApiError } from '../../api/apiClient';

vi.mock('./TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./TenantContext')>();
  return {
    ...actual,
    useTenant: vi.fn(),
  };
});

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

  it('displays restricted creation guidance and hides the form when provisioning is forbidden (403)', async () => {
    const { ForbiddenError } = await import('../../api/apiClient');
    mockProvisionWorkspace.mockRejectedValueOnce(new ForbiddenError());
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    fireEvent.change(input, { target: { value: 'Negocio No Permitido' } });

    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Creación de espacios restringida')).toBeInTheDocument();
    expect(
      screen.getByText(/La creación de nuevos espacios de trabajo no está habilitada para tu cuenta/i)
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(/Nombre del negocio/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Crear espacio de trabajo/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Cerrar sesión/i })).toBeInTheDocument();
  });

  it('renews idempotency key when changing the workspace name after an attempted submission', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new Error('Conflict or timeout'));
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    fireEvent.change(input, { target: { value: 'Mi Panadería' } });

    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // First attempt with original name
    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstKey = mockProvisionWorkspace.mock.calls[0][1];

    // Change the name to a different business name
    fireEvent.change(input, { target: { value: 'Mi Pastelería' } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-new',
      displayName: 'Mi Pastelería',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const secondKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(secondKey).toBeDefined();
    expect(secondKey).not.toBe(firstKey);
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Mi Pastelería', secondKey);
  });

  it('restores the attempted idempotency key when returning to the attempted workspace name', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new Error('Network timeout'));
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    fireEvent.change(input, { target: { value: 'Mi Panadería' } });

    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // First attempt with original name
    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstKey = mockProvisionWorkspace.mock.calls[0][1];

    // Change the name to a different business name
    fireEvent.change(input, { target: { value: 'Mi Pastelería' } });

    // Return to the first name
    fireEvent.change(input, { target: { value: 'Mi Panadería' } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-1',
      displayName: 'Mi Panadería',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const restoredKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(restoredKey).toBe(firstKey);
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Mi Panadería', firstKey);
  });

  it('validates workspace name length client-side and maps 400 server validation errors', async () => {
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // Client-side length validation (> 160 chars)
    const tooLongName = 'A'.repeat(161);
    fireEvent.change(input, { target: { value: tooLongName } });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).not.toHaveBeenCalled();
    expect(screen.getByText('El nombre del espacio no puede exceder los 160 caracteres.')).toBeInTheDocument();

    // Server-side 400 validation mapping
    fireEvent.change(input, { target: { value: 'Nombre Inválido Servidor' } });
    mockProvisionWorkspace.mockRejectedValueOnce(new ApiError(400, 'Bad Request'));

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    expect(
      screen.getByText('El nombre del espacio de trabajo no es válido o excede los 160 caracteres.')
    ).toBeInTheDocument();
  });

  it('handles logout rejection and displays retryable error alert with accessible touch target', async () => {
    mockLogout.mockRejectedValueOnce(new Error('Fallo de red al cerrar sesión'));
    render(<NoMembershipsView />);

    const logoutBtn = screen.getByRole('button', { name: /Cerrar sesión/i });
    await act(async () => {
      fireEvent.click(logoutBtn);
    });

    expect(mockLogout).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('Fallo de red al cerrar sesión')).toBeInTheDocument();

    const dismissBtn = screen.getByRole('button', { name: /Cerrar aviso de error de cierre de sesión/i });
    expect(dismissBtn).toHaveStyle({ minWidth: '44px', minHeight: '44px' });
    fireEvent.click(dismissBtn);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('accepts names with supplementary characters up to 160 code points and rejects over 160 code points', async () => {
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // 100 emojis: 200 UTF-16 code units but only 100 Unicode code points -> valid
    const valid100Emojis = '🚀'.repeat(100);
    fireEvent.change(input, { target: { value: valid100Emojis } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-emoji',
      displayName: valid100Emojis,
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    expect(mockProvisionWorkspace).toHaveBeenCalledWith(valid100Emojis, expect.any(String));

    // 161 emojis: 161 Unicode code points -> exceeds 160 limit
    const tooManyEmojis = '🚀'.repeat(161);
    fireEvent.change(input, { target: { value: tooManyEmojis } });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    expect(screen.getByText('El nombre del espacio no puede exceder los 160 caracteres.')).toBeInTheDocument();
  });

  it('normalizes boundary whitespace characters identically to backend for idempotency key association', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new Error('Ambiguous network error'));
    render(<NoMembershipsView />);

    const input = screen.getByLabelText(/Nombre del negocio/i);
    const submitBtn = screen.getByRole('button', { name: /Crear espacio de trabajo/i });

    // Input with U+0085 (next line) and U+001F (unit separator)
    const rawWithBoundary = '\u0085Café del Valle\u001F';
    fireEvent.change(input, { target: { value: rawWithBoundary } });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstKey = mockProvisionWorkspace.mock.calls[0][1];
    expect(mockProvisionWorkspace).toHaveBeenCalledWith('Café del Valle', firstKey);

    // User removes the invisible boundary characters, typing clean "Café del Valle"
    fireEvent.change(input, { target: { value: 'Café del Valle' } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-valle',
      displayName: 'Café del Valle',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(submitBtn);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const secondKey = mockProvisionWorkspace.mock.calls[1][1];
    // Key should be preserved because backend canonical name is identical!
    expect(secondKey).toBe(firstKey);
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Café del Valle', firstKey);
  });
});
