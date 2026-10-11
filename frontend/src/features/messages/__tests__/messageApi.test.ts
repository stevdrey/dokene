// ABOUTME: Contract tests for messageApi against the ADR 0023 routes, headers and bodies (spec 0004 AC-3 to AC-6).
// ABOUTME: Pins the wire format the backend accepts so a wrong enum value fails here, not in the browser.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { InvalidMessageResponseError, messageApi } from '../api/messageApi';
import { approvedEvent, installFetchRouter, pendingDetail, pendingItem, submittedEvent } from './fixtures';

type Router = ReturnType<typeof installFetchRouter>;

describe('messageApi', () => {
  let router: Router;

  beforeEach(() => {
    httpClient.setTenantId('tenant-1');
    httpClient.setCsrfToken('csrf-1');
    router = installFetchRouter();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId(null);
    httpClient.setCsrfToken(null);
  });

  describe('list (AC-1)', () => {
    it('repeats status for every filtered status and defaults the page size to 50', async () => {
      router.set('GET /api/messages', { body: { items: [pendingItem], nextCursor: null } });

      const page = await messageApi.list({ statuses: ['QUEUED', 'SENDING', 'SENT'] });

      const url = router.callsTo('GET /api/messages')[0].url;
      expect(url.searchParams.getAll('status')).toEqual(['QUEUED', 'SENDING', 'SENT']);
      expect(url.searchParams.get('limit')).toBe('50');
      expect(url.searchParams.has('customerId')).toBe(false);
      expect(url.searchParams.has('cursor')).toBe(false);
      expect(page).toEqual({ items: [pendingItem], nextCursor: null });
    });

    it('sends no status for Todos and forwards the customer and cursor', async () => {
      router.set('GET /api/messages', { body: { items: [], nextCursor: 'abc' } });

      const page = await messageApi.list({ statuses: [], customerId: 'cust-1', cursor: 'c2', limit: 10 });

      const url = router.callsTo('GET /api/messages')[0].url;
      expect(url.searchParams.getAll('status')).toEqual([]);
      expect(url.searchParams.get('customerId')).toBe('cust-1');
      expect(url.searchParams.get('cursor')).toBe('c2');
      expect(url.searchParams.get('limit')).toBe('10');
      expect(page.nextCursor).toBe('abc');
    });

    it('sends the tenant header on every list call', async () => {
      router.set('GET /api/messages', { body: { items: [], nextCursor: null } });

      await messageApi.list({ statuses: ['PENDING_APPROVAL'] });

      expect(router.calls[0].headers['X-Tenant-Id']).toBe('tenant-1');
    });

    it('rejects a page whose items lack the members the list renders', async () => {
      router.set('GET /api/messages', { body: { items: [{ messageId: 'x' }], nextCursor: null } });

      await expect(messageApi.list({ statuses: [] })).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });
  });

  describe('getDetail (AC-2)', () => {
    it('returns the detail with the strong ETag as the version', async () => {
      router.set('GET /api/messages/msg-1', { body: pendingDetail, etag: '"4"' });

      const result = await messageApi.getDetail('msg-1');

      expect(result.version).toBe(4);
      expect(result.detail.body).toBe(pendingDetail.body);
    });

    it.each([
      ['a missing ETag', undefined],
      ['a weak ETag', 'W/"4"']
    ])('rejects a detail with %s instead of inventing version 0', async (_label, etag) => {
      router.set('GET /api/messages/msg-1', { body: pendingDetail, etag });

      await expect(messageApi.getDetail('msg-1')).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });

    it('rejects a detail body without the members the panel renders', async () => {
      router.set('GET /api/messages/msg-1', { body: { ...pendingDetail, attempts: undefined }, etag: '"1"' });

      await expect(messageApi.getDetail('msg-1')).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });

    it('encodes the message id in the path', async () => {
      router.set('GET /api/messages/a%2Fb', { body: pendingDetail, etag: '"1"' });

      await messageApi.getDetail('a/b');

      expect(router.calls[0].url.pathname).toBe('/api/messages/a%2Fb');
    });
  });

  describe('getEvents (AC-2)', () => {
    it('returns the events ordered by sequence number whatever order the server used', async () => {
      router.set('GET /api/messages/msg-1/events', { body: { events: [approvedEvent, submittedEvent] } });

      const events = await messageApi.getEvents('msg-1');

      expect(events.map((event) => event.sequenceNumber)).toEqual([1, 2]);
    });

    it('rejects an events body that is not a list of events', async () => {
      router.set('GET /api/messages/msg-1/events', { body: { events: [{ eventType: 'SUBMITTED' }] } });

      await expect(messageApi.getEvents('msg-1')).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });
  });

  describe('decide (AC-3)', () => {
    it('approves with the ADR 0023 decision value, the held version and the intent key', async () => {
      router.set('POST /api/messages/msg-1/approvals', { body: { ...pendingDetail, status: 'APPROVED', version: 2 }, etag: '"2"' });

      const result = await messageApi.decide('msg-1', { decision: 'APPROVE', version: 1, idempotencyKey: 'key-1' });

      const call = router.callsTo('POST /api/messages/msg-1/approvals')[0];
      expect(call.body).toEqual({ decision: 'APPROVE' });
      expect(call.headers['If-Match']).toBe('"1"');
      expect(call.headers['Idempotency-Key']).toBe('key-1');
      expect(call.headers['Content-Type']).toBe('application/json');
      expect(call.headers['X-CSRF-TOKEN']).toBe('csrf-1');
      expect(result.version).toBe(2);
      expect(result.detail.status).toBe('APPROVED');
    });

    it('rejects with the note when one is given and omits it when empty', async () => {
      router.set('POST /api/messages/msg-1/approvals', { body: { ...pendingDetail, status: 'REJECTED', version: 2 }, etag: '"2"' });

      await messageApi.decide('msg-1', { decision: 'REJECT', note: 'Tono informal', version: 1, idempotencyKey: 'k' });
      await messageApi.decide('msg-1', { decision: 'REJECT', note: '', version: 1, idempotencyKey: 'k' });

      const [withNote, withoutNote] = router.callsTo('POST /api/messages/msg-1/approvals');
      expect(withNote.body).toEqual({ decision: 'REJECT', note: 'Tono informal' });
      expect(withoutNote.body).toEqual({ decision: 'REJECT' });
    });

    it('rejects a decision response that comes back without a strong ETag', async () => {
      router.set('POST /api/messages/msg-1/approvals', { body: { ...pendingDetail, status: 'APPROVED', version: 2 } });

      await expect(
        messageApi.decide('msg-1', { decision: 'APPROVE', version: 1, idempotencyKey: 'k' })
      ).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });
  });

  describe('cancel (AC-4)', () => {
    it('posts an empty object without a note and the note when given', async () => {
      router.set('POST /api/messages/msg-1/cancellations', { body: { ...pendingDetail, status: 'CANCELLED', version: 2 }, etag: '"2"' });

      await messageApi.cancel('msg-1', { version: 1, idempotencyKey: 'k1' });
      await messageApi.cancel('msg-1', { note: 'Ya no aplica', version: 1, idempotencyKey: 'k2' });

      const [plain, noted] = router.callsTo('POST /api/messages/msg-1/cancellations');
      expect(plain.body).toEqual({});
      expect(plain.headers['If-Match']).toBe('"1"');
      expect(plain.headers['Idempotency-Key']).toBe('k1');
      expect(noted.body).toEqual({ note: 'Ya no aplica' });
    });
  });

  describe('send (AC-5)', () => {
    it('posts with no body, the held version and the intent key', async () => {
      router.set('POST /api/messages/msg-1/sends', { body: { ...pendingDetail, status: 'SENT', version: 5 }, etag: '"5"' });

      const result = await messageApi.send('msg-1', { version: 2, idempotencyKey: 'send-1' });

      const call = router.callsTo('POST /api/messages/msg-1/sends')[0];
      expect(call.body).toBeUndefined();
      expect(call.headers['Content-Type']).toBeUndefined();
      expect(call.headers['If-Match']).toBe('"2"');
      expect(call.headers['Idempotency-Key']).toBe('send-1');
      expect(result.version).toBe(5);
    });
  });

  describe('submit (AC-6)', () => {
    it('posts the draft to the customer route with the policy version as If-Match', async () => {
      router.set('POST /api/customers/cust-1/messages', { body: pendingDetail, etag: '"1"' });
      const request = {
        contactId: 'phone-1',
        action: 'REPEAT_PURCHASE_FOLLOW_UP',
        templateIntent: 'REPEAT_PURCHASE',
        body: 'Hola Valentina',
        locale: 'es-419',
        origin: 'AI_DRAFT'
      } as const;

      const result = await messageApi.submit('cust-1', { request, policyVersion: 3, idempotencyKey: 'sub-1' });

      const call = router.callsTo('POST /api/customers/cust-1/messages')[0];
      expect(call.body).toEqual(request);
      expect(call.headers['If-Match']).toBe('"3"');
      expect(call.headers['Idempotency-Key']).toBe('sub-1');
      expect(result.version).toBe(1);
      expect(result.detail.messageId).toBe('msg-1');
    });
  });
});
