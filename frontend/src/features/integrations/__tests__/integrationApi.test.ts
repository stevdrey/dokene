// ABOUTME: Tests the integration API module against the frozen messaging v1 fixtures.
// ABOUTME: Covers headers, preconditions, runtime narrowing, dropped members and ETag parsing.
import integrationJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/integration.json?raw';
import templatesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/templates.json?raw';
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, httpClient } from '@/shared/api/httpClient';
import { InvalidIntegrationResponseError, integrationApi } from '../api/integrationApi';

const integration = JSON.parse(integrationJson);
const templates = JSON.parse(templatesJson);
const problems = JSON.parse(problemsJson);

interface Captured {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
}

function reply(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers }
  });
}

function stubFetch(response: () => Response): Captured[] {
  const calls: Captured[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      headers: (init?.headers as Record<string, string>) ?? {},
      body: init?.body as string | undefined
    });
    return response();
  });
  return calls;
}

const signal = () => new AbortController().signal;

describe('integrationApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId('tenant-a');
    httpClient.setCsrfToken('csrf-a');
  });

  describe('getIntegration', () => {
    it('reads the record with the tenant header and returns the strong ETag', async () => {
      const record = integration.response.valid.enabledWithDisplayNumber;
      const calls = stubFetch(() => reply(record, 200, { ETag: '"3"' }));

      const result = await integrationApi.getIntegration(signal());

      expect(calls[0].url).toBe('/api/integrations/whatsapp');
      expect(calls[0].method).toBe('GET');
      expect(calls[0].headers['X-Tenant-Id']).toBe('tenant-a');
      expect(result).toEqual({ record, etag: '"3"' });
    });

    it('drops members the contract does not name before returning', async () => {
      const smuggled = {
        ...integration.response.valid.outboundAndWebhookReady,
        accessToken: 'SYNTHETIC-NOT-A-REAL-TOKEN'
      };
      stubFetch(() => reply(smuggled, 200, { ETag: '"3"' }));

      const result = await integrationApi.getIntegration(signal());

      expect(result.record).toEqual(integration.response.valid.outboundAndWebhookReady);
      expect(JSON.stringify(result)).not.toContain('SYNTHETIC-NOT-A-REAL-TOKEN');
    });

    it.each([
      ['a missing ETag', {}],
      ['a weak ETag', { ETag: 'W/"3"' }],
      ['a non numeric ETag', { ETag: '"abc"' }],
      ['an unquoted ETag', { ETag: '3' }]
    ])('rejects %s', async (_label, headers) => {
      stubFetch(() => reply(integration.response.valid.enabledWithDisplayNumber, 200, headers));

      await expect(integrationApi.getIntegration(signal())).rejects.toBeInstanceOf(InvalidIntegrationResponseError);
    });

    it.each([
      ['a string enabled flag', { enabled: 'true' }],
      ['a missing webhookVerified', { webhookVerified: undefined }],
      ['a null phoneNumberId', { phoneNumberId: null }],
      ['a fractional version', { version: 1.5 }],
      ['a numeric displayPhoneNumber', { displayPhoneNumber: 50600000011 }]
    ])('rejects a body with %s', async (_label, override) => {
      const body = { ...integration.response.valid.enabledWithDisplayNumber, ...override };
      stubFetch(() => reply(body, 200, { ETag: '"3"' }));

      const failure = integrationApi.getIntegration(signal());

      await expect(failure).rejects.toBeInstanceOf(InvalidIntegrationResponseError);
      await expect(failure).rejects.toThrow('La respuesta del servidor sobre la integración no tiene el formato esperado.');
    });

    it('surfaces a 404 NOT_FOUND problem as an ApiError carrying the problem', async () => {
      stubFetch(() => reply(problems.valid.NOT_FOUND, 404));

      const failure = await integrationApi.getIntegration(signal()).catch((error: unknown) => error);

      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).status).toBe(404);
      expect((failure as ApiError).payload).toEqual(problems.valid.NOT_FOUND);
    });
  });

  describe('saveIntegration', () => {
    it('creates with If-None-Match: * and the exact fixture body', async () => {
      const calls = stubFetch(() =>
        reply(integration.response.valid.enabledWithDisplayNumber, 200, { ETag: '"0"' })
      );

      const result = await integrationApi.saveIntegration(
        integration.request.valid.enabledWithDisplayNumber,
        { kind: 'create' },
        signal()
      );

      expect(calls[0].url).toBe('/api/integrations/whatsapp');
      expect(calls[0].method).toBe('PUT');
      expect(calls[0].headers['If-None-Match']).toBe('*');
      expect(calls[0].headers['If-Match']).toBeUndefined();
      expect(calls[0].headers['X-CSRF-TOKEN']).toBe('csrf-a');
      expect(calls[0].body).toBe(JSON.stringify(integration.request.valid.enabledWithDisplayNumber));
      expect(result.etag).toBe('"0"');
    });

    it('replaces with If-Match and omits an empty display number', async () => {
      const calls = stubFetch(() =>
        reply(integration.response.valid.disabledWithoutDisplayNumber, 200, { ETag: '"4"' })
      );

      await integrationApi.saveIntegration(
        { ...integration.request.valid.disabledWithoutDisplayNumber, displayPhoneNumber: '' },
        { kind: 'version', etag: '"3"' },
        signal()
      );

      expect(calls[0].headers['If-Match']).toBe('"3"');
      expect(calls[0].headers['If-None-Match']).toBeUndefined();
      expect(calls[0].body).toBe(JSON.stringify(integration.request.valid.disabledWithoutDisplayNumber));
    });

    it('never sends a member the contract does not name', async () => {
      const calls = stubFetch(() =>
        reply(integration.response.valid.enabledWithDisplayNumber, 200, { ETag: '"4"' })
      );

      await integrationApi.saveIntegration(
        integration.request.invalid.rawCredentialSmuggled,
        { kind: 'version', etag: '"3"' },
        signal()
      );

      expect(calls[0].body).not.toContain('SYNTHETIC-NOT-A-REAL-TOKEN');
      expect(Object.keys(JSON.parse(calls[0].body ?? '{}'))).toEqual([
        'phoneNumberId',
        'businessAccountId',
        'enabled',
        'secretRef'
      ]);
    });

    it('surfaces a 409 STALE_VERSION problem as an ApiError', async () => {
      stubFetch(() => reply(problems.valid.STALE_VERSION, 409));

      const failure = await integrationApi
        .saveIntegration(integration.request.valid.enabledWithDisplayNumber, { kind: 'version', etag: '"2"' }, signal())
        .catch((error: unknown) => error);

      expect((failure as ApiError).status).toBe(409);
      expect((failure as ApiError).payload).toEqual(problems.valid.STALE_VERSION);
    });
  });

  describe('verifyOwnership', () => {
    it('posts with If-Match and no body, returning the narrowed record', async () => {
      const calls = stubFetch(() =>
        reply(integration.response.valid.outboundAndWebhookReady, 200, { ETag: '"4"' })
      );

      const result = await integrationApi.verifyOwnership('"3"', signal());

      expect(calls[0].url).toBe('/api/integrations/whatsapp/ownership-verifications');
      expect(calls[0].method).toBe('POST');
      expect(calls[0].headers['If-Match']).toBe('"3"');
      expect(calls[0].body).toBeUndefined();
      expect(result).toEqual({ record: integration.response.valid.outboundAndWebhookReady, etag: '"4"' });
    });
  });

  describe('getTemplates', () => {
    const validItems = () => Object.values(templates.response.valid);

    it('reads the template list and keeps the returned order', async () => {
      const calls = stubFetch(() => reply({ items: validItems() }));

      const result = await integrationApi.getTemplates(signal());

      expect(calls[0].url).toBe('/api/templates');
      expect(calls[0].headers['X-Tenant-Id']).toBe('tenant-a');
      expect(result.items).toEqual(validItems());
    });

    it('accepts the one slot list fixture', async () => {
      stubFetch(() => reply(templates.list.valid.oneSlotPerIntent));

      const result = await integrationApi.getTemplates(signal());

      expect(result).toEqual(templates.list.valid.oneSlotPerIntent);
    });

    it('drops unnamed members on the list and on each item', async () => {
      const item = { ...templates.response.valid.approvedAndSendable, providerTemplateId: '1259544702043867' };
      stubFetch(() => reply({ items: [item], cursor: 'x' }));

      const result = await integrationApi.getTemplates(signal());

      expect(result).toEqual({ items: [templates.response.valid.approvedAndSendable] });
    });

    it.each([
      ['an unknown approval status', { approvalStatus: 'MAYBE' }],
      ['an unknown intent', { templateIntent: 'COUPON' }],
      ['a non string parameter name', { parameterNames: [1] }],
      ['a string sendable flag', { sendable: 'true' }],
      ['a fractional version', { version: 0.5 }]
    ])('rejects an item with %s', async (_label, override) => {
      stubFetch(() => reply({ items: [{ ...templates.response.valid.approvedAndSendable, ...override }] }));

      await expect(integrationApi.getTemplates(signal())).rejects.toBeInstanceOf(InvalidIntegrationResponseError);
    });

    it('rejects a body without an items array', async () => {
      stubFetch(() => reply({ items: null }));

      await expect(integrationApi.getTemplates(signal())).rejects.toBeInstanceOf(InvalidIntegrationResponseError);
    });
  });
});
