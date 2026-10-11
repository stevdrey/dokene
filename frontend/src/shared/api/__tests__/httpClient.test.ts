// ABOUTME: Unit tests for the shared HTTP client's problem body reading.
// ABOUTME: Covers ApiError.code, Retry-After seconds and the unchanged legacy error message path.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { httpClient, ApiError, parseRetryAfterSeconds } from '@/shared/api/httpClient';
import { parseStrongNumericEtag } from '@/shared/api/etag';

function mockFetch(body: unknown, status: number, headers: Record<string, string> = {}) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/problem+json', ...headers }
    })
  );
}

async function expectApiError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (err) {
    if (err instanceof ApiError) return err;
    throw err;
  }
  throw new Error('expected an ApiError');
}

describe('httpClient problem body reading', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sets ApiError.code from a problem+json body and keeps the friendly message', async () => {
    mockFetch({ type: 'about:blank', title: 'Conflict', status: 409, code: 'STALE_VERSION' }, 409);
    const err = await expectApiError(httpClient.request('/api/messages/1/approvals', { method: 'POST', body: {} }));
    expect(err.status).toBe(409);
    expect(err.code).toBe('STALE_VERSION');
    expect(err.message).toBe('Conflicto: el registro o número de contacto ya existe o está en conflicto.');
    expect(err.retryAfterSeconds).toBeUndefined();
  });

  it('leaves code undefined for a legacy JSON error body and uses its message', async () => {
    mockFetch({ status: 400, message: 'Nombre requerido', field: 'displayName' }, 400, {
      'Content-Type': 'application/json'
    });
    const err = await expectApiError(httpClient.request('/api/customers', { method: 'POST', body: {} }));
    expect(err.code).toBeUndefined();
    expect(err.message).toBe('Nombre requerido');
    expect(err.payload?.field).toBe('displayName');
  });

  it('leaves code undefined when the body is empty', async () => {
    mockFetch(undefined, 403);
    const err = await expectApiError(httpClient.request('/api/messages/1/sends', { method: 'POST' }));
    expect(err.status).toBe(403);
    expect(err.code).toBeUndefined();
  });

  it('reads a numeric Retry-After on 429 into retryAfterSeconds', async () => {
    mockFetch({ status: 429, code: 'RATE_LIMITED' }, 429, { 'Retry-After': '7' });
    const err = await expectApiError(httpClient.request('/api/messages/1/sends', { method: 'POST' }));
    expect(err.code).toBe('RATE_LIMITED');
    expect(err.retryAfterSeconds).toBe(7);
  });

  it('leaves retryAfterSeconds undefined on a 429 without a numeric header', async () => {
    mockFetch({ status: 429, code: 'RATE_LIMITED' }, 429, { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' });
    const err = await expectApiError(httpClient.request('/api/messages/1/sends', { method: 'POST' }));
    expect(err.retryAfterSeconds).toBeUndefined();
  });

  it('ignores Retry-After on statuses other than 429', async () => {
    mockFetch({ status: 503, code: 'PROVIDER_UNAVAILABLE' }, 503, { 'Retry-After': '30' });
    const err = await expectApiError(httpClient.request('/api/messages/1/sends', { method: 'POST' }));
    expect(err.code).toBe('PROVIDER_UNAVAILABLE');
    expect(err.retryAfterSeconds).toBeUndefined();
  });

  it('keeps the success shape {data, etag}', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', ETag: '"3"' }
      })
    );
    const result = await httpClient.request<{ ok: boolean }>('/api/messages/1');
    expect(result).toEqual({ data: { ok: true }, etag: '"3"' });
  });
});

describe('parseRetryAfterSeconds', () => {
  it.each([
    [null, undefined],
    ['', undefined],
    ['7', 7],
    [' 12 ', 12],
    ['0', 0],
    ['-1', undefined],
    ['1.5', undefined],
    ['Wed, 21 Oct 2026 07:28:00 GMT', undefined]
  ])('maps %j to %j', (header, expected) => {
    expect(parseRetryAfterSeconds(header)).toBe(expected);
  });
});

describe('parseStrongNumericEtag', () => {
  it.each([
    ['"0"', 0],
    ['"42"', 42],
    [null, null],
    ['42', null],
    ['W/"42"', null],
    ['"042"', null],
    ['"abc"', null],
    ['"99999999999999999999"', null]
  ])('maps %j to %j', (etag, expected) => {
    expect(parseStrongNumericEtag(etag)).toBe(expected);
  });
});
