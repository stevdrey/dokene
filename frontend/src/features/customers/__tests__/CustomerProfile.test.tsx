import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CustomerProfile } from '@/features/customers/components/CustomerProfile';
import { customerApi } from '@/features/customers/api/customerApi';
import { useTenant } from '@/features/tenants/TenantContext';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return {
    ...actual,
    useTenant: vi.fn()
  };
});

describe('CustomerProfile', () => {
  const mockCustomer = {
    id: 'cust-abc-123',
    displayName: 'Valentina Morales Gómez',
    notes: 'Taller de Pastelería Las Lilas',
    phones: [{ id: 'phone-1', e164: '+56984521190', primary: true }],
    status: 'ACTIVE' as const,
    version: 2,
    createdAt: '2024-09-01T10:00:00Z',
    updatedAt: '2025-01-14T10:00:00Z',
    archivedAt: null
  };

  const mockLastPurchase = {
    id: 'purch-1',
    customerId: 'cust-abc-123',
    purchasedAt: '2025-01-14T12:00:00Z',
    description: 'Kit Harinas Especiales + Esencias',
    status: 'VALID' as const,
    version: 0,
    createdAt: '2025-01-14T12:00:00Z',
    updatedAt: '2025-01-14T12:00:00Z',
    voidedAt: null
  };

  const mockPolicy = {
    customerId: 'cust-abc-123',
    version: 1,
    doNotContact: false,
    doNotContactSource: null,
    doNotContactChangedAt: null,
    consents: [
      {
        contactId: 'phone-1',
        channel: 'WHATSAPP' as const,
        status: 'GRANTED' as const,
        source: 'CUSTOMER_WRITTEN' as const,
        changedAt: '2024-09-01T10:00:00Z'
      }
    ]
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [{ tenantId: 't-1', displayName: 'Workspace Principal', role: 'OWNER' }],
      activeWorkspace: { tenantId: 't-1', displayName: 'Workspace Principal', role: 'OWNER' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({
      customer: mockCustomer,
      version: 2
    });
    vi.spyOn(customerApi, 'getLastPurchase').mockResolvedValue(mockLastPurchase);
    vi.spyOn(customerApi, 'getContactEligibility').mockResolvedValue({
      eligible: true,
      reasons: []
    });
    vi.spyOn(customerApi, 'getContactPolicy').mockResolvedValue({
      policy: mockPolicy,
      version: 1
    });
    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: [mockLastPurchase],
      nextCursor: null
    });
  });

  it('renders customer header with name, badges, and back navigation', async () => {
    const onBack = vi.fn();
    render(<CustomerProfile customerId="cust-abc-123" onBack={onBack} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Valentina Morales Gómez');
    });

    expect(screen.getByText('Cliente Activo')).toBeInTheDocument();
    expect(screen.getByText(/WhatsApp \(\+56984521190\)/i)).toBeInTheDocument();
    expect(screen.getByText(/Taller de Pastelería Las Lilas/i)).toBeInTheDocument();

    const backBtn = screen.getByRole('button', { name: /Volver a Clientes/i });
    fireEvent.click(backBtn);
    expect(onBack).toHaveBeenCalled();
  });

  it('displays summary card with last purchase information', async () => {
    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Resumen y última actividad')).toBeInTheDocument();
    });

    expect(screen.getAllByText('Kit Harinas Especiales + Esencias').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/Contacto habilitado/i)).toBeInTheDocument();
  });

  it('renders "Sin compras registradas" when getLastPurchase returns null', async () => {
    vi.spyOn(customerApi, 'getLastPurchase').mockResolvedValue(null);

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Resumen y última actividad')).toBeInTheDocument();
    });

    expect(screen.getByText('Sin compras registradas')).toBeInTheDocument();
  });

  it('renders error alert and retry button when getLastPurchase fails, and allows retrying', async () => {
    const getLastPurchaseSpy = vi.spyOn(customerApi, 'getLastPurchase')
      .mockRejectedValueOnce(new Error('500 Error al consultar'))
      .mockResolvedValueOnce(mockLastPurchase);

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('500 Error al consultar');
      expect(screen.getByRole('button', { name: /Reintentar/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Reintentar/i }));

    await waitFor(() => {
      expect(getLastPurchaseSpy).toHaveBeenCalledTimes(2);
      expect(screen.getAllByText('Kit Harinas Especiales + Esencias').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('renders archive button for OWNER and ADMIN roles', async () => {
    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Archivar cliente/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Archivar ficha de cliente/i })).toBeInTheDocument();
    });
  });

  it('hides archive buttons from operators without delete permission', async () => {
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [{ tenantId: 't-1', displayName: 'Workspace Principal', role: 'OPERATOR' }],
      activeWorkspace: { tenantId: 't-1', displayName: 'Workspace Principal', role: 'OPERATOR' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /Archivar cliente/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archivar ficha de cliente/i })).not.toBeInTheDocument();
  });

  it('hides edit and archive buttons for VIEWER role', async () => {
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [{ tenantId: 't-1', displayName: 'Workspace Principal', role: 'VIEWER' }],
      activeWorkspace: { tenantId: 't-1', displayName: 'Workspace Principal', role: 'VIEWER' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /Editar cliente/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archivar/i })).not.toBeInTheDocument();
  });
});
