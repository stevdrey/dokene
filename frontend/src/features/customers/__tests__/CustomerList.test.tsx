import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CustomerList } from '@/features/customers/components/CustomerList';
import { customerApi } from '@/features/customers/api/customerApi';
import { CustomerResponse } from '@/features/customers/types';

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
        expect.objectContaining({ status: 'ACTIVE' })
      );
    });

    const archivedTab = screen.getByRole('button', { name: 'Archivados' });
    fireEvent.click(archivedTab);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ARCHIVED' })
      );
    });

    const allTab = screen.getByRole('button', { name: 'Todos' });
    fireEvent.click(allTab);

    await waitFor(() => {
      expect(customerApi.listCustomers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ALL' })
      );
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
});
