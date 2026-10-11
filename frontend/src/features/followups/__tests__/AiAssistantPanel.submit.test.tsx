// ABOUTME: Tests for the Enviar a aprobación control in the AI assistant panel (spec 0004 AC-6).
// ABOUTME: Covers success, MESSAGE_ALREADY_OPEN, stale, ineligible, missing phone, failing fetch, key rules.

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { AiAssistantPanel } from '@/features/followups/components/AiAssistantPanel';
import { followUpApi } from '@/features/followups/api/followUpApi';
import { customerApi } from '@/features/customers/api/customerApi';
import { messageApi } from '@/features/messages/api/messageApi';
import { ApiError } from '@/shared/api/httpClient';
import type { CustomerResponse } from '@/features/customers/types';
import type { DraftResponse, EvaluationResponse, RecommendationResponse } from '@/features/followups/types';
import type { MessageDetail } from '@/features/messages/types';

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

const rec: RecommendationResponse = {
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

const draft: DraftResponse = {
  status: 'AVAILABLE',
  customerId: 'c-1',
  evaluation,
  draft: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    body: 'Hola Valentina, ¿cómo te fue con el café?',
    draftVariables: [],
    locale: 'es-419',
    evidence: [],
    warnings: [],
    rationale: 'Seguimiento de recompra.',
    confidence: 0.8
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

const customer: CustomerResponse = {
  id: 'c-1',
  displayName: 'Valentina Morales Gómez',
  notes: null,
  phones: [
    { id: 'phone-old', e164: '+56911111111', primary: false },
    { id: 'phone-1', e164: '+56984521190', primary: true }
  ],
  status: 'ACTIVE',
  version: 2,
  createdAt: '2024-09-01T10:00:00Z',
  updatedAt: '2025-01-14T10:00:00Z',
  archivedAt: null
};

const submittedDetail: MessageDetail = {
  messageId: 'msg-9',
  customerId: 'c-1',
  customerDisplayName: 'Valentina Morales Gómez',
  contactId: 'phone-1',
  recipientPhone: '+56984521190',
  channel: 'WHATSAPP',
  status: 'PENDING_APPROVAL',
  outcomeUnknown: false,
  action: 'REPEAT_PURCHASE_FOLLOW_UP',
  templateIntent: 'REPEAT_PURCHASE',
  locale: 'es-419',
  origin: 'AI_DRAFT',
  body: 'Hola Valentina, ¿cómo te fue con el café?',
  sourcePolicyVersion: 3,
  approval: null,
  cancellation: null,
  attempts: [],
  lastAttempt: null,
  sentAt: null,
  deliveredAt: null,
  readAt: null,
  contactRemoved: false,
  createdAt: '2026-10-10T14:05:00Z',
  updatedAt: '2026-10-10T14:05:00Z',
  version: 1
};

function problem(status: number, code: string, retryAfterSeconds?: number) {
  return new ApiError(status, 'refused', { status, message: '', code }, { retryAfterSeconds });
}

function renderPanel(overrides: Partial<React.ComponentProps<typeof AiAssistantPanel>> = {}) {
  const props = {
    customerId: 'c-1',
    policyVersion: 3,
    canUseAi: true,
    onRequestRefresh: vi.fn().mockResolvedValue(true),
    canSubmitMessage: true,
    onOpenMessage: vi.fn(),
    onOpenCustomerMessages: vi.fn(),
    ...overrides
  };
  render(<AiAssistantPanel {...props} />);
  return props;
}

async function openDraft() {
  fireEvent.click(screen.getByRole('button', { name: 'Obtener recomendación' }));
  await screen.findByText(rec.recommendation!.rationale);
  fireEvent.click(screen.getByRole('button', { name: 'Generar borrador' }));
  return screen.findByLabelText('Borrador del mensaje (editable)');
}

describe('AiAssistantPanel · Enviar a aprobación', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: rec, version: 3 });
    vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: draft, version: 3 });
  });

  it('is hidden for a role without message actions and disabled until a draft exists', async () => {
    renderPanel({ canSubmitMessage: false });
    await openDraft();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).not.toBeInTheDocument();
  });

  it('resolves the primary contact, submits the edited draft and locks the panel on 201', async () => {
    const getCustomer = vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    const submit = vi.spyOn(messageApi, 'submit').mockResolvedValue({ detail: submittedDetail, version: 1 });
    const props = renderPanel();
    const textarea = await openDraft();
    fireEvent.change(textarea, { target: { value: 'Hola Valentina, ¿cómo te fue con el café? Tenemos más.' } });

    const button = screen.getByRole('button', { name: 'Enviar a aprobación' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    expect(await screen.findByText('Mensaje enviado a aprobación.')).toBeInTheDocument();
    expect(getCustomer).toHaveBeenCalledWith('c-1');
    expect(submit).toHaveBeenCalledTimes(1);
    const [customerId, input] = submit.mock.calls[0];
    expect(customerId).toBe('c-1');
    expect(input.policyVersion).toBe(3);
    expect(input.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(input.request).toEqual({
      contactId: 'phone-1',
      action: 'REPEAT_PURCHASE_FOLLOW_UP',
      templateIntent: 'REPEAT_PURCHASE',
      body: 'Hola Valentina, ¿cómo te fue con el café? Tenemos más.',
      locale: 'es-419',
      origin: 'AI_DRAFT'
    });

    // Locked: textarea, regenerate and requery; copy stays; the link opens the message.
    expect(screen.getByLabelText('Borrador del mensaje (editable)')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Regenerar borrador' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Consultar de nuevo' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Copiar borrador' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Ver en Mensajes' }));
    expect(props.onOpenMessage).toHaveBeenCalledWith('msg-9');
    expect(props.onRequestRefresh).toHaveBeenCalled();

    // The whole words Aprobar, Enviar, Programar and Enviado appear nowhere in the panel.
    const text = screen.getByRole('heading', { name: 'Asistente IA' }).closest('section')?.textContent ?? '';
    expect(text.replace(/Enviar a aprobación|Mensaje enviado a aprobación\./g, '')).not.toMatch(
      /\b(Aprobar|Enviar|Programar|Enviado)\b/
    );
  });

  it('keeps the draft on MESSAGE_ALREADY_OPEN and links to the customer messages', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    vi.spyOn(messageApi, 'submit').mockRejectedValue(problem(409, 'MESSAGE_ALREADY_OPEN'));
    const props = renderPanel();
    const textarea = await openDraft();
    fireEvent.change(textarea, { target: { value: 'Texto editado' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Ya hay un mensaje abierto para este cliente.');
    expect(screen.getByLabelText('Borrador del mensaje (editable)')).toHaveValue('Texto editado');
    fireEvent.click(within(alert).getByRole('button', { name: 'Ver mensajes del cliente' }));
    expect(props.onOpenCustomerMessages).toHaveBeenCalledWith('c-1', 'Valentina Morales Gómez');
    expect(screen.getByRole('button', { name: 'Enviar a aprobación' })).toBeEnabled();
  });

  it('marks the panel stale on STALE_VERSION and keeps the text without a submitted line', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    vi.spyOn(messageApi, 'submit').mockRejectedValue(problem(409, 'STALE_VERSION'));
    const props = renderPanel();
    const textarea = await openDraft();
    fireEvent.change(textarea, { target: { value: 'Texto editado' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));

    await waitFor(() => expect(props.onRequestRefresh).toHaveBeenCalledWith('stale'));
    expect(await screen.findByRole('alert')).toHaveTextContent(/El seguimiento cambió/);
    expect(screen.getByDisplayValue('Texto editado')).toBeInTheDocument();
    expect(screen.queryByText('Mensaje enviado a aprobación.')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).not.toBeInTheDocument();
  });

  it('shows the ineligible sentence and refreshes the queue on FOLLOW_UP_INELIGIBLE', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    vi.spyOn(messageApi, 'submit').mockRejectedValue(problem(409, 'FOLLOW_UP_INELIGIBLE'));
    const props = renderPanel();
    await openDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El cliente no está elegible para seguimiento ahora.');
    await waitFor(() => expect(props.onRequestRefresh).toHaveBeenCalledWith('ineligible'));
  });

  it('refuses without submitting when the customer has no primary phone', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({
      customer: { ...customer, phones: [{ id: 'phone-old', e164: '+56911111111', primary: false }] },
      version: 2
    });
    const submit = vi.spyOn(messageApi, 'submit');
    renderPanel();
    await openDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El cliente no tiene un teléfono principal.');
    expect(submit).not.toHaveBeenCalled();
  });

  it('refuses without submitting when the customer fetch fails', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockRejectedValue(new ApiError(500, 'boom'));
    const submit = vi.spyOn(messageApi, 'submit');
    renderPanel();
    await openDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo obtener el contacto del cliente.');
    expect(submit).not.toHaveBeenCalled();
  });

  it('reuses the key for a retry with the same text and rotates it when the text changes', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    const submit = vi
      .spyOn(messageApi, 'submit')
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockRejectedValueOnce(problem(503, 'PROVIDER_UNAVAILABLE'))
      .mockResolvedValue({ detail: submittedDetail, version: 1 });
    renderPanel();
    const textarea = await openDraft();

    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo conectar. Intenta de nuevo.');
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('El proveedor de mensajería no está disponible.'));

    fireEvent.change(textarea, { target: { value: 'Otro texto' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));
    await screen.findByText('Mensaje enviado a aprobación.');

    expect(submit).toHaveBeenCalledTimes(3);
    const keys = submit.mock.calls.map(([, input]) => input.idempotencyKey);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
    expect(submit.mock.calls[2][1].request.body).toBe('Otro texto');
  });

  it('shows the rate limit sentence with the Retry-After seconds', async () => {
    vi.spyOn(customerApi, 'getCustomer').mockResolvedValue({ customer, version: 2 });
    vi.spyOn(messageApi, 'submit').mockRejectedValue(problem(429, 'RATE_LIMITED', 12));
    renderPanel();
    await openDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Enviar a aprobación' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Se alcanzó el límite de envíos por minuto. Espera 12 s.');
  });
});
