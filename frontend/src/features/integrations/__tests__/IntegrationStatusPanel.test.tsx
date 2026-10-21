// ABOUTME: Tests the WhatsApp integration panel end to end inside jsdom with fetch stubbed from the frozen fixtures.
// ABOUTME: Covers states, readiness, role gating, saves, stale recovery, ownership proof, tenant switch, session end and leaks.
import integrationJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/integration.json?raw';
import templatesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/templates.json?raw';
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { IntegrationStatusPanel } from '../components/IntegrationStatusPanel';
import type { IntegrationResponse, TemplateResponse } from '../types';

const integration = JSON.parse(integrationJson);
const templates = JSON.parse(templatesJson);
const problems = JSON.parse(problemsJson);

const readyRecord: IntegrationResponse = integration.response.valid.outboundAndWebhookReady;
const pausedRecord: IntegrationResponse = integration.response.valid.enabledWithDisplayNumber;
const disabledRecord: IntegrationResponse = integration.response.valid.disabledWithoutDisplayNumber;
const tpl: Record<string, TemplateResponse> = templates.response.valid;
const fourTemplates = { items: [tpl.approvedAndSendable, tpl.mappedButUnverified, tpl.approvalSchemaMismatch, tpl.unmapped] };
const noneSendable = { items: [tpl.mappedButUnverified, tpl.unmapped] };

interface Reply {
  status: number;
  body?: unknown;
  etag?: string;
}

interface Pending {
  reply: () => Promise<Reply>;
  resolve: (reply: Reply) => void;
  /** A late reply ignores the abort signal, as a response already on the wire would. */
  late: boolean;
}

type Responder = Reply | Pending;

interface Captured {
  key: string;
  headers: Record<string, string>;
  body: string | undefined;
  signal: AbortSignal | undefined;
}

const ok = (body: unknown, etag?: string): Reply => ({ status: 200, body, etag });
const problem = (code: string): Reply => ({ status: problems.valid[code].status, body: problems.valid[code] });
const recordReply = (record: IntegrationResponse) => ok(record, `"${record.version}"`);

function pending(late = false): Pending {
  let resolve: (reply: Reply) => void = () => {};
  const promise = new Promise<Reply>((settle) => {
    resolve = settle;
  });
  return { reply: () => promise, resolve, late };
}

function toResponse(reply: Reply): Response {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (reply.etag) headers.ETag = reply.etag;
  return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), { status: reply.status, headers });
}

const GET_RECORD = 'GET /api/integrations/whatsapp';
const PUT_RECORD = 'PUT /api/integrations/whatsapp';
const POST_PROOF = 'POST /api/integrations/whatsapp/ownership-verifications';
const GET_TEMPLATES = 'GET /api/templates';

class FakeServer {
  readonly calls: Captured[] = [];
  readonly unexpected: string[] = [];
  private readonly routes = new Map<string, Responder[]>();

  on(key: string, ...replies: Responder[]): this {
    this.routes.set(key, replies);
    return this;
  }

  count(key: string): number {
    return this.calls.filter((call) => call.key === key).length;
  }

  last(key: string): Captured {
    const matching = this.calls.filter((call) => call.key === key);
    return matching[matching.length - 1];
  }

  install() {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const key = `${init?.method ?? 'GET'} ${String(input)}`;
      const signal = init?.signal ?? undefined;
      this.calls.push({ key, headers: (init?.headers as Record<string, string>) ?? {}, body: init?.body as string | undefined, signal });
      const queue = this.routes.get(key);
      if (!queue || queue.length === 0) {
        this.unexpected.push(key);
        throw new TypeError(`unexpected request ${key}`);
      }
      const responder = queue.length > 1 ? queue.shift()! : queue[0];
      if (!('reply' in responder)) return toResponse(responder);
      if (responder.late) return toResponse(await responder.reply());
      return new Promise<Response>((resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
        void responder.reply().then((reply) => resolve(toResponse(reply)));
      });
    });
  }
}

let server: FakeServer;
let consoleSpies: MockInstance[];
let storageSpies: MockInstance[];

function renderPanel(role: string | null = 'OWNER', tenantId = 'tenant-a') {
  httpClient.setTenantId(tenantId);
  const view = render(<IntegrationStatusPanel workspace={{ tenantId, role }} />);
  return {
    ...view,
    switchTo(nextTenant: string, nextRole: string | null = role) {
      httpClient.setTenantId(nextTenant);
      view.rerender(<IntegrationStatusPanel workspace={{ tenantId: nextTenant, role: nextRole }} />);
    }
  };
}

const card = () => screen.getByRole('region', { name: 'Configuración del número' });
const templatesRegion = () => screen.getByRole('region', { name: 'Plantillas de mensaje' });
const liveRegion = () => screen.getByRole('status');
const form = () => {
  const element = document.querySelector('form');
  if (!element) throw new Error('form not rendered');
  return element;
};
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

async function loaded(phoneNumberId = readyRecord.phoneNumberId) {
  await screen.findByText(phoneNumberId);
  await waitFor(() => expect(templatesRegion()).not.toHaveTextContent('Cargando plantillas…'));
}

beforeEach(() => {
  vi.restoreAllMocks();
  httpClient.setCsrfToken('csrf-a');
  server = new FakeServer();
  server.install();
  consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method));
  storageSpies = [
    vi.spyOn(Storage.prototype, 'getItem'),
    vi.spyOn(Storage.prototype, 'setItem'),
    vi.spyOn(Storage.prototype, 'removeItem'),
    vi.spyOn(Document.prototype, 'cookie', 'get'),
    vi.spyOn(Document.prototype, 'cookie', 'set')
  ];
});

afterEach(() => {
  expect(server.unexpected).toEqual([]);
  for (const spy of consoleSpies) expect(spy).not.toHaveBeenCalled();
  for (const spy of storageSpies) expect(spy).not.toHaveBeenCalled();
});

describe('IntegrationStatusPanel', () => {
  describe('reading', () => {
    it('shows a busy loading region and a checking badge until both reads answer', async () => {
      const record = pending();
      server.on(GET_RECORD, record).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel();

      expect(screen.getByText('Cargando integración…').closest('[aria-busy="true"]')).not.toBeNull();
      expect(screen.getByText('Comprobando…')).toBeInTheDocument();

      await act(async () => record.resolve(recordReply(readyRecord)));
      await loaded();
      expect(screen.queryByText('Cargando integración…')).toBeNull();
    });

    it('renders the record card, ready badge and template count from the fixtures', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel();
      await loaded();

      const values = within(card());
      expect(values.getByText('Identificador del número').nextElementSibling).toHaveTextContent('100000000000001');
      expect(values.getByText('Cuenta de WhatsApp Business').nextElementSibling).toHaveTextContent('200000000000001');
      expect(values.getByText('Número visible').nextElementSibling).toHaveTextContent('+50600000011');
      expect(values.getByText('synthetic-wa-token')).toBeInTheDocument();
      expect(values.getByText('Solo el nombre de la referencia; el valor nunca sale del servidor.')).toBeInTheDocument();
      expect(values.getByText('Habilitada')).toBeInTheDocument();
      expect(values.getByText('Envíos activos')).toBeInTheDocument();
      expect(values.getByText('Propiedad comprobada')).toBeInTheDocument();
      expect(screen.getByText('Lista para enviar')).toBeInTheDocument();
      expect(screen.queryByRole('list', { name: 'Lo que falta para enviar' })).toBeNull();
      expect(
        screen.getByText('Una pausa global de la plataforma puede bloquear los envíos aunque la integración esté lista.')
      ).toBeInTheDocument();
      expect(within(templatesRegion()).getByText('1 de 4 plantillas listas para enviar')).toBeInTheDocument();
      expect(server.last(GET_RECORD).headers['X-Tenant-Id']).toBe('tenant-a');
      expect(server.last(GET_TEMPLATES).headers['X-Tenant-Id']).toBe('tenant-a');
    });

    it('lists what is missing in the fixed order', async () => {
      server.on(GET_RECORD, recordReply(pausedRecord)).on(GET_TEMPLATES, ok(noneSendable));
      renderPanel();
      await loaded();

      expect(screen.getByText('No lista para enviar')).toBeInTheDocument();
      const missing = within(screen.getByRole('list', { name: 'Lo que falta para enviar' })).getAllByRole('listitem');
      expect(missing.map((item) => item.textContent)).toEqual([
        'envíos pausados',
        'propiedad del número sin comprobar',
        'ninguna plantilla lista'
      ]);
      expect(within(card()).getByText('Envíos pausados')).toBeInTheDocument();
      expect(within(card()).getByText('Propiedad sin comprobar')).toBeInTheDocument();
    });

    it('shows a missing display number and a disabled integration in words', async () => {
      server.on(GET_RECORD, recordReply(disabledRecord)).on(GET_TEMPLATES, ok(noneSendable));
      renderPanel();
      await loaded();

      expect(within(card()).getByText('Sin número visible')).toBeInTheDocument();
      expect(within(card()).getByText('Deshabilitada')).toBeInTheDocument();
      expect(screen.getByRole('list', { name: 'Lo que falta para enviar' })).toHaveTextContent('integración deshabilitada');
    });

    it('keeps the record when the templates read fails and retries only the templates', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, { status: 500, body: {} }, ok(fourTemplates));
      renderPanel();
      await screen.findByText('100000000000001');

      const region = within(templatesRegion());
      expect(
        await region.findByText('Ocurrió un problema en el servidor al procesar la solicitud. Por favor intenta nuevamente.')
      ).toBeInTheDocument();
      expect(screen.getByText('Sin determinar')).toBeInTheDocument();

      fireEvent.click(region.getByRole('button', { name: 'Reintentar' }));

      expect(await region.findByText('1 de 4 plantillas listas para enviar')).toBeInTheDocument();
      expect(server.count(GET_TEMPLATES)).toBe(2);
      expect(server.count(GET_RECORD)).toBe(1);
      expect(screen.getByText('Lista para enviar')).toBeInTheDocument();
    });

    it('keeps the templates when the record read fails and retries only the record', async () => {
      server.on(GET_RECORD, { status: 503, body: {} }, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel();

      expect(
        await screen.findByText('El servicio no está disponible temporalmente. Por favor intenta de nuevo en unos momentos.')
      ).toBeInTheDocument();
      expect(await within(templatesRegion()).findByText('1 de 4 plantillas listas para enviar')).toBeInTheDocument();
      expect(screen.getByText('Sin determinar')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));

      await loaded();
      expect(server.count(GET_RECORD)).toBe(2);
      expect(server.count(GET_TEMPLATES)).toBe(1);
    });

    it('treats a body that fails narrowing as a read error', async () => {
      server.on(GET_RECORD, ok({ ...readyRecord, enabled: 'yes' }, '"3"')).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel();

      expect(
        await screen.findByText('La respuesta del servidor sobre la integración no tiene el formato esperado.')
      ).toBeInTheDocument();
    });
  });

  describe('not configured', () => {
    it('shows managers the create form and creates with If-None-Match: * and the fixture body', async () => {
      server
        .on(GET_RECORD, problem('NOT_FOUND'))
        .on(GET_TEMPLATES, ok(noneSendable), ok(fourTemplates))
        .on(PUT_RECORD, ok(readyRecord, '"0"'));
      renderPanel('ADMIN');

      expect(await screen.findByRole('heading', { name: 'Integración de WhatsApp sin configurar' })).toBeInTheDocument();
      expect(screen.getByText('Sin determinar')).toBeInTheDocument();
      expect(document.activeElement).toBe(document.body);

      const fixture = integration.request.valid.enabledWithDisplayNumber;
      type('Identificador del número', fixture.phoneNumberId);
      type('Cuenta de WhatsApp Business', fixture.businessAccountId);
      type('Número visible', fixture.displayPhoneNumber);
      type('Referencia de credencial', fixture.secretRef);
      fireEvent.click(screen.getByLabelText('Integración habilitada'));
      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      const put = server.last(PUT_RECORD);
      expect(put.headers['If-None-Match']).toBe('*');
      expect(put.headers['If-Match']).toBeUndefined();
      expect(put.body).toBe(JSON.stringify(fixture));
      expect(within(card()).getByText('Propiedad comprobada')).toBeInTheDocument();
      expect(document.querySelector('form')).toBeNull();
      expect(await within(templatesRegion()).findByText('1 de 4 plantillas listas para enviar')).toBeInTheDocument();
      expect(server.count(GET_TEMPLATES)).toBe(2);
    });

    it('shows viewers the not configured heading and the read only sentence', async () => {
      server.on(GET_RECORD, problem('NOT_FOUND')).on(GET_TEMPLATES, ok(noneSendable));
      renderPanel('VIEWER');

      expect(await screen.findByRole('heading', { name: 'Integración de WhatsApp sin configurar' })).toBeInTheDocument();
      expect(screen.getByText('Solo lectura: tu rol no permite administrar la integración.')).toBeInTheDocument();
      expect(document.querySelector('form')).toBeNull();
    });

    it('treats a 404 without the NOT_FOUND problem as an error, not as absent', async () => {
      server.on(GET_RECORD, { status: 404, body: { message: 'nope' } }).on(GET_TEMPLATES, ok(noneSendable));
      renderPanel();

      expect(await screen.findByText('El recurso solicitado no fue encontrado.')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Integración de WhatsApp sin configurar' })).toBeNull();
    });
  });

  describe('roles', () => {
    it.each(['OPERATOR', 'VIEWER', null])('gives %s a read only view after the same reads', async (role) => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel(role);
      await loaded();

      expect(screen.queryByRole('button', { name: 'Editar' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Verificar propiedad' })).toBeNull();
      expect(screen.getByText('Solo lectura: tu rol no permite administrar la integración.')).toBeInTheDocument();
      expect(server.count(GET_RECORD)).toBe(1);
      expect(server.count(GET_TEMPLATES)).toBe(1);
    });

    it.each(['OWNER', 'ADMIN', 'TENANT_ADMIN'])('gives %s the manage controls', async (role) => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel(role);
      await loaded();

      expect(screen.getByRole('button', { name: 'Editar' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Verificar propiedad' })).toBeInTheDocument();
      expect(screen.queryByText('Solo lectura: tu rol no permite administrar la integración.')).toBeNull();
    });

    it('hides the controls when the server answers 403 to a manager', async () => {
      server.on(GET_RECORD, recordReply(pausedRecord)).on(GET_TEMPLATES, ok(fourTemplates)).on(PUT_RECORD, problem('FORBIDDEN'));
      renderPanel('OWNER');
      await loaded();

      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));
      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Acceso denegado en este espacio de trabajo.'));
      expect(document.querySelector('form')).toBeNull();
      expect(screen.queryByRole('button', { name: 'Editar' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Verificar propiedad' })).toBeNull();
    });
  });

  describe('saving', () => {
    async function openEditor(record: IntegrationResponse = pausedRecord) {
      server.on(GET_RECORD, recordReply(record)).on(GET_TEMPLATES, ok(fourTemplates));
      const view = renderPanel('OWNER');
      await loaded();
      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));
      return view;
    }

    it('resaves the stored record unchanged with If-Match and the fixture body', async () => {
      await openEditor();
      server.on(PUT_RECORD, recordReply({ ...pausedRecord, version: 4 }));

      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      const put = server.last(PUT_RECORD);
      expect(put.headers['If-Match']).toBe('"3"');
      expect(put.headers['If-None-Match']).toBeUndefined();
      expect(put.body).toBe(JSON.stringify(integration.request.valid.enabledWithDisplayNumber));
    });

    it('re reads the templates after a save and updates the count from the reply', async () => {
      await openEditor();
      server.on(PUT_RECORD, recordReply({ ...pausedRecord, version: 4 })).on(GET_TEMPLATES, ok(noneSendable));

      fireEvent.submit(form());

      expect(await within(templatesRegion()).findByText('0 de 2 plantillas listas para enviar')).toBeInTheDocument();
      expect(server.count(GET_TEMPLATES)).toBe(2);
    });

    it('keeps the ownership proof disabled while the form is open', async () => {
      await openEditor();

      expect(screen.getByRole('button', { name: 'Verificar propiedad' })).toBeDisabled();

      fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
      expect(screen.getByRole('button', { name: 'Verificar propiedad' })).toBeEnabled();
    });

    it('starts only one request when submitted twice', async () => {
      await openEditor();
      const put = pending();
      server.on(PUT_RECORD, put);

      fireEvent.submit(form());
      expect(screen.getByRole('button', { name: 'Guardando…' })).toBeDisabled();
      fireEvent.submit(form());

      await act(async () => put.resolve(recordReply({ ...pausedRecord, version: 4 })));
      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.count(PUT_RECORD)).toBe(1);
    });

    it('sends one disabling request only after the confirmation', async () => {
      await openEditor();
      server.on(PUT_RECORD, recordReply({ ...pausedRecord, enabled: false, version: 4 }));

      fireEvent.click(screen.getByLabelText('Integración habilitada'));
      fireEvent.submit(form());
      expect(server.count(PUT_RECORD)).toBe(0);
      fireEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.count(PUT_RECORD)).toBe(1);
      expect(JSON.parse(server.last(PUT_RECORD).body ?? '{}').enabled).toBe(false);
    });

    it('recovers from a stale version keeping typed values and resubmitting with the new ETag', async () => {
      await openEditor();
      const current = { ...pausedRecord, secretRef: 'otra-referencia', version: 4 };
      server.on(PUT_RECORD, problem('STALE_VERSION'), recordReply({ ...current, version: 5 })).on(GET_RECORD, recordReply(current));

      type('Número visible', '+50688888888');
      fireEvent.submit(form());

      await waitFor(() =>
        expect(liveRegion()).toHaveTextContent(
          'Otra persona cambió la integración. Revisa los valores actuales y guarda de nuevo.'
        )
      );
      const values = screen.getByRole('region', { name: 'Valores actuales en el servidor' });
      await waitFor(() => expect(values).toHaveTextContent('otra-referencia'));
      expect(within(values).getByRole('heading', { level: 3 })).toHaveTextContent('Valores actuales en el servidor');
      expect(screen.getByLabelText('Número visible')).toHaveValue('+50688888888');
      expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('synthetic-wa-token');

      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.last(PUT_RECORD).headers['If-Match']).toBe('"4"');
    });

    it('switches a stale create to the version precondition when the re read finds a record', async () => {
      server
        .on(GET_RECORD, problem('NOT_FOUND'), recordReply(pausedRecord))
        .on(GET_TEMPLATES, ok(fourTemplates))
        .on(PUT_RECORD, problem('STALE_VERSION'), recordReply({ ...pausedRecord, version: 4 }));
      renderPanel('OWNER');
      await screen.findByRole('heading', { name: 'Integración de WhatsApp sin configurar' });

      const fixture = integration.request.valid.enabledWithDisplayNumber;
      type('Identificador del número', fixture.phoneNumberId);
      type('Cuenta de WhatsApp Business', fixture.businessAccountId);
      type('Referencia de credencial', 'mi-referencia');
      fireEvent.click(screen.getByLabelText('Integración habilitada'));
      fireEvent.submit(form());

      await screen.findByRole('region', { name: 'Valores actuales en el servidor' });
      await waitFor(() => expect(screen.getByRole('region', { name: 'Valores actuales en el servidor' })).toHaveTextContent('synthetic-wa-token'));
      expect(server.calls.find((call) => call.key === PUT_RECORD)?.headers['If-None-Match']).toBe('*');
      expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('mi-referencia');

      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.last(PUT_RECORD).headers['If-Match']).toBe('"3"');
      expect(server.last(PUT_RECORD).headers['If-None-Match']).toBeUndefined();
    });

    async function failStaleReread() {
      await openEditor();
      server
        .on(PUT_RECORD, problem('STALE_VERSION'), recordReply({ ...pausedRecord, version: 5 }))
        .on(GET_RECORD, { status: 500, body: {} }, recordReply({ ...pausedRecord, version: 4 }));

      type('Referencia de credencial', 'mi-referencia');
      fireEvent.submit(form());

      const values = await screen.findByRole('region', { name: 'Valores actuales en el servidor' });
      await waitFor(() =>
        expect(values).toHaveTextContent('Ocurrió un problema en el servidor al procesar la solicitud. Por favor intenta nuevamente.')
      );
      expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('mi-referencia');
      return values;
    }

    it('keeps typed values and the previous ETag when the stale re read fails', async () => {
      await failStaleReread();

      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.last(PUT_RECORD).headers['If-Match']).toBe('"3"');
    });

    it('retries a failed stale re read and then saves with the ETag it returned', async () => {
      const values = await failStaleReread();

      fireEvent.click(within(values).getByRole('button', { name: 'Reintentar' }));
      await waitFor(() => expect(values).toHaveTextContent('synthetic-wa-token'));
      expect(server.count(GET_RECORD)).toBe(3);
      expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('mi-referencia');

      fireEvent.submit(form());
      await waitFor(() => expect(liveRegion()).toHaveTextContent('Integración guardada.'));
      expect(server.last(PUT_RECORD).headers['If-Match']).toBe('"4"');
    });

    it.each([
      ['INVALID_INPUT', problem('INVALID_INPUT'), 'Revisa los identificadores, el número visible y la referencia.'],
      ['RATE_LIMITED', problem('RATE_LIMITED'), 'Demasiadas solicitudes. Espera un momento antes de reintentar.'],
      [
        'a malformed 400',
        { status: 400, body: { message: 'phoneNumberId 100000000000001 rejected' } },
        'La solicitud no pudo ser procesada. Revisa los datos ingresados.'
      ],
      [
        'a 500',
        { status: 500, body: {} },
        'Ocurrió un problema en el servidor al procesar la solicitud. Por favor intenta nuevamente.'
      ]
    ])('shows the copy for %s and keeps the form open', async (_label, reply, message) => {
      await openEditor();
      server.on(PUT_RECORD, reply);

      fireEvent.submit(form());

      await waitFor(() => expect(liveRegion()).toHaveTextContent(message));
      expect(liveRegion()).not.toHaveTextContent('100000000000001');
      expect(document.querySelector('form')).not.toBeNull();
      expect(screen.getByRole('button', { name: 'Guardar' })).toBeEnabled();
    });
  });

  describe('ownership proof', () => {
    async function ready(record: IntegrationResponse = pausedRecord) {
      server.on(GET_RECORD, recordReply(record)).on(GET_TEMPLATES, ok(noneSendable));
      renderPanel('OWNER');
      await loaded();
    }

    it('posts with the current ETag and applies the proved record', async () => {
      await ready();
      server.on(POST_PROOF, recordReply({ ...readyRecord, version: 4 })).on(GET_TEMPLATES, ok(fourTemplates));

      fireEvent.click(screen.getByRole('button', { name: 'Verificar propiedad' }));

      await waitFor(() => expect(liveRegion()).toHaveTextContent('Propiedad del número comprobada.'));
      expect(server.last(POST_PROOF).headers['If-Match']).toBe('"3"');
      expect(server.last(POST_PROOF).body).toBeUndefined();
      expect(within(card()).getByText('Propiedad comprobada')).toBeInTheDocument();
      expect(await screen.findByText('Lista para enviar')).toBeInTheDocument();
      expect(server.count(GET_TEMPLATES)).toBe(2);
      expect(screen.getByRole('button', { name: 'Verificar propiedad' })).toBeEnabled();
    });

    it('is disabled while the proof is in flight', async () => {
      await ready();
      const proof = pending();
      server.on(POST_PROOF, proof).on(GET_TEMPLATES, ok(noneSendable));

      fireEvent.click(screen.getByRole('button', { name: 'Verificar propiedad' }));
      expect(screen.getByRole('button', { name: 'Verificando…' })).toBeDisabled();

      await act(async () => proof.resolve(recordReply({ ...readyRecord, version: 4 })));
      await waitFor(() => expect(liveRegion()).toHaveTextContent('Propiedad del número comprobada.'));
      expect(server.count(POST_PROOF)).toBe(1);
    });

    it.each([
      [
        'PROVIDER_UNAVAILABLE',
        'El proveedor no está disponible en este momento. Intenta más tarde.'
      ],
      [
        'INVALID_INPUT',
        'No se pudo comprobar la propiedad del número con la referencia configurada. Revisa que la integración esté habilitada y que los identificadores sean correctos.'
      ],
      ['RATE_LIMITED', 'Demasiadas solicitudes. Espera un momento antes de reintentar.']
    ])('shows the copy for %s and leaves the badge alone', async (code, message) => {
      await ready();
      server.on(POST_PROOF, problem(code));

      fireEvent.click(screen.getByRole('button', { name: 'Verificar propiedad' }));

      await waitFor(() => expect(liveRegion()).toHaveTextContent(message));
      expect(within(card()).getByText('Propiedad sin comprobar')).toBeInTheDocument();
      expect(server.count(GET_TEMPLATES)).toBe(1);
    });

    it('re reads the record after a stale proof and asks to verify again', async () => {
      await ready();
      server
        .on(POST_PROOF, problem('STALE_VERSION'))
        .on(GET_RECORD, recordReply({ ...pausedRecord, secretRef: 'otra-referencia', version: 5 }));

      fireEvent.click(screen.getByRole('button', { name: 'Verificar propiedad' }));

      await waitFor(() =>
        expect(liveRegion()).toHaveTextContent('La integración cambió. Revisa los valores y vuelve a verificar.')
      );
      expect(await within(card()).findByText('otra-referencia')).toBeInTheDocument();
    });
  });

  describe('tenant switch', () => {
    it('aborts the first reads, never renders their late reply, and reads again for the new tenant', async () => {
      const lateRecord = pending(true);
      const firstTemplates = pending();
      server.on(GET_RECORD, lateRecord).on(GET_TEMPLATES, firstTemplates);
      const view = renderPanel('OWNER', 'tenant-a');
      const firstSignal = server.calls[0].signal;

      const tenantB = { ...readyRecord, phoneNumberId: '100000000000002' };
      server.on(GET_RECORD, recordReply(tenantB)).on(GET_TEMPLATES, ok(fourTemplates));
      view.switchTo('tenant-b');

      expect(firstSignal?.aborted).toBe(true);
      expect(screen.getByText('Cargando integración…')).toBeInTheDocument();
      await loaded('100000000000002');

      await act(async () => lateRecord.resolve(recordReply(readyRecord)));
      expect(screen.queryByText('100000000000001')).toBeNull();
      expect(server.last(GET_RECORD).headers['X-Tenant-Id']).toBe('tenant-b');
      expect(server.last(GET_TEMPLATES).headers['X-Tenant-Id']).toBe('tenant-b');
      expect(server.count(GET_RECORD)).toBe(2);
    });

    it('clears an open form and its typed values', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      const view = renderPanel('OWNER', 'tenant-a');
      await loaded();
      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));
      type('Referencia de credencial', 'borrador-de-a');

      view.switchTo('tenant-b');

      expect(document.querySelector('form')).toBeNull();
      expect(screen.queryByDisplayValue('borrador-de-a')).toBeNull();
      expect(screen.getByText('Cargando integración…')).toBeInTheDocument();
      await loaded();
    });
  });

  describe('session end', () => {
    it('shows only the session sentence when a read answers 401', async () => {
      server.on(GET_RECORD, { status: 401 }).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel('OWNER');

      expect(
        await screen.findByText('Tu sesión ya no está activa. Por favor inicia sesión nuevamente.')
      ).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: 'Integración de WhatsApp' })).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Plantillas de mensaje' })).toBeNull();
      expect(screen.queryByText('Sin determinar')).toBeNull();
      expect(screen.queryByText(/Una pausa global/)).toBeNull();
      expect(screen.queryByText('100000000000001')).toBeNull();
    });

    it('clears the form when a save answers 401', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates)).on(PUT_RECORD, { status: 401 });
      renderPanel('OWNER');
      await loaded();
      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));
      type('Referencia de credencial', 'borrador');

      fireEvent.submit(form());

      expect(
        await screen.findByText('Tu sesión ya no está activa. Por favor inicia sesión nuevamente.')
      ).toBeInTheDocument();
      expect(document.querySelector('form')).toBeNull();
      expect(screen.queryByDisplayValue('borrador')).toBeNull();
      expect(screen.queryByText('100000000000001')).toBeNull();
    });
  });

  describe('credential guard', () => {
    it('never lets a smuggled token reach the DOM', async () => {
      server
        .on(GET_RECORD, ok({ ...readyRecord, accessToken: 'SYNTHETIC-NOT-A-REAL-TOKEN' }, '"3"'))
        .on(GET_TEMPLATES, ok({ items: [{ ...tpl.approvedAndSendable, accessToken: 'SYNTHETIC-NOT-A-REAL-TOKEN' }] }));
      renderPanel('OWNER');
      await loaded();
      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));

      expect(document.body.innerHTML).not.toContain('SYNTHETIC-NOT-A-REAL-TOKEN');
    });

    it('renders no input that could carry a credential', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel('OWNER');
      await loaded();
      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));

      for (const input of Array.from(document.querySelectorAll('input'))) {
        expect(input.type).not.toBe('password');
        const label = input.labels?.[0]?.textContent ?? '';
        expect(`${input.name} ${label} ${input.autocomplete}`).not.toMatch(/token|password|contraseña|valor secreto|secret value/i);
      }
    });
  });

  describe('accessibility', () => {
    it('is a labelled region with ordered headings and one status region', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel('OWNER');
      await loaded();

      const panel = screen.getByRole('region', { name: 'Integración de WhatsApp' });
      expect(panel.tagName).toBe('SECTION');
      const headings = within(panel).getAllByRole('heading');
      expect(headings.map((heading) => `${heading.tagName} ${heading.textContent}`)).toEqual([
        'H2 Integración de WhatsApp',
        'H3 Configuración del número',
        'H3 Plantillas de mensaje'
      ]);
      expect(screen.getAllByRole('status')).toHaveLength(1);
    });

    it('keeps the controls as native buttons in keyboard order', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, { status: 500, body: {} });
      renderPanel('OWNER');
      await screen.findByText('100000000000001');
      await within(templatesRegion()).findByRole('button', { name: 'Reintentar' });

      const order = ['Editar', 'Verificar propiedad', 'Reintentar'].map((name) => screen.getByRole('button', { name }));
      for (const button of order) expect(button.tagName).toBe('BUTTON');
      for (let index = 1; index < order.length; index += 1) {
        expect(order[index - 1].compareDocumentPosition(order[index]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      }
    });

    it('moves focus into the form on Editar and back to Editar on Cancelar', async () => {
      server.on(GET_RECORD, recordReply(readyRecord)).on(GET_TEMPLATES, ok(fourTemplates));
      renderPanel('OWNER');
      await loaded();

      fireEvent.click(screen.getByRole('button', { name: 'Editar' }));
      expect(document.activeElement).toBe(screen.getByLabelText('Identificador del número'));

      const fields = ['Identificador del número', 'Cuenta de WhatsApp Business', 'Número visible', 'Referencia de credencial', 'Integración habilitada'].map(
        (label) => screen.getByLabelText(label)
      );
      const controls = [...fields, screen.getByRole('button', { name: 'Guardar' }), screen.getByRole('button', { name: 'Cancelar' })];
      for (let index = 1; index < controls.length; index += 1) {
        expect(controls[index - 1].compareDocumentPosition(controls[index]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      }

      fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
      expect(document.querySelector('form')).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Editar' }));
    });
  });
});
