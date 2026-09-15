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

  it('retains the same idempotency key across submit retries of the same attempt, and rotates when edited', async () => {
    const recordSpy = vi.spyOn(customerApi, 'recordPurchase')
      .mockRejectedValueOnce(new Error('500 Internal Server Error'))
      .mockResolvedValueOnce({
        id: 'purch-retry',
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

    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(descInput, { target: { value: 'Bandejas de horneado' } });

    const submitBtn = screen.getByRole('button', { name: /Guardar compra/i });
    // First attempt -> fails
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('500 Internal Server Error');
      expect(recordSpy).toHaveBeenCalledTimes(1);
    });

    const firstKey = recordSpy.mock.calls[0][1];

    // Retry submit without editing payload -> must send exact same key
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(recordSpy).toHaveBeenCalledTimes(2);
    });

    const secondKey = recordSpy.mock.calls[1][1];
    expect(secondKey).toBe(firstKey);
  });

  it('preserves purchase seconds and fractional instant when only description is corrected', async () => {
    const originalPurchasedAt = '2025-01-14T10:15:42.123Z';
    const purchaseWithSeconds = {
      id: 'purch-seconds',
      customerId: 'cust-1',
      purchasedAt: originalPurchasedAt,
      description: 'Molde Silicona Original',
      status: 'VALID' as const,
      version: 0,
      createdAt: originalPurchasedAt,
      updatedAt: originalPurchasedAt,
      voidedAt: null
    };

    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: [purchaseWithSeconds],
      nextCursor: null
    });

    const correctSpy = vi.spyOn(customerApi, 'correctPurchase').mockResolvedValue({
      ...purchaseWithSeconds,
      description: 'Molde Silicona Corregido',
      version: 1
    });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Molde Silicona Original')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Corregir compra/i }));

    expect(screen.getByRole('dialog', { name: /Corregir compra/i })).toBeInTheDocument();

    const descInput = screen.getByLabelText(/Descripción corregida/i);
    fireEvent.change(descInput, { target: { value: 'Molde Silicona Corregido' } });

    fireEvent.click(screen.getByRole('button', { name: /Guardar corrección/i }));

    await waitFor(() => {
      expect(correctSpy).toHaveBeenCalledWith(
        'cust-1',
        'purch-seconds',
        0,
        {
          purchasedAt: originalPurchasedAt,
          description: 'Molde Silicona Corregido'
        }
      );
    });
  });
});
