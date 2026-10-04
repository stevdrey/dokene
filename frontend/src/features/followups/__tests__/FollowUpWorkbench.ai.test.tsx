import React from 'react';
import { render, screen, waitFor, fireEvent, act, cleanup } from '@testing-library/react';
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
  it('blocks pagination while an AI-triggered background refresh is in flight', async () => {
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockResolvedValue({ items: [baseItem], nextCursor: 'c1' });
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findByRole('button', { name: 'Obtener recomendación' });
    expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeEnabled();

    const refresh = deferred<{ items: QueueItemResponse[]; nextCursor: string | null }>();
    queue.mockClear();
    queue.mockReturnValueOnce(refresh.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    await screen.findByText(/El seguimiento cambió mientras consultabas/);

    const loadMore = screen.getByRole('button', { name: /Cargar más seguimientos|Cargando/ });
    expect(loadMore).toBeDisabled();
    fireEvent.click(loadMore);
    expect(queue).toHaveBeenCalledTimes(1);
    expect(queue).not.toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything());

    await act(async () => {
      refresh.resolve({ items: [{ ...baseItem, policyVersion: 3 }], nextCursor: 'c1' });
    });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar más seguimientos' })).toBeEnabled());
  });

  it('refreshes the queue and says so when the AI reports the customer is no longer eligible', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...recommendation,
        status: 'INELIGIBLE',
        recommendation: null,
        rejectionReason: 'NO_CONTACT_CONSENT',
        evaluation: {
          customerId: 'cust-1',
          eligible: false,
          status: 'INELIGIBLE',
          reasons: ['DO_NOT_CONTACT'],
          evaluatedAt: '2026-09-15T12:00:00Z',
          tenantDate: '2026-09-15',
          tenantTimeZone: 'America/Santiago',
          nextFollowUpDate: null,
          timingSource: 'NONE',
          effectiveCadenceDays: 60,
          lastPurchaseAt: null
        }
      },
      version: 2
    });
    await renderWorkbench();
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockClear();
    queue.mockResolvedValue({ items: [], nextCursor: null });

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    await waitFor(() => expect(queue).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/ya no es elegible para seguimiento/i)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Registrar seguimiento/i })).not.toBeInTheDocument()
    );
  });
  describe('background refresh keeps looking for the selected customer', () => {
    const itemA: QueueItemResponse = { ...baseItem, customerId: 'cust-A', displayName: 'Ana Primera Página' };
    const itemB: QueueItemResponse = { ...baseItem, customerId: 'cust-B', displayName: 'Bruno Segunda Página', policyVersion: 2 };
    const itemX: QueueItemResponse = { ...baseItem, customerId: 'cust-X', displayName: 'Ximena Recién Vencida' };

    async function selectBFromSecondPage() {
      const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
      queue.mockImplementation(async (params: { cursor?: string }) =>
        params.cursor === 'c1' ? { items: [itemB], nextCursor: null } : { items: [itemA], nextCursor: 'c1' }
      );
      const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
      render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
      await screen.findAllByText('Ana Primera Página');
      fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));
      fireEvent.click(await screen.findByRole('button', { name: /Bruno Segunda Página/ }));
      await screen.findByRole('heading', { name: 'Bruno Segunda Página', level: 2 });
      queue.mockClear();
      return { queue, rec };
    }

    it('continues past the previously loaded count when a newly due customer pushes the selection beyond it', async () => {
      const { queue, rec } = await selectBFromSecondPage();
      vi.spyOn(followUpApi, 'getFollowUpEligibility').mockResolvedValue({
        customerId: 'cust-B', eligible: true, status: 'OVERDUE', reasons: ['OVERDUE'], evaluatedAt: '2026-09-15T12:00:00Z',
        tenantDate: '2026-09-15', tenantTimeZone: 'America/Santiago', nextFollowUpDate: null, timingSource: 'LAST_PURCHASE',
        effectiveCadenceDays: 60, lastPurchaseAt: null
      });
      // A new customer became due and now sorts before B: page 1 already holds as many rows as before.
      queue.mockImplementation(async (params: { cursor?: string }) =>
        params.cursor === 'c1'
          ? { items: [{ ...itemB, policyVersion: 3 }], nextCursor: null }
          : { items: [itemA, itemX], nextCursor: 'c1' }
      );

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await screen.findByText(/El seguimiento cambió mientras consultabas/);
      await waitFor(() => expect(queue).toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything()));
      expect(screen.getByRole('heading', { name: 'Bruno Segunda Página', level: 2 })).toBeInTheDocument();
      const update = await screen.findByRole('button', { name: 'Actualizar recomendación' });
      await waitFor(() => expect(update).toBeEnabled());
      rec.mockResolvedValueOnce({ data: { ...recommendation, customerId: 'cust-B' }, version: 3 });
      fireEvent.click(update);
      await waitFor(() => expect(rec).toHaveBeenLastCalledWith('cust-B', 3, expect.any(AbortSignal)));
    });

    it('stops when the cursor is exhausted and falls back when the selected customer is really gone', async () => {
      const { queue } = await selectBFromSecondPage();
      queue.mockImplementation(async (params: { cursor?: string }) =>
        params.cursor === 'c1' ? { items: [itemX], nextCursor: null } : { items: [itemA], nextCursor: 'c1' }
      );

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(queue).toHaveBeenCalledTimes(2));
      await waitFor(() =>
        expect(screen.getByRole('heading', { name: 'Ana Primera Página', level: 2 })).toBeInTheDocument()
      );
      expect(queue).toHaveBeenCalledTimes(2);
    });
  });

  it('announces the ineligibility refresh only after it succeeds, and offers a retry when it fails', async () => {
    const ineligible = {
      ...recommendation,
      status: 'INELIGIBLE' as const,
      recommendation: null,
      rejectionReason: 'NO_CONTACT_CONSENT' as const,
      evaluation: {
        customerId: 'cust-1',
        eligible: false,
        status: 'INELIGIBLE' as const,
        reasons: ['DO_NOT_CONTACT' as const],
        evaluatedAt: '2026-09-15T12:00:00Z',
        tenantDate: '2026-09-15',
        tenantTimeZone: 'America/Santiago',
        nextFollowUpDate: null,
        timingSource: 'NONE' as const,
        effectiveCadenceDays: 60,
        lastPurchaseAt: null
      }
    };
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: ineligible, version: 2 });
    await renderWorkbench();
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    queue.mockClear();
    queue.mockRejectedValueOnce(new Error('boom'));

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByText('No pudimos actualizar la lista de seguimientos.')).toBeInTheDocument();
    expect(screen.queryByText(/ya no es elegible para seguimiento/i)).not.toBeInTheDocument();
    expect(screen.getAllByText('Valentina Morales').length).toBeGreaterThan(0);

    queue.mockResolvedValueOnce({ items: [], nextCursor: null });
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar actualizar lista' }));

    expect(await screen.findByText(/ya no es elegible para seguimiento/i)).toBeInTheDocument();
  });
  describe('background refresh when the selected customer is missing after covering the loaded extent', () => {
    const itemA: QueueItemResponse = { ...baseItem, customerId: 'cust-A', displayName: 'Ana Primera Página' };
    const itemX: QueueItemResponse = { ...baseItem, customerId: 'cust-X', displayName: 'Ximena Recién Vencida' };
    const eligibility = (overrides: Record<string, unknown>) => ({
      customerId: 'cust-A',
      eligible: true,
      status: 'OVERDUE',
      reasons: ['OVERDUE'],
      evaluatedAt: '2026-09-15T12:00:00Z',
      tenantDate: '2026-09-15',
      tenantTimeZone: 'America/Santiago',
      nextFollowUpDate: null,
      timingSource: 'LAST_PURCHASE',
      effectiveCadenceDays: 60,
      lastPurchaseAt: null,
      ...overrides
    });

    async function renderWithSelectedA() {
      const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
      queue.mockImplementation(async () => ({ items: [itemA], nextCursor: 'c1' }));
      vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
      render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
      await screen.findAllByText('Ana Primera Página');
      queue.mockClear();
      // After the change a newly due customer sorts first and A would only be on the next page.
      queue.mockImplementation(async (params: { cursor?: string }) =>
        params.cursor === 'c1'
          ? { items: [{ ...itemA, policyVersion: 3 }], nextCursor: null }
          : { items: [itemX], nextCursor: 'c1' }
      );
      return { queue };
    }

    it('stops after a single eligibility check when the customer is provably no longer in the queue', async () => {
      const { queue } = await renderWithSelectedA();
      const check = vi.spyOn(followUpApi, 'getFollowUpEligibility').mockResolvedValue(
        eligibility({ eligible: false, status: 'INELIGIBLE' }) as never
      );

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(check).toHaveBeenCalledTimes(1));
      expect(check).toHaveBeenCalledWith('cust-A', expect.anything());
      await waitFor(() =>
        expect(screen.getByRole('heading', { name: 'Ximena Recién Vencida', level: 2 })).toBeInTheDocument()
      );
      expect(queue).toHaveBeenCalledTimes(1);
      expect(queue).not.toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything());
    });

    it('keeps following the cursor when the customer is still due and finds it', async () => {
      const { queue } = await renderWithSelectedA();
      const check = vi.spyOn(followUpApi, 'getFollowUpEligibility').mockResolvedValue(eligibility({}) as never);

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(queue).toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything()));
      expect(check).toHaveBeenCalledTimes(1);
      expect(screen.getByRole('heading', { name: 'Ana Primera Página', level: 2 })).toBeInTheDocument();
      expect(await screen.findByRole('button', { name: 'Actualizar recomendación' })).toBeInTheDocument();
    });

    it('stops without a screen error when the eligibility check fails', async () => {
      const { queue } = await renderWithSelectedA();
      const check = vi.spyOn(followUpApi, 'getFollowUpEligibility').mockRejectedValue(new Error('boom'));

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(check).toHaveBeenCalledTimes(1));
      await waitFor(() =>
        expect(screen.getByRole('heading', { name: 'Ximena Recién Vencida', level: 2 })).toBeInTheDocument()
      );
      expect(queue).toHaveBeenCalledTimes(1);
      expect(screen.queryByText(/Error al cargar/i)).not.toBeInTheDocument();
    });

    it('treats a customer that is due but not overdue as gone from the "Vencidos" queue', async () => {
      const { queue } = await renderWithSelectedA();
      queue.mockImplementation(async () => ({ items: [itemA], nextCursor: 'c1' }));
      fireEvent.click(screen.getByRole('button', { name: /Vencidos/i }));
      await waitFor(() => expect(queue).toHaveBeenCalledWith(expect.objectContaining({ status: 'OVERDUE' }), expect.anything()));
      await screen.findAllByText('Ana Primera Página');
      queue.mockClear();
      queue.mockImplementation(async (params: { cursor?: string }) =>
        params.cursor === 'c1'
          ? { items: [{ ...itemA, policyVersion: 3 }], nextCursor: null }
          : { items: [itemX], nextCursor: 'c1' }
      );
      const check = vi.spyOn(followUpApi, 'getFollowUpEligibility').mockResolvedValue(
        eligibility({ status: 'DUE' }) as never
      );
      (followUpApi.requestRecommendation as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(409, 'conflict'));

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(check).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(queue).toHaveBeenCalledTimes(1));
      expect(queue).not.toHaveBeenCalledWith(expect.objectContaining({ cursor: 'c1' }), expect.anything());
    });
  });

  it('covers the whole loaded extent even when the operator had paged past the safety cap', async () => {
    const total = 25;
    const mk = (i: number): QueueItemResponse => ({ ...baseItem, customerId: `cust-${i}`, displayName: `Cliente Número ${i}` });
    const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
    const pageFor = (cursor?: string) => {
      const i = cursor ? Number(cursor) : 0;
      return { items: [mk(i)], nextCursor: i + 1 < total ? String(i + 1) : null };
    };
    queue.mockImplementation(async (params: { cursor?: string }) => pageFor(params.cursor));
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValueOnce(new ApiError(409, 'conflict'));
    render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
    await screen.findAllByText('Cliente Número 0');
    for (let i = 1; i < total; i += 1) {
      fireEvent.click(screen.getByRole('button', { name: 'Cargar más seguimientos' }));
      await screen.findAllByText(`Cliente Número ${i}`);
    }
    queue.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    await screen.findByText(/El seguimiento cambió mientras consultabas/);
    await waitFor(() => expect(queue).toHaveBeenCalledTimes(total));
    expect(screen.getAllByText(`Cliente Número ${total - 1}`).length).toBeGreaterThan(0);
  }, 30000);

  describe('ineligibility notice', () => {
    const itemX: QueueItemResponse = { ...baseItem, customerId: 'cust-X', displayName: 'Ximena Recién Vencida' };
    const itemY: QueueItemResponse = { ...baseItem, customerId: 'cust-Y', displayName: 'Yolanda Tercera' };
    const ineligibleResult = {
      ...recommendation,
      status: 'INELIGIBLE' as const,
      recommendation: null,
      rejectionReason: 'NO_CONTACT_CONSENT' as const,
      evaluation: {
        customerId: 'cust-1',
        eligible: false,
        status: 'INELIGIBLE' as const,
        reasons: ['DO_NOT_CONTACT' as const],
        evaluatedAt: '2026-09-15T12:00:00Z',
        tenantDate: '2026-09-15',
        tenantTimeZone: 'America/Santiago',
        nextFollowUpDate: null,
        timingSource: 'NONE' as const,
        effectiveCadenceDays: 60,
        lastPurchaseAt: null
      }
    };

    async function showNotice() {
      const queue = followUpApi.getFollowUpQueue as ReturnType<typeof vi.fn>;
      queue.mockResolvedValue({ items: [baseItem, itemX, itemY], nextCursor: null });
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: ineligibleResult, version: 2 });
      render(<FollowUpWorkbench onNavigateToCustomer={onNavigateToCustomer} />);
      await screen.findByRole('button', { name: 'Obtener recomendación' });
      queue.mockResolvedValue({ items: [itemX, itemY], nextCursor: null });
      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
      return screen.findByText('Valentina Morales ya no es elegible para seguimiento. La lista se ha actualizado.');
    }

    it('names the customer and survives the programmatic fallback selection of the refresh itself', async () => {
      await showNotice();

      await waitFor(() =>
        expect(screen.getByRole('heading', { name: 'Ximena Recién Vencida', level: 2 })).toBeInTheDocument()
      );
      expect(screen.getByText(/Valentina Morales ya no es elegible/)).toBeInTheDocument();
    });

    it('is cleared when the operator selects another customer', async () => {
      await showNotice();

      fireEvent.click(screen.getByRole('button', { name: /Yolanda Tercera/ }));

      expect(screen.queryByText(/ya no es elegible para seguimiento/)).not.toBeInTheDocument();
    });

    it('is cleared when the operator changes the filter or the search', async () => {
      await showNotice();
      fireEvent.click(screen.getByRole('button', { name: /Vencidos/i }));
      await waitFor(() => expect(screen.queryByText(/ya no es elegible para seguimiento/)).not.toBeInTheDocument());

      cleanup();
      vi.restoreAllMocks();
      setTenant('tenant-123');
      vi.spyOn(followUpApi, 'getTenantFollowUpPolicy').mockResolvedValue({
        policy: { cadenceDays: 14, timeZone: 'America/Santiago' },
        version: 1
      });
      vi.spyOn(followUpApi, 'getCustomerFollowUpPolicy').mockResolvedValue({
        policy: { customerId: 'cust-1', cadenceDays: 60, explicitNextDate: null, snoozedUntil: null, lastManualFollowUpDate: null, lastDismissedDate: null },
        version: 2
      });
      vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({ purchases: [], nextCursor: null });
      vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({ items: [baseItem, itemX, itemY], nextCursor: null });
      await showNotice();
      fireEvent.change(screen.getByPlaceholderText(/Buscar por nombre o teléfono/i), { target: { value: 'Yol' } });
      expect(screen.queryByText(/ya no es elegible para seguimiento/)).not.toBeInTheDocument();
    });
  });
});
