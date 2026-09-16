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

  it('renders both historical purchase timestamp and revision timestamp in audit history modal', async () => {
    vi.spyOn(customerApi, 'getPurchaseHistory').mockResolvedValueOnce({
      events: [
        {
          id: 'evt-1',
          type: 'RECORDED',
          purchasedAt: '2025-01-14T10:00:00Z',
          description: 'Kit Harinas Especiales',
          occurredAt: '2025-01-14T10:05:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 0
        },
        {
          id: 'evt-2',
          type: 'CORRECTED',
          purchasedAt: '2025-01-14T12:30:45Z',
          description: 'Kit Harinas Especiales - Corrección de hora',
          occurredAt: '2025-01-14T14:00:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 1
        }
      ],
      nextCursor: null
    });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    const historyBtns = screen.getAllByRole('button', { name: /Ver historial de auditoría/i });
    fireEvent.click(historyBtns[0]);

    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: /Historial de revisiones de compra/i })).toBeInTheDocument();
    });

    expect(screen.getAllByText(/Fecha de compra registrada:/i).length).toBe(2);
    expect(screen.getAllByText(/Revisión:/i).length).toBe(2);
  });

  it('hides create, edit, and void controls when canWrite is false', async () => {
    render(<PurchaseSection customerId="cust-1" isArchived={false} canWrite={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /Registrar compra/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Corregir compra/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Anular compra/i })).not.toBeInTheDocument();
    // History buttons must still be available
    expect(screen.getAllByRole('button', { name: /Ver historial de auditoría/i }).length).toBeGreaterThan(0);
  });

  it('provides descriptive accessible names on row action buttons', async () => {
    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    expect(
      screen.getByRole('button', { name: /^Ver historial de auditoría: Kit Harinas Especiales/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /^Corregir compra: Kit Harinas Especiales/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /^Anular compra: Kit Harinas Especiales/i })
    ).toBeInTheDocument();
  });

  it('paginates purchase revision history in audit modal when historyNextCursor is present', async () => {
    const historySpy = vi.spyOn(customerApi, 'getPurchaseHistory')
      .mockResolvedValueOnce({
        events: [
          {
            id: 'evt-1',
            type: 'RECORDED',
            purchasedAt: '2025-01-14T10:00:00Z',
            description: 'Primera revisión',
            occurredAt: '2025-01-14T10:05:00Z',
            actorId: 'user-1',
            membershipId: 'mem-1',
            purchaseVersion: 0
          }
        ],
        nextCursor: 'cursor-hist-page-2'
      })
      .mockResolvedValueOnce({
        events: [
          {
            id: 'evt-2',
            type: 'CORRECTED',
            purchasedAt: '2025-01-14T10:30:00Z',
            description: 'Segunda revisión histórica',
            occurredAt: '2025-01-14T10:35:00Z',
            actorId: 'user-1',
            membershipId: 'mem-1',
            purchaseVersion: 1
          }
        ],
        nextCursor: null
      });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    const historyBtns = screen.getAllByRole('button', { name: /Ver historial de auditoría/i });
    fireEvent.click(historyBtns[0]);

    await waitFor(() => {
      expect(screen.getByText('Primera revisión')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Cargar revisiones anteriores/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Cargar revisiones anteriores/i }));

    await waitFor(() => {
      expect(screen.getByText('Segunda revisión histórica')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Cargar revisiones anteriores/i })).not.toBeInTheDocument();
    });

    expect(historySpy).toHaveBeenCalledWith('cust-1', 'purch-1', undefined, 50, expect.any(AbortSignal));
    expect(historySpy).toHaveBeenCalledWith('cust-1', 'purch-1', 'cursor-hist-page-2', 50, expect.any(AbortSignal));
  });

  it('discards stale purchase pagination responses when a refresh supersedes it', async () => {
    let resolveLoadMore: ((val: any) => void) | null = null;
    const loadMorePromise = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(customerApi, 'listPurchases')
      .mockResolvedValueOnce({
        purchases: [mockPurchases[0]],
        nextCursor: 'cursor-purch-2'
      })
      .mockImplementationOnce(() => loadMorePromise as any)
      .mockResolvedValueOnce({
        purchases: [
          {
            id: 'purch-refreshed',
            customerId: 'cust-1',
            purchasedAt: '2025-01-20T10:00:00Z',
            description: 'Fresh Refreshed Purchase',
            status: 'VALID',
            version: 0,
            createdAt: '2025-01-20T10:00:00Z',
            updatedAt: '2025-01-20T10:00:00Z',
            voidedAt: null
          }
        ],
        nextCursor: null
      });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Cargar compras anteriores/i })).toBeInTheDocument();
    });

    // Start loading more purchases (in-flight)
    fireEvent.click(screen.getByRole('button', { name: /Cargar compras anteriores/i }));

    // User records/voids or a fresh reload happens in between
    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));
    fireEvent.change(screen.getByLabelText(/Fecha y hora de la compra/i), { target: { value: '2025-01-20T10:00' } });
    fireEvent.change(screen.getByLabelText(/Descripción de la compra o pedido/i), { target: { value: 'Fresh Refreshed Purchase' } });

    vi.spyOn(customerApi, 'recordPurchase').mockResolvedValue({
      id: 'purch-refreshed',
      customerId: 'cust-1',
      purchasedAt: '2025-01-20T10:00:00Z',
      description: 'Fresh Refreshed Purchase',
      status: 'VALID',
      version: 0,
      createdAt: '2025-01-20T10:00:00Z',
      updatedAt: '2025-01-20T10:00:00Z',
      voidedAt: null
    });

    fireEvent.click(screen.getByRole('button', { name: 'Guardar compra' }));

    await waitFor(() => {
      expect(screen.getByText('Fresh Refreshed Purchase')).toBeInTheDocument();
    });

    // Older loadMore resolves late with stale page
    resolveLoadMore!({
      purchases: [
        {
          id: 'purch-stale-page',
          customerId: 'cust-1',
          purchasedAt: '2024-10-01T10:00:00Z',
          description: 'Stale Page Purchase',
          status: 'VALID',
          version: 0,
          createdAt: '2024-10-01T10:00:00Z',
          updatedAt: '2024-10-01T10:00:00Z',
          voidedAt: null
        }
      ],
      nextCursor: null
    });

    await new Promise((r) => setTimeout(r, 50));

    // Stale pagination page must NOT have been appended to the refreshed list
    expect(screen.getByText('Fresh Refreshed Purchase')).toBeInTheDocument();
    expect(screen.queryByText('Stale Page Purchase')).not.toBeInTheDocument();
  });

  it('discards stale purchase-list responses from superseded loads', async () => {
    let resolveInitialLoad: ((val: any) => void) | null = null;
    const initialLoadPromise = new Promise((resolve) => {
      resolveInitialLoad = resolve;
    });

    vi.spyOn(customerApi, 'listPurchases')
      .mockImplementationOnce(() => initialLoadPromise as any)
      .mockResolvedValueOnce({
        purchases: [
          {
            id: 'purch-new',
            customerId: 'cust-1',
            purchasedAt: '2025-01-15T12:00:00Z',
            description: 'Fresh Newly Recorded Purchase',
            status: 'VALID',
            version: 0,
            createdAt: '2025-01-15T12:00:00Z',
            updatedAt: '2025-01-15T12:00:00Z',
            voidedAt: null
          }
        ],
        nextCursor: null
      });

    vi.spyOn(customerApi, 'recordPurchase').mockResolvedValue({
      id: 'purch-new',
      customerId: 'cust-1',
      purchasedAt: '2025-01-15T12:00:00Z',
      description: 'Fresh Newly Recorded Purchase',
      status: 'VALID',
      version: 0,
      createdAt: '2025-01-15T12:00:00Z',
      updatedAt: '2025-01-15T12:00:00Z',
      voidedAt: null
    });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    // While initial load is in-flight, user opens record modal and creates purchase
    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));

    const dateInput = screen.getByLabelText(/Fecha y hora de la compra/i);
    const descInput = screen.getByLabelText(/Descripción de la compra o pedido/i);
    fireEvent.change(dateInput, { target: { value: '2025-01-15T12:00' } });
    fireEvent.change(descInput, { target: { value: 'Fresh Newly Recorded Purchase' } });

    fireEvent.click(screen.getByRole('button', { name: 'Guardar compra' }));

    await waitFor(() => {
      expect(screen.getByText('Fresh Newly Recorded Purchase')).toBeInTheDocument();
    });

    // Now older initial load finally resolves with stale purchase
    resolveInitialLoad!({
      purchases: [
        {
          id: 'purch-stale',
          customerId: 'cust-1',
          purchasedAt: '2025-01-01T10:00:00Z',
          description: 'Stale Purchase From Slow Request',
          status: 'VALID',
          version: 0,
          createdAt: '2025-01-01T10:00:00Z',
          updatedAt: '2025-01-01T10:00:00Z',
          voidedAt: null
        }
      ],
      nextCursor: null
    });

    await new Promise((r) => setTimeout(r, 50));

    // Stale purchase must NOT overwrite the fresh list
    expect(screen.getByText('Fresh Newly Recorded Purchase')).toBeInTheDocument();
    expect(screen.queryByText('Stale Purchase From Slow Request')).not.toBeInTheDocument();
  });

  it('scopes history responses to the selected purchase and discards superseded history loads', async () => {
    let resolvePurchaseA: (value: unknown) => void;
    const purchaseAPromise = new Promise((resolve) => {
      resolvePurchaseA = resolve;
    });

    const getHistorySpy = vi.spyOn(customerApi, 'getPurchaseHistory')
      .mockImplementationOnce(() => purchaseAPromise as any)
      .mockResolvedValueOnce({
        events: [
          {
            id: 'evt-b-1',
            type: 'RECORDED',
            purchasedAt: '2024-11-18T15:30:00Z',
            description: 'Evento de Compra B (Molde Desmontable)',
            occurredAt: '2024-11-18T15:35:00Z',
            actorId: 'user-1',
            membershipId: 'mem-1',
            purchaseVersion: 0
          }
        ],
        nextCursor: null
      });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
      expect(screen.getByText('Molde Desmontable')).toBeInTheDocument();
    });

    const historyBtnA = screen.getByRole('button', { name: /^Ver historial de auditoría: Kit Harinas Especiales/i });
    const historyBtnB = screen.getByRole('button', { name: /^Ver historial de auditoría: Molde Desmontable/i });

    // Operator clicks history for Purchase A (slow request)
    fireEvent.click(historyBtnA);

    // Operator quickly clicks history for Purchase B before A finishes
    fireEvent.click(historyBtnB);

    // Purchase B resolves immediately
    await waitFor(() => {
      expect(screen.getByText('Evento de Compra B (Molde Desmontable)')).toBeInTheDocument();
    });

    // Older slow Purchase A response finally arrives
    resolvePurchaseA!({
      events: [
        {
          id: 'evt-a-1',
          type: 'RECORDED',
          purchasedAt: '2025-01-14T10:00:00Z',
          description: 'Evento Obsoleto de Compra A',
          occurredAt: '2025-01-14T10:05:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 0
        }
      ],
      nextCursor: null
    });

    await new Promise((r) => setTimeout(r, 50));

    // Superseded response for Purchase A must NOT have overwritten Purchase B's events
    expect(screen.getByText('Evento de Compra B (Molde Desmontable)')).toBeInTheDocument();
    expect(screen.queryByText('Evento Obsoleto de Compra A')).not.toBeInTheDocument();
  });

  it('renders correct labels and error badge for VOIDED revisions matching backend enum', async () => {
    vi.spyOn(customerApi, 'getPurchaseHistory').mockResolvedValueOnce({
      events: [
        {
          id: 'evt-rec',
          type: 'RECORDED',
          purchasedAt: '2025-01-14T10:00:00Z',
          description: 'Compra original',
          occurredAt: '2025-01-14T10:05:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 0
        },
        {
          id: 'evt-cor',
          type: 'CORRECTED',
          purchasedAt: '2025-01-14T10:30:00Z',
          description: 'Compra corregida',
          occurredAt: '2025-01-14T10:35:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 1
        },
        {
          id: 'evt-void',
          type: 'VOIDED',
          purchasedAt: '2025-01-14T10:30:00Z',
          description: 'Compra anulada',
          occurredAt: '2025-01-14T11:00:00Z',
          actorId: 'user-1',
          membershipId: 'mem-1',
          purchaseVersion: 2
        }
      ],
      nextCursor: null
    });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    const historyBtn = screen.getByRole('button', { name: /^Ver historial de auditoría: Kit Harinas Especiales/i });
    fireEvent.click(historyBtn);

    await waitFor(() => {
      expect(screen.getByText('Registro inicial')).toBeInTheDocument();
      expect(screen.getByText('Corrección')).toBeInTheDocument();
      expect(screen.getByText('Anulación')).toBeInTheDocument();
    });

    const voidBadge = screen.getByText('Anulación');
    expect(voidBadge.closest('span')).toHaveStyle({ color: 'var(--color-error-text)' });
  });

  it('displays modal error inside the void confirmation dialog when voidPurchase fails', async () => {
    vi.spyOn(customerApi, 'voidPurchase').mockRejectedValueOnce(
      new Error('Conflicto de concurrencia: los datos fueron modificados por otro usuario.')
    );

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    const voidBtn = screen.getByRole('button', { name: /^Anular compra: Kit Harinas Especiales/i });
    fireEvent.click(voidBtn);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '¿Anular esta compra?' })).toBeInTheDocument();
    });

    const confirmBtn = screen.getByRole('button', { name: 'Confirmar anulación' });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      const alert = screen.getByRole('alert');
      expect(alert).toHaveTextContent('Conflicto de concurrencia: los datos fueron modificados por otro usuario.');
    });

    // Dialog remains open with error displayed
    expect(screen.getByRole('heading', { name: '¿Anular esta compra?' })).toBeInTheDocument();
  });

  it('allows correcting and voiding purchases for archived customers but suppresses recording new ones', async () => {
    render(<PurchaseSection customerId="cust-1" isArchived={true} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    // Recording new purchases is hidden for archived customers
    expect(screen.queryByRole('button', { name: /Registrar compra/i })).not.toBeInTheDocument();

    // Corrections and voids remain available for valid historical purchases
    expect(screen.getByRole('button', { name: /^Corregir compra: Kit Harinas Especiales/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Anular compra: Kit Harinas Especiales/i })).toBeInTheDocument();
  });

  it('calls onPurchaseMutated callback when purchase is recorded, corrected, or voided', async () => {
    const onMutatedSpy = vi.fn();
    vi.spyOn(customerApi, 'recordPurchase').mockResolvedValueOnce({
      id: 'purch-new',
      customerId: 'cust-1',
      purchasedAt: new Date().toISOString(),
      description: 'Nueva harina',
      status: 'VALID',
      version: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      voidedAt: null
    });
    vi.spyOn(customerApi, 'correctPurchase').mockResolvedValueOnce({
      id: 'purch-1',
      customerId: 'cust-1',
      purchasedAt: '2025-01-14T10:00:00Z',
      description: 'Kit Harinas Editado',
      status: 'VALID',
      version: 1,
      createdAt: '2025-01-14T10:00:00Z',
      updatedAt: new Date().toISOString(),
      voidedAt: null
    });
    vi.spyOn(customerApi, 'voidPurchase').mockResolvedValueOnce();

    render(<PurchaseSection customerId="cust-1" isArchived={false} onPurchaseMutated={onMutatedSpy} />);

    await waitFor(() => {
      expect(screen.getByText('Kit Harinas Especiales')).toBeInTheDocument();
    });

    // 1. Record purchase
    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));
    const dateInput = screen.getByLabelText(/Fecha y hora de la compra/i);
    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(dateInput, { target: { value: '2025-01-10T12:00' } });
    fireEvent.change(descInput, { target: { value: 'Nueva harina' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar compra' }));

    await waitFor(() => {
      expect(onMutatedSpy).toHaveBeenCalledTimes(1);
    });

    // 2. Correct purchase
    const editBtn = screen.getByRole('button', { name: /^Corregir compra: Kit Harinas Especiales/i });
    fireEvent.click(editBtn);

    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: /Corregir compra/i })).toBeInTheDocument();
    });

    const editDescInput = screen.getByLabelText(/Descripción corregida/i);
    fireEvent.change(editDescInput, { target: { value: 'Kit Harinas Editado' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar corrección' }));

    await waitFor(() => {
      expect(onMutatedSpy).toHaveBeenCalledTimes(2);
    });

    // 3. Void purchase
    const voidBtn = screen.getByRole('button', { name: /^Anular compra: Kit Harinas Especiales/i });
    fireEvent.click(voidBtn);
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar anulación' }));

    await waitFor(() => {
      expect(onMutatedSpy).toHaveBeenCalledTimes(3);
    });
  });

  it('resets pagination loading state when a purchase mutation triggers loadPurchases', async () => {
    let resolveLoadMore: (value: unknown) => void;
    const loadMorePromise = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(customerApi, 'listPurchases')
      .mockResolvedValueOnce({
        purchases: [mockPurchases[0]],
        nextCursor: 'cursor-p2'
      })
      .mockImplementationOnce(() => loadMorePromise as any)
      .mockResolvedValueOnce({
        purchases: [mockPurchases[0]],
        nextCursor: 'cursor-p3'
      });

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Cargar compras anteriores/i })).toBeInTheDocument();
    });

    // Start loadMore
    fireEvent.click(screen.getByRole('button', { name: /Cargar compras anteriores/i }));
    expect(screen.getByRole('button', { name: /Cargando\.\.\./i })).toBeDisabled();

    // Now record a purchase (mutation triggers loadPurchases)
    vi.spyOn(customerApi, 'recordPurchase').mockResolvedValueOnce(mockPurchases[1]);
    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));

    const dateInput = screen.getByLabelText(/Fecha y hora de la compra/i);
    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(dateInput, { target: { value: '2025-01-10T12:00' } });
    fireEvent.change(descInput, { target: { value: 'Nueva harina' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar compra' }));

    // When loadPurchases finishes, pagination button must not remain disabled
    await waitFor(() => {
      const loadMoreBtn = screen.getByRole('button', { name: /Cargar compras anteriores/i });
      expect(loadMoreBtn).not.toBeDisabled();
    });

    resolveLoadMore!({
      purchases: [mockPurchases[1]],
      nextCursor: null
    });
  });

  it('disables form inputs while submitting in record purchase modal', async () => {
    let resolveRecord: (value: unknown) => void;
    const recordPromise = new Promise((resolve) => {
      resolveRecord = resolve;
    });

    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: [mockPurchases[0]],
      nextCursor: null
    });
    vi.spyOn(customerApi, 'recordPurchase').mockImplementationOnce(() => recordPromise as any);

    render(<PurchaseSection customerId="cust-1" isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Registrar compra/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Registrar compra/i }));

    const dateInput = screen.getByLabelText(/Fecha y hora de la compra/i);
    const descInput = screen.getByLabelText(/Descripción de la compra/i);
    fireEvent.change(dateInput, { target: { value: '2025-01-10T12:00' } });
    fireEvent.change(descInput, { target: { value: 'Nueva compra en progreso' } });

    fireEvent.click(screen.getByRole('button', { name: 'Guardar compra' }));

    // During in-flight submit, both fields must be disabled
    expect(dateInput).toBeDisabled();
    expect(descInput).toBeDisabled();

    resolveRecord!(mockPurchases[1]);
  });
});
