// ABOUTME: Tests compose mode and Corregir: the counter, the confirm group, the exact submit body, refusals, stale and unsure paths.
// ABOUTME: Also proves the switch to review without a second read, the customer switch reset, and that nothing here calls /sends.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { MessageReview, type MessageReviewMode } from '../components/MessageReview';
import {
  FakeServer,
  cancellationsPath,
  created,
  details,
  detailPath,
  detailReply,
  eventsPath,
  eventsReply,
  networkFailure,
  pending,
  problem,
  requests,
  status,
  submitPath
} from './messageTestServer';

let server: FakeServer;

const draft = requests.submit.valid.aiDraft;
const pendingApproval = details.pendingApproval;
const customerId = pendingApproval.customerId;

const composeMode = (changes: Partial<Extract<MessageReviewMode, { kind: 'compose' }>> = {}): MessageReviewMode => ({
  kind: 'compose',
  customerId,
  customerDisplayName: 'Ana Pérez',
  contactId: draft.contactId,
  recipientPhone: pendingApproval.recipientPhone,
  action: draft.action,
  templateIntent: draft.templateIntent,
  origin: draft.origin,
  policyVersion: 7,
  initialBody: draft.body,
  ...changes
});

beforeEach(() => {
  vi.restoreAllMocks();
  httpClient.setCsrfToken('csrf-a');
  httpClient.setTenantId('tenant-a');
  server = new FakeServer();
  server.install();
});

afterEach(() => {
  expect(server.unexpected).toEqual([]);
  expect(server.calls.filter((call) => call.key.endsWith('/sends'))).toEqual([]);
});

function renderCompose(mode = composeMode(), role = 'OPERATOR', callbacks: { onSubmitted?: (id: string) => void; onPolicyStale?: () => void } = {}) {
  const view = render(<MessageReview tenantId="tenant-a" role={role} mode={mode} {...callbacks} />);
  return {
    ...view,
    rerenderWith(next: MessageReviewMode) {
      view.rerender(<MessageReview tenantId="tenant-a" role={role} mode={next} {...callbacks} />);
    }
  };
}

const textarea = () => screen.getByLabelText('Texto final del mensaje');
const submitButton = () => screen.getByRole('button', { name: 'Enviar a aprobación' });
const group = () => screen.getByRole('group', { name: 'Confirmar envío a aprobación' });
const confirmSubmit = () => fireEvent.click(within(group()).getByRole('button', { name: 'Confirmar envío a aprobación' }));
const type = (value: string) => fireEvent.change(textarea(), { target: { value } });

// covers: AC-2, AC-3, AC-13, AC-14
describe('compose mode', () => {
  it('closes the confirmation and offers no retry once the role can no longer act', async () => {
    const mode = composeMode();
    const view = render(<MessageReview tenantId="tenant-a" role="OPERATOR" mode={mode} />);
    fireEvent.click(submitButton());
    expect(group()).toBeInTheDocument();
    view.rerender(<MessageReview tenantId="tenant-a" role="VIEWER" mode={mode} />);
    expect(screen.queryByRole('group', { name: 'Confirmar envío a aprobación' })).toBeNull();
    expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).toBeNull();

    view.rerender(<MessageReview tenantId="tenant-a" role="OPERATOR" mode={mode} />);
    server.on(submitPath(customerId), networkFailure());
    fireEvent.click(submitButton());
    confirmSubmit();
    expect(await screen.findByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
    view.rerender(<MessageReview tenantId="tenant-a" role="VIEWER" mode={mode} />);
    expect(screen.queryByRole('button', { name: 'Reintentar' })).toBeNull();
    expect(screen.getByText('No se pudo confirmar la acción.')).toBeInTheDocument();
    expect(server.count(submitPath(customerId))).toBe(1);
  });

  it('shows the customer, the masked phone, the labels and the starting text with a counter', () => {
    renderCompose();
    expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toBeInTheDocument();
    expect(screen.getByText('•••• 0001')).toBeInTheDocument();
    expect(screen.queryByText(pendingApproval.recipientPhone)).toBeNull();
    expect(screen.getByText('Seguimiento por recompra')).toBeInTheDocument();
    expect(textarea()).toHaveValue(draft.body);
    expect(screen.getByText(`${[...draft.body].length}/1000`)).toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
  });

  it('disables submit for blank text and for more than 1000 code points', () => {
    renderCompose();
    type('   ');
    expect(submitButton()).toBeDisabled();
    type('😀'.repeat(1000));
    expect(screen.getByText('1000/1000')).toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
    type('😀'.repeat(1001));
    expect(textarea()).toHaveAttribute('aria-invalid', 'true');
    expect(submitButton()).toBeDisabled();
  });

  it('is read only for a viewer', () => {
    renderCompose(composeMode(), 'VIEWER');
    expect(screen.queryByRole('button', { name: 'Enviar a aprobación' })).toBeNull();
    expect(textarea()).toHaveAttribute('readonly');
    expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
  });

  it('confirms the exact text and recipient, and closes the group when the text changes', () => {
    renderCompose();
    fireEvent.click(submitButton());
    expect(group()).toHaveTextContent('Para Ana Pérez · •••• 0001');
    expect(group()).toHaveTextContent(
      'Se enviará a aprobación exactamente el texto mostrado. Nada se envía al cliente hasta que alguien lo apruebe y lo envíe.'
    );
    expect(within(group()).getByRole('button', { name: 'Confirmar envío a aprobación' })).toHaveFocus();
    type(`${draft.body} Saludos.`);
    expect(screen.queryByRole('group')).toBeNull();
  });

  it('closes on Escape and returns focus to the submit control', () => {
    renderCompose();
    fireEvent.click(submitButton());
    fireEvent.keyDown(group(), { key: 'Escape' });
    expect(screen.queryByRole('group')).toBeNull();
    expect(submitButton()).toHaveFocus();
  });

  it('submits the fixture body byte for byte and switches to review without a second read', async () => {
    const onSubmitted = vi.fn();
    server.on(submitPath(customerId), created(pendingApproval)).on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
    renderCompose(composeMode(), 'OPERATOR', { onSubmitted });
    fireEvent.click(submitButton());
    confirmSubmit();

    expect(await screen.findByText('Revisión 1')).toBeInTheDocument();
    const call = server.last(submitPath(customerId));
    expect(call.body).toBe(JSON.stringify(draft));
    expect(call.headers['If-Match']).toBe('"7"');
    expect(call.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByRole('status')).toHaveTextContent('Mensaje enviado a aprobación. Aún no se ha enviado al cliente.');
    expect(screen.getByText('Pendiente de aprobación')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Aprobar' })).toBeInTheDocument();
    expect(onSubmitted).toHaveBeenCalledWith(pendingApproval.messageId);
    expect(server.count(detailPath(pendingApproval.messageId))).toBe(0);
    await waitFor(() => expect(server.count(eventsPath(pendingApproval.messageId))).toBe(1));
  });

  it('announces the current status when a replayed submit returns a message that already moved on', async () => {
    server.on(submitPath(customerId), detailReply(details.sent)).on(eventsPath(details.sent.messageId), eventsReply(details.sent));
    renderCompose();
    fireEvent.click(submitButton());
    confirmSubmit();
    expect(await screen.findByText(`Revisión ${details.sent.version}`)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Este mensaje ya se había enviado a aprobación. Estado actual: Enviado.'
    );
  });

  it('starts one submit on a double click', async () => {
    const reply = pending();
    server.on(submitPath(customerId), reply).on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
    renderCompose();
    fireEvent.click(submitButton());
    const confirm = within(group()).getByRole('button', { name: 'Confirmar envío a aprobación' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(server.count(submitPath(customerId))).toBe(1);
    await act(async () => reply.resolve(created(pendingApproval)));
    expect(await screen.findByText('Revisión 1')).toBeInTheDocument();
  });

  // covers: AC-4, AC-9
  describe('refusals', () => {
    const codes: [string, string][] = [
      ['MESSAGE_ALREADY_OPEN', 'Ya hay un mensaje abierto para este cliente.'],
      ['NO_CONTACT_CONSENT', 'El cliente no ha dado consentimiento para este canal.'],
      ['DO_NOT_CONTACT', 'El cliente pidió no ser contactado.'],
      ['CUSTOMER_ARCHIVED', 'El cliente está archivado.'],
      ['FOLLOW_UP_INELIGIBLE', 'El cliente no está elegible para seguimiento ahora.'],
      ['TEMPLATE_NOT_MAPPED', 'No hay una plantilla configurada para este tipo de mensaje.'],
      ['BODY_REJECTED', 'El texto del mensaje no pasó la validación.'],
      ['IDEMPOTENCY_KEY_REUSED', 'Esta acción ya se registró con otros datos. Actualiza e intenta de nuevo.'],
      ['INVALID_INPUT', 'Revisa los datos e intenta de nuevo.'],
      ['NOT_FOUND', 'Este mensaje ya no está disponible.'],
      ['FORBIDDEN', 'No tienes permiso para esta acción.']
    ];

    it.each(codes)('renders %s and keeps the typed text', async (code, sentence) => {
      server.on(submitPath(customerId), problem(code));
      renderCompose();
      type('Texto corregido por el operador.');
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByRole('alert')).toHaveTextContent(sentence);
      expect(textarea()).toHaveValue('Texto corregido por el operador.');
      expect(submitButton()).toBeEnabled();
    });

    it('moves focus to the heading when a refusal closes the confirm group', async () => {
      server.on(submitPath(customerId), problem('BODY_REJECTED'));
      renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      await screen.findByRole('alert');
      expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus();
    });

    it('shows the Retry-After seconds on a 429', async () => {
      server.on(submitPath(customerId), { ...problem('RATE_LIMITED'), retryAfter: 9 });
      renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByRole('alert')).toHaveTextContent('Se alcanzó el límite de envíos. Espera 9 s.');
    });

    it('reuses the key for unchanged text and rotates it after an edit', async () => {
      server.on(submitPath(customerId), problem('BODY_REJECTED'));
      renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      await screen.findByRole('alert');
      fireEvent.click(submitButton());
      confirmSubmit();
      await waitFor(() => expect(server.count(submitPath(customerId))).toBe(2));
      type('Otro texto.');
      fireEvent.click(submitButton());
      confirmSubmit();
      await waitFor(() => expect(server.count(submitPath(customerId))).toBe(3));
      const [first, second, third] = server.all(submitPath(customerId)).map((call) => call.headers['Idempotency-Key']);
      expect(second).toBe(first);
      expect(third).not.toBe(first);
    });

    it('goes stale on STALE_VERSION, tells the parent, and recovers on a new policy version with a new key', async () => {
      const onPolicyStale = vi.fn();
      // The reply must describe the draft that was submitted, body included, or the client refuses it.
      server.on(submitPath(customerId), problem('STALE_VERSION'), created({ ...pendingApproval, body: 'Texto del operador.' }));
      server.on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
      const view = renderCompose(composeMode(), 'OPERATOR', { onPolicyStale });
      type('Texto del operador.');
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'La situación del cliente cambió desde que se redactó este mensaje. Actualiza el seguimiento antes de enviarlo a aprobación.'
      );
      expect(onPolicyStale).toHaveBeenCalledTimes(1);
      expect(submitButton()).toBeDisabled();
      expect(textarea()).toHaveValue('Texto del operador.');

      view.rerenderWith(composeMode({ policyVersion: 8 }));
      expect(screen.queryByRole('alert')).toBeNull();
      expect(textarea()).toHaveValue('Texto del operador.');
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByText('Revisión 1')).toBeInTheDocument();
      const [first, second] = server.all(submitPath(customerId));
      expect(second.headers['If-Match']).toBe('"8"');
      expect(second.headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);
    });

    it('adopts a policy version that arrived while submitting once STALE_VERSION comes back', async () => {
      const reply = pending();
      server.on(submitPath(customerId), reply, created(pendingApproval));
      server.on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
      const view = renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      await waitFor(() => expect(server.count(submitPath(customerId))).toBe(1));
      view.rerenderWith(composeMode({ policyVersion: 8 }));
      await act(async () => reply.resolve(problem('STALE_VERSION')));

      await waitFor(() => expect(submitButton()).toBeEnabled());
      expect(screen.queryByRole('alert')).toBeNull();
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByText('Revisión 1')).toBeInTheDocument();
      expect(server.last(submitPath(customerId)).headers['If-Match']).toBe('"8"');
    });

    it('resends the same key and body after an unsure submit and locks the text meanwhile', async () => {
      server
        .on(submitPath(customerId), networkFailure(), created(pendingApproval))
        .on(eventsPath(pendingApproval.messageId), eventsReply(pendingApproval));
      renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo confirmar la acción.');
      expect(textarea()).toHaveAttribute('readonly');
      expect(submitButton()).toBeDisabled();
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      expect(await screen.findByText('Revisión 1')).toBeInTheDocument();
      const [first, second] = server.all(submitPath(customerId));
      expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
      expect(second.body).toBe(first.body);
      expect(second.headers['If-Match']).toBe(first.headers['If-Match']);
    });

    it('ends the session on a 401', async () => {
      server.on(submitPath(customerId), status(401));
      renderCompose();
      fireEvent.click(submitButton());
      confirmSubmit();
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
    });
  });

  it('resets and aborts on a customer switch, rendering nothing of the previous customer', async () => {
    const reply = pending();
    server.on(submitPath(customerId), reply);
    const view = renderCompose();
    type('Texto para Ana.');
    fireEvent.click(submitButton());
    confirmSubmit();
    view.rerenderWith(
      composeMode({ customerId: 'other-customer', contactId: 'other-contact', customerDisplayName: 'Luis Mora', initialBody: 'Hola Luis.' })
    );
    await act(async () => reply.resolve(created(pendingApproval)));
    expect(screen.getByRole('heading', { level: 2, name: 'Luis Mora' })).toBeInTheDocument();
    expect(textarea()).toHaveValue('Hola Luis.');
    expect(screen.queryByText('Texto para Ana.')).toBeNull();
    expect(screen.queryByRole('group')).toBeNull();
    expect(screen.queryByText('Revisión 1')).toBeNull();
  });
});

// covers: AC-11
describe('Corregir', () => {
  const cancelledCopy = { ...details.cancelled, messageId: pendingApproval.messageId, body: 'Texto aprobado con un error.', version: 2 };

  async function openReview(detail = pendingApproval) {
    server.on(detailPath(detail.messageId), detailReply(detail)).on(eventsPath(detail.messageId), eventsReply(detail));
    render(
      <MessageReview
        tenantId="tenant-a"
        role="OPERATOR"
        mode={{ kind: 'review', messageId: detail.messageId, customerDisplayName: 'Ana Pérez' }}
      />
    );
    await screen.findByText(`Revisión ${detail.version}`);
  }

  it('cancels, then opens compose with the cancelled text and its policy version', async () => {
    await openReview();
    server.on(cancellationsPath(pendingApproval.messageId), created(cancelledCopy));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    const confirm = screen.getByRole('group', { name: 'Confirmar corrección' });
    expect(confirm).toHaveTextContent(
      'Corregir cancela este mensaje y abre una copia editable. El nuevo mensaje necesitará una nueva aprobación.'
    );
    fireEvent.click(within(confirm).getByRole('button', { name: 'Confirmar corrección' }));

    expect(await screen.findByLabelText('Texto final del mensaje')).toHaveValue('Texto aprobado con un error.');
    expect(server.last(cancellationsPath(pendingApproval.messageId)).body).toBe('{}');
    // The confirm button that had focus left the page, so the draft heading takes it (in a passive effect, so wait).
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus());

    const next = { ...pendingApproval, messageId: '7a5c0000-0000-4000-8000-0000000000ff', body: 'Texto corregido.' };
    server.on(submitPath(customerId), created(next)).on(eventsPath(next.messageId), eventsReply(next));
    type('Texto corregido.');
    fireEvent.click(submitButton());
    confirmSubmit();
    expect(await screen.findByText('Texto corregido.', { selector: 'p' })).toBeInTheDocument();
    const call = server.last(submitPath(customerId));
    expect(call.headers['If-Match']).toBe(`"${cancelledCopy.sourcePolicyVersion}"`);
    expect(JSON.parse(call.body!)).toMatchObject({ contactId: cancelledCopy.contactId, body: 'Texto corregido.', origin: cancelledCopy.origin });
  });

  it('shows the correction stale sentence when the copied policy version is stale', async () => {
    await openReview();
    server.on(cancellationsPath(pendingApproval.messageId), created(cancelledCopy)).on(submitPath(customerId), problem('STALE_VERSION'));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Confirmar corrección' })).getByRole('button', { name: 'Confirmar corrección' }));
    await screen.findByLabelText('Texto final del mensaje');
    fireEvent.click(submitButton());
    confirmSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'La situación del cliente cambió. Vuelve a redactar el mensaje desde el seguimiento.'
    );
  });

  it('leaves review unchanged when the cancel is refused', async () => {
    await openReview();
    server.on(cancellationsPath(pendingApproval.messageId), problem('FORBIDDEN'));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    const confirm = screen.getByRole('group', { name: 'Confirmar corrección' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Confirmar corrección' }));
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('No tienes permiso para esta acción.');
    expect(screen.queryByLabelText('Texto final del mensaje')).toBeNull();
    expect(screen.getByText('Revisión 1')).toBeInTheDocument();
  });

  it('reuses the cancel key on Reintentar after an unsure cancel', async () => {
    await openReview();
    server
      .on(cancellationsPath(pendingApproval.messageId), status(504), created(cancelledCopy))
      .on(detailPath(pendingApproval.messageId), detailReply(pendingApproval));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    const confirm = screen.getByRole('group', { name: 'Confirmar corrección' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Confirmar corrección' }));
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByLabelText('Texto final del mensaje')).toHaveValue('Texto aprobado con un error.');
    const [first, second] = server.all(cancellationsPath(pendingApproval.messageId));
    expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
  });

  it('opens compose when the read after an unsure cancel shows the message cancelled', async () => {
    await openReview();
    server
      .on(cancellationsPath(pendingApproval.messageId), networkFailure())
      .on(detailPath(pendingApproval.messageId), detailReply(cancelledCopy));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    const confirm = screen.getByRole('group', { name: 'Confirmar corrección' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Confirmar corrección' }));
    expect(await screen.findByLabelText('Texto final del mensaje')).toHaveValue('Texto aprobado con un error.');
    expect(screen.getByRole('status')).toHaveTextContent('Mensaje cancelado. No se enviará al cliente.');
    expect(server.count(cancellationsPath(pendingApproval.messageId))).toBe(1);
  });

  it('stays in review when the read after an unsure cancel shows a different change', async () => {
    await openReview();
    const approvedMeanwhile = { ...details.approved, messageId: pendingApproval.messageId, version: 2 };
    server
      .on(cancellationsPath(pendingApproval.messageId), networkFailure())
      .on(detailPath(pendingApproval.messageId), detailReply(approvedMeanwhile));
    fireEvent.click(screen.getByRole('button', { name: 'Corregir' }));
    const confirm = screen.getByRole('group', { name: 'Confirmar corrección' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Confirmar corrección' }));
    expect(await screen.findByText('La acción pudo haberse completado. Revisa el estado actual.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Texto final del mensaje')).toBeNull();
    expect(screen.getByText('Revisión 2')).toBeInTheDocument();
  });
});

// covers: AC-13
describe('source guards', () => {
  it('has no dangerouslySetInnerHTML anywhere under features/messages', () => {
    const files = import.meta.glob('../**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
    const offenders = Object.entries(files)
      .filter(([path]) => !path.endsWith('MessageCompose.test.tsx'))
      .filter(([, source]) => source.includes('dangerouslySetInnerHTML'))
      .map(([path]) => path);
    expect(Object.keys(files).length).toBeGreaterThan(10);
    expect(offenders).toEqual([]);
  });
});
