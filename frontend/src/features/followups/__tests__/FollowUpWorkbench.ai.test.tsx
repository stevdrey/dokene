import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { FollowUpWorkbench } from '../components/FollowUpWorkbench';
import { useTenant } from '@/features/tenants/TenantContext';
import { followUpApi } from '../api/followUpApi';
import { customerApi } from '@/features/customers/api/customerApi';
import { ApiError } from '@/shared/api/httpClient';
import type { QueueItemResponse, RecommendationResponse } from '../types';

vi.mock('@/features/tenants/TenantContext', () => ({
  useTenant: vi.fn()
}));

const baseItem: QueueItemResponse = {
  customerId: 'cust-1',
  displayName: 'Valentina Morales',
  primaryPhone: '+56984521190',
  status: 'DUE',
  reasons: ['DUE_TODAY'],
  dueDate: '2026-09-15',
  timingSource: 'LAST_PURCHASE',
  policyVersion: 2,
  effectiveCadenceDays: 60,
  lastPurchaseAt: '2026-07-17T10:00:00Z',
  lastManualFollowUpDate: null,
  lastDismissedDate: null,
  evaluatedAt: '2026-09-15T12:00:00Z'
};

const recommendation: RecommendationResponse = {
  status: 'AVAILABLE',
  customerId: 'cust-1',
  evaluation: null,
  recommendation: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    rationale: 'Suele repetir su compra cada dos meses.',
    confidence: 0.8,
    draftVariables: []
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

function setTenant(tenantId: string, role = 'TENANT_ADMIN') {
  (useTenant as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
    activeWorkspace: { tenantId, displayName: `Workspace ${tenantId}`, role }
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('FollowUpWorkbench AI assistance', () => {
  const onNavigateToCustomer = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    setTenant('tenant-123');

    vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({ items: [baseItem], nextCursor: null });
    vi.spyOn(followUpApi, 'getTenantFollowUpPolicy').mockResolvedValue({
      policy: { cadenceDays: 14, timeZone: 'America/Santiago' },
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
    vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({ purchases: [], nextCursor: null });
  });

  async function renderWorkbench() {
    const utils = render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findByRole('button', { name: 'Obtener recomendación' });
    return utils;
  }

  it('keeps the deterministic due reason distinct from the AI recommendation', async () => {
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: recommendation, version: 2 });
    await renderWorkbench();

    expect(screen.getByRole('heading', { name: '¿Por qué contactar hoy?' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    const aiRegion = await screen.findByRole('region', { name: 'Recomendación de la IA' });
    expect(rec).toHaveBeenCalledWith('cust-1', 2, expect.any(AbortSignal));
    expect(aiRegion).toHaveTextContent('Suele repetir su compra cada dos meses.');
    expect(screen.getByRole('heading', { name: '¿Por qué contactar hoy?' })).toBeInTheDocument();
    expect(aiRegion).not.toContainElement(screen.getByRole('heading', { name: '¿Por qué contactar hoy?' }));
  });

  it('keeps customer context and manual actions usable while the AI is loading', async () => {
    const pending = deferred<{ data: RecommendationResponse; version: number }>();
    vi.spyOn(followUpApi, 'requestRecommendation').mockReturnValue(pending.promise);
    await renderWorkbench();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(screen.getByText('Consultando al asistente…')).toBeInTheDocument();
    expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /Registrar seguimiento/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /Descartar/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /Posponer/i })).toBeEnabled();

    await act(async () => {
      pending.resolve({ data: recommendation, version: 2 });
    });
  });

  it('leaves manual follow-up usable when the AI is unavailable', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...recommendation,
        status: 'AI_UNAVAILABLE',
        recommendation: null,
        unavailableReason: 'NOT_AVAILABLE',
        retryable: false
      },
      version: 2
    });
    await renderWorkbench();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText(/no está habilitado/);

    fireEvent.click(screen.getByRole('button', { name: /Registrar seguimiento/i }));
    expect(screen.getByText('Registrar seguimiento manual')).toBeInTheDocument();
  });

  it('disables the AI request for read-only roles while the rest of the detail stays visible', async () => {
    setTenant('tenant-123', 'VIEWER');
    const rec = vi.spyOn(followUpApi, 'requestRecommendation');
    await renderWorkbench();

    const button = screen.getByRole('button', { name: 'Obtener recomendación' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(rec).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: '¿Por qué contactar hoy?' })).toBeInTheDocument();
  });

  it('refreshes the queue when the AI request hits a stale-state conflict', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(409, 'conflict'));
    await renderWorkbench();
    const queueSpy = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    const callsBefore = queueSpy.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    await screen.findByText(/El seguimiento cambió mientras consultabas/);
    await waitFor(() => expect(queueSpy.mock.calls.length).toBeGreaterThan(callsBefore));
  });

  it('does not silently reuse a recommendation after a disposition changes the policy version', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: recommendation, version: 2 });
    vi.spyOn(followUpApi, 'recordManualFollowUp').mockResolvedValue({
      completion: {
        id: 'mfu-1',
        customerId: 'cust-1',
        completedOn: '2026-09-15',
        policyVersion: 3,
        notes: null
      },
      version: 3
    });
    await renderWorkbench();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText('Suele repetir su compra cada dos meses.');

    (followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [{ ...baseItem, policyVersion: 3, reasons: ['OVERDUE'], status: 'OVERDUE' }],
      nextCursor: null
    });
    fireEvent.click(screen.getByRole('button', { name: /Registrar seguimiento/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Guardar seguimiento' }));

    await screen.findByText(/ya no está actualizada/);
    expect(screen.queryByText('Suele repetir su compra cada dos meses.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeInTheDocument();
  });

  it('never shows Tenant A AI output inside Tenant B after a workspace switch', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: recommendation, version: 2 });
    const { rerender } = await renderWorkbench();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText('Suele repetir su compra cada dos meses.');

    setTenant('tenant-B');
    rerender(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);

    await screen.findByRole('button', { name: 'Obtener recomendación' });
    expect(screen.queryByText('Suele repetir su compra cada dos meses.')).not.toBeInTheDocument();
  });

  it('ignores a late Tenant A AI response that resolves after switching to Tenant B', async () => {
    const pendingA = deferred<{ data: RecommendationResponse; version: number }>();
    vi.spyOn(followUpApi, 'requestRecommendation').mockReturnValue(pendingA.promise);
    const { rerender } = await renderWorkbench();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    setTenant('tenant-B');
    rerender(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findByRole('button', { name: 'Obtener recomendación' });

    await act(async () => {
      pendingA.resolve({ data: recommendation, version: 2 });
    });

    expect(screen.queryByText('Suele repetir su compra cada dos meses.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Obtener recomendación' })).toBeEnabled();
  });
  it('keeps the follow-up detail and the edited draft mounted while the AI-triggered refresh is in flight', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: recommendation, version: 2 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValueOnce({
      data: {
        status: 'AVAILABLE',
        customerId: 'cust-1',
        evaluation: null,
        draft: {
          action: 'REPEAT_PURCHASE_FOLLOW_UP',
          templateIntent: 'REPEAT_PURCHASE',
          body: 'Hola Valentina.',
          draftVariables: [],
          locale: 'es-419',
          evidence: [],
          warnings: [],
          rationale: 'r',
          confidence: 0.8
        },
        refusal: null,
        refusalReason: null,
        rejectionReason: null,
        unavailableReason: null,
        retryable: false
      },
      version: 2
    });
    await renderWorkbench();
    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText('Suele repetir su compra cada dos meses.');
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
    fireEvent.change(textarea, { target: { value: 'Mi texto editado' } });

    // The next draft request conflicts; the queue refresh it triggers stays pending.
    vi.spyOn(followUpApi, 'requestDraft').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    const refresh = deferred<{ items: QueueItemResponse[]; nextCursor: string | null }>();
    (followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>).mockReturnValue(refresh.promise);

    fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reemplazar mis cambios' }));

    await screen.findByText(/El seguimiento cambió mientras consultabas/);
    expect(screen.queryByText('Cargando seguimientos...')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Tu borrador editado/)).toHaveValue('Mi texto editado');

    await act(async () => {
      refresh.resolve({ items: [{ ...baseItem, policyVersion: 3 }], nextCursor: null });
    });

    expect(screen.getByLabelText(/Tu borrador editado/)).toHaveValue('Mi texto editado');
    expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Registrar seguimiento/i })).toBeEnabled();
  });

  it('reports a failed AI-triggered refresh, keeps the detail and retries it before allowing a new request', async () => {
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    await renderWorkbench();
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockRejectedValueOnce(new Error('boom'));

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByText('No pudimos actualizar la lista de seguimientos.')).toBeInTheDocument();
    expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Error al cargar/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actualizar recomendación' })).not.toBeInTheDocument();
    expect(rec).toHaveBeenCalledTimes(1);

    queue.mockResolvedValueOnce({ items: [{ ...baseItem, policyVersion: 3 }], nextCursor: null });
    rec.mockResolvedValueOnce({ data: recommendation, version: 3 });
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar actualizar lista' }));

    const update = await screen.findByRole('button', { name: 'Actualizar recomendación' });
    await waitFor(() => expect(update).toBeEnabled());
    fireEvent.click(update);

    await waitFor(() => expect(rec).toHaveBeenLastCalledWith('cust-1', 3, expect.any(AbortSignal)));
    await screen.findByText('Suele repetir su compra cada dos meses.');
  });

  it('keeps a customer loaded from a later page selected (with its stale notice) when the AI refresh reloads the loaded extent', async () => {
    const itemA: QueueItemResponse = { ...baseItem, customerId: 'cust-A', displayName: 'Ana Primera Página' };
    const itemB: QueueItemResponse = { ...baseItem, customerId: 'cust-B', displayName: 'Bruno Segunda Página', policyVersion: 2 };
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockImplementation(async (params: { cursor?: string }) =>
      params.cursor === 'c1' ? { items: [itemB], nextCursor: null } : { items: [itemA], nextCursor: 'c1' }
    );
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findAllByText('Ana Primera Página');
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));
    const cardB = await screen.findByRole('button', { name: /Bruno Segunda Página/ });
    fireEvent.click(cardB);
    await screen.findByRole('heading', { name: 'Bruno Segunda Página', level: 2 });

    // The refresh must now return B with a new version, but only from the second page.
    queue.mockClear();
    queue.mockImplementation(async (params: { cursor?: string }) =>
      params.cursor === 'c1'
        ? { items: [{ ...itemB, policyVersion: 3 }], nextCursor: null }
        : { items: [itemA], nextCursor: 'c1' }
    );
    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    await screen.findByText(/El seguimiento cambió mientras consultabas/);
    await waitFor(() =>
      expect(queue).toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything())
    );
    expect(screen.getByRole('heading', { name: 'Bruno Segunda Página', level: 2 })).toBeInTheDocument();
    const update = await screen.findByRole('button', { name: 'Actualizar recomendación' });
    await waitFor(() => expect(update).toBeEnabled());

    rec.mockResolvedValueOnce({ data: { ...recommendation, customerId: 'cust-B' }, version: 3 });
    fireEvent.click(update);
    await waitFor(() => expect(rec).toHaveBeenLastCalledWith('cust-B', 3, expect.any(AbortSignal)));
  });

  it('does not reload extra pages for a background refresh when only the first page was loaded', async () => {
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockResolvedValue({ items: [baseItem], nextCursor: 'c1' });
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findByRole('button', { name: 'Obtener recomendación' });
    queue.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText(/El seguimiento cambió mientras consultabas/);
    await waitFor(() => expect(queue).toHaveBeenCalledTimes(1));

    expect(queue).not.toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything());
  });
});
