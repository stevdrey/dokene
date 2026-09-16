import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CustomerProfile, formatEligibilityReason } from '@/features/customers/components/CustomerProfile';
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

  it('distinguishes eligibility dependency failures from negative restrictions and offers retry', async () => {
    const eligSpy = vi.spyOn(customerApi, 'getContactEligibility')
      .mockRejectedValueOnce(new Error('503 Service Unavailable'))
      .mockResolvedValueOnce({
        eligible: true,
        reasons: []
      });

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    // When eligibility failed, it must NOT say "No elegible (Restringido)"
    expect(screen.queryByText(/No elegible/i)).not.toBeInTheDocument();

    // Instead, it displays the specific dependency failure and a retry button
    expect(screen.getByText('503 Service Unavailable')).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: /Reintentar/i });
    expect(retryBtn).toBeInTheDocument();

    // Click retry
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.getByText('Contacto habilitado')).toBeInTheDocument();
      expect(screen.queryByText('503 Service Unavailable')).not.toBeInTheDocument();
    });

    expect(eligSpy).toHaveBeenCalledTimes(2);
  });

  it('translates eligibility reason codes into Spanish before rendering', async () => {
    vi.spyOn(customerApi, 'getContactEligibility').mockResolvedValueOnce({
      eligible: false,
      reasons: ['CONSENT_REVOKED', 'DO_NOT_CONTACT']
    });

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    // Should render translated Spanish labels instead of raw ENUM codes
    expect(
      screen.getByText(/No elegible \(Consentimiento revocado, Restricción No contactar activa\)/i)
    ).toBeInTheDocument();
    expect(screen.queryByText(/CONSENT_REVOKED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/DO_NOT_CONTACT/)).not.toBeInTheDocument();
  });

  it('formats each eligibility reason code correctly and falls back to original value for unknown codes', () => {
    expect(formatEligibilityReason('DO_NOT_CONTACT')).toBe('Restricción No contactar activa');
    expect(formatEligibilityReason('CONSENT_UNKNOWN')).toBe('Sin registro de consentimiento');
    expect(formatEligibilityReason('CONSENT_REVOKED')).toBe('Consentimiento revocado');
    expect(formatEligibilityReason('CONTACT_NOT_ACTIVE')).toBe('Contacto inactivo');
    expect(formatEligibilityReason('CUSTOMER_ARCHIVED')).toBe('Cliente archivado');
    expect(formatEligibilityReason('CUSTOM_REASON_CODE')).toBe('CUSTOM_REASON_CODE');
  });

  it('discards superseded eligibility requests if a newer request completes first', async () => {
    let resolveFirstElig: (value: unknown) => void;
    const firstEligPromise = new Promise((resolve) => {
      resolveFirstElig = resolve;
    });

    vi.spyOn(customerApi, 'getContactEligibility')
      .mockResolvedValueOnce({
        eligible: true,
        reasons: []
      })
      .mockImplementationOnce(() => firstEligPromise as any)
      .mockResolvedValueOnce({
        eligible: false,
        reasons: ['DO_NOT_CONTACT']
      });

    render(<CustomerProfile customerId="cust-abc-123" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
      expect(screen.getByText('Contacto habilitado')).toBeInTheDocument();
    });

    // 1. First mutation triggering first retry (which hangs on firstEligPromise)
    vi.spyOn(customerApi, 'changeConsent').mockResolvedValueOnce({
      customerId: 'cust-abc-123',
      version: 2,
      doNotContact: false,
      doNotContactSource: null,
      doNotContactChangedAt: null,
      consents: []
    });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Gestionar consentimiento/i })).toBeInTheDocument();
    });
    const consentBtn = screen.getByRole('button', { name: /Gestionar consentimiento/i });
    fireEvent.click(consentBtn);
    const revokeRadio = screen.getByRole('radio', { name: /Revocado/i });
    fireEvent.click(revokeRadio);
    fireEvent.click(screen.getByRole('button', { name: 'Guardar consentimiento' }));

    // 2. Second mutation triggering second retry (which resolves with DO_NOT_CONTACT)
    vi.spyOn(customerApi, 'changeDoNotContact').mockResolvedValueOnce({
      customerId: 'cust-abc-123',
      version: 3,
      doNotContact: true,
      doNotContactSource: 'CUSTOMER_VERBAL',
      doNotContactChangedAt: new Date().toISOString(),
      consents: []
    });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Marcar No contactar/i })).toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole('button', { name: /Marcar No contactar/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar restricción' }));

    // Second eligibility call finishes with DO_NOT_CONTACT
    await waitFor(() => {
      expect(screen.getByText(/Restricción No contactar activa/i)).toBeInTheDocument();
    });

    // Now resolve the older first call with "eligible: true"
    resolveFirstElig!({
      eligible: true,
      reasons: []
    });

    // Older response must NOT overwrite the newer state
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText(/Restricción No contactar activa/i)).toBeInTheDocument();
    expect(screen.queryByText('Contacto habilitado')).not.toBeInTheDocument();
  });
});
