import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CustomerProfile } from '@/features/customers/components/CustomerProfile';
import { customerApi } from '@/features/customers/api/customerApi';

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
});
