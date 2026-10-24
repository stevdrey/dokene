// ABOUTME: Tests the message API module against the frozen v1 fixtures: strict version parsing, narrowing and request shapes.
// ABOUTME: Proves the submit body matches the contract byte for byte and that a send carries no body and no Content-Type.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, httpClient } from '@/shared/api/httpClient';
import { InvalidMessageResponseError, messageApi, parseMessageVersion } from '../api/messageApi';
import {
  FakeServer,
  approvalsPath,
  cancellationsPath,
  details,
  detailPath,
  detailReply,
  eventsFixtures,
  eventsPath,
  historyPath,
  localesFixtures,
  localesPath,
  ok,
  pages,
  pending,
  requests,
  sendsPath,
  submitPath
} from './messageTestServer';

const pendingApproval = details.pendingApproval;
const id = pendingApproval.messageId;
let server: FakeServer;

beforeEach(() => {
  vi.restoreAllMocks();
  httpClient.setTenantId('tenant-a');
  httpClient.setCsrfToken('csrf-a');
  server = new FakeServer();
  server.install();
});

// covers: AC-5
describe('parseMessageVersion', () => {
  it('accepts a strong numeric ETag equal to the body version', () => {
    expect(parseMessageVersion('"7"', 7)).toBe(7);
    expect(parseMessageVersion('"0"', 0)).toBe(0);
  });

  it.each([
    ['missing', null, 1],
    ['weak', 'W/"1"', 1],
    ['unquoted', '1', 1],
    ['leading zero', '"01"', 1],
    ['not numeric', '"abc"', 1],
    ['negative', '"-1"', -1],
    ['mismatched with the body', '"2"', 1]
  ])('refuses a %s ETag instead of reading version 0', (_name, etag, bodyVersion) => {
    expect(() => parseMessageVersion(etag, bodyVersion)).toThrow(InvalidMessageResponseError);
  });
});

// covers: AC-3, AC-5, AC-15
describe('messageApi', () => {
  it('reads every detail fixture with its version from the ETag', async () => {
    for (const detail of Object.values(details)) {
      server.on(detailPath(detail.messageId), detailReply(detail));
      const read = await messageApi.getDetail(detail.messageId);
      expect(read.version).toBe(detail.version);
      expect(read.detail.status).toBe(detail.status);
    }
  });

  it('keeps a detail without template, as a backend from before issue 268 sends it, apart from null', async () => {
    const { template: _omitted, ...older } = details.pendingApproval;
    server.on(detailPath(older.messageId), detailReply(older as typeof details.pendingApproval));
    const read = await messageApi.getDetail(older.messageId);
    // Absent means "no preview from this backend", which is not "the approval no longer covers the message".
    expect(read.detail.template).toBeUndefined();
    server.on(detailPath(older.messageId), detailReply({ ...details.pendingApproval, template: null }));
    expect((await messageApi.getDetail(older.messageId)).detail.template).toBeNull();
  });

  describe('getLocales (issue 268)', () => {
    it.each(Object.entries(localesFixtures))('reads the %s fixture', async (_name, fixture) => {
      server.on(localesPath, ok(fixture));
      expect(await messageApi.getLocales()).toEqual(fixture);
    });

    it('drops a locale this client does not know, but not the default', async () => {
      server.on(localesPath, ok({ locales: ['es-419', 'pt-BR'], defaultLocale: 'es-419' }));
      expect(await messageApi.getLocales()).toEqual({ locales: ['es-419'], defaultLocale: 'es-419' });
    });

    it.each([
      ['a default outside the list', { locales: ['en-US'], defaultLocale: 'es-419' }],
      ['an unknown default', { locales: ['es-419', 'pt-BR'], defaultLocale: 'pt-BR' }],
      ['a non string locale', { locales: ['es-419', 1], defaultLocale: 'es-419' }],
      ['no list', { defaultLocale: 'es-419' }]
    ])('rejects %s', async (_label, body) => {
      server.on(localesPath, ok(body));
      await expect(messageApi.getLocales()).rejects.toBeInstanceOf(InvalidMessageResponseError);
    });
  });

  it('treats a detail without a strong ETag as invalid', async () => {
    server.on(detailPath(id), ok(pendingApproval));
    await expect(messageApi.getDetail(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('treats a detail missing a member the view needs as invalid', async () => {
    const { body: _body, ...withoutBody } = pendingApproval;
    server.on(detailPath(id), ok(withoutBody, '"1"'));
    await expect(messageApi.getDetail(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it.each([
    ['sentAt', 1700000000],
    ['failureCategory', 'SOMETHING_ELSE'],
    ['action', 'UNKNOWN_ACTION'],
    ['templateIntent', 42],
    ['channel', 'SMS'],
    ['locale', null],
    ['createdAt', undefined],
    ['updatedAt', 1700000000]
  ])('treats a detail with an invalid %s as invalid', async (member, value) => {
    server.on(detailPath(id), ok({ ...pendingApproval, [member]: value }, '"1"'));
    await expect(messageApi.getDetail(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it.each([
    ['an object failureCategory', { failureCategory: { code: 'RATE_LIMITED' } }],
    ['an unknown outcome', { outcome: 'EXPLODED' }],
    ['a missing startedAt', { startedAt: undefined }]
  ])('treats a detail whose attempt has %s as invalid', async (_name, changes) => {
    const base = details.approvedAfterTransientFailure;
    server.on(detailPath(base.messageId), ok({ ...base, attempts: [{ ...base.attempts[0], ...changes }] }, `"${base.version}"`));
    await expect(messageApi.getDetail(base.messageId)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('refuses a detail that describes another message than the one requested', async () => {
    server.on(detailPath(id), detailReply(details.approved));
    await expect(messageApi.getDetail(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
    server.on(sendsPath(id), detailReply(details.approved));
    await expect(messageApi.send(id, 1, 'key-1')).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('exposes a numeric Retry-After on a 429 and nothing otherwise', async () => {
    server.on(sendsPath(id), { status: 429, body: { code: 'RATE_LIMITED' }, retryAfter: 7 });
    const limited = await messageApi.send(id, 1, 'key-1').catch((error: unknown) => error);
    expect(limited).toBeInstanceOf(ApiError);
    expect((limited as ApiError).retryAfterSeconds).toBe(7);
    server.on(sendsPath(id), { status: 429, body: { code: 'RATE_LIMITED' } });
    const bare = await messageApi.send(id, 1, 'key-2').catch((error: unknown) => error);
    expect((bare as ApiError).retryAfterSeconds).toBeUndefined();
    server.on(sendsPath(id), { status: 503, body: { code: 'PROVIDER_UNAVAILABLE' }, retryAfter: 7 });
    const unavailable = await messageApi.send(id, 1, 'key-3').catch((error: unknown) => error);
    expect((unavailable as ApiError).retryAfterSeconds).toBeUndefined();
  });

  it('returns events ordered by sequence number', async () => {
    const fixture = eventsFixtures.transientFailureBackToApproved;
    server.on(eventsPath(id), ok({ ...fixture, messageId: id, events: [...fixture.events].reverse() }));
    const events = await messageApi.getEvents(id);
    expect(events.map((event) => event.sequenceNumber)).toEqual(fixture.events.map((event) => event.sequenceNumber));
  });

  it.each([
    ['an object failureCategory', { failureCategory: { code: 'UNKNOWN' } }],
    ['a string attemptNumber', { attemptNumber: '1' }]
  ])('refuses an event with %s', async (_name, changes) => {
    const fixture = eventsFixtures.rejected;
    server.on(eventsPath(id), ok({ ...fixture, messageId: id, events: [{ ...fixture.events[0], ...changes }] }));
    await expect(messageApi.getEvents(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('refuses an events body with an unknown event type', async () => {
    const fixture = eventsFixtures.rejected;
    server.on(eventsPath(id), ok({ ...fixture, messageId: id, events: [{ ...fixture.events[0], type: 'EDITED' }] }));
    await expect(messageApi.getEvents(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('refuses a history that belongs to another message', async () => {
    server.on(eventsPath(id), ok(eventsFixtures.rejected));
    await expect(messageApi.getEvents(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it.each([
    ['another customer', { customerId: 'someone-else' }],
    ['another contact', { contactId: 'other-contact' }],
    ['another body', { body: 'Otro texto que nadie confirmó.' }],
    ['another action', { action: 'GENERAL_CHECK_IN' }],
    ['another template intent', { templateIntent: 'GENERAL_FOLLOW_UP' }],
    ['another locale', { locale: 'es-CL' }],
    ['another origin', { origin: 'MANUAL' }],
    ['another policy version', { sourcePolicyVersion: 8 }]
  ])('refuses a submit reply for %s', async (_name, changes) => {
    const request = requests.submit.valid.aiDraft;
    server.on(submitPath(pendingApproval.customerId), { status: 201, body: { ...pendingApproval, ...changes }, etag: '"1"' });
    await expect(messageApi.submit(pendingApproval.customerId, { ...request }, 7, 'key-x')).rejects.toBeInstanceOf(
      InvalidMessageResponseError
    );
  });

  it('submits the aiDraft fixture body byte for byte with the policy version and key', async () => {
    const request = requests.submit.valid.aiDraft;
    server.on(submitPath(pendingApproval.customerId), { status: 201, body: pendingApproval, etag: '"1"' });
    await messageApi.submit(pendingApproval.customerId, { ...request }, 7, 'key-1');
    const call = server.last(submitPath(pendingApproval.customerId));
    expect(call.body).toBe(JSON.stringify(request));
    expect(call.headers['If-Match']).toBe('"7"');
    expect(call.headers['Idempotency-Key']).toBe('key-1');
    expect(call.headers['X-CSRF-TOKEN']).toBe('csrf-a');
    expect(call.headers['X-Tenant-Id']).toBe('tenant-a');
  });

  it('sends approval and rejection bodies that match the fixtures', async () => {
    server.on(approvalsPath(id), { status: 201, body: { ...details.approved, messageId: id }, etag: '"2"' });
    await messageApi.decide(
      id,
      { decision: 'APPROVE', templateRevision: requests.approval.valid.approve.templateRevision },
      1,
      'key-a'
    );
    expect(server.last(approvalsPath(id)).body).toBe(JSON.stringify(requests.approval.valid.approve));
    await messageApi.decide(id, { decision: 'REJECT', note: requests.approval.valid.rejectWithNote.note }, 1, 'key-r');
    expect(server.last(approvalsPath(id)).body).toBe(JSON.stringify(requests.approval.valid.rejectWithNote));
  });

  it('sends an empty cancellation as {} and a noted one as the fixture', async () => {
    server.on(cancellationsPath(id), { status: 201, body: { ...details.cancelled, messageId: id }, etag: '"2"' });
    await messageApi.cancel(id, '   ', 1, 'key-c');
    expect(server.last(cancellationsPath(id)).body).toBe(JSON.stringify(requests.cancellation.valid.empty));
    await messageApi.cancel(id, requests.cancellation.valid.withNote.note, 1, 'key-d');
    expect(server.last(cancellationsPath(id)).body).toBe(JSON.stringify(requests.cancellation.valid.withNote));
  });

  it('sends with If-Match and a key but no body and no Content-Type', async () => {
    const approvedId = details.approved.messageId;
    server.on(sendsPath(approvedId), { status: 201, body: { ...details.sent, messageId: approvedId }, etag: '"5"' });
    const read = await messageApi.send(approvedId, 2, 'key-s');
    const call = server.last(sendsPath(approvedId));
    expect(call.body).toBeUndefined();
    expect(call.headers['Content-Type']).toBeUndefined();
    expect(call.headers['If-Match']).toBe('"2"');
    expect(call.headers['Idempotency-Key']).toBe('key-s');
    expect(read.version).toBe(5);
  });
});

// covers: AC-2 (spec 0012)
describe('listCustomerHistory', () => {
  const customerId = pendingApproval.customerId;
  const firstItem = pages.firstPage.items[0];

  it('reads the first page with customerId then limit, no status, and keeps the server order', async () => {
    server.on(historyPath(customerId), ok(pages.firstPage));
    const page = await messageApi.listCustomerHistory(customerId, null);
    expect(server.last(historyPath(customerId)).key).toBe(`GET /api/messages?customerId=${customerId}&limit=20`);
    expect(server.last(historyPath(customerId)).headers['X-Tenant-Id']).toBe('tenant-a');
    expect(page.items.map((item) => item.messageId)).toEqual(pages.firstPage.items.map((item) => item.messageId));
    expect(page.nextCursor).toBe(pages.firstPage.nextCursor);
  });

  it('appends the cursor last and reads the empty last page', async () => {
    const cursor = pages.firstPage.nextCursor as string;
    server.on(historyPath(customerId, cursor), ok(pages.lastPage));
    const page = await messageApi.listCustomerHistory(customerId, cursor);
    expect(server.last(historyPath(customerId, cursor)).key).toBe(
      `GET /api/messages?customerId=${customerId}&limit=20&cursor=${cursor}`
    );
    expect(page).toEqual({ items: [], nextCursor: null });
  });

  it('drops an item of another customer without failing the page', async () => {
    const stranger = { ...firstItem, messageId: 'other-1', customerId: 'someone-else' };
    server.on(historyPath(customerId), ok({ items: [stranger, firstItem], nextCursor: null }));
    const page = await messageApi.listCustomerHistory(customerId, null);
    expect(page.items.map((item) => item.messageId)).toEqual([firstItem.messageId]);
  });

  it('accepts an item without locale, as a backend from before issue 268 sends it', async () => {
    const { locale: _omitted, ...older } = firstItem;
    server.on(historyPath(customerId), ok({ items: [older], nextCursor: null }));
    const page = await messageApi.listCustomerHistory(customerId, null);
    expect(page.items.map((item) => item.messageId)).toEqual([firstItem.messageId]);
    expect(page.items[0].locale).toBeUndefined();
  });

  it('accepts a status the contract does not know', async () => {
    server.on(historyPath(customerId), ok({ items: [{ ...firstItem, status: 'ARCHIVED_LATER' }], nextCursor: null }));
    const page = await messageApi.listCustomerHistory(customerId, null);
    expect(page.items[0].status).toBe('ARCHIVED_LATER');
  });

  it.each([
    ['a missing items array', { nextCursor: null }],
    ['an undefined nextCursor', { items: [] }],
    ['a numeric nextCursor', { items: [], nextCursor: 7 }],
    ['an item without a version', { items: [{ ...firstItem, version: undefined }], nextCursor: null }],
    ['an item with a bad origin', { items: [{ ...firstItem, origin: 'EMAIL' }], nextCursor: null }],
    ['an item with a bad action', { items: [{ ...firstItem, action: 'SOMETHING' }], nextCursor: null }],
    ['an item with a bad templateIntent', { items: [{ ...firstItem, templateIntent: 'NOT_A_REAL_INTENT' }], nextCursor: null }],
    ['an item whose contactRemoved is a string', { items: [{ ...firstItem, contactRemoved: 'no' }], nextCursor: null }],
    ['an item whose outcomeUnknown is a string', { items: [{ ...firstItem, outcomeUnknown: 'yes' }], nextCursor: null }],
    ['an item without a messageId', { items: [{ ...firstItem, messageId: undefined }], nextCursor: null }],
    ['a plain array body', []]
  ])('refuses a page with %s', async (_name, body) => {
    server.on(historyPath(customerId), ok(body));
    await expect(messageApi.listCustomerHistory(customerId, null)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('forwards the abort signal', async () => {
    const controller = new AbortController();
    server.on(historyPath(customerId), pending());
    const read = messageApi.listCustomerHistory(customerId, null, controller.signal);
    controller.abort();
    await expect(read).rejects.toMatchObject({ name: 'AbortError' });
  });
});
