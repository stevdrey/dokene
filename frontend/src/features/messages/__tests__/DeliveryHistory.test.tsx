// ABOUTME: Tests the delivery history from the frozen fixtures: every outcome row, the open row, guidance, states and guards.
// ABOUTME: Proves provider acceptance never reads as delivered, nothing resends, and no state survives a customer or tenant switch.
import { act, fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import type { MessageResponse } from '../types';
import {
  details,
  detailPath,
  detailReply,
  eventsFixtures,
  eventsPath,
  eventsReply,
  historyPath,
  networkFailure,
  ok,
  pages,
  pending,
  status,
  variant
} from './messageTestServer';
import {
  CUSTOMER_A,
  CUSTOMER_B,
  everyOutcome,
  forCustomerB,
  hasRow,
  listed,
  liveRegion,
  nonFrozenSending,
  pageOf,
  panelOf,
  renderHistory,
  rowIds,
  rowLoaded,
  rowOf,
  servePage,
  serveRow,
  toggleOf,
  useHistorySuite
} from './deliveryHistoryTestSupport';

const suite = useHistorySuite();

describe('DeliveryHistory', () => {
  // covers: AC-2, AC-3, AC-9
  describe('rows', () => {
    it('reads the first page on mount and shows the loading region until it answers', async () => {
      const reply = pending();
      suite.server.on(historyPath(CUSTOMER_A), reply);
      renderHistory();
      expect(liveRegion()).toHaveTextContent('Cargando mensajes del cliente…');
      expect(screen.queryByRole('button', { name: 'Actualizar historial' })).toBeNull();
      expect(suite.server.last(historyPath(CUSTOMER_A)).key).toBe(`GET /api/messages?customerId=${CUSTOMER_A}&limit=20`);
      await act(async () => reply.resolve(ok(pageOf([details.sent]))));
      await listed();
      expect(liveRegion()).not.toHaveTextContent('Cargando');
      expect(screen.getByRole('button', { name: 'Actualizar historial' })).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 2, name: 'Historial de mensajes' })).toBeInTheDocument();
    });

    const expected: [string, string, string | null][] = [
      ['pendingApproval', 'Aún no enviado', 'Pendiente de aprobación'],
      ['approved', 'Aún no enviado', 'Aprobado, sin enviar'],
      ['approvedContactRemoved', 'Aún no enviado', 'Aprobado, sin enviar'],
      ['approvedAfterTransientFailure', 'Aún no enviado', 'Aprobado, sin enviar'],
      ['rejected', 'Rechazado', 'No se enviará al cliente'],
      ['cancelled', 'Cancelado', 'No se enviará al cliente'],
      ['queued', 'En cola', 'Esperando el envío al proveedor'],
      [
        'sendingOutcomeUnknown',
        'Resultado sin confirmar',
        'El proveedor no confirmó el envío. No lo reenvíes; actualiza o pide a un administrador que lo resuelva.'
      ],
      ['sent', 'Aceptado por el proveedor', 'Aún sin confirmación de entrega'],
      ['delivered', 'Entregado', 'Entregado al teléfono del cliente'],
      ['read', 'Leído', 'El cliente abrió el mensaje'],
      ['failedPermanently', 'Fallido', 'No se entregó al cliente.'],
      ['failedByWebhook', 'Fallido', 'No se entregó al cliente.'],
      ['failedByResolution', 'Fallido', 'No se entregó al cliente.']
    ];

    it.each(expected)('reads the %s fixture as "%s"', async (name, label, secondary) => {
      servePage(suite.server, everyOutcome);
      renderHistory();
      await listed();
      const toggle = toggleOf(details[name].messageId);
      expect(toggle).toHaveTextContent(label);
      if (secondary) expect(toggle).toHaveTextContent(secondary);
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      expect(toggle).toHaveAttribute('aria-controls', `delivery-row-${details[name].messageId}`);
    });

    it('reads a SENDING message the provider has not answered as sending, not uncertain', async () => {
      servePage(suite.server, everyOutcome);
      renderHistory();
      await listed();
      const toggle = toggleOf(nonFrozenSending.messageId);
      expect(toggle).toHaveTextContent('Enviando al proveedor');
      expect(toggle).toHaveTextContent('Esperando la respuesta del proveedor');
      expect(within(rowOf(nonFrozenSending.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull();
    });

    it('never lets the SENT row read as delivered or read', async () => {
      servePage(suite.server, [details.sent]);
      renderHistory();
      await listed();
      expect(rowOf(details.sent.messageId)).not.toHaveTextContent(/Entregado|Leído/);
    });

    it('keeps the server order and shows action, origin, date and the contact removed tag', async () => {
      servePage(suite.server, [details.approvedContactRemoved, details.sent, details.read]);
      renderHistory();
      await listed();
      expect(rowIds()).toEqual([details.approvedContactRemoved.messageId, details.sent.messageId, details.read.messageId]);
      const toggle = toggleOf(details.approvedContactRemoved.messageId);
      expect(toggle).toHaveTextContent('Seguimiento por recompra');
      expect(toggle).toHaveTextContent('Borrador de IA');
      expect(toggle.querySelector('time')).toHaveAttribute('dateTime', details.approvedContactRemoved.createdAt);
      expect(toggle).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      expect(toggleOf(details.sent.messageId)).not.toHaveTextContent('El teléfono ya no pertenece');
    });

    it('renders the firstPage fixture as it is and then the empty last page appends nothing and says nothing', async () => {
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pages.firstPage))
        .on(historyPath(CUSTOMER_A, pages.firstPage.nextCursor), ok(pages.lastPage));
      renderHistory();
      await listed();
      expect(rowIds()).toEqual(pages.firstPage.items.map((item) => item.messageId));
      const more = screen.getByRole('button', { name: 'Cargar más' });
      more.focus();
      fireEvent.click(more);
      await act(async () => {});
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
      expect(rowIds()).toHaveLength(2);
      expect(liveRegion()).toHaveTextContent('');
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 }));
    });

    it('renders an unknown status raw with its badge and drops an item of another customer', async () => {
      const unknown = { ...pageOf([details.sent]).items[0], status: 'ARCHIVED_LATER' as MessageResponse['status'] };
      const stranger = { ...pageOf([details.read]).items[0], customerId: CUSTOMER_B };
      suite.server.on(historyPath(CUSTOMER_A), ok({ items: [unknown, stranger], nextCursor: null }));
      renderHistory();
      await listed();
      expect(rowIds()).toEqual([details.sent.messageId]);
      expect(toggleOf(details.sent.messageId)).toHaveTextContent('ARCHIVED_LATER');
    });

    it('shows the empty sentence with Actualizar historial on an empty first page', async () => {
      servePage(suite.server, []);
      renderHistory();
      expect(await screen.findByText('Este cliente no tiene mensajes todavía.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Actualizar historial' })).toBeInTheDocument();
      expect(screen.queryByRole('list')).toBeNull();
    });
  });

  // covers: AC-4, AC-5, AC-6, AC-12
  describe('open row', () => {
    it('reads detail and events in parallel and shows body, masked phone, timestamps and the timeline', async () => {
      const detail = details.read;
      const detailReplyPending = pending();
      const eventsReplyPending = pending();
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), detailReplyPending).on(eventsPath(detail.messageId), eventsReplyPending);
      renderHistory();
      await listed();
      const toggle = toggleOf(detail.messageId);
      toggle.focus();
      fireEvent.click(toggle);
      expect(suite.server.count(detailPath(detail.messageId))).toBe(1);
      expect(suite.server.count(eventsPath(detail.messageId))).toBe(1);
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
      expect(document.activeElement).toBe(toggle);
      expect(within(panelOf(detail.messageId)).getByText('Cargando mensaje…').closest('[aria-busy="true"]')).not.toBeNull();
      await act(async () => detailReplyPending.resolve(detailReply(detail)));
      await act(async () =>
        eventsReplyPending.resolve(eventsReply(detail, eventsFixtures.acceptedDeliveredThenStaleReport.events))
      );
      await rowLoaded(detail);
      const panel = panelOf(detail.messageId);
      expect(within(panel).getByText(detail.body)).toBeInTheDocument();
      expect(within(panel).getByText('•••• 0001')).toBeInTheDocument();
      expect(within(panel).queryByText(detail.recipientPhone)).toBeNull();
      const facts = within(panel).getAllByRole('term').map((term) => term.textContent);
      expect(facts).toEqual(['Teléfono', 'Aceptado por el proveedor', 'Entregado', 'Leído']);
      expect(within(panel).getAllByRole('listitem')).toHaveLength(6);
      expect(within(panel).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent('Leído');
      expect(document.body.textContent).not.toContain(detail.providerMessageId as string);
      expect(document.body.textContent).not.toContain('Intento');
      expect(screen.queryByRole('button', { name: 'Abrir en revisión' })).toBeNull();
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      expect(document.activeElement).toBe(toggle);
      expect(panel).toBeEmptyDOMElement();
    });

    it('shows only the timestamps that are set', async () => {
      servePage(suite.server, [details.sent]);
      serveRow(suite.server, details.sent);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(details.sent.messageId));
      await rowLoaded(details.sent);
      const panel = panelOf(details.sent.messageId);
      expect(within(panel).getAllByRole('term').map((term) => term.textContent)).toEqual(['Teléfono', 'Aceptado por el proveedor']);
      expect(within(panel).getByRole('region', { name: 'Resultado del mensaje' })).not.toHaveTextContent(/Entregado|Leído/);
      expect(toggleOf(details.sent.messageId)).not.toHaveTextContent(/Entregado|Leído/);
      expect(within(rowOf(details.sent.messageId)).getByRole('button', { name: 'Actualizar' })).toBeInTheDocument();
    });

    it('keeps one row open at a time and aborts the reads of the row that closes', async () => {
      const first = details.sent;
      const second = details.delivered;
      servePage(suite.server, [first, second]);
      suite.server.on(detailPath(first.messageId), pending()).on(eventsPath(first.messageId), pending());
      serveRow(suite.server, second);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(first.messageId));
      fireEvent.click(toggleOf(second.messageId));
      await rowLoaded(second);
      expect(toggleOf(first.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(panelOf(first.messageId)).toBeEmptyDOMElement();
      expect(document.querySelectorAll('[aria-expanded="true"]')).toHaveLength(1);
      expect(suite.server.last(detailPath(first.messageId)).signal?.aborted).toBe(true);
      expect(suite.server.last(eventsPath(first.messageId)).signal?.aborted).toBe(true);
    });

    it.each([
      ['failedPermanently', 'Destinatario inválido', 'El número no es válido para WhatsApp. Verifica el teléfono del cliente antes de redactar otro mensaje.'],
      ['failedByWebhook', 'Rechazado por política del proveedor', 'El proveedor rechazó el mensaje por su política. Revisa el texto antes de redactar otro.'],
      ['failedByResolution', 'Error desconocido', 'No se conoce la causa. Revisa el historial del mensaje antes de volver a contactar al cliente.']
    ])('guides an open %s row without offering a resend', async (name, category, guidance) => {
      const detail = details[name];
      servePage(suite.server, [detail]);
      serveRow(suite.server, detail, eventsFixtures.outcomeUnknownResolvedFailed.events);
      renderHistory();
      await listed();
      expect(toggleOf(detail.messageId)).toHaveTextContent('No se entregó al cliente.');
      fireEvent.click(toggleOf(detail.messageId));
      await rowLoaded(detail);
      const outcome = within(panelOf(detail.messageId)).getByRole('region', { name: 'Resultado del mensaje' });
      expect(outcome).toHaveTextContent('Fallido');
      expect(outcome).toHaveTextContent(category);
      expect(outcome).toHaveTextContent(guidance);
      expect(outcome).toHaveTextContent('Este mensaje no se reenviará automáticamente.');
      expect(document.body.textContent).not.toMatch(/Reenviar|Reintentar envío/);
      expect(within(outcome).queryAllByRole('button')).toHaveLength(0);
    });

    it('uses the UNKNOWN guidance for a failure without a category', async () => {
      const detail = variant(details.failedPermanently, { failureCategory: null });
      servePage(suite.server, [detail]);
      serveRow(suite.server, detail);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      await rowLoaded(detail);
      const outcome = within(panelOf(detail.messageId)).getByRole('region', { name: 'Resultado del mensaje' });
      expect(outcome).toHaveTextContent('Error desconocido');
      expect(outcome).toHaveTextContent('No se conoce la causa.');
    });

    it('patches the row from a detail with a higher version', async () => {
      const listed_ = details.sent;
      const newer = variant(details.delivered, { messageId: listed_.messageId });
      servePage(suite.server, [listed_]);
      serveRow(suite.server, newer);
      renderHistory();
      await listed();
      expect(toggleOf(listed_.messageId)).toHaveTextContent('Aceptado por el proveedor');
      fireEvent.click(toggleOf(listed_.messageId));
      await rowLoaded(newer);
      expect(toggleOf(listed_.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(listed_.messageId)).not.toHaveTextContent('Aceptado por el proveedor');
    });

    it('offers Abrir en revisión only when the callback is given and calls it with the id', async () => {
      const onOpenMessage = vi.fn();
      servePage(suite.server, [details.sent]);
      serveRow(suite.server, details.sent);
      renderHistory({ onOpenMessage });
      await listed();
      fireEvent.click(toggleOf(details.sent.messageId));
      await rowLoaded(details.sent);
      fireEvent.click(screen.getByRole('button', { name: 'Abrir en revisión' }));
      expect(onOpenMessage).toHaveBeenCalledWith(details.sent.messageId);
      expect(suite.server.calls.filter((call) => !call.key.startsWith('GET '))).toEqual([]);
    });

    it('shows the uncertain line and Actualizar on the collapsed row, and exactly one Actualizar when open', async () => {
      const detail = details.sendingOutcomeUnknown;
      servePage(suite.server, [detail]);
      serveRow(suite.server, detail, eventsFixtures.outcomeUnknownResolvedFailed.events.slice(0, 5));
      renderHistory();
      await listed();
      const row = rowOf(detail.messageId);
      expect(row).toHaveTextContent('Resultado sin confirmar');
      expect(row).toHaveTextContent('No lo reenvíes; actualiza o pide a un administrador que lo resuelva.');
      expect(within(row).getAllByRole('button', { name: 'Actualizar' })).toHaveLength(1);
      fireEvent.click(toggleOf(detail.messageId));
      await rowLoaded(detail);
      expect(within(row).getAllByRole('button', { name: 'Actualizar' })).toHaveLength(1);
      expect(within(panelOf(detail.messageId)).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent(
        'El proveedor no confirmó el envío.'
      );
    });
  });

  // covers: AC-9
  describe('states', () => {
    it('shows the load failure with Reintentar, which reads the page again', async () => {
      suite.server.on(historyPath(CUSTOMER_A), status(500), ok(pageOf([details.sent])));
      renderHistory();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo cargar el historial.');
      expect(screen.queryByRole('button', { name: 'Actualizar historial' })).toBeNull();
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      await listed();
      expect(suite.server.count(historyPath(CUSTOMER_A))).toBe(2);
    });

    it('moves focus to the heading when a focused Reintentar leaves with the first page', async () => {
      suite.server.on(historyPath(CUSTOMER_A), status(500), status(500), ok(pageOf([details.sent])));
      renderHistory();
      const alert = await screen.findByRole('alert');
      const retry = within(alert).getByRole('button', { name: 'Reintentar' });
      retry.focus();
      fireEvent.click(retry);
      await act(async () => {});
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Historial de mensajes' }));
      fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: 'Reintentar' }));
      await listed();
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Historial de mensajes' }));
    });

    it.each([
      ['a network failure', networkFailure()],
      ['a 400', status(400)],
      ['a 404', status(404)],
      ['a 503 without a problem body', status(503)],
      ['a body that fails narrowing', ok({ items: [{ messageId: 'x' }], nextCursor: null })]
    ])('treats %s on the first page as the error state', async (_name, reply) => {
      suite.server.on(historyPath(CUSTOMER_A), reply);
      renderHistory();
      expect(await screen.findByText('No se pudo cargar el historial.')).toBeInTheDocument();
    });

    it('shows the permission sentence on a 403 and the session sentence with no controls on a 401', async () => {
      suite.server.on(historyPath(CUSTOMER_A), status(403));
      const forbidden = renderHistory();
      expect(await screen.findByText('No tienes permiso para ver estos mensajes.')).toBeInTheDocument();
      // Nothing takes focus and the status region empties, so the denial has to be announced on its own.
      expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
      forbidden.unmount();

      suite.server.on(historyPath(CUSTOMER_A), status(401));
      renderHistory();
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
    });

    it('closes a row on a 404, keeps the notice on it and removes it on the next list refresh', async () => {
      const gone = details.sent;
      const kept = details.read;
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([gone, kept])), ok(pageOf([kept])));
      suite.server.on(detailPath(gone.messageId), status(404)).on(eventsPath(gone.messageId), status(404));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(gone.messageId));
      const notice = await within(rowOf(gone.messageId)).findByRole('alert');
      expect(notice).toHaveTextContent('Este mensaje ya no está disponible.');
      expect(toggleOf(gone.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(toggleOf(gone.messageId)).toHaveAttribute('aria-disabled', 'true');
      fireEvent.click(toggleOf(gone.messageId));
      expect(toggleOf(gone.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(suite.server.count(detailPath(gone.messageId))).toBe(1);
      fireEvent.click(screen.getByRole('button', { name: 'Actualizar historial' }));
      await act(async () => {});
      expect(hasRow(gone.messageId)).toBe(false);
      expect(rowIds()).toEqual([kept.messageId]);
    });

    it('shows the permission sentence inside a row on a 403 detail read', async () => {
      const detail = details.sent;
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), status(403)).on(eventsPath(detail.messageId), eventsReply(detail));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      expect(await within(panelOf(detail.messageId)).findByText('No tienes permiso para ver estos mensajes.')).toBeInTheDocument();
      expect(toggleOf(detail.messageId)).toHaveAttribute('aria-expanded', 'true');
      // The row toggle keeps focus, so the sentence that ends the loading state has to be announced.
      expect(within(panelOf(detail.messageId)).getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
    });

    it('shows the row failure with Reintentar, which reads detail and events again', async () => {
      const detail = details.sent;
      servePage(suite.server, [detail]);
      serveRow(suite.server, detail, eventsFixtures.rejected.events, status(500), detailReply(detail));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      const alert = await within(panelOf(detail.messageId)).findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo cargar el mensaje.');
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      await rowLoaded(detail);
      expect(suite.server.count(detailPath(detail.messageId))).toBe(2);
      expect(suite.server.count(eventsPath(detail.messageId))).toBe(2);
    });

    it('keeps the detail when the events read fails and shows the permission sentence on a 403 events read', async () => {
      const detail = details.sent;
      servePage(suite.server, [detail]);
      suite.server
        .on(detailPath(detail.messageId), detailReply(detail))
        .on(eventsPath(detail.messageId), status(500), eventsReply(detail, eventsFixtures.rejected.events.slice(0, 1)));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      const panel = panelOf(detail.messageId);
      const alert = await within(panel).findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo cargar el historial del mensaje.');
      expect(within(panel).getByText(detail.body)).toBeInTheDocument();
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      expect(await within(panel).findByText('Miembro · Enviado a aprobación')).toBeInTheDocument();
      expect(suite.server.count(detailPath(detail.messageId))).toBe(1);
      fireEvent.click(toggleOf(detail.messageId));

      const other = details.read;
      suite.server.on(detailPath(other.messageId), detailReply(other)).on(eventsPath(other.messageId), status(403));
      // The second row is not on the page; reuse the page refresh to add it.
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([other, detail])));
      fireEvent.click(screen.getByRole('button', { name: 'Actualizar historial' }));
      await act(async () => {});
      fireEvent.click(toggleOf(other.messageId));
      await rowLoaded(other);
      expect(within(panelOf(other.messageId)).getByText('No tienes permiso para ver estos mensajes.')).toBeInTheDocument();
      expect(within(panelOf(other.messageId)).queryByRole('list', { name: 'Historial del mensaje' })).toBeNull();
    });

    it('replaces the whole component with the session sentence on a 401 row read', async () => {
      const detail = details.sent;
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), status(401)).on(eventsPath(detail.messageId), pending());
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(screen.queryByRole('list')).toBeNull();
    });

    it('replaces the whole component with the session sentence on a 401 events read', async () => {
      const detail = details.sent;
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), detailReply(detail)).on(eventsPath(detail.messageId), status(401));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(screen.queryByText(detail.body)).toBeNull();
    });

    it('offers no control at all on a forbidden first page', async () => {
      suite.server.on(historyPath(CUSTOMER_A), status(403));
      renderHistory();
      expect(await screen.findByText('No tienes permiso para ver estos mensajes.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      // The permission sentence is the only alert; there is no Reintentar alert beside it.
      expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
      expect(screen.queryByText('Reintentar')).toBeNull();
      expect(suite.server.count(historyPath(CUSTOMER_A))).toBe(1);
    });

    it('drops a late row refresh once the open row has answered 404', async () => {
      const detail = details.sent;
      const ownRead = pending();
      const refresh = pending(true);
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), ownRead, refresh).on(eventsPath(detail.messageId), eventsReply(detail));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      fireEvent.click(within(rowOf(detail.messageId)).getByRole('button', { name: 'Actualizar' }));
      expect(suite.server.count(detailPath(detail.messageId))).toBe(2);
      await act(async () => ownRead.resolve(status(404)));
      expect(within(rowOf(detail.messageId)).getByRole('alert')).toHaveTextContent('Este mensaje ya no está disponible.');
      expect(toggleOf(detail.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(suite.server.last(detailPath(detail.messageId)).signal?.aborted).toBe(true);
      await act(async () => refresh.resolve(detailReply(variant(details.delivered, { messageId: detail.messageId }))));
      expect(toggleOf(detail.messageId)).toHaveTextContent('Aceptado por el proveedor');
      expect(toggleOf(detail.messageId)).not.toHaveTextContent('Entregado');
      expect(within(rowOf(detail.messageId)).getByRole('alert')).toHaveTextContent('Este mensaje ya no está disponible.');
      expect(within(rowOf(detail.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull();
      expect(liveRegion()).toHaveTextContent('');
    });
  });

  // covers: AC-1, AC-10
  describe('switching', () => {
    it('renders nothing and sends nothing for an inactive tenant, a missing tenant or a missing role', () => {
      const inactive = renderHistory({ tenantId: 'tenant-a', activeTenantId: 'tenant-b' });
      expect(inactive.container).toBeEmptyDOMElement();
      inactive.unmount();

      const noTenant = renderHistory({ tenantId: 'tenant-a', activeTenantId: null });
      expect(noTenant.container).toBeEmptyDOMElement();
      noTenant.unmount();

      for (const role of [null, '']) {
        const { container, unmount } = renderHistory({ role });
        expect(container).toBeEmptyDOMElement();
        unmount();
      }
      expect(suite.server.calls).toHaveLength(0);
    });

    it('shows the full history to a viewer', async () => {
      servePage(suite.server, [details.sent, details.failedPermanently]);
      serveRow(suite.server, details.failedPermanently);
      renderHistory({ role: 'VIEWER' });
      await listed();
      expect(rowIds()).toHaveLength(2);
      fireEvent.click(toggleOf(details.failedPermanently.messageId));
      await rowLoaded(details.failedPermanently);
      expect(panelOf(details.failedPermanently.messageId)).toHaveTextContent('Destinatario inválido');
    });

    it('never renders a late first page of the previous customer under the new one', async () => {
      const late = pending(true);
      suite.server.on(historyPath(CUSTOMER_A), late);
      const forB = forCustomerB(details.read, '0001');
      servePage(suite.server, [forB], CUSTOMER_B);
      const view = renderHistory();
      expect(liveRegion()).toHaveTextContent('Cargando mensajes del cliente…');
      view.rerenderWith({ customerId: CUSTOMER_B });
      await listed();
      expect(rowIds()).toEqual([forB.messageId]);
      await act(async () => late.resolve(ok(pageOf([details.failedPermanently, details.sendingOutcomeUnknown]))));
      expect(rowIds()).toEqual([forB.messageId]);
      expect(document.body.textContent).not.toMatch(/Fallido|Resultado sin confirmar/);
    });

    it('never renders a late row read of the previous tenant after a tenant change', async () => {
      const detail = variant(details.sent, { body: 'Texto del espacio anterior' });
      const late = pending(true);
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), late).on(eventsPath(detail.messageId), pending(true));
      const view = renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      expect(toggleOf(detail.messageId)).toHaveAttribute('aria-expanded', 'true');
      view.rerenderWith({ tenantId: 'tenant-b' });
      await listed();
      expect(toggleOf(detail.messageId)).toHaveAttribute('aria-expanded', 'false');
      await act(async () => late.resolve(detailReply(detail)));
      expect(screen.queryByText(detail.body)).toBeNull();
      expect(screen.queryByText('•••• 0001')).toBeNull();
      expect(panelOf(detail.messageId)).toBeEmptyDOMElement();
    });

    it('never renders a late row read of the previous customer after a customer change', async () => {
      const detail = variant(details.sent, { body: 'Texto del cliente anterior' });
      const late = pending(true);
      servePage(suite.server, [detail]);
      suite.server.on(detailPath(detail.messageId), late).on(eventsPath(detail.messageId), pending(true));
      const forB = forCustomerB(details.read, '0003');
      servePage(suite.server, [forB], CUSTOMER_B);
      const view = renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      view.rerenderWith({ customerId: CUSTOMER_B });
      await listed();
      expect(rowIds()).toEqual([forB.messageId]);
      await act(async () => late.resolve(detailReply(detail)));
      expect(rowIds()).toEqual([forB.messageId]);
      expect(screen.queryByText(detail.body)).toBeNull();
      expect(screen.queryByText('•••• 0001')).toBeNull();
      expect(document.querySelectorAll('[aria-expanded="true"]')).toHaveLength(0);
    });

    it('aborts a pending page of the previous customer and renders the new one fresh', async () => {
      suite.server.on(historyPath(CUSTOMER_A), pending());
      const forB = forCustomerB(details.sent, '0002');
      servePage(suite.server, [forB], CUSTOMER_B);
      const view = renderHistory();
      view.rerenderWith({ customerId: CUSTOMER_B });
      await listed();
      expect(rowIds()).toEqual([forB.messageId]);
      expect(suite.server.count(historyPath(CUSTOMER_A))).toBe(1);
    });
  });

  // covers: AC-11
  describe('no leaks', () => {
    it('renders markup in the body as literal text and adds no element', async () => {
      const markup = '<img src=x onerror=alert(1)>';
      const detail = variant(details.sent, { body: markup });
      servePage(suite.server, [detail]);
      serveRow(suite.server, detail);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(detail.messageId));
      await rowLoaded(detail);
      expect(within(panelOf(detail.messageId)).getByText(markup)).toBeInTheDocument();
      expect(document.querySelector('img')).toBeNull();
    });

    it('uses native buttons for every control', async () => {
      servePage(suite.server, [details.sendingOutcomeUnknown], CUSTOMER_A, pages.firstPage.nextCursor);
      serveRow(suite.server, details.sendingOutcomeUnknown);
      renderHistory({ onOpenMessage: () => {} });
      await listed();
      fireEvent.click(toggleOf(details.sendingOutcomeUnknown.messageId));
      await rowLoaded(details.sendingOutcomeUnknown);
      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.tagName)).toEqual(buttons.map(() => 'BUTTON'));
      expect(buttons.map((button) => button.textContent)).toEqual(
        expect.arrayContaining(['Actualizar historial', 'Actualizar', 'Abrir en revisión', 'Cargar más'])
      );
    });
  });
});
