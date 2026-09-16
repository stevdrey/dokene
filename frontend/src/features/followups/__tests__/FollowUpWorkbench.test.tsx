import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { FollowUpWorkbench } from '../components/FollowUpWorkbench';
import { getCalendarDateInTimeZone } from '../components/SnoozeModal';
import * as dateUtils from '../utils/dateUtils';
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

    vi.spyOn(followUpApi, 'getTenantFollowUpPolicy').mockResolvedValue({
      policy: {
        cadenceDays: 14,
        timeZone: 'America/Santiago'
      },
      version: 1
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

  it('surfaces purchase-history request failures and allows retry', async () => {
    vi.spyOn(customerApi, 'listPurchases')
      .mockRejectedValueOnce(new Error('Network error loading purchases'))
      .mockResolvedValueOnce({
        purchases: [
          {
            id: 'p-retry',
            customerId: 'cust-1',
            description: 'Café Grano Especial',
            purchasedAt: '2026-09-01T10:00:00Z',
            status: 'VALID',
            version: 1,
            createdAt: '2026-09-01T10:00:00Z',
            updatedAt: '2026-09-01T10:00:00Z',
            voidedAt: null
          }
        ],
        nextCursor: null
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('No se pudo cargar el historial de compras.')).toBeInTheDocument();
    });

    const retryPurchasesBtn = screen.getByRole('button', { name: 'Reintentar' });
    fireEvent.click(retryPurchasesBtn);

    await waitFor(() => {
      expect(screen.getByText('Café Grano Especial')).toBeInTheDocument();
    });
  });

  it('distinguishes voided purchases as Anulada in history', async () => {
    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({
      purchases: [
        {
          id: 'p-voided',
          customerId: 'cust-1',
          description: 'Compra Cancelada',
          purchasedAt: '2026-09-01T10:00:00Z',
          status: 'VOID',
          version: 2,
          createdAt: '2026-09-01T10:00:00Z',
          updatedAt: '2026-09-02T10:00:00Z',
          voidedAt: '2026-09-02T10:00:00Z'
        }
      ],
      nextCursor: null
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByText('Compra Cancelada')).toBeInTheDocument();
      expect(screen.getByText('Anulada')).toBeInTheDocument();
    });
  });

  it('excludes overdue items from contacts scheduled for today count', async () => {
    // mockItems has 1 DUE item and 1 OVERDUE item
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      // Due today count should be 1, not 2
      expect(screen.getByText('1 programado')).toBeInTheDocument();
      expect(screen.getByText('1 con retraso')).toBeInTheDocument();
    });
  });

  it('explains due items from their actual timing source (e.g. EXPLICIT_DATE)', async () => {
    vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({
      items: [
        {
          ...mockItems[0],
          status: 'DUE',
          reasons: ['DUE_TODAY'],
          timingSource: 'EXPLICIT_DATE',
          dueDate: '2026-09-15'
        }
      ],
      nextCursor: null
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(
        screen.getByText(/Se ha alcanzado la fecha específica de seguimiento programada manualmente/i)
      ).toBeInTheDocument();
    });
  });

  it('allows loading more pages when active search yields no local results and nextCursor exists', async () => {
    vi.spyOn(followUpApi, 'getFollowUpQueue')
      .mockResolvedValueOnce({
        items: [mockItems[0]], // Valentina Morales
        nextCursor: 'cursor-page-2'
      })
      .mockResolvedValueOnce({
        items: [mockItems[1]], // Marcela Domínguez
        nextCursor: null
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    });

    // Search for Marcela who is on page 2
    const searchInput = screen.getByPlaceholderText('Buscar por nombre o teléfono...');
    fireEvent.change(searchInput, { target: { value: 'Marcela' } });

    await waitFor(() => {
      expect(screen.getByText('Sin resultados en los seguimientos cargados')).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: 'Cargar más seguimientos' })
      ).toBeInTheDocument();
    });

    // Click load more button from empty search state
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));

    await waitFor(() => {
      expect(screen.getAllByText('Marcela Domínguez Peña').length).toBeGreaterThan(0);
    });
  });

  it('calculates snooze dates in tenant time zone without shifting days due to UTC offset', () => {
    // 23:30 in Santiago (UTC-3) on 2026-09-15 is 02:30 UTC on 2026-09-16
    const fixedInstant = new Date('2026-09-16T02:30:00Z');
    vi.useFakeTimers();
    vi.setSystemTime(fixedInstant);

    try {
      // In America/Santiago, it is still September 15
      const santiagoToday = getCalendarDateInTimeZone(0, 'America/Santiago');
      const santiagoTomorrow = getCalendarDateInTimeZone(1, 'America/Santiago');
      const santiagoNextWeek = getCalendarDateInTimeZone(7, 'America/Santiago');

      expect(santiagoToday).toBe('2026-09-15');
      expect(santiagoTomorrow).toBe('2026-09-16');
      expect(santiagoNextWeek).toBe('2026-09-22');

      // Contrast with UTC where it is already September 16
      const utcToday = getCalendarDateInTimeZone(0, 'UTC');
      expect(utcToday).toBe('2026-09-16');
    } finally {
      vi.useRealTimers();
    }
  });

  it('discards stale load-more responses when filter changes before response arrives', async () => {
    let resolveLoadMorePromise!: (value: any) => void;
    const delayedLoadMorePromise = new Promise((resolve) => {
      resolveLoadMorePromise = resolve;
    });

    vi.spyOn(followUpApi, 'getFollowUpQueue')
      // 1. Initial fetch under ALL
      .mockResolvedValueOnce({
        items: [mockItems[0]], // Valentina Morales
        nextCursor: 'cursor-all-page-2'
      })
      // 2. Load more call under ALL (delayed)
      .mockImplementationOnce(() => delayedLoadMorePromise as any)
      // 3. Switch filter to OVERDUE
      .mockResolvedValueOnce({
        items: [mockItems[1]], // Marcela Domínguez
        nextCursor: null
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
      expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeInTheDocument();
    });

    // Operator starts loading more under ALL
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));

    // While request is pending, operator switches filter to "Vencidos" (OVERDUE)
    const vencidosFilterBtn = screen.getByRole('button', { name: /Vencidos/i });
    fireEvent.click(vencidosFilterBtn);

    // Overdue list loads
    await waitFor(() => {
      expect(screen.getAllByText('Marcela Domínguez Peña').length).toBeGreaterThan(0);
    });

    // Now resolve the delayed load-more response from ALL
    resolveLoadMorePromise({
      items: [
        {
          ...mockItems[0],
          customerId: 'stale-customer',
          displayName: 'Stale Customer From All'
        }
      ],
      nextCursor: 'stale-cursor'
    });

    // Wait a tick and verify stale items/cursor were discarded
    await new Promise((r) => setTimeout(r, 50));

    expect(screen.queryByText('Stale Customer From All')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cargar más seguimientos' })).not.toBeInTheDocument();
  });

  it('provides an accessible name for the search input', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(
        screen.getByRole('searchbox', { name: /buscar seguimientos por nombre o teléfono/i })
      ).toBeInTheDocument();
    });
  });

  it('keeps selected card synchronized with filtered detail when search excludes previous customer', async () => {
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    // Initially Valentina (cust-1) is selected
    await waitFor(() => {
      const valentinaCard = screen.getByRole('button', { name: /Valentina Morales/i });
      expect(valentinaCard).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    });

    // Search for Marcela (cust-2), filtering out Valentina
    const searchInput = screen.getByRole('searchbox', { name: /buscar seguimientos/i });
    fireEvent.change(searchInput, { target: { value: 'Marcela' } });

    await waitFor(() => {
      const marcelaCard = screen.getByRole('button', { name: /Marcela Domínguez Peña/i });
      expect(marcelaCard).toHaveAttribute('aria-pressed', 'true');
    });

    // Clear search: Marcela remains selected instead of jumping back to Valentina
    fireEvent.change(searchInput, { target: { value: ' ' } });

    await waitFor(() => {
      const marcelaCard = screen.getByRole('button', { name: /Marcela Domínguez Peña/i });
      expect(marcelaCard).toHaveAttribute('aria-pressed', 'true');
    });
  });

  it('resets loadingMore state when replacing queue while load-more was pending', async () => {
    let resolveLoadMore!: (val: any) => void;
    const pendingLoadMore = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(followUpApi, 'getFollowUpQueue')
      .mockResolvedValueOnce({
        items: [mockItems[0]],
        nextCursor: 'cursor-p1'
      })
      .mockImplementationOnce(() => pendingLoadMore as any)
      .mockResolvedValueOnce({
        items: [mockItems[1]],
        nextCursor: 'cursor-vencidos-p2'
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeInTheDocument();
    });

    // Click load more
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));

    // Switch to Vencidos before load more resolves
    fireEvent.click(screen.getByRole('button', { name: /Vencidos/i }));

    await waitFor(() => {
      // Button for new page with cursor should be enabled, not stuck in loadingMore
      const loadMoreBtn = screen.getByRole('button', { name: 'Cargar más seguimientos' });
      expect(loadMoreBtn).toBeInTheDocument();
      expect(loadMoreBtn).not.toBeDisabled();
    });

    resolveLoadMore({ items: [], nextCursor: null });
  });

  it('surfaces tenant policy error banner and disables snooze until resolved', async () => {
    vi.spyOn(followUpApi, 'getTenantFollowUpPolicy')
      .mockRejectedValueOnce(new Error('Network error loading policy'))
      .mockResolvedValueOnce({
        policy: { cadenceDays: 14, timeZone: 'America/Santiago' },
        version: 1
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(
        screen.getByText(/No se pudo obtener la zona horaria del espacio comercial/i)
      ).toBeInTheDocument();
    });

    // Snooze button should be disabled because timeZone is missing
    const snoozeBtn = screen.getByRole('button', { name: /Posponer/i });
    expect(snoozeBtn).toBeDisabled();

    // Click retry on policy error banner
    const retryPolicyBtn = screen.getByRole('button', { name: /Reintentar cargar política/i });
    fireEvent.click(retryPolicyBtn);

    await waitFor(() => {
      expect(
        screen.queryByText(/No se pudo obtener la zona horaria del espacio comercial/i)
      ).not.toBeInTheDocument();
      expect(snoozeBtn).not.toBeDisabled();
    });
  });

  it('protects tenant time-zone loading across workspace switches against delayed policy responses', async () => {
    let resolvePolicyA!: (val: any) => void;
    const delayedPolicyA = new Promise((resolve) => {
      resolvePolicyA = resolve;
    });

    const tenantMock = useTenant as unknown as ReturnType<typeof vi.fn>;
    tenantMock.mockReturnValue({
      activeWorkspace: { tenantId: 'tenant-A', displayName: 'Workspace A', role: 'TENANT_ADMIN' }
    });

    vi.spyOn(followUpApi, 'getTenantFollowUpPolicy')
      // 1. Tenant A policy request (delayed)
      .mockImplementationOnce(() => delayedPolicyA as any)
      // 2. Tenant B policy request (fast)
      .mockResolvedValueOnce({
        policy: { cadenceDays: 14, timeZone: 'America/Costa_Rica' },
        version: 1
      });

    const { rerender } = render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    // While Tenant A's policy is unresolved, Snooze action is disabled
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Posponer/i })).toBeDisabled();
    });

    // Switch workspace to Tenant B
    tenantMock.mockReturnValue({
      activeWorkspace: { tenantId: 'tenant-B', displayName: 'Workspace B', role: 'TENANT_ADMIN' }
    });
    rerender(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    // Snooze remains disabled while Tenant B's zone is being fetched, then enables once B resolves
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Posponer/i })).not.toBeDisabled();
    });

    // Now delayed Tenant A response completes with a different time zone
    resolvePolicyA({
      policy: { cadenceDays: 14, timeZone: 'America/Santiago' },
      version: 1
    });

    await new Promise((r) => setTimeout(r, 50));

    // Open snooze modal and verify that Tenant B's zone remains authoritative
    fireEvent.click(screen.getByRole('button', { name: /Posponer/i }));
    expect(screen.getByRole('dialog', { name: /Posponer seguimiento/i })).toBeInTheDocument();
    // SnoozeModal should not produce an error and should be active
    expect(screen.getByRole('button', { name: /Confirmar fecha/i })).toBeInTheDocument();
  });

  it('invalidates in-flight pagination when queue is replaced by a successful disposition', async () => {
    let resolveLoadMore!: (val: any) => void;
    const delayedLoadMore = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    vi.spyOn(followUpApi, 'getFollowUpQueue')
      // 1. Initial page 1 with nextCursor
      .mockResolvedValueOnce({
        items: [mockItems[0]],
        nextCursor: 'cursor-page-2'
      })
      // 2. Delayed load-more for page 2
      .mockImplementationOnce(() => delayedLoadMore as any)
      // 3. Replacement queue fetch after disposition
      .mockResolvedValueOnce({
        items: [],
        nextCursor: null
      });

    vi.spyOn(followUpApi, 'recordManualFollowUp').mockResolvedValueOnce({
      completion: {
        id: 'comp-1',
        customerId: 'cust-1',
        completedOn: '2026-09-15',
        notes: 'Llamada realizada',
        policyVersion: 3
      },
      version: 3
    });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeInTheDocument();
    });

    // Operator starts loading more
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));

    // Before loadMore resolves, operator completes a manual follow-up
    fireEvent.click(screen.getByRole('button', { name: /Registrar seguimiento/i }));
    await waitFor(() => {
      expect(screen.getByText('Registrar seguimiento manual')).toBeInTheDocument();
    });
    fireEvent.change(screen.getByPlaceholderText(/Conversamos por WhatsApp/i), {
      target: { value: 'Cliente atendido con éxito' }
    });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar seguimiento' }));

    // Fresh replacement queue completes (empty queue)
    await waitFor(() => {
      expect(screen.getByText('¡Todo al día! No hay clientes que requieran atención en este momento.')).toBeInTheDocument();
    });

    // Delayed page 2 resolves with stale item and cursor
    resolveLoadMore({
      items: [
        {
          ...mockItems[1],
          customerId: 'stale-customer',
          displayName: 'Stale Customer After Disposition'
        }
      ],
      nextCursor: 'stale-cursor'
    });

    await new Promise((r) => setTimeout(r, 50));

    // Stale item must NOT be appended and stale cursor must NOT be restored
    expect(screen.queryByText('Stale Customer After Disposition')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cargar más seguimientos' })).not.toBeInTheDocument();
  });

  it('invalidates in-flight pagination when queue is replaced by tenant-local date rollover', async () => {
    let resolveLoadMore!: (val: any) => void;
    const delayedLoadMore = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    const dateSpy = vi.spyOn(dateUtils, 'getCalendarDateInTimeZone').mockReturnValue('2026-09-15');

    vi.spyOn(followUpApi, 'getFollowUpQueue')
      // 1. Initial page 1 with nextCursor
      .mockResolvedValueOnce({
        items: [mockItems[0]],
        nextCursor: 'cursor-rollover-p2'
      })
      // 2. In-flight pagination
      .mockImplementationOnce(() => delayedLoadMore as any)
      // 3. Queue reload triggered by date rollover
      .mockResolvedValueOnce({
        items: [mockItems[1]],
        nextCursor: null
      });

    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeInTheDocument();
    });

    // Start loading more
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));

    // Simulate tenant calendar date crossing midnight
    dateSpy.mockReturnValue('2026-09-16');
    window.dispatchEvent(new Event('focus'));

    // Rollover reload completes
    await waitFor(() => {
      expect(screen.getAllByText('Marcela Domínguez Peña').length).toBeGreaterThan(0);
    });

    // Now delayed old pagination completes
    resolveLoadMore({
      items: [
        {
          ...mockItems[0],
          customerId: 'stale-rollover-customer',
          displayName: 'Stale Rollover Customer'
        }
      ],
      nextCursor: 'stale-rollover-cursor'
    });

    await new Promise((r) => setTimeout(r, 50));

    expect(screen.queryByText('Stale Rollover Customer')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cargar más seguimientos' })).not.toBeInTheDocument();
    dateSpy.mockRestore();
  });
});
