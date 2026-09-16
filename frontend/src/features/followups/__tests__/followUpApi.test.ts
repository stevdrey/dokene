import { describe, it, expect, beforeEach, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { followUpApi } from '@/features/followups/api/followUpApi';

describe('followUpApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId('tenant-123');
    httpClient.setCsrfToken('csrf-token-abc');
  });

  it('fetches follow-up queue with query parameters', async () => {
    let capturedUrl = '';
    let capturedHeaders: Record<string, string> = {};

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedUrl = String(input);
      capturedHeaders = (init?.headers as Record<string, string>) || {};

      return new Response(
        JSON.stringify({
          items: [
            {
              customerId: 'c-1',
              displayName: 'Valentina Morales',
              primaryPhone: '+56984521190',
              status: 'DUE',
              reasons: ['DUE_TODAY'],
              dueDate: '2026-09-15',
              timingSource: 'LAST_PURCHASE',
              policyVersion: 2,
              effectiveCadenceDays: 60,
              lastPurchaseAt: '2026-07-17T10:00:00Z',
              lastManualFollowUpDate: null,
              lastDismissedDate: null,
              evaluatedAt: '2026-09-15T12:00:00Z'
            }
          ],
          nextCursor: 'cursor-token-xyz'
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        }
      );
    });

    const result = await followUpApi.getFollowUpQueue({ status: 'DUE', cursor: 'prev-cursor', limit: 25 });

    expect(capturedUrl).toContain('/api/follow-up-queue?status=DUE&cursor=prev-cursor&limit=25');
    expect(capturedHeaders['X-Tenant-Id']).toBe('tenant-123');
    expect(result.items).toHaveLength(1);
    expect(result.items[0].displayName).toBe('Valentina Morales');
    expect(result.nextCursor).toBe('cursor-token-xyz');
  });

  it('gets customer follow-up policy and parses numeric version from ETag', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          customerId: 'c-1',
          cadenceDays: 45,
          explicitNextDate: null,
          snoozedUntil: null,
          lastManualFollowUpDate: '2026-08-01',
          lastDismissedDate: null
        }),
        {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"7"'
          }
        }
      );
    });

    const { policy, version } = await followUpApi.getCustomerFollowUpPolicy('c-1');
    expect(policy.cadenceDays).toBe(45);
    expect(policy.lastManualFollowUpDate).toBe('2026-08-01');
    expect(version).toBe(7);
  });

  it('gets customer follow-up eligibility evaluation', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          customerId: 'c-1',
          eligible: true,
          status: 'DUE',
          reasons: ['DUE_TODAY'],
          evaluatedAt: '2026-09-15T12:00:00Z',
          tenantDate: '2026-09-15',
          tenantTimeZone: 'America/Santiago',
          nextFollowUpDate: '2026-09-15',
          timingSource: 'LAST_PURCHASE',
          effectiveCadenceDays: 30,
          lastPurchaseAt: '2026-08-16T12:00:00Z'
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        }
      );
    });

    const evalResult = await followUpApi.getFollowUpEligibility('c-1');
    expect(evalResult.eligible).toBe(true);
    expect(evalResult.status).toBe('DUE');
    expect(evalResult.tenantTimeZone).toBe('America/Santiago');
  });

  it('snoozes follow-up with If-Match header and date body', async () => {
    let capturedHeaders: Record<string, string> = {};
    let capturedBody = '';

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      capturedBody = (init?.body as string) || '';

      return new Response(
        JSON.stringify({
          customerId: 'c-1',
          cadenceDays: null,
          explicitNextDate: null,
          snoozedUntil: '2026-09-20',
          lastManualFollowUpDate: null,
          lastDismissedDate: null
        }),
        {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"8"'
          }
        }
      );
    });

    const { policy, version } = await followUpApi.snoozeFollowUp('c-1', '2026-09-20', 7);
    expect(capturedHeaders['If-Match']).toBe('"7"');
    expect(JSON.parse(capturedBody).until).toBe('2026-09-20');
    expect(policy.snoozedUntil).toBe('2026-09-20');
    expect(version).toBe(8);
  });

  it('dismisses follow-up with If-Match, Idempotency-Key, and optional notes', async () => {
    let capturedHeaders: Record<string, string> = {};
    let capturedBody = '';

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      capturedBody = (init?.body as string) || '';

      return new Response(
        JSON.stringify({
          id: 'd-1',
          customerId: 'c-1',
          dismissedOn: '2026-09-15',
          policyVersion: 9,
          notes: 'Cliente de vacaciones'
        }),
        {
          status: 201,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"9"'
          }
        }
      );
    });

    const { dismissal, version } = await followUpApi.dismissFollowUp(
      'c-1',
      8,
      'idem-dismiss-123',
      'Cliente de vacaciones'
    );

    expect(capturedHeaders['If-Match']).toBe('"8"');
    expect(capturedHeaders['Idempotency-Key']).toBe('idem-dismiss-123');
    expect(JSON.parse(capturedBody).notes).toBe('Cliente de vacaciones');
    expect(dismissal.dismissedOn).toBe('2026-09-15');
    expect(version).toBe(9);
  });

  it('records manual follow-up completion with If-Match, Idempotency-Key, and notes', async () => {
    let capturedHeaders: Record<string, string> = {};
    let capturedBody = '';

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      capturedBody = (init?.body as string) || '';

      return new Response(
        JSON.stringify({
          id: 'm-1',
          customerId: 'c-1',
          completedOn: '2026-09-15',
          policyVersion: 10,
          notes: 'Conversamos por WhatsApp; pedirá la próxima semana'
        }),
        {
          status: 201,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"10"'
          }
        }
      );
    });

    const { completion, version } = await followUpApi.recordManualFollowUp(
      'c-1',
      9,
      'idem-manual-456',
      'Conversamos por WhatsApp; pedirá la próxima semana'
    );

    expect(capturedHeaders['If-Match']).toBe('"9"');
    expect(capturedHeaders['Idempotency-Key']).toBe('idem-manual-456');
    expect(JSON.parse(capturedBody).notes).toBe('Conversamos por WhatsApp; pedirá la próxima semana');
    expect(completion.completedOn).toBe('2026-09-15');
    expect(version).toBe(10);
  });

  it('gets tenant follow-up policy', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          cadenceDays: 30,
          timeZone: 'America/Santiago'
        }),
        {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"1"'
          }
        }
      );
    });

    const { policy, version } = await followUpApi.getTenantFollowUpPolicy();
    expect(policy.cadenceDays).toBe(30);
    expect(policy.timeZone).toBe('America/Santiago');
    expect(version).toBe(1);
  });
});
