import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { AppShell } from './AppShell';
import { useSession } from '../../features/auth/SessionContext';
import { useTenant } from '../../features/tenants/TenantContext';
import { ApiError } from '../../api/apiClient';

vi.mock('../../features/auth/SessionContext', () => ({
  useSession: vi.fn(),
}));

vi.mock('../../features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../features/tenants/TenantContext')>();
  return {
    ...actual,
    useTenant: vi.fn(),
  };
});

describe('AppShell', () => {
  const mockLogout = vi.fn();
  const mockSwitchWorkspace = vi.fn();
  const mockProvisionWorkspace = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.mocked(useSession).mockReturnValue({
      status: 'authenticated',
      identityId: 'd45b7f64-9917-4a62-b3fc-2c963f66afa6',
      csrfToken: 'csrf-123',
      wasExpired: false,
      error: null,
      loginUrl: '/login',
      checkSession: vi.fn(),
      logout: mockLogout,
    });

    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [
        { tenantId: 't-1', displayName: 'Café & Taller Artesano', role: 'ADMIN' },
        { tenantId: 't-2', displayName: 'Floristería del Valle', role: 'OPERATOR' },
      ],
      activeWorkspace: { tenantId: 't-1', displayName: 'Café & Taller Artesano', role: 'ADMIN' },
      error: null,
      switchWorkspace: mockSwitchWorkspace,
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: mockProvisionWorkspace,
    });
  });

  it('renders Dokene brand, navigation links and user initials', () => {
    render(<AppShell />);

    // Brand
    expect(screen.getAllByText('Dokene').length).toBeGreaterThan(0);

    // Navigation links
    expect(screen.getAllByText('Seguimientos').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Clientes').length).toBeGreaterThan(0);
    expect(screen.getByText('Configuración')).toBeInTheDocument();

    // User initials (first two characters uppercase of identityId "d45b...")
    expect(screen.getByText('D4')).toBeInTheDocument();
    expect(screen.getByText('Administrador(a)')).toBeInTheDocument();
  });

  it('switches tabs when clicking navigation buttons', () => {
    render(<AppShell />);

    const clientesBtns = screen.getAllByText('Clientes');
    act(() => {
      clientesBtns[0].click();
    });

    // Content title should reflect Clientes
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Clientes');
  });

  it('calls logout when clicking Cerrar sesión', () => {
    render(<AppShell />);

    const logoutButtons = screen.getAllByRole('button', { name: /Cerrar sesión/i });
    expect(logoutButtons.length).toBeGreaterThan(0);

    act(() => {
      logoutButtons[0].click();
    });

    expect(mockLogout).toHaveBeenCalledTimes(1);
  });

  it('displays error alert when logout fails and allows dismissal', async () => {
    mockLogout.mockRejectedValueOnce(new Error('Fallo de red al cerrar sesión'));
    render(<AppShell />);

    const logoutButtons = screen.getAllByRole('button', { name: /Cerrar sesión/i });
    await act(async () => {
      logoutButtons[0].click();
    });

    expect(mockLogout).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('Fallo de red al cerrar sesión')).toBeInTheDocument();

    const dismissBtn = screen.getByRole('button', { name: 'Cerrar aviso de error' });
    expect(dismissBtn).toHaveStyle({ minWidth: '44px', minHeight: '44px' });
    fireEvent.click(dismissBtn);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('opens workspace dropdown and switches workspace when an option is selected', () => {
    render(<AppShell />);

    const selectorButton = screen.getAllByRole('button', { name: /Seleccionar espacio de trabajo/i })[0];
    expect(selectorButton).toHaveAttribute('aria-expanded', 'false');

    act(() => {
      fireEvent.click(selectorButton);
    });

    expect(selectorButton).toHaveAttribute('aria-expanded', 'true');

    // List of options should appear
    const option = screen.getByText('Floristería del Valle');
    expect(option).toBeInTheDocument();

    act(() => {
      fireEvent.click(option);
    });

    expect(mockSwitchWorkspace).toHaveBeenCalledWith('t-2');
  });

  it('displays creation form in workspace selector and preserves idempotency key on retry', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new ApiError(500, 'Server Error'));
    render(<AppShell />);

    const selectorButton = screen.getAllByRole('button', { name: /Seleccionar espacio de trabajo/i })[0];
    fireEvent.click(selectorButton);

    const newWsButton = screen.getByText('Nuevo espacio de trabajo');
    fireEvent.click(newWsButton);

    const input = screen.getByLabelText('Nombre del nuevo negocio');
    fireEvent.change(input, { target: { value: 'Nuevo Café' } });

    const createButton = screen.getByRole('button', { name: 'Crear' });
    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstCallKey = mockProvisionWorkspace.mock.calls[0][1];
    expect(firstCallKey).toBeDefined();
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Nuevo Café', firstCallKey);
    expect(screen.getByText('No se pudo crear el espacio de trabajo. Inténtalo nuevamente.')).toBeInTheDocument();

    // Retry form submission
    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-3',
      displayName: 'Nuevo Café',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const secondCallKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(secondCallKey).toBe(firstCallKey);
  });

  it('renews idempotency key when changing workspace name in workspace selector after an attempt', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new ApiError(500, 'Server Error'));
    render(<AppShell />);

    const selectorButton = screen.getAllByRole('button', { name: /Seleccionar espacio de trabajo/i })[0];
    fireEvent.click(selectorButton);

    const newWsButton = screen.getByText('Nuevo espacio de trabajo');
    fireEvent.click(newWsButton);

    const input = screen.getByLabelText('Nombre del nuevo negocio');
    fireEvent.change(input, { target: { value: 'Primer Nombre' } });

    const createButton = screen.getByRole('button', { name: 'Crear' });
    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const firstCallKey = mockProvisionWorkspace.mock.calls[0][1];

    // Change the name to a different name
    fireEvent.change(input, { target: { value: 'Segundo Nombre' } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-3',
      displayName: 'Segundo Nombre',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const secondCallKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(secondCallKey).toBeDefined();
    expect(secondCallKey).not.toBe(firstCallKey);
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Segundo Nombre', secondCallKey);
  });

  it('restores the attempted idempotency key when returning to the attempted name in WorkspaceSelector', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new ApiError(500, 'Server Error'));
    render(<AppShell />);

    const selectorButton = screen.getAllByRole('button', { name: /Seleccionar espacio de trabajo/i })[0];
    fireEvent.click(selectorButton);

    const newWsButton = screen.getByText('Nuevo espacio de trabajo');
    fireEvent.click(newWsButton);

    const input = screen.getByLabelText('Nombre del nuevo negocio');
    fireEvent.change(input, { target: { value: 'Nombre Original' } });

    const createButton = screen.getByRole('button', { name: 'Crear' });
    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const originalKey = mockProvisionWorkspace.mock.calls[0][1];

    // Change to another name
    fireEvent.change(input, { target: { value: 'Nombre Temporal' } });

    // Change back to original name
    fireEvent.change(input, { target: { value: 'Nombre Original' } });

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-orig',
      displayName: 'Nombre Original',
      role: 'TENANT_ADMIN',
    });

    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const restoredKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(restoredKey).toBe(originalKey);
    expect(mockProvisionWorkspace).toHaveBeenLastCalledWith('Nombre Original', originalKey);
  });

  it('retains the existing idempotency key when reopening an unfinished creation in WorkspaceSelector', async () => {
    mockProvisionWorkspace.mockRejectedValueOnce(new ApiError(500, 'Server Error'));
    render(<AppShell />);

    const selectorButton = screen.getAllByRole('button', { name: /Seleccionar espacio de trabajo/i })[0];
    fireEvent.click(selectorButton);

    const newWsButton = screen.getByText('Nuevo espacio de trabajo');
    fireEvent.click(newWsButton);

    const input = screen.getByLabelText('Nombre del nuevo negocio');
    fireEvent.change(input, { target: { value: 'Borrador Inconcluso' } });

    const createButton = screen.getByRole('button', { name: 'Crear' });
    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(1);
    const initialKey = mockProvisionWorkspace.mock.calls[0][1];

    // Close the dropdown via Escape
    fireEvent.keyDown(document, { key: 'Escape' });

    // Reopen dropdown and reopen creation form
    fireEvent.click(selectorButton);
    const reopenButton = screen.getByText('Nuevo espacio de trabajo');
    fireEvent.click(reopenButton);

    // The name is preserved
    const reopenedInput = screen.getByLabelText('Nombre del nuevo negocio') as HTMLInputElement;
    expect(reopenedInput.value).toBe('Borrador Inconcluso');

    mockProvisionWorkspace.mockResolvedValueOnce({
      tenantId: 't-unf',
      displayName: 'Borrador Inconcluso',
      role: 'TENANT_ADMIN',
    });

    const submitRetryButton = screen.getByRole('button', { name: 'Crear' });
    await act(async () => {
      fireEvent.click(submitRetryButton);
    });

    expect(mockProvisionWorkspace).toHaveBeenCalledTimes(2);
    const retryKey = mockProvisionWorkspace.mock.calls[1][1];
    expect(retryKey).toBe(initialKey);
  });
});
