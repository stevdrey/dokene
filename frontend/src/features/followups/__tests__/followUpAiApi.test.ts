import { describe, it, expect, beforeEach, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { followUpApi, InvalidAiResponseError } from '@/features/followups/api/followUpApi';

const evaluation = {
  customerId: 'c-1',
  eligible: true,
  status: 'DUE',
  reasons: ['DUE_TODAY'],
  evaluatedAt: '2026-09-15T12:00:00Z',
  tenantDate: '2026-09-15',
  tenantTimeZone: 'America/Santiago',
  nextFollowUpDate: '2026-09-15',
  timingSource: 'LAST_PURCHASE',
  effectiveCadenceDays: 60,
  lastPurchaseAt: '2026-07-17T10:00:00Z'
};

const recommendationBody = {
  status: 'AVAILABLE',
  customerId: 'c-1',
  evaluation,
  recommendation: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    rationale: 'Compró café hace 60 días.',
    confidence: 0.82,
    draftVariables: [{ key: 'producto', value: 'Café molido' }]
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

const draftBody = {
  status: 'AVAILABLE',
  customerId: 'c-1',
  evaluation,
  draft: {
    action: 'REPEAT_PURCHASE_FOLLOW_UP',
    templateIntent: 'REPEAT_PURCHASE',
    body: 'Hola Valentina, ¿cómo te fue con el café?',
    draftVariables: [],
    locale: 'es-419',
    evidence: ['Compra reciente: Café molido'],
    warnings: [],
    rationale: 'Seguimiento de recompra.',
    confidence: 0.8
  },
  refusal: null,
  refusalReason: null,
  rejectionReason: null,
  unavailableReason: null,
  retryable: false
};

function mockFetchJson(body: unknown, etag = '"3"', status = 200) {
  let url = '';
  let init: RequestInit | undefined;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, requestInit) => {
    url = String(input);
    init = requestInit;
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json', ETag: etag }
    });
  });
  return {
    url: () => url,
    init: () => init,
    headers: () => (init?.headers ?? {}) as Record<string, string>
  };
}

describe('followUpApi AI assistance', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId('tenant-123');
    httpClient.setCsrfToken('csrf-token-abc');
  });

  it('requests a recommendation with tenant, CSRF and If-Match headers and parses the ETag version', async () => {
    const captured = mockFetchJson(recommendationBody, '"5"');

    const result = await followUpApi.requestRecommendation('c-1', 4);

    expect(captured.url()).toBe('/api/customers/c-1/recommendation');
    expect(captured.init()?.method).toBe('POST');
    expect(captured.headers()['X-Tenant-Id']).toBe('tenant-123');
    expect(captured.headers()['X-CSRF-TOKEN']).toBe('csrf-token-abc');
    expect(captured.headers()['If-Match']).toBe('"4"');
    expect(result.version).toBe(5);
    expect(result.data.status).toBe('AVAILABLE');
    expect(result.data.recommendation?.rationale).toBe('Compró café hace 60 días.');
  });

  it('requests a draft passing the recommended action and template intent', async () => {
    const captured = mockFetchJson(draftBody, '"5"');

    const result = await followUpApi.requestDraft(
      'c-1',
      { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' },
      5
    );

    expect(captured.url()).toBe('/api/customers/c-1/draft');
    expect(captured.init()?.method).toBe('POST');
    expect(captured.headers()['If-Match']).toBe('"5"');
    expect(JSON.parse(String(captured.init()?.body))).toEqual({
      action: 'REPEAT_PURCHASE_FOLLOW_UP',
      templateIntent: 'REPEAT_PURCHASE'
    });
    expect(result.data.draft?.body).toBe('Hola Valentina, ¿cómo te fue con el café?');
    expect(result.version).toBe(5);
  });

  it('accepts AI_UNAVAILABLE results carrying a closed unavailable reason', async () => {
    mockFetchJson({
      ...recommendationBody,
      status: 'AI_UNAVAILABLE',
      recommendation: null,
      unavailableReason: 'TIMEOUT',
      retryable: true
    });

    const { data } = await followUpApi.requestRecommendation('c-1', 3);

    expect(data.status).toBe('AI_UNAVAILABLE');
    expect(data.unavailableReason).toBe('TIMEOUT');
    expect(data.retryable).toBe(true);
  });

  it('rejects a recommendation response with an unknown status', async () => {
    mockFetchJson({ ...recommendationBody, status: 'SOMETHING_NEW' });

    await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
      InvalidAiResponseError
    );
  });

  it('rejects an AVAILABLE recommendation that carries no recommendation payload', async () => {
    mockFetchJson({ ...recommendationBody, recommendation: null });

    await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
      InvalidAiResponseError
    );
  });

  it('rejects an AVAILABLE draft whose body is not a string', async () => {
    mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, body: 42 } });

    await expect(
      followUpApi.requestDraft('c-1', { action: 'GENERAL_CHECK_IN', templateIntent: 'GENERAL_FOLLOW_UP' }, 3)
    ).rejects.toBeInstanceOf(InvalidAiResponseError);
  });

  it('rejects a non-object response body', async () => {
    mockFetchJson('nope');

    await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
      InvalidAiResponseError
    );
  });
  describe('customer correlation', () => {
    it('rejects a recommendation that belongs to another customer', async () => {
      mockFetchJson({ ...recommendationBody, customerId: 'c-OTHER' });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });

    it('rejects a draft that belongs to another customer', async () => {
      mockFetchJson({ ...draftBody, customerId: 'c-OTHER' });

      await expect(
        followUpApi.requestDraft('c-1', { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' }, 3)
      ).rejects.toBeInstanceOf(InvalidAiResponseError);
    });

    it('rejects a response whose evaluation belongs to another customer', async () => {
      mockFetchJson({ ...recommendationBody, evaluation: { ...evaluation, customerId: 'c-OTHER' } });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });

    it('rejects content-bearing results that carry no customerId', async () => {
      mockFetchJson({ ...recommendationBody, customerId: null });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });

    it('compares customer ids case-insensitively', async () => {
      mockFetchJson({ ...recommendationBody, customerId: 'C-1', evaluation: { ...evaluation, customerId: 'C-1' } });

      const { data } = await followUpApi.requestRecommendation('c-1', 3);

      expect(data.status).toBe('AVAILABLE');
    });

    it.each(['INELIGIBLE', 'STALE_STATE', 'AI_UNAVAILABLE'] as const)(
      'accepts a %s result without customerId or evaluation',
      async (status) => {
        mockFetchJson({
          ...recommendationBody,
          status,
          customerId: null,
          evaluation: null,
          recommendation: null,
          unavailableReason: status === 'AI_UNAVAILABLE' ? 'NOT_AVAILABLE' : null
        });

        const { data } = await followUpApi.requestRecommendation('c-1', 3);

        expect(data.status).toBe(status);
      }
    );

    it('still rejects a non-content result that names another customer', async () => {
      mockFetchJson({ ...recommendationBody, status: 'INELIGIBLE', recommendation: null, customerId: 'c-OTHER' });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });
  });

  describe('closed vocabularies and draft bounds', () => {
    it('rejects a recommendation with an unknown action', async () => {
      mockFetchJson({
        ...recommendationBody,
        recommendation: { ...recommendationBody.recommendation, action: 'SEND_MESSAGE_NOW' }
      });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });

    it('rejects a recommendation with an unknown template intent', async () => {
      mockFetchJson({
        ...recommendationBody,
        recommendation: { ...recommendationBody.recommendation, templateIntent: 'WHATSAPP_HSM_42' }
      });

      await expect(followUpApi.requestRecommendation('c-1', 3)).rejects.toBeInstanceOf(
        InvalidAiResponseError
      );
    });

    it('rejects a draft with an unknown action or template intent', async () => {
      mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, action: 'NOPE' } });
      await expect(
        followUpApi.requestDraft('c-1', { action: 'GENERAL_CHECK_IN', templateIntent: 'GENERAL_FOLLOW_UP' }, 3)
      ).rejects.toBeInstanceOf(InvalidAiResponseError);

      vi.restoreAllMocks();
      mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, templateIntent: 'NOPE' } });
      await expect(
        followUpApi.requestDraft('c-1', { action: 'GENERAL_CHECK_IN', templateIntent: 'GENERAL_FOLLOW_UP' }, 3)
      ).rejects.toBeInstanceOf(InvalidAiResponseError);
    });

    it.each([
      ['empty', ''],
      ['blank', '   \n  '],
      ['over 1000 code points', 'a'.repeat(1001)]
    ])('rejects a draft body that is %s', async (_label, body) => {
      mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, body } });

      await expect(
        followUpApi.requestDraft('c-1', { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' }, 3)
      ).rejects.toBeInstanceOf(InvalidAiResponseError);
    });

    it('counts code points, not UTF-16 units, for the 1000 character limit', async () => {
      const body = '😀'.repeat(1000);
      mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, body } });

      const { data } = await followUpApi.requestDraft(
        'c-1',
        { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' },
        3
      );

      expect(data.draft?.body).toBe(body);
    });

    it('accepts a draft body of exactly 1000 code points', async () => {
      const body = 'a'.repeat(1000);
      mockFetchJson({ ...draftBody, draft: { ...draftBody.draft, body } });

      const { data } = await followUpApi.requestDraft(
        'c-1',
        { action: 'REPEAT_PURCHASE_FOLLOW_UP', templateIntent: 'REPEAT_PURCHASE' },
        3
      );

      expect(data.draft?.body).toHaveLength(1000);
    });
  });
});
