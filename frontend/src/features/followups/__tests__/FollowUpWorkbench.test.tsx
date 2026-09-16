import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { FollowUpWorkbench } from '../components/FollowUpWorkbench';
import { useTenant } from '@/features/tenants/TenantContext';
import { followUpApi } from '../api/followUpApi';
import { customerApi } from '@/features/customers/api/customerApi';
import { ApiError } from '@/shared/api/httpClient';

vi.mock('@/features/tenants/TenantContext', () => ({
  useTenant: vi.fn()
}));

const mockItems = [
  {
    customerId: 'cust-1',
    displayName: 'Valentina Morales',
    primaryPhone: '+56984521190',
    status: 'DUE' as const,
    reasons: ['DUE_TODAY' as const],
    dueDate: '2026-09-15',
    timingSource: 'LAST_PURCHASE' as const,
    policyVersion: 2,
    effectiveCadenceDays: 60,
    lastPurchaseAt: '2026-07-17T10:00:00Z',
    lastManualFollowUpDate: null,
    lastDismissedDate: null,
    evaluatedAt: '2026-09-15T12:00:00Z'
  },
  {
    customerId: 'cust-2',
    displayName: 'Marcela Domínguez Peña',
    primaryPhone: '+56991122334',
    status: 'OVERDUE' as const,
    reasons: ['OVERDUE' as const],
    dueDate: '2026-09-10',
    timingSource: 'LAST_PURCHASE' as const,
    policyVersion: 1,
    effectiveCadenceDays: 30,
    lastPurchaseAt: '2026-08-11T10:00:00Z',
    lastManualFollowUpDate: null,
    lastDismissedDate: null,
    evaluatedAt: '2026-09-15T12:00:00Z'
  }
];

describe('FollowUpWorkbench', () => {
  const onNavigateToCustomer = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    onNavigateToCustomer.mockReset();

    (useTenant as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
      activeWorkspace: {
        tenantId: 'tenant-123',
        displayName: 'Café & Taller Artesano',
        role: 'TENANT_ADMIN'
      }
    });

    vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({
      items: mockItems,
      nextCursor: null
    });

    vi.spyOn(followUpApi, 'getCustomerFollowUpPolicy').mockResolvedValue({
      policy: {
        customerId: 'cust-1',
        cadenceDays: 60,
        explicitNextDate: null,
        snoozedUntil: null,
        lastManualFollowUpDate: null,
        lastDismissedDate: null
      },
      version: 2
    });

    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: [
        {
          id: 'p-1',
          customerId: 'cust-1',
          description: 'Kit Harinas Especiales + Esencias',
          purchasedAt: '2026-07-17T10:00:00Z',
          status: 'VALID',
          version: 1,
          createdAt: '2026-07-17T10:00:00Z',
          updatedAt: '2026-07-17T10:00:00Z',
          voidedAt: null
        }
      ],
      nextCursor: null
    });
  });

  it('renders workbench title, stats counters, and queue items', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    expect(screen.getByText('Cargando seguimientos...')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Seguimientos' })).toBeInTheDocument();
      expect(screen.getByText('Ten presente a quién contactar y por qué.')).toBeInTheDocument();
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
      expect(screen.getByText('Marcela Domínguez Peña')).toBeInTheDocument();
    });

    // Stats
    expect(screen.getByText(/1 con retraso/i)).toBeInTheDocument();
  });

  it('selects first item on desktop and displays customer detail context', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('¿Por qué contactar hoy?')).toBeInTheDocument();
      expect(screen.getByText('+56984521190')).toBeInTheDocument();
      expect(screen.getByText('Kit Harinas Especiales + Esencias')).toBeInTheDocument();
    });

    // Verify "Ver cliente" navigation
    const verClienteBtn = screen.getByRole('button', { name: /Ver cliente/i });
    fireEvent.click(verClienteBtn);
    expect(onNavigateToCustomer).toHaveBeenCalledWith('cust-1');
  });

  it('changes selected customer when clicking another card', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    });

    // Click on Marcela's card
    const marcelaCard = screen.getByRole('button', { name: /Marcela Domínguez Peña/i });
    fireEvent.click(marcelaCard);

    await waitFor(() => {
      expect(screen.getAllByText('Marcela Domínguez Peña').length).toBeGreaterThan(1);
    });
  });

  it('filters by status tabs (Vencidos vs Pendientes)', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Pendientes/i })).toBeInTheDocument();
    });

    const vencidosTab = screen.getByRole('button', { name: /Vencidos/i });
    fireEvent.click(vencidosTab);

    await waitFor(() => {
      expect(followUpApi.getFollowUpQueue).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'OVERDUE' }),
        expect.anything()
      );
    });
  });

  it('filters queue items with search bar', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
      expect(screen.getByText('Marcela Domínguez Peña')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/Buscar por nombre o teléfono.../i);
    fireEvent.change(searchInput, { target: { value: 'Marcela' } });

    expect(screen.queryByText('Valentina Morales')).not.toBeInTheDocument();
    expect(screen.getAllByText('Marcela Domínguez Peña').length).toBeGreaterThan(0);
  });

  it('handles manual follow-up completion with modal notes and idempotency key', async () => {
    const recordSpy = vi.spyOn(followUpApi, 'recordManualFollowUp').mockResolvedValue({
      completion: {
        id: 'mfu-1',
        customerId: 'cust-1',
        completedOn: '2026-09-15',
        policyVersion: 3,
        notes: 'Cliente atendido con éxito'
      },
      version: 3
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Registrar seguimiento/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Registrar seguimiento/i }));

    expect(screen.getByText('Registrar seguimiento manual')).toBeInTheDocument();

    const notesInput = screen.getByPlaceholderText(/Conversamos por WhatsApp/i);
    fireEvent.change(notesInput, { target: { value: 'Cliente atendido con éxito' } });

    const submitBtn = screen.getByRole('button', { name: 'Guardar seguimiento' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(recordSpy).toHaveBeenCalledWith(
        'cust-1',
        2,
        expect.any(String),
        'Cliente atendido con éxito'
      );
    });
  });

  it('handles snooze disposition', async () => {
    const snoozeSpy = vi.spyOn(followUpApi, 'snoozeFollowUp').mockResolvedValue({
      policy: {
        customerId: 'cust-1',
        cadenceDays: 60,
        explicitNextDate: null,
        snoozedUntil: '2026-09-18',
        lastManualFollowUpDate: null,
        lastDismissedDate: null
      },
      version: 3
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Posponer/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Posponer/i }));

    expect(screen.getByText('Posponer seguimiento')).toBeInTheDocument();

    // Click "En 3 días" preset
    const presetBtn = screen.getByRole('button', { name: 'En 3 días' });
    fireEvent.click(presetBtn);

    const submitBtn = screen.getByRole('button', { name: 'Confirmar fecha' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(snoozeSpy).toHaveBeenCalledWith('cust-1', expect.any(String), 2);
    });
  });

  it('handles dismissal disposition', async () => {
    const dismissSpy = vi.spyOn(followUpApi, 'dismissFollowUp').mockResolvedValue({
      dismissal: {
        id: 'dis-1',
        customerId: 'cust-1',
        dismissedOn: '2026-09-15',
        policyVersion: 3,
        notes: 'Cliente fuera de la ciudad'
      },
      version: 3
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Descartar/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Descartar/i }));

    expect(screen.getByText('¿Descartar este seguimiento?')).toBeInTheDocument();

    const notesInput = screen.getByPlaceholderText(/no requiere contacto/i);
    fireEvent.change(notesInput, { target: { value: 'Cliente fuera de la ciudad' } });

    const submitBtn = screen.getByRole('button', { name: 'Descartar seguimiento' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(dismissSpy).toHaveBeenCalledWith(
        'cust-1',
        2,
        expect.any(String),
        'Cliente fuera de la ciudad'
      );
    });
  });

  it('handles 409 conflict and displays stale candidate alert', async () => {
    vi.spyOn(followUpApi, 'recordManualFollowUp').mockRejectedValue(
      new ApiError(409, 'Customer is no longer eligible')
    );

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Registrar seguimiento/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Registrar seguimiento/i }));

    const submitBtn = screen.getByRole('button', { name: 'Guardar seguimiento' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText(/El estado del cliente cambió o ya no es elegible para seguimiento/i)
      ).toBeInTheDocument();
    });
  });

  it('restricts mutations for VIEWER role', async () => {
    (useTenant as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
      activeWorkspace: {
        tenantId: 'tenant-123',
        displayName: 'Café & Taller Artesano',
        role: 'VIEWER'
      }
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText(/Tu rol en este espacio es de sólo lectura/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Registrar seguimiento/i })).toBeDisabled();
      expect(screen.getByRole('button', { name: /Posponer/i })).toBeDisabled();
      expect(screen.getByRole('button', { name: /Descartar/i })).toBeDisabled();
    });
  });

  it('renders empty state when queue returns 0 items', async () => {
    vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({
      items: [],
      nextCursor: null
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('No hay seguimientos pendientes')).toBeInTheDocument();
      expect(
        screen.getByText('¡Todo al día! No hay clientes que requieran atención en este momento.')
      ).toBeInTheDocument();
    });
  });

  it('renders error state and allows retry', async () => {
    vi.spyOn(followUpApi, 'getFollowUpQueue').mockRejectedValueOnce(
      new Error('Fallo de conexión')
    );

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('No se pudieron cargar los seguimientos')).toBeInTheDocument();
      expect(screen.getByText('Fallo de conexión')).toBeInTheDocument();
    });

    // Click retry
    const retryBtn = screen.getByRole('button', { name: 'Reintentar' });
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    });
  });

  it('displays the chronologically latest interaction when both manual follow-up and dismissal exist', async () => {
    vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({
      items: [
        {
          ...mockItems[0],
          lastManualFollowUpDate: '2026-08-01',
          lastDismissedDate: '2026-08-15'
        }
      ],
      nextCursor: null
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText(/Último ciclo descartado el 15.*2026/i)).toBeInTheDocument();
    });
  });
});
