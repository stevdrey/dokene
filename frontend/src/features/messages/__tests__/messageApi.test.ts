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
  ok,
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
    ['locale', null]
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
    server.on(eventsPath(id), ok({ ...fixture, events: [...fixture.events].reverse() }));
    const events = await messageApi.getEvents(id);
    expect(events.map((event) => event.sequenceNumber)).toEqual(fixture.events.map((event) => event.sequenceNumber));
  });

  it.each([
    ['an object failureCategory', { failureCategory: { code: 'UNKNOWN' } }],
    ['a string attemptNumber', { attemptNumber: '1' }]
  ])('refuses an event with %s', async (_name, changes) => {
    const fixture = eventsFixtures.rejected;
    server.on(eventsPath(id), ok({ ...fixture, events: [{ ...fixture.events[0], ...changes }] }));
    await expect(messageApi.getEvents(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
  });

  it('refuses an events body with an unknown event type', async () => {
    const fixture = eventsFixtures.rejected;
    server.on(eventsPath(id), ok({ ...fixture, events: [{ ...fixture.events[0], type: 'EDITED' }] }));
    await expect(messageApi.getEvents(id)).rejects.toBeInstanceOf(InvalidMessageResponseError);
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
    await messageApi.decide(id, 'APPROVE', undefined, 1, 'key-a');
    expect(server.last(approvalsPath(id)).body).toBe(JSON.stringify(requests.approval.valid.approve));
    await messageApi.decide(id, 'REJECT', requests.approval.valid.rejectWithNote.note, 1, 'key-r');
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
