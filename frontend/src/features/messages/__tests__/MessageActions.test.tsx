// ABOUTME: Tests review actions end to end in jsdom: confirm groups, keys, outcomes, refusals, uncertain replies, polling and 429.
// ABOUTME: Proves approving never sends, a double click starts one request, and an unsure reply is resolved by reading, not resending.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { MessageReview } from '../components/MessageReview';
import type { MessageResponse } from '../types';
import { statusLabel } from '../utils/messageLabels';
import {
  FakeServer,
  approvalsPath,
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
  sendsPath,
  status,
  variant
} from './messageTestServer';

let server: FakeServer;

beforeEach(() => {
  vi.restoreAllMocks();
  httpClient.setCsrfToken('csrf-a');
  httpClient.setTenantId('tenant-a');
  server = new FakeServer();
  server.install();
});

afterEach(() => {
  vi.useRealTimers();
  expect(server.unexpected).toEqual([]);
});

const pendingApproval = details.pendingApproval;
const approved = details.approved;
const queuedFromApproved = variant(details.queued, { messageId: approved.messageId, version: 3 });
const sentFromApproved = variant(details.sent, { messageId: approved.messageId });
const deliveredFromApproved = variant(details.delivered, { messageId: approved.messageId });
// Back in APPROVED, the approval still covers what would be sent, so the backend shows its preview again.
const backToApproved = variant(details.approvedAfterTransientFailure, {
  messageId: approved.messageId,
  version: 3,
  template: approved.template
});
// Replies must describe the message that was asked about; a fixture of another message is re-identified first.
const approvedHere = variant(approved, { messageId: pendingApproval.messageId });
const rejectedHere = variant(details.rejected, { messageId: pendingApproval.messageId });
const cancelledHere = variant(details.cancelled, { messageId: pendingApproval.messageId });

function serve(detail: MessageResponse) {
  server.on(detailPath(detail.messageId), detailReply(detail)).on(eventsPath(detail.messageId), eventsReply(detail));
}

function renderReview(detail: MessageResponse, role = 'OPERATOR') {
  return render(
    <MessageReview tenantId="tenant-a" role={role} mode={{ kind: 'review', messageId: detail.messageId, customerDisplayName: 'Ana Pérez' }} />
  );
}

async function openMessage(detail: MessageResponse, role = 'OPERATOR') {
  serve(detail);
  const view = renderReview(detail, role);
  await screen.findByText(`Revisión ${detail.version}`);
  await waitFor(() => expect(screen.queryByText('Cargando historial…')).toBeNull());
  return view;
}

const liveRegion = () => screen.getByRole('status');
const VIEWER_VIEW = <MessageReview tenantId="tenant-a" role="VIEWER" mode={{ kind: 'review', messageId: pendingApproval.messageId, customerDisplayName: 'Ana Pérez' }} />;
const OPERATOR_VIEW = <MessageReview tenantId="tenant-a" role="OPERATOR" mode={{ kind: 'review', messageId: pendingApproval.messageId, customerDisplayName: 'Ana Pérez' }} />;
const group = (name: string) => screen.getByRole('group', { name });
const click = (name: string, scope: HTMLElement = document.body) =>
  fireEvent.click(within(scope).getByRole('button', { name }));

/** Fake timer tests cannot use findBy or waitFor, so they flush microtasks and timers explicitly. */
const flush = () => act(async () => {
  await vi.advanceTimersByTimeAsync(0);
});
const advance = (ms: number) => act(async () => {
  await vi.advanceTimersByTimeAsync(ms);
});

async function openWithFakeTimers(detail: MessageResponse) {
  vi.useFakeTimers();
  serve(detail);
  const view = renderReview(detail);
  await flush();
  await flush();
  expect(screen.getByText(`Revisión ${detail.version}`)).toBeInTheDocument();
  return view;
}

describe('review actions', () => {
  // covers: AC-7, AC-8, AC-13, AC-14
  describe('approve, reject and cancel', () => {
    it('approves the template preview it showed, naming its revision (issue 268)', async () => {
      const english = details.englishHelloWorldPendingApproval;
      await openMessage(english);
      server.on(approvalsPath(english.messageId), created(variant(approved, { messageId: english.messageId })));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await screen.findByText('Mensaje aprobado. Aún no se ha enviado.');
      expect(server.last(approvalsPath(english.messageId)).body).toBe(
        JSON.stringify({ decision: 'APPROVE', templateRevision: english.template?.revision })
      );
    });

    it('approves with If-Match and a key, announces it, and never calls /sends', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), created(approvedHere));
      click('Aprobar');
      const confirm = group('Confirmar aprobación');
      expect(confirm).toHaveTextContent('Para Ana Pérez · •••• 0001');
      expect(within(confirm).getByRole('button', { name: 'Confirmar aprobación' })).toHaveFocus();
      click('Confirmar aprobación', confirm);

      await screen.findByText('Mensaje aprobado. Aún no se ha enviado.');
      expect(liveRegion()).toHaveTextContent('Mensaje aprobado. Aún no se ha enviado.');
      const call = server.last(approvalsPath(pendingApproval.messageId));
      expect(call.headers['If-Match']).toBe('"1"');
      expect(call.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
      expect(call.body).toBe(
        JSON.stringify({ decision: 'APPROVE', templateRevision: pendingApproval.template?.revision })
      );
      expect(screen.getByText('Revisión 2')).toBeInTheDocument();
      expect(screen.getByText('Aprobado')).toBeInTheDocument();
      expect(screen.queryByText('Enviado')).toBeNull();
      expect(screen.queryByText('Entregado')).toBeNull();
      expect(server.count(sendsPath(pendingApproval.messageId))).toBe(0);
      await waitFor(() => expect(server.count(eventsPath(pendingApproval.messageId))).toBe(2));
    });

    it('keeps the group open on Escape and disables Cerrar while its request is in flight', async () => {
      await openMessage(pendingApproval);
      const reply = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply);
      click('Aprobar');
      const confirm = group('Confirmar aprobación');
      click('Confirmar aprobación', confirm);
      expect(within(confirm).getByRole('button', { name: 'Cerrar' })).toBeDisabled();
      fireEvent.keyDown(confirm, { key: 'Escape' });
      expect(group('Confirmar aprobación')).toBeInTheDocument();

      await act(async () => reply.resolve(created(approvedHere)));
      expect(await screen.findByText('Mensaje aprobado. Aún no se ha enviado.')).toBeInTheDocument();
    });

    it('moves focus to the heading once a confirmed action closes its group', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), created(approvedHere));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await screen.findByText('Mensaje aprobado. Aún no se ha enviado.');
      await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus());
    });

    it.each([
      [approved, 'Enviar', 'Confirmar envío'],
      [pendingApproval, 'Corregir', 'Confirmar corrección']
    ])('names the customer and the masked phone in the %#. group', async (detail, trigger, label) => {
      await openMessage(detail);
      click(trigger);
      expect(group(label)).toHaveTextContent('Para Ana Pérez · •••• 0001');
    });

    it('rejects with a bounded note, focusing the note first', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), created(rejectedHere));
      click('Rechazar');
      const confirm = group('Confirmar rechazo');
      const note = within(confirm).getByLabelText('Nota (opcional)');
      expect(note).toHaveFocus();
      expect(note).toHaveAttribute('maxLength', '500');
      fireEvent.change(note, { target: { value: 'El tono no es adecuado.' } });
      expect(confirm).toHaveTextContent('23/500');
      click('Confirmar rechazo', confirm);
      expect(await screen.findByText('Mensaje rechazado. No se enviará al cliente.')).toBeInTheDocument();
      expect(server.last(approvalsPath(pendingApproval.messageId)).body).toBe(
        JSON.stringify({ decision: 'REJECT', note: 'El tono no es adecuado.' })
      );
      expect(server.count(sendsPath(pendingApproval.messageId))).toBe(0);
    });

    it('cancels with an empty body when no note is given', async () => {
      await openMessage(pendingApproval);
      server.on(cancellationsPath(pendingApproval.messageId), created(cancelledHere));
      click('Cancelar');
      click('Confirmar cancelación', group('Confirmar cancelación'));
      expect(await screen.findByText('Mensaje cancelado. No se enviará al cliente.')).toBeInTheDocument();
      expect(server.last(cancellationsPath(pendingApproval.messageId)).body).toBe('{}');
      expect(server.count(sendsPath(pendingApproval.messageId))).toBe(0);
    });

    it('starts one request on a double click', async () => {
      await openMessage(pendingApproval);
      const reply = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply);
      click('Aprobar');
      const confirmButton = within(group('Confirmar aprobación')).getByRole('button', { name: 'Confirmar aprobación' });
      fireEvent.click(confirmButton);
      fireEvent.click(confirmButton);
      // Busy means aria-disabled, never natively disabled, so the button keeps focus while the request runs.
      expect(confirmButton).not.toBeDisabled();
      expect(confirmButton).toHaveAttribute('aria-disabled', 'true');
      expect(confirmButton).toHaveFocus();
      expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1);
      await act(async () => reply.resolve(created(approvedHere)));
      expect(await screen.findByText('Revisión 2')).toBeInTheDocument();
    });

    it('closes on Escape and on Cerrar, returning focus to the opener', async () => {
      await openMessage(pendingApproval);
      const opener = screen.getByRole('button', { name: 'Rechazar' });
      fireEvent.click(opener);
      fireEvent.keyDown(group('Confirmar rechazo'), { key: 'Escape' });
      expect(screen.queryByRole('group')).toBeNull();
      expect(opener).toHaveFocus();

      const cancel = screen.getByRole('button', { name: 'Cancelar' });
      fireEvent.click(cancel);
      click('Cerrar', group('Confirmar cancelación'));
      expect(screen.queryByRole('group')).toBeNull();
      expect(cancel).toHaveFocus();
    });
  });

  // covers: AC-8
  describe('refusals', () => {
    it('shows the permission sentence on a 403 and keeps the detail', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), status(403));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      expect(await within(group('Confirmar aprobación')).findByRole('alert')).toHaveTextContent(
        'No tienes permiso para esta acción.'
      );
      expect(screen.getByText('Revisión 1')).toBeInTheDocument();
      expect(server.count(detailPath(pendingApproval.messageId))).toBe(1);
    });

    it.each(['STALE_VERSION', 'INVALID_TRANSITION'])('reads again on %s and says what it is now, with no retry', async (code) => {
      await openMessage(pendingApproval);
      server
        .on(approvalsPath(pendingApproval.messageId), problem(code))
        .on(detailPath(pendingApproval.messageId), detailReply(cancelledHere));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      expect(await screen.findByText('Este mensaje cambió: ahora está Cancelado.')).toBeInTheDocument();
      expect(screen.queryByRole('group')).toBeNull();
      expect(screen.queryByRole('button', { name: 'Reintentar' })).toBeNull();
      expect(screen.getByText('Revisión 2')).toBeInTheDocument();
    });

    it('reads again on APPROVAL_INVALIDATED and shows its sentence', async () => {
      await openMessage(approved);
      server
        .on(sendsPath(approved.messageId), problem('APPROVAL_INVALIDATED'))
        .on(detailPath(approved.messageId), detailReply(variant(details.pendingApproval, { messageId: approved.messageId, version: 3 })));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('La aprobación ya no es válida. Revisa el mensaje de nuevo.')).toBeInTheDocument();
      expect(screen.getByText('Revisión 3')).toBeInTheDocument();
    });

    it('renders a policy refusal sentence and reuses the key on a retry of the same intent', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), problem('OUTBOUND_DISABLED_FOR_TENANT'));
      click('Enviar');
      const confirm = group('Confirmar envío');
      expect(confirm).toHaveTextContent('Se enviará exactamente el texto mostrado arriba.');
      click('Confirmar envío', confirm);
      expect(await within(confirm).findByRole('alert')).toHaveTextContent(
        'Los envíos están desactivados para este espacio de trabajo.'
      );
      click('Confirmar envío', confirm);
      await waitFor(() => expect(server.count(sendsPath(approved.messageId))).toBe(2));
      const [first, second] = server.all(sendsPath(approved.messageId));
      expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
    });

    it('treats a coded 503 PROVIDER_UNAVAILABLE as definite and does not read again', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), problem('PROVIDER_UNAVAILABLE'));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('El proveedor de mensajería no está disponible.')).toBeInTheDocument();
      expect(server.count(detailPath(approved.messageId))).toBe(1);
    });

    it('shows not found on a 404 from an action', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), problem('NOT_FOUND'));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      expect(await screen.findByText('Este mensaje ya no está disponible.')).toBeInTheDocument();
    });

    it('ends the session on a 401 from an action and removes every control', async () => {
      await openMessage(pendingApproval);
      server.on(approvalsPath(pendingApproval.messageId), status(401));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
    });
  });

  // covers: AC-9
  describe('uncertain replies', () => {
    it('reads again after a network failure and reports a moved version without resending', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), networkFailure()).on(detailPath(approved.messageId), detailReply(deliveredFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('La acción pudo haberse completado. Revisa el estado actual.')).toBeInTheDocument();
      expect(screen.getByText('Entregado')).toBeInTheDocument();
      expect(screen.queryByRole('group')).toBeNull();
      expect(server.count(sendsPath(approved.messageId))).toBe(1);
    });

    it('never sends an approval without a reviewed preview, since it would carry no revision', async () => {
      const unpreviewed = variant(pendingApproval, { template: null });
      await openMessage(unpreviewed);
      expect(screen.queryByRole('button', { name: 'Aprobar' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Rechazar' })).toBeInTheDocument();
      expect(server.count(approvalsPath(unpreviewed.messageId))).toBe(0);
    });

    it('settles an unsure approval whose preview moved, so the new preview is approved with a new key', async () => {
      const english = details.englishHelloWorldPendingApproval;
      const moved = variant(english, { template: { ...english.template!, revision: 'f'.repeat(64) } });
      await openMessage(english);
      server
        .on(approvalsPath(english.messageId), status(502), created(variant(approved, { messageId: english.messageId })))
        .on(detailPath(english.messageId), detailReply(moved));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      expect(await screen.findByText('Este mensaje cambió. Revísalo de nuevo antes de continuar.')).toBeInTheDocument();
      expect(screen.queryByRole('group')).toBeNull();

      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await screen.findByText('Mensaje aprobado. Aún no se ha enviado.');
      const [first, second] = server.all(approvalsPath(english.messageId));
      expect(JSON.parse(first.body!)).toMatchObject({ templateRevision: english.template!.revision });
      expect(JSON.parse(second.body!)).toMatchObject({ templateRevision: 'f'.repeat(64) });
      expect(second.headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);
    });

    it('offers Reintentar with the same key when the version did not move', async () => {
      await openMessage(approved);
      server
        .on(sendsPath(approved.messageId), status(502), created(deliveredFromApproved))
        .on(detailPath(approved.messageId), detailReply(approved));
      click('Enviar');
      const confirm = group('Confirmar envío');
      click('Confirmar envío', confirm);
      expect(await within(confirm).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');
      click('Reintentar', confirm);
      expect(await screen.findByText('Revisión 7')).toBeInTheDocument();
      const [first, second] = server.all(sendsPath(approved.messageId));
      expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
    });

    it('starts polling when the read after an unsure send shows the provider still has to report', async () => {
      await openWithFakeTimers(approved);
      server
        .on(sendsPath(approved.messageId), networkFailure())
        .on(detailPath(approved.messageId), detailReply(queuedFromApproved), detailReply(deliveredFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      await flush();
      expect(screen.getByText('La acción pudo haberse completado. Revisa el estado actual.')).toBeInTheDocument();
      expect(screen.getByText('En cola')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Actualizar' })).toBeNull();

      await advance(2000);
      expect(screen.getByText('Entregado')).toBeInTheDocument();
      expect(server.count(sendsPath(approved.messageId))).toBe(1);
    });

    it('locks every other action while an attempt is unsure, so only its own retry can follow', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), status(502)).on(detailPath(approved.messageId), detailReply(approved));
      click('Enviar');
      const confirm = group('Confirmar envío');
      click('Confirmar envío', confirm);
      expect(await within(confirm).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');

      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Corregir' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeEnabled();
      click('Cancelar');
      click('Enviar');
      expect(group('Confirmar envío')).toHaveTextContent('No se pudo confirmar la acción.');
      expect(screen.queryByRole('group', { name: 'Confirmar cancelación' })).toBeNull();
      expect(server.count(cancellationsPath(approved.messageId))).toBe(0);
    });

    it('keeps the unsure attempt after Cerrar, and reopening it retries on the same key', async () => {
      await openMessage(approved);
      server
        .on(sendsPath(approved.messageId), status(502), created(deliveredFromApproved))
        .on(detailPath(approved.messageId), detailReply(approved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await within(group('Confirmar envío')).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');
      click('Cerrar', group('Confirmar envío'));
      expect(screen.queryByRole('group')).toBeNull();
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled();

      click('Enviar');
      const reopened = group('Confirmar envío');
      expect(reopened).toHaveTextContent('No se pudo confirmar la acción.');
      click('Reintentar', reopened);
      expect(await screen.findByText('Revisión 7')).toBeInTheDocument();
      const [first, second] = server.all(sendsPath(approved.messageId));
      expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
    });

    it('stays unsure when the read again fails, and shows not found when it is a 404', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), status(500)).on(detailPath(approved.messageId), status(500), status(404));
      click('Enviar');
      const confirm = group('Confirmar envío');
      click('Confirmar envío', confirm);
      expect(await within(confirm).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');
      click('Reintentar', confirm);
      expect(await screen.findByText('Este mensaje ya no está disponible.')).toBeInTheDocument();
    });
  });

  // covers: AC-7, AC-10, AC-12
  describe('send and polling', () => {
    it('polls every 2 s after a send until a terminal status, reading events when the version moves', async () => {
      await openWithFakeTimers(approved);
      server
        .on(sendsPath(approved.messageId), created(queuedFromApproved))
        .on(detailPath(approved.messageId), detailReply(sentFromApproved), detailReply(deliveredFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      expect(screen.getByText('Envío procesado. Estado actual: En cola.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Actualizar' })).toBeNull();
      const eventsBefore = server.count(eventsPath(approved.messageId));

      await advance(2000);
      expect(screen.getByText('Revisión 5')).toBeInTheDocument();
      expect(server.count(eventsPath(approved.messageId))).toBe(eventsBefore + 1);

      await advance(2000);
      expect(screen.getByText('Entregado')).toBeInTheDocument();
      const reads = server.count(detailPath(approved.messageId));
      await advance(10000);
      expect(server.count(detailPath(approved.messageId))).toBe(reads);
    });

    it('announces each status a poll tick applies, and stays quiet when only the version moved', async () => {
      await openWithFakeTimers(approved);
      const sentAgain = variant(sentFromApproved, { version: sentFromApproved.version + 1 });
      server
        .on(sendsPath(approved.messageId), created(queuedFromApproved))
        .on(detailPath(approved.messageId), detailReply(sentFromApproved), detailReply(sentAgain), detailReply(deliveredFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      expect(liveRegion()).toHaveTextContent('Envío procesado. Estado actual: En cola.');
      await advance(2000);
      expect(liveRegion()).toHaveTextContent('Este mensaje cambió: ahora está Enviado.');
      await advance(2000);
      expect(screen.getByText(`Revisión ${sentAgain.version}`)).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('Este mensaje cambió: ahora está Enviado.');
      expect(liveRegion().childElementCount).toBe(1);
      await advance(2000);
      expect(liveRegion()).toHaveTextContent('Este mensaje cambió: ahora está Entregado.');
    });

    it('stops after 20 s and leaves Actualizar', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), detailReply(queuedFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      const readsAtStart = server.count(detailPath(approved.messageId));
      await advance(22000);
      const polled = server.count(detailPath(approved.messageId)) - readsAtStart;
      expect(polled).toBeGreaterThanOrEqual(9);
      expect(polled).toBeLessThanOrEqual(10);
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
      await advance(10000);
      expect(server.count(detailPath(approved.messageId)) - readsAtStart).toBe(polled);
    });

    it('stops polling on unmount', async () => {
      const view = await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), detailReply(queuedFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      const reads = server.count(detailPath(approved.messageId));
      view.unmount();
      await advance(10000);
      expect(server.count(detailPath(approved.messageId))).toBe(reads);
    });

    it('stops polling when the provider outcome becomes unknown and shows the frozen notice', async () => {
      await openWithFakeTimers(approved);
      const frozen = variant(details.sendingOutcomeUnknown, { messageId: approved.messageId });
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), detailReply(frozen));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      await advance(2000);
      expect(screen.getByText('El proveedor no confirmó el envío. Un administrador debe resolverlo.')).toBeInTheDocument();
      const reads = server.count(detailPath(approved.messageId));
      await advance(10000);
      expect(server.count(detailPath(approved.messageId))).toBe(reads);
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
    });

    it('gives the next send intent a new key after a temporary failure lands back in APPROVED', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), created(backToApproved), created(sentFromApproved));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('Revisión 3')).toBeInTheDocument();
      expect(screen.getByRole('region', { name: 'Resultado del envío' })).toHaveTextContent('Falló temporalmente');
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await waitFor(() => expect(server.count(sendsPath(approved.messageId))).toBe(2));
      const [first, second] = server.all(sendsPath(approved.messageId));
      expect(second.headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);
    });

    it('locks Enviar for 60 s on RATE_LIMITED, counting down, then retries with the same key', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), problem('RATE_LIMITED'), created(sentFromApproved), detailReply(sentFromApproved));
      server.on(detailPath(approved.messageId), detailReply(sentFromApproved));
      click('Enviar');
      const confirm = group('Confirmar envío');
      const lockedConfirm = within(confirm).getByRole('button', { name: 'Confirmar envío' });
      lockedConfirm.focus();
      fireEvent.click(lockedConfirm);
      await flush();
      // The alert fires once; the ticking count lives outside it so it is not announced every second.
      expect(within(confirm).getByRole('alert')).toHaveTextContent(/^Se alcanzó el límite de envíos\.$/);
      expect(confirm).toHaveTextContent('Se alcanzó el límite de envíos. Espera 60 s.');
      // Locked means aria-disabled, never natively disabled, so the keyboard user stays on the button for the lockout.
      expect(lockedConfirm).not.toBeDisabled();
      expect(lockedConfirm).toHaveAttribute('aria-disabled', 'true');
      expect(lockedConfirm).toHaveFocus();
      fireEvent.click(lockedConfirm);
      await flush();
      expect(server.count(sendsPath(approved.messageId))).toBe(1);
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();

      await advance(1000);
      expect(within(confirm).getByRole('alert')).toHaveTextContent(/^Se alcanzó el límite de envíos\.$/);
      expect(confirm).toHaveTextContent('Espera 59 s.');
      await advance(59000);
      const confirmButton = within(group('Confirmar envío')).getByRole('button', { name: 'Confirmar envío' });
      expect(confirmButton).toBeEnabled();
      expect(confirmButton).not.toHaveAttribute('aria-disabled');
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeEnabled();
      fireEvent.click(confirmButton);
      await flush();
      const [first, second] = server.all(sendsPath(approved.messageId));
      expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
    });

    it('locks Enviar for the Retry-After seconds when the 429 carries one', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), { ...problem('RATE_LIMITED'), retryAfter: 7 });
      click('Enviar');
      const confirm = group('Confirmar envío');
      click('Confirmar envío', confirm);
      await flush();
      expect(confirm).toHaveTextContent('Se alcanzó el límite de envíos. Espera 7 s.');
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();
      await advance(7000);
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeEnabled();
      expect(within(group('Confirmar envío')).getByRole('button', { name: 'Confirmar envío' })).toBeEnabled();
    });

    it('ends the poll at the deadline when a tick never answers, and leaves Actualizar', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), pending());
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      expect(screen.queryByRole('button', { name: 'Actualizar' })).toBeNull();
      await advance(22000);
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
      expect(screen.getByText('En cola')).toBeInTheDocument();
    });

    it('announces a repeated identical outcome again', async () => {
      await openMessage(approved);
      server.on(sendsPath(approved.messageId), created(backToApproved), created(variant(backToApproved, { version: 4 })));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('Revisión 3')).toBeInTheDocument();
      const first = liveRegion().firstElementChild;
      expect(first).toHaveTextContent('Envío procesado. Estado actual: Aprobado.');
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      expect(await screen.findByText('Revisión 4')).toBeInTheDocument();
      const second = liveRegion().firstElementChild;
      expect(second).toHaveTextContent('Envío procesado. Estado actual: Aprobado.');
      expect(second).not.toBe(first);
    });

    it('keeps Enviar locked after the rate limited group is closed', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), problem('RATE_LIMITED'));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      click('Cerrar', group('Confirmar envío'));
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();
      await advance(60000);
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeEnabled();
    });

    it('stops polling when the message changes mid poll', async () => {
      const view = await openWithFakeTimers(approved);
      const other = details.rejected;
      server
        .on(sendsPath(approved.messageId), created(queuedFromApproved))
        .on(detailPath(approved.messageId), detailReply(queuedFromApproved));
      serve(other);
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      const reads = server.count(detailPath(approved.messageId));

      view.rerender(
        <MessageReview tenantId="tenant-a" role="OPERATOR" mode={{ kind: 'review', messageId: other.messageId }} />
      );
      await flush();
      await advance(10000);
      expect(screen.getByText('Rechazado')).toBeInTheDocument();
      expect(server.count(detailPath(approved.messageId))).toBe(reads);
    });

    it('stops polling and leaves Actualizar when a poll tick fails', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), status(500));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      await advance(2000);
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
      expect(screen.getByText('En cola')).toBeInTheDocument();
      const reads = server.count(detailPath(approved.messageId));
      await advance(10000);
      expect(server.count(detailPath(approved.messageId))).toBe(reads);
    });

    it('shows not found when a poll tick answers 404', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), created(queuedFromApproved)).on(detailPath(approved.messageId), status(404));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      await advance(2000);
      expect(screen.getByText('Este mensaje ya no está disponible.')).toBeInTheDocument();
    });
  });

  // covers: AC-10
  describe('Actualizar', () => {
    const queued = details.queued;

    it('reads the message again and shows the new state and history', async () => {
      await openMessage(queued);
      const delivered = variant(details.delivered, { messageId: queued.messageId });
      server.on(detailPath(queued.messageId), detailReply(delivered));
      const eventsBefore = server.count(eventsPath(queued.messageId));
      click('Actualizar');
      expect(await screen.findByText('Entregado')).toBeInTheDocument();
      // DELIVERED is not terminal (READ may follow), so Actualizar stays, and the change is heard.
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('Este mensaje cambió: ahora está Entregado.');
      await waitFor(() => expect(server.count(eventsPath(queued.messageId))).toBe(eventsBefore + 1));
    });

    it('announces no change when the read brings the same status', async () => {
      await openMessage(queued);
      server.on(detailPath(queued.messageId), detailReply(variant(queued, { version: queued.version + 1 })));
      click('Actualizar');
      expect(await screen.findByText(`Revisión ${queued.version + 1}`)).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('Sin cambios desde la última lectura.');
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
    });

    it('keeps the detail and shows an alert when the read fails', async () => {
      await openMessage(queued);
      server.on(detailPath(queued.messageId), status(500));
      click('Actualizar');
      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo actualizar el mensaje. Intenta de nuevo.');
      expect(screen.getByText(`Revisión ${queued.version}`)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
    });

    it('shows not found when the read answers 404', async () => {
      await openMessage(queued);
      server.on(detailPath(queued.messageId), status(404));
      click('Actualizar');
      expect(await screen.findByText('Este mensaje ya no está disponible.')).toBeInTheDocument();
    });

    it('reads a delivered message again so a later READ can show', async () => {
      const delivered = details.delivered;
      await openMessage(delivered);
      server.on(detailPath(delivered.messageId), detailReply(variant(details.read, { messageId: delivered.messageId })));
      click('Actualizar');
      expect(await screen.findByText('Leído')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Actualizar' })).toBeNull();
    });

    it('moves focus to the heading when the refresh result removes Actualizar', async () => {
      const delivered = details.delivered;
      await openMessage(delivered);
      server.on(detailPath(delivered.messageId), detailReply(variant(details.read, { messageId: delivered.messageId })));
      const refresh = screen.getByRole('button', { name: 'Actualizar' });
      refresh.focus();
      fireEvent.click(refresh);
      expect(await screen.findByText('Leído')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Actualizar' })).toBeNull();
      await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus());
    });

    it('keeps focus on Actualizar when the refresh result keeps it', async () => {
      const queued = details.queued;
      await openMessage(queued);
      server.on(detailPath(queued.messageId), detailReply(variant(details.delivered, { messageId: queued.messageId })));
      const refresh = screen.getByRole('button', { name: 'Actualizar' });
      refresh.focus();
      fireEvent.click(refresh);
      expect(await screen.findByText('Entregado')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Actualizar' })).toHaveFocus();
    });

    it('drops an earlier Actualizar reply that lands after a later one', async () => {
      await openMessage(queued);
      const delivered = variant(details.delivered, { messageId: queued.messageId });
      const first = pending();
      const second = pending();
      server.on(detailPath(queued.messageId), first, second);
      click('Actualizar');
      click('Actualizar');
      await waitFor(() => expect(server.count(detailPath(queued.messageId))).toBe(3));

      await act(async () => second.resolve(detailReply(delivered)));
      expect(await screen.findByText('Entregado')).toBeInTheDocument();
      await act(async () => {
        first.resolve(detailReply(queued));
        await new Promise((settle) => setTimeout(settle, 0));
      });
      expect(screen.getByText('Entregado')).toBeInTheDocument();
      expect(screen.queryByText('En cola')).toBeNull();
    });
  });
  // covers: the fifth Codex review round of 2026-10-22
  describe('removed contacts, locked openers and approval replays', () => {
    it('reads the message again on CONTACT_NOT_OWNED so the removed contact restricts the controls', async () => {
      await openMessage(pendingApproval);
      const removed = variant(pendingApproval, { contactRemoved: true });
      server
        .on(approvalsPath(pendingApproval.messageId), problem('CONTACT_NOT_OWNED'))
        .on(detailPath(pendingApproval.messageId), detailReply(removed));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await screen.findByText(/Este mensaje no puede avanzar/);
      expect(screen.getByRole('alert')).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Aprobar' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Corregir' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Rechazar' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeEnabled();
      expect(server.count(detailPath(pendingApproval.messageId))).toBe(2);
      expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1);
    });

    it('closes an open group and moves focus to the heading when the role can no longer act', async () => {
      const view = await openMessage(pendingApproval);
      click('Aprobar');
      expect(group('Confirmar aprobación')).toBeInTheDocument();
      view.rerender(
        <MessageReview tenantId="tenant-a" role="VIEWER" mode={{ kind: 'review', messageId: pendingApproval.messageId, customerDisplayName: 'Ana Pérez' }} />
      );
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
      await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus());
      view.rerender(
        <MessageReview tenantId="tenant-a" role="OPERATOR" mode={{ kind: 'review', messageId: pendingApproval.messageId, customerDisplayName: 'Ana Pérez' }} />
      );
      // The old confirmation does not come back with the role; the operator starts again from the controls.
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Aprobar' })).toBeEnabled();
      expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(0);
    });

    it('drops a late refused result after the role lost the right to act, so nothing comes back with the role', async () => {
      const view = await openMessage(pendingApproval);
      const reply = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply);
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await waitFor(() => expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1));
      view.rerender(VIEWER_VIEW);
      await act(async () => reply.resolve(problem('FORBIDDEN')));
      view.rerender(OPERATOR_VIEW);
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.queryByRole('alert')).toBeNull();
      expect(screen.getByRole('button', { name: 'Aprobar' })).toBeEnabled();
    });

    it('drops a late unsure result after the role lost the right to act and holds no retry', async () => {
      const view = await openMessage(pendingApproval);
      const reply = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply).on(detailPath(pendingApproval.messageId), detailReply(pendingApproval));
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await waitFor(() => expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1));
      view.rerender(VIEWER_VIEW);
      await act(async () => reply.resolve(networkFailure()));
      await waitFor(() => expect(server.count(detailPath(pendingApproval.messageId))).toBe(2));
      view.rerender(OPERATOR_VIEW);
      // The read's reply still has to settle, or the assertions below would only see what role loss already did.
      await act(async () => {});
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      // No unsure attempt is held, so every control is open again instead of only the approve retry.
      expect(screen.getByRole('button', { name: 'Rechazar' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeEnabled();
    });

    it('moves focus to the heading when the role loses the right to act while a trigger has focus', async () => {
      const view = await openMessage(pendingApproval);
      screen.getByRole('button', { name: 'Aprobar' }).focus();
      view.rerender(VIEWER_VIEW);
      expect(screen.queryByRole('button', { name: 'Aprobar' })).toBeNull();
      await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus());
    });

    it('moves focus to the heading when Cerrar leaves a rate limited group whose opener is locked', async () => {
      await openWithFakeTimers(approved);
      server.on(sendsPath(approved.messageId), problem('RATE_LIMITED'));
      click('Enviar');
      click('Confirmar envío', group('Confirmar envío'));
      await flush();
      const close = within(group('Confirmar envío')).getByRole('button', { name: 'Cerrar' });
      close.focus();
      fireEvent.click(close);
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();
      expect(document.activeElement).not.toBe(document.body);
      expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toHaveFocus();
    });

    it('announces the current status when an approval replay shows the message moved on', async () => {
      await openMessage(pendingApproval);
      const sentHere = variant(details.sent, { messageId: pendingApproval.messageId });
      server
        .on(approvalsPath(pendingApproval.messageId), networkFailure(), detailReply(sentHere))
        .on(detailPath(pendingApproval.messageId), detailReply(pendingApproval));
      click('Aprobar');
      const confirm = group('Confirmar aprobación');
      click('Confirmar aprobación', confirm);
      expect(await within(confirm).findByRole('alert')).toHaveTextContent('No se pudo confirmar la acción.');
      click('Reintentar', confirm);
      expect(await screen.findByText(`Revisión ${sentHere.version}`)).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent(`Este mensaje ya se había aprobado. Estado actual: ${statusLabel('SENT')}.`);
      expect(liveRegion()).not.toHaveTextContent('Aún no se ha enviado');
    });

    it('does not open the correction when the cancel reply shows the contact removed', async () => {
      await openMessage(pendingApproval);
      const cancelledWithoutContact = variant(cancelledHere, { contactRemoved: true });
      server.on(cancellationsPath(pendingApproval.messageId), created(cancelledWithoutContact));
      click('Corregir');
      click('Confirmar corrección', group('Confirmar corrección'));
      expect(await screen.findByText(`Revisión ${cancelledWithoutContact.version}`)).toBeInTheDocument();
      expect(screen.queryByLabelText('Texto final del mensaje')).toBeNull();
      expect(liveRegion()).toHaveTextContent('Mensaje cancelado. No se enviará al cliente.');
      expect(screen.getByRole('alert')).toHaveTextContent(
        'El teléfono ya no pertenece a este cliente. Redacta un mensaje nuevo con el número correcto.'
      );
    });

    it('does not open the correction when the read after an unsure cancel shows the contact removed', async () => {
      await openMessage(pendingApproval);
      const cancelledWithoutContact = variant(cancelledHere, { contactRemoved: true });
      server
        .on(cancellationsPath(pendingApproval.messageId), networkFailure())
        .on(detailPath(pendingApproval.messageId), detailReply(cancelledWithoutContact));
      click('Corregir');
      click('Confirmar corrección', group('Confirmar corrección'));
      expect(await screen.findByText(`Revisión ${cancelledWithoutContact.version}`)).toBeInTheDocument();
      expect(screen.queryByLabelText('Texto final del mensaje')).toBeNull();
      expect(liveRegion()).toHaveTextContent('Mensaje cancelado. No se enviará al cliente.');
      expect(screen.getByRole('alert')).toHaveTextContent('Redacta un mensaje nuevo con el número correcto.');
    });

    it('keeps the group closed when the role returns before a late refused result lands', async () => {
      const view = await openMessage(pendingApproval);
      const reply = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply);
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await waitFor(() => expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1));
      view.rerender(VIEWER_VIEW);
      view.rerender(OPERATOR_VIEW);
      await act(async () => reply.resolve(problem('FORBIDDEN')));
      // The role flapped while the request was in flight; the result belongs to the dropped group and stays dropped.
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.queryByRole('alert')).toBeNull();
      expect(screen.getByRole('button', { name: 'Aprobar' })).toBeEnabled();
    });

    it('holds no retry when the role returns before a late unsure read settles', async () => {
      const view = await openMessage(pendingApproval);
      const reply = pending();
      const read = pending();
      server.on(approvalsPath(pendingApproval.messageId), reply).on(detailPath(pendingApproval.messageId), read);
      click('Aprobar');
      click('Confirmar aprobación', group('Confirmar aprobación'));
      await waitFor(() => expect(server.count(approvalsPath(pendingApproval.messageId))).toBe(1));
      view.rerender(VIEWER_VIEW);
      await act(async () => reply.resolve(networkFailure()));
      await waitFor(() => expect(server.count(detailPath(pendingApproval.messageId))).toBe(2));
      view.rerender(OPERATOR_VIEW);
      await act(async () => read.resolve(detailReply(pendingApproval)));
      expect(screen.queryByRole('group', { name: 'Confirmar aprobación' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Rechazar' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeEnabled();
    });

    it('stays on the review view when a Corregir result lands after the role lost the right to act', async () => {
      const view = await openMessage(pendingApproval);
      const reply = pending();
      server.on(cancellationsPath(pendingApproval.messageId), reply);
      click('Corregir');
      click('Confirmar corrección', group('Confirmar corrección'));
      await waitFor(() => expect(server.count(cancellationsPath(pendingApproval.messageId))).toBe(1));
      view.rerender(VIEWER_VIEW);
      await act(async () => reply.resolve(created(cancelledHere)));
      // The cancel went through and is shown; a read only role is not moved into an editable copy.
      expect(await screen.findByText(`Revisión ${cancelledHere.version}`)).toBeInTheDocument();
      expect(screen.queryByLabelText('Texto final del mensaje')).toBeNull();
      expect(liveRegion()).toHaveTextContent('Mensaje cancelado. No se enviará al cliente.');
    });

    it('leaves focus where it was when a read only role opens a message', async () => {
      await openMessage(pendingApproval, 'VIEWER');
      expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
      expect(document.body).toHaveFocus();
    });
  });
});
