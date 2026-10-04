import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { AiAssistantPanel } from '@/features/followups/components/AiAssistantPanel';
import { followUpApi, InvalidAiResponseError } from '@/features/followups/api/followUpApi';
import { ApiError } from '@/shared/api/httpClient';
import type {
  RecommendationResponse,
  DraftResponse,
  EvaluationResponse
} from '@/features/followups/types';

const evaluation: EvaluationResponse = {
  customerId: 'c-1',
  eligible: true,
  status: 'DUE',
  reasons: ['DUE_TODAY'],
  evaluatedAt: '2026-09-15T12:00:00Z',
  tenantDate: '2026-09-15',
  tenantTimeZone: 'America/Santiago',
  nextFollowUpDate: '2026-09-15',
  timingSource: 'LAST_PURCHASE',
  effectiveCadenceDays: 60,
  lastPurchaseAt: '2026-07-17T10:00:00Z'
};

const baseRec: RecommendationResponse = {
  status: 'AVAILABLE',
  customerId: 'c-1',
  evaluation,
  recommendation: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    rationale: 'Compró café molido hace 60 días y suele repetir.',
    confidence: 0.82,
    draftVariables: []
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

const baseDraft: DraftResponse = {
  status: 'AVAILABLE',
  customerId: 'c-1',
  evaluation,
  draft: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    body: 'Hola Valentina, ¿cómo te fue con el café?',
    draftVariables: [],
    locale: 'es-419',
    evidence: ['Compra reciente: Café molido'],
    warnings: ['Verifica el stock antes de ofrecer.'],
    rationale: 'Seguimiento de recompra.',
    confidence: 0.8
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

interface PanelOverrides {
  customerId?: string;
  policyVersion?: number;
  canUseAi?: boolean;
  onRequestRefresh?: () => Promise<boolean>;
}

function renderPanel(overrides: PanelOverrides = {}) {
  const props = {
    customerId: 'c-1',
    policyVersion: 3,
    canUseAi: true,
    onRequestRefresh: vi.fn().mockResolvedValue(true),
    ...overrides
  };
  const utils = render(<AiAssistantPanel {...props} />);
  return {
    ...utils,
    props,
    rerenderWith: (next: PanelOverrides) =>
      utils.rerender(<AiAssistantPanel {...props} {...next} />)
  };
}

async function getRecommendation() {
  fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
  await screen.findByText(baseRec.recommendation!.rationale);
}

describe('AiAssistantPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts idle, labels the content as AI advisory and does not call the API on mount', () => {
    const rec = vi.spyOn(followUpApi, 'requestRecommendation');
    renderPanel();

    expect(screen.getByRole('heading', { name: 'Asistente IA' })).toBeInTheDocument();
    expect(screen.getByText(/Sugerencia generada por IA/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Obtener recomendación' })).toBeEnabled();
    expect(rec).not.toHaveBeenCalled();
  });

  it('disables the AI controls and explains why when the role cannot use the assistant', () => {
    const rec = vi.spyOn(followUpApi, 'requestRecommendation');
    renderPanel({ canUseAi: false });

    const button = screen.getByRole('button', { name: 'Obtener recomendación' });
    expect(button).toBeDisabled();
    expect(screen.getByText(/Tu rol no permite usar el asistente IA/)).toBeInTheDocument();
    fireEvent.click(button);
    expect(rec).not.toHaveBeenCalled();
  });

  it('requests the recommendation with the item policy version and shows loading without erasing the panel', async () => {
    const pending = deferred<{ data: RecommendationResponse; version: number }>();
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockReturnValue(pending.promise);
    renderPanel({ policyVersion: 7 });

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(rec).toHaveBeenCalledWith('c-1', 7, expect.any(AbortSignal));
    expect(screen.getByRole('status')).toHaveTextContent('Consultando al asistente');
    expect(screen.getByRole('heading', { name: 'Asistente IA' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Obtener recomendación/ })).toBeDisabled();

    await act(async () => {
      pending.resolve({ data: baseRec, version: 7 });
    });
    expect(await screen.findByText(baseRec.recommendation!.rationale)).toBeInTheDocument();
  });

  it('shows the AI action, rationale and confidence in a region labelled as AI-generated', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    renderPanel();

    await getRecommendation();

    const region = screen.getByRole('region', { name: 'Recomendación de la IA' });
    expect(region).toHaveTextContent('Seguimiento por recompra');
    expect(region).toHaveTextContent('Compró café molido hace 60 días y suele repetir.');
    expect(region).toHaveTextContent('82 %');
  });

  it('renders model text as plain text, never as HTML', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...baseRec,
        recommendation: { ...baseRec.recommendation!, rationale: '<b>negrita</b><img src=x onerror=alert(1)>' }
      },
      version: 3
    });
    const { container } = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByText(/<b>negrita<\/b>/)).toBeInTheDocument();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
  });

  it('falls back to a neutral label for an action value it does not know', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...baseRec,
        recommendation: {
          ...baseRec.recommendation!,
          action: 'FUTURE_ACTION' as unknown as 'GENERAL_CHECK_IN'
        }
      },
      version: 3
    });
    renderPanel();

    await getRecommendation();

    expect(screen.getByRole('region', { name: 'Recomendación de la IA' })).toHaveTextContent(
      'Acción no reconocida'
    );
  });

  it('requests the draft as an explicit second step using the recommended action and intent', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    const draft = vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    expect(draft).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));

    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
    expect(draft).toHaveBeenCalledWith(
      'c-1',
      { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' },
      3,
      expect.any(AbortSignal)
    );
    expect(textarea).toHaveValue('Hola Valentina, ¿cómo te fue con el café?');
    expect(screen.getByText('Compra reciente: Café molido')).toBeInTheDocument();
    expect(screen.getByText('Verifica el stock antes de ofrecer.')).toBeInTheDocument();
  });

  it('moves focus to the draft editor when the draft arrives', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));

    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
    await waitFor(() => expect(textarea).toHaveFocus());
  });

  it('lets the operator edit the draft locally without any further network call', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    const draft = vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');

    fireEvent.change(textarea, { target: { value: 'Hola, ¿necesitas más café?' } });

    expect(textarea).toHaveValue('Hola, ¿necesitas más café?');
    expect(screen.getByText('26 / 1000')).toBeInTheDocument();
    expect(draft).toHaveBeenCalledTimes(1);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('never offers a way to approve or send the message', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    await screen.findByLabelText('Borrador del mensaje (editable)');

    const controls = screen.getAllByRole('button').map((b) => b.textContent ?? '');
    for (const label of controls) {
      expect(label).not.toMatch(/enviar|aprobar|programar|publicar|confirmar env/i);
    }
    expect(screen.getByText(/El envío es manual/)).toBeInTheDocument();
  });

  it('copies the edited draft to the clipboard and announces it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
    fireEvent.change(textarea, { target: { value: 'Texto editado' } });

    fireEvent.click(screen.getByRole('button', { name: 'Copiar borrador' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('Texto editado'));
    expect(await screen.findByText('Borrador copiado')).toBeInTheDocument();
  });

  it('asks for confirmation before regenerating a draft with unsaved edits', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    const draft = vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
    fireEvent.change(textarea, { target: { value: 'Mis cambios' } });

    fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));
    expect(screen.getByText(/Regenerar reemplazará tus cambios/)).toBeInTheDocument();
    expect(draft).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByText(/Regenerar reemplazará tus cambios/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Borrador del mensaje (editable)')).toHaveValue('Mis cambios');

    fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reemplazar mis cambios' }));

    await waitFor(() => expect(draft).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByLabelText('Borrador del mensaje (editable)')).toHaveValue(
        'Hola Valentina, ¿cómo te fue con el café?'
      )
    );
  });

  it('regenerates an unedited draft without asking for confirmation', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    const draft = vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    await screen.findByLabelText('Borrador del mensaje (editable)');

    fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));

    await waitFor(() => expect(draft).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/Regenerar reemplazará tus cambios/)).not.toBeInTheDocument();
  });

  it('prevents duplicate recommendation requests from rapid clicks', async () => {
    const pending = deferred<{ data: RecommendationResponse; version: number }>();
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockReturnValue(pending.promise);
    renderPanel();

    const button = screen.getByRole('button', { name: 'Obtener recomendación' });
    fireEvent.click(button);
    fireEvent.click(button);

    expect(rec).toHaveBeenCalledTimes(1);
  });

  it('shows the advisory refusal when the AI recommends no action', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...baseRec,
        status: 'NO_RECOMMENDATION',
        recommendation: null,
        refusal: { reason: 'RECENTLY_CONTACTED', rationale: 'Se contactó hace dos días.', confidence: 0.7 },
        refusalReason: 'RECENTLY_CONTACTED'
      },
      version: 3
    });
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByText(/La IA no recomienda una acción por ahora/)).toBeInTheDocument();
    expect(screen.getByText(/contactado recientemente/i)).toBeInTheDocument();
    expect(screen.getByText('Se contactó hace dos días.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Generar borrador' })).not.toBeInTheDocument();
  });

  it('shows a deterministic ineligibility message', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...baseRec,
        status: 'INELIGIBLE',
        recommendation: null,
        rejectionReason: 'NO_CONTACT_CONSENT'
      },
      version: 3
    });
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByText(/no tiene consentimiento de contacto/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Generar borrador' })).not.toBeInTheDocument();
  });

  it('keeps the recommendation visible and shows the refusal when no draft can be written', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({
      data: {
        ...baseDraft,
        status: 'NO_DRAFT',
        draft: null,
        refusal: { reason: 'SAFETY_VIOLATION', rationale: 'El texto inventaba una oferta.', confidence: 0.9 },
        refusalReason: 'SAFETY_VIOLATION'
      },
      version: 3
    });
    renderPanel();

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));

    expect(await screen.findByText(/La IA no pudo redactar un borrador/)).toBeInTheDocument();
    expect(screen.getByText('El texto inventaba una oferta.')).toBeInTheDocument();
    expect(screen.getByText(baseRec.recommendation!.rationale)).toBeInTheDocument();
  });

  it('offers a retry only for retryable AI_UNAVAILABLE results and keeps the manual workflow note', async () => {
    const rec = vi
      .spyOn(followUpApi, 'requestRecommendation')
      .mockResolvedValueOnce({
        data: {
          ...baseRec,
          status: 'AI_UNAVAILABLE',
          recommendation: null,
          unavailableReason: 'TIMEOUT',
          retryable: true
        },
        version: 3
      })
      .mockResolvedValueOnce({ data: baseRec, version: 3 });
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('El asistente IA no está disponible en este momento');
    expect(alert).toHaveTextContent('seguimiento manual');

    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByText(baseRec.recommendation!.rationale)).toBeInTheDocument();
    expect(rec).toHaveBeenCalledTimes(2);
  });

  it('does not offer a retry when the AI is not available for the workspace', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: {
        ...baseRec,
        status: 'AI_UNAVAILABLE',
        recommendation: null,
        unavailableReason: 'NOT_AVAILABLE',
        retryable: false
      },
      version: 3
    });
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('no está habilitado');
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });

  it.each([
    [403, /Tu rol no permite usar el asistente IA/],
    [429, /demasiadas solicitudes al asistente IA/i],
    [500, /No pudimos consultar al asistente IA/],
    [503, /No pudimos consultar al asistente IA/]
  ])('shows an accessible error for HTTP %i', async (status, message) => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(status, 'x'));
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByRole('button', { name: 'Obtener recomendación' })).toBeEnabled();
  });

  it('shows a generic error for a malformed AI response', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new InvalidAiResponseError());
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos consultar al asistente IA');
  });

  it('treats a 409 as stale state: asks the workbench to refresh and does not reuse a result', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(409, 'conflict'));
    const { props } = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El seguimiento cambió');
    expect(props.onRequestRefresh).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('region', { name: 'Recomendación de la IA' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeEnabled());
    expect(screen.queryByRole('button', { name: 'Obtener recomendación' })).not.toBeInTheDocument();
  });

  it('treats a STALE_STATE result as stale and asks the workbench to refresh', async () => {
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
      data: { ...baseRec, status: 'STALE_STATE', recommendation: null, rejectionReason: 'STALE_STATE' },
      version: 4
    });
    const { props } = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El seguimiento cambió');
    expect(props.onRequestRefresh).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Obtener recomendación' })).not.toBeInTheDocument();
  });

  it('hides a recommendation and draft once the item policy version changes and requires an explicit refresh', async () => {
    const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
    const { rerenderWith } = renderPanel({ policyVersion: 3 });

    await getRecommendation();
    fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
    await screen.findByLabelText('Borrador del mensaje (editable)');

    rerenderWith({ policyVersion: 4 });

    expect(screen.getByRole('alert')).toHaveTextContent('ya no está actualizada');
    expect(screen.queryByText(baseRec.recommendation!.rationale)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Borrador del mensaje (editable)')).not.toBeInTheDocument();
    expect(rec).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Actualizar recomendación' }));

    await waitFor(() => expect(rec).toHaveBeenCalledTimes(2));
    expect(rec).toHaveBeenLastCalledWith('c-1', 4, expect.any(AbortSignal));
  });

  it('aborts the in-flight request on unmount and ignores its late response', async () => {
    const pending = deferred<{ data: RecommendationResponse; version: number }>();
    let signal: AbortSignal | undefined;
    vi.spyOn(followUpApi, 'requestRecommendation').mockImplementation((_id, _v, s) => {
      signal = s;
      return pending.promise;
    });
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { unmount } = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
    unmount();

    expect(signal?.aborted).toBe(true);
    await act(async () => {
      pending.resolve({ data: baseRec, version: 3 });
    });
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('does not show an error when its own request is aborted', async () => {
    const abort = new DOMException('Aborted', 'AbortError');
    vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(abort);
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Obtener recomendación' })).toBeEnabled()
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  describe('stale state after a conflict', () => {
    async function openDraft(draftImpl: () => Promise<{ data: DraftResponse; version: number }>) {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      const draft = vi.spyOn(followUpApi, 'requestDraft').mockImplementation(draftImpl);
      const utils = renderPanel({ policyVersion: 3 });
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      return { ...utils, draft };
    }

    it.each([
      ['a 409', () => Promise.reject(new ApiError(409, 'conflict'))],
      ['a version mismatch', () => Promise.resolve({ data: baseDraft, version: 4 })],
      [
        'a STALE_STATE result',
        () =>
          Promise.resolve({
            data: { ...baseDraft, status: 'STALE_STATE' as const, draft: null, rejectionReason: 'STALE_STATE' as const },
            version: 4
          })
      ]
    ])('invalidates the recommendation as well when the draft request hits %s', async (_label, impl) => {
      const { props } = await openDraft(impl);

      expect(await screen.findByRole('alert')).toHaveTextContent('El seguimiento cambió');
      expect(screen.queryByRole('region', { name: 'Recomendación de la IA' })).not.toBeInTheDocument();
      expect(screen.queryByText(baseRec.recommendation!.rationale)).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Borrador del mensaje (editable)')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Generar borrador' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Obtener recomendación' })).not.toBeInTheDocument();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeEnabled());
      expect(props.onRequestRefresh).toHaveBeenCalledTimes(1);
    });

    it('does not make old advice actionable again once the queue refresh brings the new version', async () => {
      const { rerenderWith } = await openDraft(() => Promise.resolve({ data: baseDraft, version: 4 }));
      await screen.findByRole('alert');

      rerenderWith({ policyVersion: 4 });

      expect(screen.queryByText(baseRec.recommendation!.rationale)).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Borrador del mensaje (editable)')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeInTheDocument();
    });

    it('re-requests the recommendation with the current version from the refresh action', async () => {
      const { rerenderWith } = await openDraft(() => Promise.reject(new ApiError(409, 'conflict')));
      await screen.findByRole('alert');
      const rec = followUpApi.requestRecommendation as ReturnType<typeof vi.fn>;
      rec.mockClear();

      rerenderWith({ policyVersion: 4 });
      fireEvent.click(screen.getByRole('button', { name: 'Actualizar recomendación' }));

      await waitFor(() => expect(rec).toHaveBeenCalledWith('c-1', 4, expect.any(AbortSignal)));
    });
  });

  describe('edited draft survives a stale recommendation', () => {
    async function editedDraft() {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
      const utils = renderPanel({ policyVersion: 3 });
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
      fireEvent.change(textarea, { target: { value: 'Mi texto editado' } });
      return utils;
    }

    it('keeps the edited text editable and copyable when the result becomes stale', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
      const { rerenderWith } = await editedDraft();

      rerenderWith({ policyVersion: 4 });

      expect(screen.getByRole('alert')).toHaveTextContent('ya no está actualizada');
      expect(screen.queryByText(baseRec.recommendation!.rationale)).not.toBeInTheDocument();
      const kept = screen.getByLabelText(/Tu borrador editado/);
      expect(kept).toHaveValue('Mi texto editado');

      fireEvent.change(kept, { target: { value: 'Mi texto editado y ajustado' } });
      fireEvent.click(screen.getByRole('button', { name: 'Copiar borrador' }));
      await waitFor(() => expect(writeText).toHaveBeenCalledWith('Mi texto editado y ajustado'));
    });

    it('asks before refreshing and keeps the text when the operator cancels', async () => {
      const { rerenderWith } = await editedDraft();
      rerenderWith({ policyVersion: 4 });
      const rec = followUpApi.requestRecommendation as ReturnType<typeof vi.fn>;
      rec.mockClear();

      fireEvent.click(screen.getByRole('button', { name: 'Actualizar recomendación' }));

      expect(screen.getByText('Consultar de nuevo descartará el borrador y tus cambios.')).toBeInTheDocument();
      expect(rec).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
      expect(screen.getByLabelText(/Tu borrador editado/)).toHaveValue('Mi texto editado');
      expect(rec).not.toHaveBeenCalled();
    });

    it('refreshes and drops the edited text once the operator confirms', async () => {
      const { rerenderWith } = await editedDraft();
      rerenderWith({ policyVersion: 4 });
      const rec = followUpApi.requestRecommendation as ReturnType<typeof vi.fn>;
      rec.mockClear();

      fireEvent.click(screen.getByRole('button', { name: 'Actualizar recomendación' }));
      fireEvent.click(screen.getByRole('button', { name: 'Descartar y consultar' }));

      await waitFor(() => expect(rec).toHaveBeenCalledWith('c-1', 4, expect.any(AbortSignal)));
      expect(screen.queryByLabelText(/Tu borrador editado/)).not.toBeInTheDocument();
    });

    it('does not ask for confirmation when the draft was never edited', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
      const { rerenderWith } = renderPanel({ policyVersion: 3 });
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      await screen.findByLabelText('Borrador del mensaje (editable)');
      const rec = followUpApi.requestRecommendation as ReturnType<typeof vi.fn>;
      rec.mockClear();

      rerenderWith({ policyVersion: 4 });
      expect(screen.queryByLabelText(/Tu borrador editado/)).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Actualizar recomendación' }));

      await waitFor(() => expect(rec).toHaveBeenCalledTimes(1));
    });
  });

  describe('discard confirmation wording', () => {
    async function dirtyEditor() {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
      renderPanel();
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
      fireEvent.change(textarea, { target: { value: 'Mis cambios' } });
    }

    it('explains that querying again discards the draft', async () => {
      await dirtyEditor();

      fireEvent.click(screen.getByRole('button', { name: 'Consultar de nuevo' }));

      expect(screen.getByText('Consultar de nuevo descartará el borrador y tus cambios.')).toBeInTheDocument();
      expect(screen.queryByText(/Regenerar reemplazará/)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Reemplazar mis cambios' })).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Descartar y consultar' }));
      await waitFor(() =>
        expect(followUpApi.requestRecommendation).toHaveBeenCalledTimes(2)
      );
    });

    it('keeps the regenerate wording for regenerating the draft', async () => {
      await dirtyEditor();

      fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));

      expect(screen.getByText(/Regenerar reemplazará tus cambios/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Reemplazar mis cambios' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Descartar y consultar' })).not.toBeInTheDocument();
    });
  });

  describe('assistive technology announcements for terminal outcomes', () => {
    it('announces a recommendation refusal in the status region', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
        data: {
          ...baseRec,
          status: 'NO_RECOMMENDATION',
          recommendation: null,
          refusal: { reason: 'RECENTLY_CONTACTED', rationale: 'Se contactó hace dos días.', confidence: 0.7 },
          refusalReason: 'RECENTLY_CONTACTED'
        },
        version: 3
      });
      renderPanel();

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Sin recomendación de la IA'));
    });

    it('announces an ineligible recommendation in the status region', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({
        data: { ...baseRec, status: 'INELIGIBLE', recommendation: null, rejectionReason: 'NO_CONTACT_CONSENT' },
        version: 3
      });
      renderPanel();

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Recomendación no disponible para este cliente')
      );
    });

    it('announces a missing draft and an ineligible draft in the status region', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft')
        .mockResolvedValueOnce({
          data: {
            ...baseDraft,
            status: 'NO_DRAFT',
            draft: null,
            refusal: { reason: 'SAFETY_VIOLATION', rationale: 'Texto inseguro.', confidence: 0.9 },
            refusalReason: 'SAFETY_VIOLATION'
          },
          version: 3
        })
        .mockResolvedValueOnce({
          data: { ...baseDraft, status: 'INELIGIBLE', draft: null, rejectionReason: 'DO_NOT_CONTACT' },
          version: 3
        });
      renderPanel();
      await getRecommendation();

      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Borrador no disponible'));

      fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Borrador no disponible para este cliente')
      );
    });
  });
  describe('draft length counted in Unicode code points', () => {
    async function openEditor() {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: baseDraft, version: 3 });
      renderPanel();
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      return screen.findByLabelText('Borrador del mensaje (editable)');
    }

    it('accepts 1000 emoji (2000 UTF-16 units) and shows 1000 / 1000', async () => {
      const textarea = await openEditor();
      const text = '😀'.repeat(1000);

      fireEvent.change(textarea, { target: { value: text } });

      expect(textarea).toHaveValue(text);
      expect(screen.getByText('1000 / 1000')).toBeInTheDocument();
    });

    it('truncates by code point without leaving a lone surrogate', async () => {
      const textarea = await openEditor();

      fireEvent.change(textarea, { target: { value: '😀'.repeat(1001) } });

      const value = (textarea as HTMLTextAreaElement).value;
      expect([...value]).toHaveLength(1000);
      expect(value).toBe('😀'.repeat(1000));
      expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(value)).toBe(false);
      expect(screen.getByText('1000 / 1000')).toBeInTheDocument();
    });

    it('does not rely on the UTF-16 based maxLength attribute', async () => {
      const textarea = await openEditor();

      expect(textarea).not.toHaveAttribute('maxlength');
    });

    it('counts a draft coming from the API by code point', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({
        data: { ...baseDraft, draft: { ...baseDraft.draft!, body: '😀'.repeat(10) } },
        version: 3
      });
      renderPanel();
      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      await screen.findByLabelText('Borrador del mensaje (editable)');

      expect(screen.getByText('10 / 1000')).toBeInTheDocument();
    });
  });
  describe('stale refresh lifecycle', () => {
    it('keeps "Actualizar recomendación" disabled and announces progress while the list refresh is pending', async () => {
      const pending = deferred<boolean>();
      vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(409, 'conflict'));
      renderPanel({ onRequestRefresh: vi.fn().mockReturnValue(pending.promise) });

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      expect(await screen.findByRole('button', { name: 'Actualizar recomendación' })).toBeDisabled();
      expect(screen.getByRole('status')).toHaveTextContent('Actualizando la lista…');
      expect(screen.getByRole('alert')).not.toHaveTextContent('Actualizamos la lista');

      await act(async () => {
        pending.resolve(true);
      });
      await waitFor(() => expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeEnabled());
      expect(screen.getByRole('alert')).toHaveTextContent('Actualizamos la lista');
    });

    it('reports a failed refresh and only offers to retry the list refresh, never a request with the old version', async () => {
      const rec = vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(409, 'conflict'));
      const onRequestRefresh = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
      renderPanel({ onRequestRefresh });

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      expect(await screen.findByText('No pudimos actualizar la lista de seguimientos.')).toBeInTheDocument();
      expect(screen.getByRole('alert')).not.toHaveTextContent('Actualizamos la lista');
      expect(screen.queryByRole('button', { name: 'Actualizar recomendación' })).not.toBeInTheDocument();
      expect(rec).toHaveBeenCalledTimes(1);

      fireEvent.click(screen.getByRole('button', { name: 'Reintentar actualizar lista' }));

      await waitFor(() => expect(screen.getByRole('button', { name: 'Actualizar recomendación' })).toBeEnabled());
      expect(onRequestRefresh).toHaveBeenCalledTimes(2);
      expect(rec).toHaveBeenCalledTimes(1);
    });

    it('treats a rejected refresh as a failed refresh', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockRejectedValue(new ApiError(409, 'conflict'));
      renderPanel({ onRequestRefresh: vi.fn().mockRejectedValue(new Error('network')) });

      fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));

      expect(await screen.findByRole('button', { name: 'Reintentar actualizar lista' })).toBeInTheDocument();
    });

    it('keeps the edited draft in every refresh state', async () => {
      vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: baseRec, version: 3 });
      vi.spyOn(followUpApi, 'requestDraft').mockRejectedValue(new ApiError(409, 'conflict'));
      const pending = deferred<boolean>();
      const { props } = renderPanel({ onRequestRefresh: vi.fn() });
      (props.onRequestRefresh as ReturnType<typeof vi.fn>).mockReturnValueOnce(pending.promise);
      // First draft succeeds so the operator can edit it; the second one conflicts.
      (followUpApi.requestDraft as ReturnType<typeof vi.fn>)
        .mockResolvedValueOnce({ data: baseDraft, version: 3 })
        .mockRejectedValueOnce(new ApiError(409, 'conflict'));

      await getRecommendation();
      fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
      const textarea = await screen.findByLabelText('Borrador del mensaje (editable)');
      fireEvent.change(textarea, { target: { value: 'Mi texto editado' } });
      fireEvent.click(screen.getByRole('button', { name: 'Regenerar borrador' }));
      fireEvent.click(screen.getByRole('button', { name: 'Reemplazar mis cambios' }));

      expect(await screen.findByLabelText(/Tu borrador editado/)).toHaveValue('Mi texto editado');
      await act(async () => {
        pending.resolve(false);
      });
      expect(await screen.findByRole('button', { name: 'Reintentar actualizar lista' })).toBeInTheDocument();
      expect(screen.getByLabelText(/Tu borrador editado/)).toHaveValue('Mi texto editado');
    });
  });
});
