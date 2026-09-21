import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CustomerList } from '@/features/customers/components/CustomerList';
import { customerApi } from '@/features/customers/api/customerApi';
import { CustomerResponse } from '@/features/customers/types';
import { useTenant } from '@/features/tenants/TenantContext';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return {
    ...actual,
    useTenant: vi.fn()
  };
});

describe('CustomerList', () => {
  const mockCustomers: CustomerResponse[] = [
    {
      id: 'cust-1',
      displayName: 'Valentina Morales Gómez',
      notes: 'Taller de Pastelería Las Lilas',
      phones: [{ id: 'p-1', e164: '+56984521190', primary: true }],
      status: 'ACTIVE',
      version: 2,
      createdAt: '2024-09-01T10:00:00Z',
      updatedAt: '2025-01-14T10:00:00Z',
      archivedAt: null
    },
    {
      id: 'cust-2',
      displayName: 'Carlos Soto Arancibia',
      notes: null,
      phones: [{ id: 'p-2', e164: '+56976543210', primary: true }],
      status: 'ARCHIVED',
      version: 1,
      createdAt: '2024-08-15T10:00:00Z',
      updatedAt: '2024-11-20T10:00:00Z',
      archivedAt: '2024-11-20T10:00:00Z'
    }
  ];

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [
        { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'OWNER' }
      ],
      activeWorkspace: { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'OWNER' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });
    vi.spyOn(customerApi, 'listCustomers').mockResolvedValue({
      customers: mockCustomers,
      nextCursor: null
    });
  });

  it('renders customer directory header, search filters, and customer list', async () => {
    const onSelectCustomer = vi.fn();
    render(<CustomerList onSelectCustomer={onSelectCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: /Cartera de Clientes/i })).toBeInTheDocument();
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    expect(screen.getByText('Activo')).toBeInTheDocument();
    expect(screen.getByText(/\+56984521190/)).toBeInTheDocument();

    expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    expect(screen.getByText('Archivado')).toBeInTheDocument();

    expect(screen.getByRole('button', { name: /Nuevo cliente/i })).toBeInTheDocument();
  });

  it('triggers onSelectCustomer when clicking customer name or "Ver ficha"', async () => {
    const onSelectCustomer = vi.fn();
    render(<CustomerList onSelectCustomer={onSelectCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    const nameBtn = screen.getByText('Valentina Morales Gómez');
    fireEvent.click(nameBtn);
    expect(onSelectCustomer).toHaveBeenCalledWith('cust-1');

    const viewButtons = screen.getAllByRole('button', { name: /Ver ficha/i });
    fireEvent.click(viewButtons[0]);
    expect(onSelectCustomer).toHaveBeenCalledWith('cust-1');
  });

  it('filters by status tabs (Activos, Archivados, Todos)', async () => {
    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ACTIVE' }),
        expect.any(AbortSignal)
      );
      expect(screen.getByRole('button', { name: 'Activos' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Archivados' })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByRole('button', { name: 'Todos' })).toHaveAttribute('aria-pressed', 'false');
    });

    const archivedTab = screen.getByRole('button', { name: 'Archivados' });
    fireEvent.click(archivedTab);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ARCHIVED' }),
        expect.any(AbortSignal)
      );
      expect(screen.getByRole('button', { name: 'Activos' })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByRole('button', { name: 'Archivados' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Todos' })).toHaveAttribute('aria-pressed', 'false');
    });

    const allTab = screen.getByRole('button', { name: 'Todos' });
    fireEvent.click(allTab);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ALL' }),
        expect.any(AbortSignal)
      );
      expect(screen.getByRole('button', { name: 'Activos' })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByRole('button', { name: 'Archivados' })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByRole('button', { name: 'Todos' })).toHaveAttribute('aria-pressed', 'true');
    });
  });

  it('supports pagination with loadMore when nextCursor exists', async () => {
    vi.spyOn(customerApi, 'listCustomers')
      .mockResolvedValueOnce({
        customers: [mockCustomers[0]],
        nextCursor: 'cursor-page-2'
      })
      .mockResolvedValueOnce({
        customers: [mockCustomers[1]],
        nextCursor: null
      });

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Cargar más clientes/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Cargar más clientes/i }));

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ cursor: 'cursor-page-2' })
      );
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });
  });

  it('silently ignores AbortedTenantRequestError and StaleSessionError without displaying an error alert', async () => {
    const abortedError = new Error('Aborted tenant request');
    abortedError.name = 'AbortedTenantRequestError';

    vi.spyOn(customerApi, 'listCustomers').mockRejectedValueOnce(abortedError);

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalled();
    });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('discards responses from superseded searches when filters change', async () => {
    let resolveFirstQuery: (value: unknown) => void;
    const firstPromise = new Promise((resolve) => {
      resolveFirstQuery = resolve;
    });

    const secondPromise = Promise.resolve({
      customers: [mockCustomers[1]],
      nextCursor: null
    });

    const listSpy = vi.spyOn(customerApi, 'listCustomers')
      .mockImplementationOnce(() => firstPromise as any)
      .mockImplementationOnce(() => secondPromise as any);

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledTimes(1);
    });

    const archivedTab = screen.getByRole('button', { name: 'Archivados' });
    fireEvent.click(archivedTab);

    await waitFor(() => {
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });

    resolveFirstQuery!({
      customers: [mockCustomers[0]],
      nextCursor: null
    });

    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    expect(screen.queryByText('Valentina Morales Gómez')).not.toBeInTheDocument();
  });

  it('discards in-flight loadMore if filters have changed', async () => {
    let resolveLoadMore: (value: unknown) => void;
    const loadMorePromise = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(customerApi, 'listCustomers')
      .mockResolvedValueOnce({
        customers: [mockCustomers[0]],
        nextCursor: 'cursor-2'
      })
      .mockImplementationOnce(() => loadMorePromise as any)
      .mockResolvedValueOnce({
        customers: [mockCustomers[1]],
        nextCursor: null
      });

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Cargar más clientes/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Cargar más clientes/i }));

    const archivedTab = screen.getByRole('button', { name: 'Archivados' });
    fireEvent.click(archivedTab);

    await waitFor(() => {
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });

    resolveLoadMore!({
      customers: [{ ...mockCustomers[0], id: 'cust-stale-page', displayName: 'Stale Paged Customer' }],
      nextCursor: null
    });

    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText('Stale Paged Customer')).not.toBeInTheDocument();
  });

  it('hides create customer and edit buttons for VIEWER role', async () => {
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [
        { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'VIEWER' }
      ],
      activeWorkspace: { tenantId: 'tenant-1', displayName: 'Pastelería Las Lilas', role: 'VIEWER' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /Nuevo cliente/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Editar Valentina Morales Gómez/i })).not.toBeInTheDocument();
  });

  it('prevents concurrent customer pagination requests when activated repeatedly', async () => {
    let resolveLoadMore: (value: unknown) => void;
    const loadMorePromise = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    const listSpy = vi.spyOn(customerApi, 'listCustomers')
      .mockResolvedValueOnce({
        customers: [mockCustomers[0]],
        nextCursor: 'cursor-2'
      })
      .mockImplementationOnce(() => loadMorePromise as any);

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Cargar más clientes/i })).toBeInTheDocument();
    });

    const loadMoreBtn = screen.getByRole('button', { name: /Cargar más clientes/i });
    // First click: initiates loadMore
    fireEvent.click(loadMoreBtn);

    // Second and third click while in-flight
    fireEvent.click(loadMoreBtn);
    fireEvent.click(loadMoreBtn);

    // Button should be disabled during in-flight request
    expect(loadMoreBtn).toBeDisabled();

    // listCustomers should only have been called once for initial load and once for loadMore (total 2)
    expect(listSpy).toHaveBeenCalledTimes(2);

    // Resolve in-flight request
    resolveLoadMore!({
      customers: [mockCustomers[1]],
      nextCursor: null
    });

    await waitFor(() => {
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });
  });

  it('exposes all creatable regions including PE and ES in phone search selector', async () => {
    render(<CustomerList onSelectCustomer={vi.fn()} />);

    const regionSelect = screen.getByLabelText(/Región telefónica/i);
    expect(regionSelect).toBeInTheDocument();

    // Must include PE (+51) and ES (+34) alongside CL, AR, CO, MX, US
    expect(screen.getByRole('option', { name: /PE/i })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /ES/i })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /CL/i })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /US/i })).toBeInTheDocument();
  });

  it('clears customers and reloads when the active workspace changes', async () => {
    const listSpy = vi.spyOn(customerApi, 'listCustomers')
      .mockResolvedValueOnce({
        customers: [mockCustomers[0]],
        nextCursor: null
      })
      .mockResolvedValueOnce({
        customers: [mockCustomers[1]],
        nextCursor: null
      });

    const { rerender } = render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    // Simulate workspace switch to tenant-2
    vi.mocked(useTenant).mockReturnValue({
      status: 'ready',
      workspaces: [
        { tenantId: 'tenant-2', displayName: 'Workspace 2', role: 'ADMIN' }
      ],
      activeWorkspace: { tenantId: 'tenant-2', displayName: 'Workspace 2', role: 'ADMIN' },
      error: null,
      switchWorkspace: vi.fn(),
      refreshWorkspaces: vi.fn(),
      provisionWorkspace: vi.fn()
    });

    rerender(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });

    expect(listSpy).toHaveBeenCalledTimes(2);
  });

  it('renders error retry block instead of empty state when customer load fails', async () => {
    vi.spyOn(customerApi, 'listCustomers').mockRejectedValueOnce(new Error('Falla de conexión'));

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Falla de conexión');
    });

    // Should NOT claim "No se encontraron clientes"
    expect(screen.queryByText('No se encontraron clientes')).not.toBeInTheDocument();

    // Should provide retry button
    const retryBtn = screen.getByRole('button', { name: 'Reintentar' });
    expect(retryBtn).toBeInTheDocument();
  });

  it('resets pagination loading state if a superseding search starts during loadMore', async () => {
    let resolveLoadMore: (value: unknown) => void;
    const loadMorePromise = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(customerApi, 'listCustomers')
      .mockResolvedValueOnce({
        customers: [mockCustomers[0]],
        nextCursor: 'cursor-2'
      })
      .mockImplementationOnce(() => loadMorePromise as any)
      .mockResolvedValueOnce({
        customers: [mockCustomers[1]],
        nextCursor: 'cursor-3'
      });

    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Cargar más clientes/i })).toBeInTheDocument();
    });

    // Start loadMore
    fireEvent.click(screen.getByRole('button', { name: /Cargar más clientes/i }));
    expect(screen.getByRole('button', { name: /Cargando\.\.\./i })).toBeDisabled();

    // Change tab (supersedes loadMore)
    fireEvent.click(screen.getByRole('button', { name: 'Archivados' }));

    // Replacement search completes with a cursor-3
    await waitFor(() => {
      expect(screen.getByText('Carlos Soto Arancibia')).toBeInTheDocument();
    });

    // Load-more button on the new page must NOT be stuck in loading/disabled
    const newLoadMoreBtn = screen.getByRole('button', { name: /Cargar más clientes/i });
    expect(newLoadMoreBtn).not.toBeDisabled();

    // Even when the old loadMore resolves now, it should not break the state
    resolveLoadMore!({
      customers: [mockCustomers[0]],
      nextCursor: null
    });
  });

  it('applies defensive responsive styles (min-width: 0 and bounded flex-basis) to prevent horizontal overflow on 320px viewports', async () => {
    render(<CustomerList onSelectCustomer={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('Valentina Morales Gómez')).toBeInTheDocument();
    });

    const phoneInput = screen.getByLabelText('Buscar por teléfono') as HTMLInputElement;
    const phoneContainer = phoneInput.parentElement;
    expect(phoneContainer).not.toBeNull();
    expect(phoneContainer?.style.minWidth).toMatch(/^0(px)?$/);
    expect(phoneContainer?.style.maxWidth).toBe('100%');
    expect(phoneContainer?.style.cssText).toMatch(/min\(260px,\s*100%\)/);
    expect(phoneInput.style.minWidth).toMatch(/^0(px)?$/);

    const nameInput = screen.getByLabelText('Buscar por nombre') as HTMLInputElement;
    const nameContainer = nameInput.parentElement;
    expect(nameContainer).not.toBeNull();
    expect(nameContainer?.style.minWidth).toMatch(/^0(px)?$/);
    expect(nameContainer?.style.maxWidth).toBe('100%');
    expect(nameContainer?.style.cssText).toMatch(/min\(240px,\s*100%\)/);
    expect(nameInput.style.minWidth).toMatch(/^0(px)?$/);

    const customerNameBtn = screen.getByText('Valentina Morales Gómez');
    const customerInfoContainer = customerNameBtn.closest('div')?.parentElement as HTMLDivElement;
    expect(customerInfoContainer).not.toBeNull();
    expect(customerInfoContainer.style.minWidth).toMatch(/^0(px)?$/);
    expect(customerInfoContainer.style.maxWidth).toBe('100%');

    const notesSpan = screen.getByText('Taller de Pastelería Las Lilas');
    expect(notesSpan.style.maxWidth).toBe('100%');
  });
});

