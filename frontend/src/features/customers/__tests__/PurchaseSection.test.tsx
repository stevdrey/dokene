import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PurchaseSection } from '@/features/customers/components/PurchaseSection';
import { customerApi } from '@/features/customers/api/customerApi';

describe('PurchaseSection', () => {
  const mockPurchases = [
    {
      id: 'purch-1',
      customerId: 'cust-1',
      purchasedAt: '2025-01-14T10:00:00Z',
      description: 'Kit Harinas Especiales',
      status: 'VALID' as const,
      version: 0,
      createdAt: '2025-01-14T10:00:00Z',
      updatedAt: '2025-01-14T10:00:00Z',
      voidedAt: null
    },
    {
      id: 'purch-2',
      customerId: 'cust-1',
      purchasedAt: '2024-11-18T15:30:00Z',
      description: 'Molde Desmontable',
      status: 'VOID' as const,
      version: 1,
      createdAt: '2024-11-18T15:30:00Z',
      updatedAt: '2024-11-18T16:00:00Z',
      voidedAt: '2024-11-18T16:00:00Z'
    }
  ];

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: mockPurchases,
      nextCursor: null
    });
  });

  it('renders purchase history with valid and void status badges', async () => {
    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    expect(screen.getByText('Molde Desmontable')).toBeInTheDocument();
    expect(screen.getByText('Válida')).toBeInTheDocument();
    expect(screen.getByText('Anulada')).toBeInTheDocument();
  });

  it('opens record purchase modal and submits with idempotency key', async () => {
    vi.spyOn(customerApi, 'recordPurchase').mockResolvedValue({
      id: 'purch-new',
      customerId: 'cust-1',
      purchasedAt: new Date().toISOString(),
      description: 'Bandejas de horneado',
      status: 'VALID',
      version: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      voidedAt: null
    });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Registrar compra/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));

    expect(screen.getByRole('dialog', { name: /Registrar nueva compra/i })).toBeInTheDocument();

    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(descInput, { target: { value: 'Bandejas de horneado' } });

    const submitBtn = screen.getByRole('button', { name: /Guardar compra/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(customerApi.recordPurchase).toHaveBeenCalledWith(
        'cust-1',
        expect.any(String),
        expect.objectContaining({
          description: 'Bandejas de horneado'
        })
      );
    });
  });

  it('validates invalid or missing purchase date without throwing unhandled exceptions', async () => {
    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Registrar compra/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));

    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(descInput, { target: { value: 'Compra de prueba' } });

    const dateInput = screen.getByLabelText(/Fecha y hora de la compra/i);
    fireEvent.change(dateInput, { target: { value: '' } });

    const form = screen.getByRole('dialog', { name: /Registrar nueva compra/i }).querySelector('form')!;
    fireEvent.submit(form);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/La fecha y hora de la compra es obligatoria/i);
    });
  });
});
