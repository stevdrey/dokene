// ABOUTME: Tests the refresh paths of the delivery history: row Actualizar, Actualizar historial and Cargar más.
// ABOUTME: Proves the version rule holds for every source, replies out of order never regress a row, and only GET is sent.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  details,
  detailPath,
  detailReply,
  eventsFixtures,
  eventsPath,
  eventsReply,
  historyPath,
  ok,
  pages,
  pending,
  status,
  variant
} from './messageTestServer';
import {
  CURSOR_2,
  CUSTOMER_A,
  listed,
  liveRegion,
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
const CURSOR_1 = pages.firstPage.nextCursor as string;

const refreshButton = (messageId: string) => within(rowOf(messageId)).getByRole('button', { name: 'Actualizar' });
const listRefresh = () => screen.getByRole('button', { name: 'Actualizar historial' });
const loadMore = () => screen.getByRole('button', { name: 'Cargar más' });

describe('DeliveryHistory refresh', () => {
  // covers: AC-6, AC-8
  describe('row Actualizar', () => {
    const uncertain = details.sendingOutcomeUnknown;
    const resolved = variant(details.failedByResolution, { messageId: uncertain.messageId });

    it('reads the detail again and announces the new outcome when the label changes', async () => {
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), detailReply(resolved));
      renderHistory();
      await listed();
      refreshButton(uncertain.messageId).focus();
      fireEvent.click(refreshButton(uncertain.messageId));
      expect(suite.server.last(detailPath(uncertain.messageId)).key).toBe(`GET /api/messages/${uncertain.messageId}`);
      await waitFor(() => expect(toggleOf(uncertain.messageId)).toHaveTextContent('Fallido'));
      const toggle = toggleOf(uncertain.messageId);
      // Actualizar left with the resolved outcome, so the row toggle takes the focus it held.
      await waitFor(() => expect(toggle).toHaveFocus());
      expect(toggle).toHaveTextContent('No se entregó al cliente.');
      expect(toggle).not.toHaveTextContent('Resultado sin confirmar');
      expect(liveRegion()).toHaveTextContent('El estado cambió: ahora Fallido.');
      expect(within(rowOf(uncertain.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull();
      expect(suite.server.count(eventsPath(uncertain.messageId))).toBe(0);
    });

    it('announces no change on an equal version and on a higher version with the same label', async () => {
      const sameLabel = variant(uncertain, { version: uncertain.version + 1, updatedAt: '2026-10-13T15:30:00Z' });
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), detailReply(uncertain), detailReply(sameLabel));
      renderHistory();
      await listed();
      fireEvent.click(refreshButton(uncertain.messageId));
      await act(async () => {});
      expect(liveRegion()).toHaveTextContent('Sin cambios desde la última lectura.');
      fireEvent.click(refreshButton(uncertain.messageId));
      await act(async () => {});
      expect(liveRegion()).toHaveTextContent('Sin cambios desde la última lectura.');
      expect(toggleOf(uncertain.messageId)).toHaveTextContent('Resultado sin confirmar');
      expect(suite.server.count(detailPath(uncertain.messageId))).toBe(2);
    });

    it('is disabled while its read is in flight and a second click sends nothing', async () => {
      const reply = pending();
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), reply);
      renderHistory();
      await listed();
      const button = refreshButton(uncertain.messageId);
      button.focus();
      fireEvent.click(button);
      expect(button).toHaveAttribute('aria-disabled', 'true');
      fireEvent.click(button);
      expect(suite.server.count(detailPath(uncertain.messageId))).toBe(1);
      expect(document.activeElement).toBe(button);
      await act(async () => reply.resolve(detailReply(uncertain)));
      expect(button).not.toHaveAttribute('aria-disabled');
    });

    it('keeps the held state and shows the row sentence when the read fails', async () => {
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), status(500), status(403));
      renderHistory();
      await listed();
      fireEvent.click(refreshButton(uncertain.messageId));
      const alert = await within(rowOf(uncertain.messageId)).findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo actualizar el mensaje. Intenta de nuevo.');
      expect(toggleOf(uncertain.messageId)).toHaveTextContent('Resultado sin confirmar');
      expect(liveRegion()).toHaveTextContent('');
      fireEvent.click(refreshButton(uncertain.messageId));
      await act(async () => {});
      expect(within(rowOf(uncertain.messageId)).getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
    });

    it('marks the row not found on a 404 and removes Actualizar', async () => {
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), status(404));
      renderHistory();
      await listed();
      refreshButton(uncertain.messageId).focus();
      fireEvent.click(refreshButton(uncertain.messageId));
      const alert = await within(rowOf(uncertain.messageId)).findByRole('alert');
      expect(alert).toHaveTextContent('Este mensaje ya no está disponible.');
      expect(within(rowOf(uncertain.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull();
      expect(toggleOf(uncertain.messageId)).toHaveAttribute('aria-disabled', 'true');
      await waitFor(() => expect(toggleOf(uncertain.messageId)).toHaveFocus());
    });

    it('leaves focus alone when Actualizar was not focused and leaves, or when it stays', async () => {
      const sent = details.sent;
      servePage(suite.server, [uncertain, sent]);
      suite.server.on(detailPath(uncertain.messageId), detailReply(resolved));
      serveRow(suite.server, sent, eventsFixtures.rejected.events, detailReply(sent), detailReply(sent));
      renderHistory();
      await listed();
      const heading = screen.getByRole('heading', { name: 'Historial de mensajes' });
      heading.focus();
      fireEvent.click(refreshButton(uncertain.messageId));
      await waitFor(() =>
        expect(within(rowOf(uncertain.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull()
      );
      // Effects of the commit that removed Actualizar run before this check, so a focus steal by the row would show.
      await act(async () => {});
      expect(heading).toHaveFocus();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(sent);
      refreshButton(sent.messageId).focus();
      fireEvent.click(refreshButton(sent.messageId));
      await act(async () => {});
      expect(refreshButton(sent.messageId)).toHaveFocus();
    });

    it('closes an open row on a 404 and keeps the notice on the collapsed row', async () => {
      const sent = details.sent;
      servePage(suite.server, [sent]);
      serveRow(suite.server, sent, eventsFixtures.rejected.events, detailReply(sent), status(404));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(sent);
      fireEvent.click(refreshButton(sent.messageId));
      const alert = await within(rowOf(sent.messageId)).findByRole('alert');
      expect(alert).toHaveTextContent('Este mensaje ya no está disponible.');
      expect(toggleOf(sent.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(toggleOf(sent.messageId)).toHaveAttribute('aria-disabled', 'true');
      expect(panelOf(sent.messageId)).toBeEmptyDOMElement();
      expect(screen.queryByText(sent.body)).toBeNull();
      expect(within(rowOf(sent.messageId)).queryByRole('button', { name: 'Actualizar' })).toBeNull();
      expect(liveRegion()).toHaveTextContent('');
    });

    it('replaces the whole component with the session sentence on a 401', async () => {
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), status(401));
      renderHistory();
      await listed();
      fireEvent.click(refreshButton(uncertain.messageId));
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(screen.queryByRole('list')).toBeNull();
    });

    it('reads detail and events again for an open row and shows the fresh detail', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      servePage(suite.server, [sent]);
      suite.server
        .on(detailPath(sent.messageId), detailReply(sent), detailReply(delivered))
        .on(eventsPath(sent.messageId), eventsReply(sent, eventsFixtures.rejected.events.slice(0, 1)), eventsReply(sent, eventsFixtures.acceptedDeliveredThenStaleReport.events));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(sent);
      fireEvent.click(refreshButton(sent.messageId));
      await act(async () => {});
      await rowLoaded(delivered);
      expect(suite.server.count(detailPath(sent.messageId))).toBe(2);
      expect(suite.server.count(eventsPath(sent.messageId))).toBe(2);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(sent.messageId)).toHaveAttribute('aria-expanded', 'true');
      const panel = panelOf(sent.messageId);
      expect(within(panel).getAllByRole('term').map((term) => term.textContent)).toEqual(['Teléfono', 'Aceptado por el proveedor', 'Entregado']);
      expect(within(panel).getAllByRole('listitem')).toHaveLength(6);
      expect(liveRegion()).toHaveTextContent('El estado cambió: ahora Entregado.');
    });

    it('survives closing the row and opening another one', async () => {
      const sent = details.sent;
      const other = details.read;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      const refresh = pending();
      servePage(suite.server, [sent, other]);
      suite.server.on(detailPath(sent.messageId), detailReply(sent), refresh).on(eventsPath(sent.messageId), eventsReply(sent));
      serveRow(suite.server, other);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(sent);
      fireEvent.click(refreshButton(sent.messageId));
      fireEvent.click(toggleOf(sent.messageId));
      fireEvent.click(toggleOf(other.messageId));
      await rowLoaded(other);
      await act(async () => refresh.resolve(detailReply(delivered)));
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(sent.messageId)).toHaveAttribute('aria-expanded', 'false');
      expect(toggleOf(other.messageId)).toHaveAttribute('aria-expanded', 'true');
      expect(liveRegion()).toHaveTextContent('El estado cambió: ahora Entregado.');
    });

    it('keeps the newer outcome when two reads of the same message answer out of order', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      const ownRead = pending();
      const refresh = pending();
      servePage(suite.server, [sent]);
      suite.server.on(detailPath(sent.messageId), ownRead, refresh).on(eventsPath(sent.messageId), eventsReply(sent));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      fireEvent.click(refreshButton(sent.messageId));
      expect(suite.server.count(detailPath(sent.messageId))).toBe(2);
      await act(async () => refresh.resolve(detailReply(delivered)));
      await act(async () => ownRead.resolve(detailReply(sent)));
      await rowLoaded(delivered);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getAllByRole('term').map((term) => term.textContent)).toContain('Entregado');
    });

    it('keeps the newer own read when the refresh lands first with the lower version', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      const ownRead = pending();
      const refresh = pending();
      servePage(suite.server, [sent]);
      suite.server.on(detailPath(sent.messageId), ownRead, refresh).on(eventsPath(sent.messageId), eventsReply(sent));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      fireEvent.click(refreshButton(sent.messageId));
      await act(async () => refresh.resolve(detailReply(sent)));
      expect(toggleOf(sent.messageId)).toHaveTextContent('Aceptado por el proveedor');
      await act(async () => ownRead.resolve(detailReply(delivered)));
      await rowLoaded(delivered);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getAllByRole('term').map((term) => term.textContent)).toContain('Entregado');
    });

    it('takes only contactRemoved from an equal version and nothing from a lower one on an open row', async () => {
      const delivered = details.delivered;
      const removed = variant(delivered, { contactRemoved: true });
      const older = variant(details.sent, { messageId: delivered.messageId });
      servePage(suite.server, [delivered]);
      serveRow(suite.server, delivered, eventsFixtures.rejected.events, detailReply(delivered), detailReply(removed), detailReply(older));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(delivered.messageId));
      await rowLoaded(delivered);
      fireEvent.click(refreshButton(delivered.messageId));
      await act(async () => {});
      expect(toggleOf(delivered.messageId)).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      // The tag appeared, so "no change" would be wrong even though the status stayed.
      expect(liveRegion()).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      expect(liveRegion()).not.toHaveTextContent('Sin cambios');
      expect(suite.server.count(eventsPath(delivered.messageId))).toBe(2);
      fireEvent.click(refreshButton(delivered.messageId));
      await act(async () => {});
      expect(toggleOf(delivered.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(delivered.messageId)).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      expect(within(panelOf(delivered.messageId)).getAllByRole('term').map((term) => term.textContent)).toContain('Entregado');
      expect(within(panelOf(delivered.messageId)).getByText(delivered.body)).toBeInTheDocument();
      expect(suite.server.count(detailPath(delivered.messageId))).toBe(3);
    });
  });

  // covers: AC-7, AC-8
  describe('Actualizar historial', () => {
    it('merges the first page again: new rows on top, higher versions win, older ones are dropped, the open row stays', async () => {
      const sent = details.sent;
      const read = details.read;
      const fresh = details.queued;
      const sentNow = variant(details.delivered, { messageId: sent.messageId });
      const readOlder = variant(read, { status: 'SENT', version: 3 });
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent, read], CURSOR_1)), ok(pageOf([fresh, sentNow, readOlder], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, CURSOR_2), ok(pages.lastPage));
      serveRow(suite.server, read);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(read.messageId));
      await rowLoaded(read);
      fireEvent.click(listRefresh());
      expect(listRefresh()).toHaveAttribute('aria-disabled', 'true');
      expect(loadMore()).toHaveAttribute('aria-disabled', 'true');
      await act(async () => {});
      expect(rowIds()).toEqual([fresh.messageId, sent.messageId, read.messageId]);
      expect(toggleOf(fresh.messageId)).toHaveTextContent('En cola');
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(read.messageId)).toHaveTextContent('Leído');
      expect(toggleOf(read.messageId)).toHaveAttribute('aria-expanded', 'true');
      expect(within(panelOf(read.messageId)).getByText(read.body)).toBeInTheDocument();
      expect(listRefresh()).not.toHaveAttribute('aria-disabled');
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_2))).toBe(1);
      expect(suite.server.count(detailPath(read.messageId))).toBe(1);
    });

    it('keeps the held cursor and the later pages once Cargar más has succeeded', async () => {
      const sent = details.sent;
      const read = details.read;
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), ok(pageOf([sent], 'another-cursor')))
        .on(historyPath(CUSTOMER_A, CURSOR_1), ok(pageOf([read], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, CURSOR_2), ok(pages.lastPage));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(rowIds()).toEqual([sent.messageId, read.messageId]);
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(rowIds()).toEqual([sent.messageId, read.messageId]);
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_2))).toBe(1);
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
    });

    it('aborts an in flight Cargar más, disables both controls and keeps the cursor for the next page', async () => {
      const sent = details.sent;
      const more = pending();
      const refresh = pending();
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), refresh)
        .on(historyPath(CUSTOMER_A, CURSOR_1), more, ok(pageOf([details.read])));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      expect(loadMore()).toHaveAttribute('aria-disabled', 'true');
      fireEvent.click(listRefresh());
      expect(listRefresh()).toHaveAttribute('aria-disabled', 'true');
      expect(loadMore()).toHaveAttribute('aria-disabled', 'true');
      await act(async () => refresh.resolve(ok(pageOf([sent], CURSOR_1))));
      expect(loadMore()).not.toHaveAttribute('aria-disabled');
      expect(listRefresh()).not.toHaveAttribute('aria-disabled');
      await act(async () => more.resolve(ok(pageOf([details.failedPermanently]))));
      expect(rowIds()).toEqual([sent.messageId]);
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_1))).toBe(2);
      expect(rowIds()).toEqual([sent.messageId, details.read.messageId]);
    });

    it('ignores a second click while in flight and a Cargar más during the refresh', async () => {
      const sent = details.sent;
      const refresh = pending();
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), refresh)
        .on(historyPath(CUSTOMER_A, CURSOR_1), ok(pageOf([details.read])));
      renderHistory();
      await listed();
      fireEvent.click(listRefresh());
      fireEvent.click(listRefresh());
      fireEvent.click(loadMore());
      expect(suite.server.count(historyPath(CUSTOMER_A))).toBe(2);
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_1))).toBe(0);
      await act(async () => refresh.resolve(ok(pageOf([sent], CURSOR_1))));
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_1))).toBe(1);
      expect(rowIds()).toEqual([sent.messageId, details.read.messageId]);
    });

    it('replaces the whole component on a 401 and aborts the open row reads', async () => {
      const sent = details.sent;
      const events = pending();
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([sent])), status(401));
      suite.server.on(detailPath(sent.messageId), detailReply(sent)).on(eventsPath(sent.messageId), events);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      expect(await within(panelOf(sent.messageId)).findByText(sent.body)).toBeInTheDocument();
      // The row's event read must be in flight before the refresh, or there is nothing to abort.
      await waitFor(() => expect(suite.server.count(eventsPath(sent.messageId))).toBe(1));
      fireEvent.click(listRefresh());
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(screen.queryByText(sent.body)).toBeNull();
      // The abort runs when the row unmounts, which can land a tick after the session sentence renders.
      await waitFor(() => expect(suite.server.last(eventsPath(sent.messageId)).signal?.aborted).toBe(true));
    });

    it('keeps every row and shows the sentence when the refresh fails', async () => {
      const sent = details.sent;
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([sent])), status(500), status(403));
      renderHistory();
      await listed();
      fireEvent.click(listRefresh());
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo actualizar el historial. Intenta de nuevo.');
      expect(rowIds()).toEqual([sent.messageId]);
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
      expect(rowIds()).toEqual([sent.messageId]);
    });

    it('does not regress an open row when the refresh carries an older version', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([sent])));
      serveRow(suite.server, delivered);
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(delivered);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(sent.messageId)).not.toHaveTextContent('Aceptado por el proveedor');
      expect(within(panelOf(sent.messageId)).getAllByRole('term').map((term) => term.textContent)).toContain('Entregado');
    });

    it('reads the open row again when the refresh raises its version, so header and panel agree', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([sent])), ok(pageOf([delivered])));
      serveRow(suite.server, sent, eventsFixtures.rejected.events, detailReply(sent), detailReply(delivered));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      await rowLoaded(sent);
      fireEvent.click(listRefresh());
      await act(async () => {});
      await rowLoaded(delivered);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent('Entregado');
      expect(within(panelOf(sent.messageId)).getAllByRole('term').map((term) => term.textContent)).toContain('Entregado');
      expect(suite.server.count(detailPath(sent.messageId))).toBe(2);
      expect(suite.server.count(eventsPath(sent.messageId))).toBe(2);
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(suite.server.count(detailPath(sent.messageId))).toBe(2);
    });

    it('clears the Cargar más failure when the refresh replaces the cursor', async () => {
      const sent = details.sent;
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), ok(pageOf([sent], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), status(500))
        .on(historyPath(CUSTOMER_A, CURSOR_2), ok(pageOf([details.read])));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      await screen.findByRole('alert');
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(screen.queryByRole('alert')).toBeNull();
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(rowIds()).toEqual([sent.messageId, details.read.messageId]);
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_1))).toBe(1);
    });

    it('announces a status change and the removed contact together, and a contact given back on its own', async () => {
      const waiting = details.pendingApproval;
      const approvedRemoved = variant(details.approved, { messageId: waiting.messageId, version: waiting.version + 1, contactRemoved: true });
      const givenBack = variant(approvedRemoved, { contactRemoved: false });
      servePage(suite.server, [waiting]);
      serveRow(suite.server, waiting, eventsFixtures.rejected.events, detailReply(waiting), detailReply(approvedRemoved), detailReply(givenBack));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(waiting.messageId));
      await rowLoaded(waiting);
      fireEvent.click(refreshButton(waiting.messageId));
      await act(async () => {});
      await rowLoaded(approvedRemoved);
      expect(liveRegion()).toHaveTextContent(
        'El estado cambió: ahora Aún no enviado. Aprobado, sin enviar. El teléfono ya no pertenece a este cliente.'
      );
      fireEvent.click(refreshButton(waiting.messageId));
      await act(async () => {});
      expect(toggleOf(waiting.messageId)).not.toHaveTextContent('El teléfono ya no pertenece a este cliente.');
      expect(liveRegion()).toHaveTextContent('El teléfono vuelve a pertenecer a este cliente.');
    });

    it('starts the list again when the refreshed first page does not overlap the held rows', async () => {
      const sent = details.sent;
      const read = details.read;
      const newest = [details.delivered, details.failedByResolution];
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), ok(pageOf(newest, 'fresh-cursor')))
        .on(historyPath(CUSTOMER_A, CURSOR_1), ok(pageOf([read], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, 'fresh-cursor'), ok(pageOf([sent, read])));
      serveRow(suite.server, read);
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      await act(async () => {});
      fireEvent.click(toggleOf(read.messageId));
      await rowLoaded(read);
      expect(rowIds()).toEqual([sent.messageId, read.messageId]);
      fireEvent.click(listRefresh());
      await act(async () => {});
      // More than a page of messages arrived: the held rows would sit below a gap, so the page replaces them.
      expect(rowIds()).toEqual(newest.map((detail) => detail.messageId));
      expect(screen.queryByRole('region', { name: 'Resultado del mensaje' })).toBeNull();
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(suite.server.count(historyPath(CUSTOMER_A, 'fresh-cursor'))).toBe(1);
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_2))).toBe(0);
      expect(rowIds()).toEqual([...newest.map((detail) => detail.messageId), sent.messageId, read.messageId]);
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
    });

    it('takes contactRemoved from an equal version', async () => {
      const approved = details.approved;
      const removed = variant(approved, { contactRemoved: true });
      suite.server.on(historyPath(CUSTOMER_A), ok(pageOf([approved])), ok(pageOf([removed])));
      renderHistory();
      await listed();
      expect(toggleOf(approved.messageId)).not.toHaveTextContent('El teléfono ya no pertenece');
      fireEvent.click(listRefresh());
      await act(async () => {});
      expect(toggleOf(approved.messageId)).toHaveTextContent('El teléfono ya no pertenece a este cliente.');
    });
  });

  // covers: AC-2, AC-9, AC-12
  describe('Cargar más', () => {
    it('appends the next page, dedupes a repeated item, announces the count and keeps focus', async () => {
      const [sent, read, failed, queued] = [details.sent, details.read, details.failedPermanently, details.queued];
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent, read], CURSOR_1)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), ok(pageOf([read, failed, queued], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, CURSOR_2), ok(pages.lastPage));
      renderHistory();
      await listed();
      const button = loadMore();
      button.focus();
      fireEvent.click(button);
      await act(async () => {});
      expect(rowIds()).toEqual([sent.messageId, read.messageId, failed.messageId, queued.messageId]);
      expect(liveRegion()).toHaveTextContent('Se cargaron 2 mensajes más.');
      expect(document.activeElement).toBe(button);
      expect(suite.server.last(historyPath(CUSTOMER_A, CURSOR_1)).key).toBe(
        `GET /api/messages?customerId=${CUSTOMER_A}&limit=20&cursor=${CURSOR_1}`
      );
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
      expect(rowIds()).toHaveLength(4);
      expect(liveRegion()).toHaveTextContent('Se cargaron 2 mensajes más.');
    });

    it('applies the version rule to a repeated item: a higher version wins, a lower one is dropped', async () => {
      const sent = details.sent;
      const read = details.read;
      const sentNow = variant(details.delivered, { messageId: sent.messageId });
      const readOlder = variant(read, { status: 'SENT', version: 3 });
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent, read], CURSOR_1)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), ok(pageOf([sentNow, readOlder, details.queued])));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      await act(async () => {});
      expect(rowIds()).toEqual([sent.messageId, read.messageId, details.queued.messageId]);
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
      expect(toggleOf(read.messageId)).toHaveTextContent('Leído');
      expect(toggleOf(read.messageId)).not.toHaveTextContent('Aceptado por el proveedor');
      expect(liveRegion()).toHaveTextContent('Se cargaron 1 mensajes más.');
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
    });

    it('replaces the whole component with the session sentence on a 401', async () => {
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([details.sent], CURSOR_1)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), status(401));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
      expect(screen.queryByRole('list')).toBeNull();
    });

    it('keeps the rows on a failure, retries with the same cursor and adds the hint on the second failure', async () => {
      const sent = details.sent;
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), status(500), status(500), status(403), ok(pageOf([details.read])));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('No se pudieron cargar más mensajes.');
      expect(alert).not.toHaveTextContent('Si sigue fallando');
      expect(rowIds()).toEqual([sent.messageId]);
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      await act(async () => {});
      expect(screen.getByRole('alert')).toHaveTextContent('Si sigue fallando, usa Actualizar historial.');
      fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: 'Reintentar' }));
      await act(async () => {});
      expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso para ver estos mensajes.');
      fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: 'Reintentar' }));
      await act(async () => {});
      expect(screen.queryByRole('alert')).toBeNull();
      expect(rowIds()).toEqual([sent.messageId, details.read.messageId]);
      expect(suite.server.count(historyPath(CUSTOMER_A, CURSOR_1))).toBe(4);
    });

    it('moves focus from a leaving Reintentar to Cargar más, and to the heading with the last page', async () => {
      const sent = details.sent;
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)))
        .on(historyPath(CUSTOMER_A, CURSOR_1), status(500), ok(pageOf([details.read], CURSOR_2)))
        .on(historyPath(CUSTOMER_A, CURSOR_2), status(500), ok(pages.lastPage));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      const retry = within(await screen.findByRole('alert')).getByRole('button', { name: 'Reintentar' });
      retry.focus();
      fireEvent.click(retry);
      // The alert leaves as soon as the read starts, so the appended row is what proves the reply landed.
      await waitFor(() => expect(rowIds()).toEqual([sent.messageId, details.read.messageId]));
      expect(screen.queryByRole('alert')).toBeNull();
      await waitFor(() => expect(document.activeElement).toBe(loadMore()));
      fireEvent.click(loadMore());
      const lastRetry = within(await screen.findByRole('alert')).getByRole('button', { name: 'Reintentar' });
      lastRetry.focus();
      fireEvent.click(lastRetry);
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull());
      await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Historial de mensajes' })));
    });

    it('leaves focus alone when Actualizar historial brings the last page after an unfocused Cargar más', async () => {
      const sent = details.sent;
      suite.server
        .on(historyPath(CUSTOMER_A), ok(pageOf([sent], CURSOR_1)), ok(pageOf([sent])))
        .on(historyPath(CUSTOMER_A, CURSOR_1), status(500));
      renderHistory();
      await listed();
      fireEvent.click(loadMore());
      await screen.findByRole('alert');
      const refresh = listRefresh();
      refresh.focus();
      fireEvent.click(refresh);
      await act(async () => {});
      expect(screen.queryByRole('button', { name: 'Cargar más' })).toBeNull();
      expect(document.activeElement).toBe(refresh);
    });
  });
  // covers: AC-6, AC-8 (review round of 2026-10-21)
  describe('row Actualizar after review', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    it('keeps a loaded detail when an older own read fails after the refresh landed', async () => {
      const sent = details.sent;
      const delivered = variant(details.delivered, { messageId: sent.messageId });
      const ownRead = pending();
      const refresh = pending();
      servePage(suite.server, [sent]);
      suite.server.on(detailPath(sent.messageId), ownRead, refresh).on(eventsPath(sent.messageId), eventsReply(sent));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(sent.messageId));
      fireEvent.click(refreshButton(sent.messageId));
      await act(async () => refresh.resolve(detailReply(delivered)));
      await rowLoaded(delivered);
      await act(async () => ownRead.resolve(status(500)));
      const panel = panelOf(sent.messageId);
      expect(within(panel).getByRole('region', { name: 'Resultado del mensaje' })).toHaveTextContent('Entregado');
      expect(within(panel).queryByText('No se pudo cargar el mensaje.')).toBeNull();
      expect(within(panel).queryByRole('alert')).toBeNull();
      expect(toggleOf(sent.messageId)).toHaveTextContent('Entregado');
    });

    it('announces a status change that keeps the same label with its secondary line', async () => {
      const waiting = details.pendingApproval;
      const approved = variant(details.approved, { messageId: waiting.messageId, version: waiting.version + 1 });
      servePage(suite.server, [waiting]);
      serveRow(suite.server, waiting, eventsFixtures.rejected.events, detailReply(waiting), detailReply(approved));
      renderHistory();
      await listed();
      fireEvent.click(toggleOf(waiting.messageId));
      await rowLoaded(waiting);
      fireEvent.click(refreshButton(waiting.messageId));
      await act(async () => {});
      await rowLoaded(approved);
      expect(toggleOf(waiting.messageId)).toHaveTextContent('Aprobado, sin enviar');
      expect(liveRegion()).toHaveTextContent('El estado cambió: ahora Aún no enviado. Aprobado, sin enviar.');
      expect(liveRegion()).not.toHaveTextContent('Sin cambios');
    });

    it('leaves no abort listener behind once a row read has settled', async () => {
      const uncertain = details.sendingOutcomeUnknown;
      const added = vi.spyOn(AbortSignal.prototype, 'addEventListener');
      const removed = vi.spyOn(AbortSignal.prototype, 'removeEventListener');
      servePage(suite.server, [uncertain]);
      suite.server.on(detailPath(uncertain.messageId), detailReply(uncertain));
      renderHistory();
      await listed();
      for (let round = 0; round < 3; round += 1) {
        fireEvent.click(refreshButton(uncertain.messageId));
        await act(async () => {});
      }
      expect(suite.server.count(detailPath(uncertain.messageId))).toBe(3);
      const net = new Map<unknown, number>();
      added.mock.calls.forEach(([type], index) => {
        if (type === 'abort') net.set(added.mock.contexts[index], (net.get(added.mock.contexts[index]) ?? 0) + 1);
      });
      removed.mock.calls.forEach(([type], index) => {
        if (type === 'abort') net.set(removed.mock.contexts[index], (net.get(removed.mock.contexts[index]) ?? 0) - 1);
      });
      // The fetch stub keeps one listener per request signal; the hook's mount signal must keep none.
      expect(Math.max(...net.values())).toBeLessThanOrEqual(1);
    });
  });
});
