// ABOUTME: The composed Phase 3 workbench (issue 140): an AI draft is handed to message review, submitted and approved
// ABOUTME: only through explicit controls, and the delivery history reopens a message. Messaging routes use FakeServer.
import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FollowUpWorkbench } from '../components/FollowUpWorkbench';
import { useTenant } from '@/features/tenants/TenantContext';
import { followUpApi } from '../api/followUpApi';
import { customerApi } from '@/features/customers/api/customerApi';
import { httpClient } from '@/shared/api/httpClient';
import type { CustomerResponse } from '@/features/customers/types';
import type { DraftResponse, QueueItemResponse, RecommendationResponse } from '../types';
import {
  FakeServer,
  approvalsPath,
  created,
  details,
  detailPath,
  detailReply,
  eventsPath,
  eventsReply,
  historyPath,
  ok,
  pages,
  pending,
  problem,
  submitPath,
  variant
} from '@/features/messages/__tests__/messageTestServer';
import { listItemFromDetail } from '@/features/messages/utils/mergeItem';

vi.mock('@/features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/tenants/TenantContext')>();
  return {
    ...actual,
    useTenant: vi.fn()
  };
});

const TENANT = 'tenant-a';
const pendingApproval = details.pendingApproval;
const customerId = pendingApproval.customerId;

const item: QueueItemResponse = {
  customerId,
  displayName: 'Ana Pérez',
  primaryPhone: pendingApproval.recipientPhone,
  status: 'DUE',
  reasons: ['DUE_TODAY'],
  dueDate: '2026-10-13',
  timingSource: 'LAST_PURCHASE',
  policyVersion: 7,
  effectiveCadenceDays: 30,
  lastPurchaseAt: '2026-09-13T10:00:00Z',
  lastManualFollowUpDate: null,
  lastDismissedDate: null,
  evaluatedAt: '2026-10-13T12:00:00Z'
};

const recommendation: RecommendationResponse = {
  status: 'AVAILABLE',
  customerId,
  evaluation: null,
  recommendation: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    rationale: 'Suele repetir su compra cada mes.',
    confidence: 0.8,
    draftVariables: []
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

const draftResponse: DraftResponse = {
  status: 'AVAILABLE',
  customerId,
  evaluation: null,
  draft: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    body: pendingApproval.body,
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
};

function customer(phones: CustomerResponse['phones']): { customer: CustomerResponse; version: number } {
  return {
    customer: {
      id: customerId,
      displayName: 'Ana Pérez',
      notes: null,
      phones,
      status: 'ACTIVE',
      version: 3,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
      archivedAt: null
    } as CustomerResponse,
    version: 3
  };
}

const primaryPhone = { id: pendingApproval.contactId, e164: pendingApproval.recipientPhone, primary: true };

let server: FakeServer;

function setRole(role: string) {
  (useTenant as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
    activeWorkspace: { tenantId: TENANT, displayName: 'Workspace A', role }
  });
}

beforeEach(() => {
  vi.restoreAllMocks();
  setRole('OPERATOR');
  httpClient.setCsrfToken('csrf-a');
  httpClient.setTenantId(TENANT);
  server = new FakeServer();
  server.install();
  server.on(historyPath(customerId), ok({ items: [], nextCursor: null }));
  vi.spyOn(followUpApi, 'getFollowUpQueue').mockResolvedValue({ items: [item], nextCursor: null });
  vi.spyOn(followUpApi, 'getTenantFollowUpPolicy').mockResolvedValue({
    policy: { cadenceDays: 30, timeZone: 'America/Costa_Rica' },
    version: 1
  });
  vi.spyOn(followUpApi, 'requestRecommendation').mockResolvedValue({ data: recommendation, version: 7 });
  vi.spyOn(followUpApi, 'requestDraft').mockResolvedValue({ data: draftResponse, version: 7 });
  vi.spyOn(customerApi, 'listPurchases').mockResolvedValue({ purchases: [], nextCursor: null });
  vi.spyOn(customerApi, 'getCustomer').mockResolvedValue(customer([primaryPhone]));
});

afterEach(() => {
  expect(server.unexpected).toEqual([]);
});

const click = (name: string, scope: HTMLElement = document.body) =>
  fireEvent.click(within(scope).getByRole('button', { name }));

async function generateDraft() {
  render(<FollowUpWorkbench onNavigateToCustomer={vi.fn()} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Obtener recomendación' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Generar borrador' }));
  await screen.findByLabelText('Borrador del mensaje (editable)');
}

async function handOff() {
  click('Revisar y enviar');
  await screen.findByRole('button', { name: 'Enviar a aprobación' });
}

function submit() {
  click('Enviar a aprobación');
  click('Confirmar envío a aprobación', screen.getByRole('group', { name: 'Confirmar envío a aprobación' }));
}

describe('FollowUpWorkbench Phase 3 messaging', () => {
  it('hands the AI draft to review, submits it against the policy version and approves the shown preview', async () => {
    server
      .on(submitPath(customerId), created(pendingApproval))
      .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval))
      .on(approvalsPath(pendingApproval.messageId), created(variant(details.approved, { messageId: pendingApproval.messageId })));
    await generateDraft();
    // A generated draft starts nothing: no message request until the operator hands it off and confirms.
    expect(server.count(submitPath(customerId))).toBe(0);

    await handOff();
    expect(customerApi.getCustomer).toHaveBeenCalledWith(customerId);
    submit();
    await waitFor(() => expect(server.count(submitPath(customerId))).toBe(1));
    const submitted = server.last(submitPath(customerId));
    expect(JSON.parse(submitted.body!)).toMatchObject({
      contactId: pendingApproval.contactId,
      action: 'REPEAT_PURCHASE_FOLLOW_UP',
      templateIntent: 'REPEAT_PURCHASE',
      body: pendingApproval.body,
      origin: 'AI_DRAFT'
    });
    expect(submitted.headers['If-Match']).toBe('"7"');

    // The new message is listed: the history reads again after the submit.
    await waitFor(() => expect(server.count(historyPath(customerId))).toBe(2));
    await screen.findByText(`Revisión ${pendingApproval.version}`);
    click('Aprobar');
    click('Confirmar aprobación', screen.getByRole('group', { name: 'Confirmar aprobación' }));
    await waitFor(() => expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1));
    expect(JSON.parse(server.last(approvalsPath(pendingApproval.messageId)).body!)).toMatchObject({
      decision: 'APPROVE',
      templateRevision: pendingApproval.template!.revision
    });
  });

  it('hands off the operator edits, not the original draft, and keeps copying available', async () => {
    await generateDraft();
    fireEvent.change(screen.getByLabelText('Borrador del mensaje (editable)'), {
      target: { value: 'Hola Ana, ¿quieres reponer tu pedido?' }
    });
    expect(screen.getByRole('button', { name: 'Copiar borrador' })).toBeEnabled();

    await handOff();

    const composed = screen.getAllByRole('textbox').find((box) => box !== screen.getByLabelText('Borrador del mensaje (editable)'));
    expect(composed).toHaveValue('Hola Ana, ¿quieres reponer tu pedido?');
  });

  it('shows a viewer the history but no hand-off or message actions', async () => {
    setRole('VIEWER');
    render(<FollowUpWorkbench onNavigateToCustomer={vi.fn()} />);

    // A viewer cannot use the assistant, so no draft and no hand-off exist; the read only history still loads.
    await waitFor(() => expect(server.count(historyPath(customerId))).toBe(1));
    expect(screen.queryByRole('button', { name: 'Revisar y enviar' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).toBeNull();
    expect(customerApi.getCustomer).not.toHaveBeenCalled();
  });

  it('refuses to compose for a customer without a primary phone', async () => {
    vi.mocked(customerApi.getCustomer).mockResolvedValue(customer([]));
    await generateDraft();

    click('Revisar y enviar');

    expect(await screen.findByText(/no tiene un teléfono principal/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).toBeNull();
  });

  it('refreshes the queue on a moved policy and keeps the handed-off draft stale until a new one is written', async () => {
    server.on(submitPath(customerId), problem('STALE_VERSION'));
    await generateDraft();
    await handOff();
    const queueReads = vi.mocked(followUpApi.getFollowUpQueue).mock.calls.length;
    // The refreshed queue carries a newer policy version for the same customer.
    vi.mocked(followUpApi.getFollowUpQueue).mockResolvedValue({ items: [{ ...item, policyVersion: 8 }], nextCursor: null });

    submit();

    await waitFor(() => expect(vi.mocked(followUpApi.getFollowUpQueue).mock.calls.length).toBeGreaterThan(queueReads));
    // The draft was written for version 7: it is never rebound to version 8 and cannot be submitted again.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enviar a aprobación' })).toBeDisabled());
    expect(server.count(submitPath(customerId))).toBe(1);
  });

  it('reads the primary phone again for every hand-off', async () => {
    const changedPhone = { id: 'contact-new', e164: '+50600000002', primary: true };
    vi.mocked(customerApi.getCustomer)
      .mockResolvedValueOnce(customer([primaryPhone]))
      .mockResolvedValueOnce(customer([{ ...primaryPhone, primary: false }, changedPhone]));
    server.on(submitPath(customerId), problem('NO_CONTACT_CONSENT'));
    await generateDraft();
    await handOff();

    click('Revisar y enviar');
    await waitFor(() => expect(customerApi.getCustomer).toHaveBeenCalledTimes(2));
    await screen.findByRole('button', { name: 'Enviar a aprobación' });
    submit();

    await waitFor(() => expect(server.count(submitPath(customerId))).toBe(1));
    expect(JSON.parse(server.last(submitPath(customerId)).body!)).toMatchObject({ contactId: 'contact-new' });
  });

  it('stops at an opt-out refusal without any further message request', async () => {
    server.on(submitPath(customerId), problem('NO_CONTACT_CONSENT'));
    await generateDraft();
    await handOff();

    submit();

    await waitFor(() => expect(server.count(submitPath(customerId))).toBe(1));
    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0));
    expect(server.calls.filter((call) => call.key.startsWith('POST /api/messages/'))).toEqual([]);
  });

  it('reopens a message from the delivery history in review', async () => {
    const listed = pages.firstPage.items.find((entry) => entry.messageId === pendingApproval.messageId)!;
    server
      .on(historyPath(customerId), ok({ items: [listed], nextCursor: null }))
      .on(detailPath(pendingApproval.messageId), detailReply(pendingApproval))
      .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
    render(<FollowUpWorkbench onNavigateToCustomer={vi.fn()} />);

    const toggle = await waitFor(() => {
      const button = document.querySelector(`[aria-controls="delivery-row-${pendingApproval.messageId}"]`);
      if (!(button instanceof HTMLButtonElement)) throw new Error('row not rendered');
      return button;
    });
    fireEvent.click(toggle);
    fireEvent.click(await screen.findByRole('button', { name: 'Abrir en revisión' }));

    expect(await screen.findByText(`Revisión ${pendingApproval.version}`)).toBeInTheDocument();
  });

  it('does not hand a submitted draft off again until a new one is written', async () => {
    server
      .on(submitPath(customerId), created(pendingApproval))
      .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
    await generateDraft();
    await handOff();
    submit();
    await screen.findByText(`Revisión ${pendingApproval.version}`);

    // A second hand-off of the same draft would create a second message from it.
    expect(screen.getByRole('button', { name: 'Revisar y enviar' })).toBeDisabled();
    // Review reports idle in the render after the message shows, so the note is awaited.
    expect(await screen.findByText(/Este borrador ya está en revisión/)).toBeInTheDocument();

    // A newly generated draft can be handed off.
    vi.mocked(followUpApi.requestDraft).mockResolvedValueOnce({
      data: { ...draftResponse, draft: { ...draftResponse.draft!, body: 'Hola Ana, ¿te ayudo con tu pedido?' } },
      version: 7
    });
    click('Regenerar borrador');
    await waitFor(() => expect(screen.getByLabelText('Borrador del mensaje (editable)')).toHaveValue('Hola Ana, ¿te ayudo con tu pedido?'));
    expect(screen.getByRole('button', { name: 'Revisar y enviar' })).toBeEnabled();
    expect(server.count(submitPath(customerId))).toBe(1);
  });

  it('keeps the review in place while its submit is in flight', async () => {
    const reply = pending();
    const listed = pages.firstPage.items.find((entry) => entry.messageId === pendingApproval.messageId)!;
    server
      .on(historyPath(customerId), ok({ items: [listed], nextCursor: null }))
      .on(submitPath(customerId), reply)
      .on(detailPath(pendingApproval.messageId), detailReply(pendingApproval))
      .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
    await generateDraft();
    const toggle = document.querySelector(`[aria-controls="delivery-row-${pendingApproval.messageId}"]`) as HTMLButtonElement;
    fireEvent.click(toggle);
    await screen.findByRole('button', { name: 'Abrir en revisión' });
    await handOff();

    submit();

    // Neither a new hand-off, closing nor opening another message may unmount the request.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Revisar y enviar' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Cerrar revisión' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Abrir en revisión' })).toBeNull();
    expect(screen.getByText(/Espera a que termine la revisión en curso/)).toBeInTheDocument();

    await act(async () => reply.resolve(created(pendingApproval)));
    await screen.findByText(`Revisión ${pendingApproval.version}`);
    expect(screen.getByRole('button', { name: 'Cerrar revisión' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Abrir en revisión' })).toBeInTheDocument();
    expect(server.count(submitPath(customerId))).toBe(1);
  });

  it('reads the history again after an approval made in review, without remounting it', async () => {
    const approved = variant(details.approved, { messageId: pendingApproval.messageId });
    const listed = pages.firstPage.items.find((entry) => entry.messageId === pendingApproval.messageId)!;
    server
      .on(
        historyPath(customerId),
        ok({ items: [], nextCursor: null }),
        ok({ items: [listed], nextCursor: null }),
        ok({ items: [{ ...listed, status: approved.status, version: approved.version }], nextCursor: null })
      )
      .on(submitPath(customerId), created(pendingApproval))
      .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval))
      .on(approvalsPath(pendingApproval.messageId), created(approved));
    await generateDraft();
    await handOff();
    submit();
    await screen.findByText(`Revisión ${pendingApproval.version}`);
    await waitFor(() => expect(server.count(historyPath(customerId))).toBe(2));
    const row = await waitFor(() => {
      const button = document.querySelector(`[aria-controls="delivery-row-${pendingApproval.messageId}"]`);
      if (!(button instanceof HTMLButtonElement)) throw new Error('row not rendered');
      return button;
    });

    click('Aprobar');
    click('Confirmar aprobación', screen.getByRole('group', { name: 'Confirmar aprobación' }));

    await waitFor(() => expect(server.count(historyPath(customerId))).toBe(3));
    await waitFor(() => expect(row).toHaveTextContent('Aprobado, sin enviar'));
    // The same row element: the history was refreshed in place, not remounted.
    expect(document.querySelector(`[aria-controls="delivery-row-${pendingApproval.messageId}"]`)).toBe(row);
  });

  it('applies a change made in review to its row beyond the first history page', async () => {
    const sent = variant(details.sent, { customerId });
    const delivered = variant(details.delivered, { messageId: sent.messageId, customerId });
    const cursor = pages.firstPage.nextCursor;
    server
      .on(historyPath(customerId), ok(pages.firstPage))
      .on(historyPath(customerId, cursor), ok({ items: [listItemFromDetail(sent)], nextCursor: null }))
      // The open history row and the review each read the sent version; the review's refresh then reads it delivered.
      .on(detailPath(sent.messageId), detailReply(sent), detailReply(sent), detailReply(delivered))
      .on(eventsPath(sent.messageId), eventsReply(sent));
    render(<FollowUpWorkbench onNavigateToCustomer={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Cargar más' }));
    const row = await waitFor(() => {
      const button = document.querySelector(`[aria-controls="delivery-row-${sent.messageId}"]`);
      if (!(button instanceof HTMLButtonElement)) throw new Error('row not rendered');
      return button;
    });
    expect(row).toHaveTextContent('Aceptado por el proveedor');
    fireEvent.click(row);
    fireEvent.click(await screen.findByRole('button', { name: 'Abrir en revisión' }));
    const actions = await screen.findByRole('region', { name: 'Acciones' });

    click('Actualizar', actions);

    await waitFor(() => expect(row).toHaveTextContent('Entregado'));
    // The first page is read again too, and the loaded second page and the open row stay.
    await waitFor(() => expect(server.count(historyPath(customerId))).toBe(2));
    expect(server.count(historyPath(customerId, cursor))).toBe(1);
    expect(document.querySelector(`[aria-controls="delivery-row-${sent.messageId}"]`)).toBe(row);
    const panel = document.getElementById(`delivery-row-${sent.messageId}`)!;
    await waitFor(() => expect(panel).toHaveTextContent('Entregado'));
  });
});
