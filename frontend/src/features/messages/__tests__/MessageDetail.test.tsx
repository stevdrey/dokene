// ABOUTME: Tests review mode rendering from the frozen fixtures: states, the action state matrix, role gating and switching.
// ABOUTME: Also proves plain text rendering, the tenant guard, session end, and that nothing leaks across a prop change.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { MessageReview, type MessageReviewMode } from '../components/MessageReview';
import type { MessageResponse } from '../types';
import {
  FakeServer,
  details,
  detailPath,
  detailReply,
  eventsFixtures,
  eventsPath,
  eventsReply,
  ok,
  pending,
  status,
  variant
} from './messageTestServer';

let server: FakeServer;

beforeEach(() => {
  vi.restoreAllMocks();
  httpClient.setCsrfToken('csrf-a');
  server = new FakeServer();
  server.install();
});

afterEach(() => {
  expect(server.unexpected).toEqual([]);
});

function serve(detail: MessageResponse, events = eventsFixtures.rejected.events) {
  server.on(detailPath(detail.messageId), detailReply(detail)).on(eventsPath(detail.messageId), eventsReply(detail, events));
}

function renderReview(messageId: string, role: string | null = 'OPERATOR', tenantId = 'tenant-a', customerDisplayName?: string) {
  httpClient.setTenantId(tenantId);
  const mode: MessageReviewMode = { kind: 'review', messageId, customerDisplayName };
  const view = render(<MessageReview tenantId={tenantId} role={role} mode={mode} />);
  return {
    ...view,
    rerenderWith(next: { tenantId?: string; messageId?: string; role?: string | null }) {
      const nextTenant = next.tenantId ?? tenantId;
      httpClient.setTenantId(nextTenant);
      view.rerender(
        <MessageReview
          tenantId={nextTenant}
          role={next.role === undefined ? role : next.role}
          mode={{ kind: 'review', messageId: next.messageId ?? messageId }}
        />
      );
    }
  };
}

const actions = () => screen.queryByRole('region', { name: 'Acciones' });
const buttonNames = () =>
  actions() ? within(actions()!).queryAllByRole('button').map((button) => button.textContent) : [];

async function loaded(detail: MessageResponse) {
  await screen.findByText(`Revisión ${detail.version}`);
  await waitFor(() => expect(screen.queryByText('Cargando historial…')).toBeNull());
}

describe('MessageReview review mode', () => {
  // covers: AC-5, AC-13
  describe('rendering', () => {
    it('shows a busy loading region until the detail answers', async () => {
      const detail = details.pendingApproval;
      const reply = pending();
      server.on(detailPath(detail.messageId), reply).on(eventsPath(detail.messageId), eventsReply(detail));
      renderReview(detail.messageId);
      expect(screen.getByText('Cargando mensaje…').closest('[aria-busy="true"]')).not.toBeNull();
      await act(async () => reply.resolve(detailReply(detail)));
      await loaded(detail);
    });

    it('renders the message from the fixture with labels, revision and the full phone', async () => {
      const detail = details.pendingApproval;
      serve(detail, eventsFixtures.rejected.events.slice(0, 1));
      renderReview(detail.messageId, 'OPERATOR', 'tenant-a', 'Ana Pérez');
      await loaded(detail);
      expect(screen.getByRole('heading', { level: 2, name: 'Ana Pérez' })).toBeInTheDocument();
      expect(screen.getByText('Pendiente de aprobación')).toBeInTheDocument();
      expect(screen.getByText(detail.recipientPhone)).toBeInTheDocument();
      expect(screen.getByText(detail.body)).toBeInTheDocument();
      expect(screen.getByText('Borrador de IA')).toBeInTheDocument();
      expect(screen.getByText('Miembro · Enviado a aprobación')).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Resultado del envío' })).toBeNull();
    });

    it('heads the view with the masked phone when no customer name is given', async () => {
      const detail = details.pendingApproval;
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      expect(screen.getByRole('heading', { level: 2, name: '•••• 0001' })).toBeInTheDocument();
    });

    it('shows the outcome card without the provider message id', async () => {
      const detail = details.delivered;
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      const card = screen.getByRole('region', { name: 'Resultado del envío' });
      expect(card).toHaveTextContent('Intento 1');
      expect(document.body.textContent).not.toContain(detail.providerMessageId!);
    });

    it('shows the failure category of a failed send', async () => {
      const detail = details.failedPermanently;
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      expect(screen.getByRole('region', { name: 'Resultado del envío' })).toHaveTextContent(/Falló definitivamente/);
    });

    it('renders the timeline transitions and hides events that were not applied', async () => {
      const detail = details.delivered;
      serve(detail, eventsFixtures.acceptedDeliveredThenStaleReport.events);
      renderReview(detail.messageId);
      await loaded(detail);
      const rows = within(screen.getByRole('list', { name: 'Historial del mensaje' })).getAllByRole('listitem');
      const applied = eventsFixtures.acceptedDeliveredThenStaleReport.events.filter((event) => event.applied);
      expect(rows).toHaveLength(applied.length);
      expect(screen.getByText('Miembro · Pendiente de aprobación → Aprobado')).toBeInTheDocument();
    });

    it('renders markup in the body as literal text and adds no element', async () => {
      const markup = '<img src=x onerror=alert(1)>';
      const detail = variant(details.pendingApproval, { body: markup });
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      expect(screen.getByText(markup)).toBeInTheDocument();
      expect(document.querySelector('img')).toBeNull();
    });

    it('shows the contact removed and frozen notices', async () => {
      serve(details.approvedContactRemoved);
      const first = renderReview(details.approvedContactRemoved.messageId);
      await loaded(details.approvedContactRemoved);
      expect(screen.getByText(/El teléfono ya no pertenece a este cliente/)).toBeInTheDocument();
      first.unmount();

      serve(details.sendingOutcomeUnknown);
      renderReview(details.sendingOutcomeUnknown.messageId);
      await loaded(details.sendingOutcomeUnknown);
      expect(
        screen.getByText('El proveedor no confirmó el envío. Un administrador debe resolverlo.')
      ).toBeInTheDocument();
    });
  });

  // covers: AC-5, AC-12
  describe('states', () => {
    it('shows not found on a 404', async () => {
      const id = details.pendingApproval.messageId;
      server.on(detailPath(id), status(404)).on(eventsPath(id), status(404));
      renderReview(id);
      expect(await screen.findByText('Este mensaje ya no está disponible.')).toBeInTheDocument();
    });

    it('shows an error with Reintentar that reads both again', async () => {
      const detail = details.pendingApproval;
      server
        .on(detailPath(detail.messageId), status(500), detailReply(detail))
        .on(eventsPath(detail.messageId), eventsReply(detail));
      renderReview(detail.messageId);
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('No se pudo cargar el mensaje.');
      fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      await loaded(detail);
      expect(server.count(detailPath(detail.messageId))).toBe(2);
      expect(server.count(eventsPath(detail.messageId))).toBe(2);
    });

    it('treats a detail without a strong ETag as the error state, never version 0', async () => {
      const detail = details.pendingApproval;
      server.on(detailPath(detail.messageId), ok(detail, 'W/"1"')).on(eventsPath(detail.messageId), eventsReply(detail));
      renderReview(detail.messageId);
      expect(await screen.findByText('No se pudo cargar el mensaje.')).toBeInTheDocument();
      expect(screen.queryByText('Revisión 0')).toBeNull();
    });

    it('keeps the detail when the events read fails and retries it inline', async () => {
      const detail = details.pendingApproval;
      server
        .on(detailPath(detail.messageId), detailReply(detail))
        .on(eventsPath(detail.messageId), status(500), eventsReply(detail, eventsFixtures.rejected.events.slice(0, 1)));
      renderReview(detail.messageId);
      const alert = await screen.findByText('No se pudo cargar el historial del mensaje.');
      expect(screen.getByText(detail.body)).toBeInTheDocument();
      fireEvent.click(within(alert.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: 'Reintentar' }));
      expect(await screen.findByText('Miembro · Enviado a aprobación')).toBeInTheDocument();
      expect(server.count(detailPath(detail.messageId))).toBe(1);
    });

    it('renders the session sentence and no controls on a 401', async () => {
      const id = details.pendingApproval.messageId;
      server.on(detailPath(id), status(401)).on(eventsPath(id), pending());
      renderReview(id);
      expect(await screen.findByText('Tu sesión terminó. Vuelve a iniciar sesión.')).toBeInTheDocument();
      expect(screen.queryAllByRole('button')).toHaveLength(0);
    });
  });

  // covers: AC-6
  describe('action state matrix', () => {
    const matrix: [string, string[]][] = [
      ['pendingApproval', ['Aprobar', 'Rechazar', 'Cancelar', 'Corregir']],
      ['approved', ['Enviar', 'Cancelar', 'Corregir']],
      ['approvedAfterTransientFailure', ['Enviar', 'Cancelar', 'Corregir']],
      ['approvedContactRemoved', ['Cancelar']],
      ['queued', ['Actualizar']],
      ['sent', ['Actualizar']],
      ['sendingOutcomeUnknown', ['Actualizar']],
      ['delivered', ['Actualizar']],
      ['read', []],
      ['failedPermanently', []],
      ['failedByWebhook', []],
      ['failedByResolution', []],
      ['rejected', []],
      ['cancelled', []]
    ];

    it.each(matrix)('offers %s exactly %j to an operator', async (name, expected) => {
      const detail = details[name];
      serve(detail);
      renderReview(detail.messageId, 'OPERATOR');
      await loaded(detail);
      expect(buttonNames()).toEqual(expected);
      for (const button of actions() ? within(actions()!).queryAllByRole('button') : []) {
        expect(button).toBeEnabled();
      }
    });

    it('offers a pending message whose contact was removed only Rechazar and Cancelar', async () => {
      const detail = variant(details.pendingApproval, { contactRemoved: true });
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      expect(buttonNames()).toEqual(['Rechazar', 'Cancelar']);
      expect(screen.getByText(/cancélalo y vuelve a redactarlo con el número correcto/)).toBeInTheDocument();
    });

    it('offers the polling row a SENDING message not yet frozen as Actualizar when no poll runs', async () => {
      const detail = variant(details.sendingOutcomeUnknown, { outcomeUnknown: false });
      serve(detail);
      renderReview(detail.messageId);
      await loaded(detail);
      expect(buttonNames()).toEqual(['Actualizar']);
    });

    it.each(['OWNER', 'ADMIN', 'TENANT_ADMIN', 'TENANT_OPERATOR'])('gives %s the same controls', async (role) => {
      serve(details.pendingApproval);
      renderReview(details.pendingApproval.messageId, role);
      await loaded(details.pendingApproval);
      expect(buttonNames()).toEqual(['Aprobar', 'Rechazar', 'Cancelar', 'Corregir']);
    });

    it.each(matrix.map(([name]) => name))('shows %s to a viewer with no controls', async (name) => {
      const detail = details[name];
      serve(detail);
      renderReview(detail.messageId, 'VIEWER');
      await loaded(detail);
      expect(actions()).toBeNull();
      expect(screen.getByText('Tu rol solo permite consultar mensajes.')).toBeInTheDocument();
    });

    it.each([[null], ['SOMETHING_ELSE']])('keeps role %s read only', async (role) => {
      serve(details.approved);
      renderReview(details.approved.messageId, role);
      await loaded(details.approved);
      expect(actions()).toBeNull();
    });
  });

  // covers: AC-1, AC-12
  describe('switching', () => {
    it('renders nothing and sends no request when the tenant is not the active one', () => {
      httpClient.setTenantId('tenant-b');
      const { container } = render(
        <MessageReview tenantId="tenant-a" role="OWNER" mode={{ kind: 'review', messageId: 'x' }} />
      );
      expect(container).toBeEmptyDOMElement();
      expect(server.calls).toHaveLength(0);
    });

    it('drops a late reply for the previous tenant and shows only the new one', async () => {
      const first = variant(details.pendingApproval, { body: 'Texto del espacio anterior' });
      const second = details.approved;
      const late = pending(true);
      server.on(detailPath(first.messageId), late).on(eventsPath(first.messageId), pending(true));
      serve(second);
      const view = renderReview(first.messageId, 'OPERATOR', 'tenant-a');
      view.rerenderWith({ tenantId: 'tenant-b', messageId: second.messageId });
      await loaded(second);
      await act(async () => late.resolve(detailReply(first)));
      expect(screen.queryByText(first.body)).toBeNull();
      expect(screen.getByText('Aprobado')).toBeInTheDocument();
    });

    it('aborts the previous message read when the message changes', async () => {
      const first = variant(details.pendingApproval, { body: 'Texto del mensaje anterior' });
      const second = details.rejected;
      server.on(detailPath(first.messageId), pending()).on(eventsPath(first.messageId), pending());
      serve(second);
      const view = renderReview(first.messageId);
      view.rerenderWith({ messageId: second.messageId });
      await loaded(second);
      expect(screen.getByText('Rechazado')).toBeInTheDocument();
      expect(screen.queryByText(first.body)).toBeNull();
    });

    it('closes an open confirm group and forgets it on a message change', async () => {
      const first = details.pendingApproval;
      const second = details.approved;
      serve(first);
      serve(second);
      const view = renderReview(first.messageId);
      await loaded(first);
      fireEvent.click(screen.getByRole('button', { name: 'Aprobar' }));
      expect(screen.getByRole('group', { name: 'Confirmar aprobación' })).toBeInTheDocument();
      view.rerenderWith({ messageId: second.messageId });
      await loaded(second);
      expect(screen.queryByRole('group')).toBeNull();
    });
  });
});
