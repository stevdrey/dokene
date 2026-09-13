import { describe, it, expect, beforeEach, vi } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { customerApi } from '@/features/customers/api/customerApi';

describe('customerApi and httpClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId('tenant-123');
    httpClient.setCsrfToken('csrf-token-abc');
  });

  it('sends X-Tenant-Id and X-CSRF-TOKEN on write operations', async () => {
    let capturedHeaders: Record<string, string> = {};
    let capturedMethod = '';
    let capturedBody = '';

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedMethod = init?.method || 'GET';
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      capturedBody = (init?.body as string) || '';

      return new Response(
        JSON.stringify({
          id: 'cust-1',
          displayName: 'Valentina Morales',
          notes: null,
          phones: [{ id: 'p-1', e164: '+56984521190', primary: true }],
          status: 'ACTIVE',
          version: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          archivedAt: null
        }),
        {
          status: 201,
          headers: {
            'Content-Type': 'application/json',
            'ETag': '"1"'
          }
        }
      );
    });

    const result = await customerApi.createCustomer({
      displayName: 'Valentina Morales',
      notes: null,
      phones: [{ number: '984521190', region: 'CL', primary: true }]
    });

    expect(capturedMethod).toBe('POST');
    expect(capturedHeaders['X-Tenant-Id']).toBe('tenant-123');
    expect(capturedHeaders['X-CSRF-TOKEN']).toBe('csrf-token-abc');
    expect(JSON.parse(capturedBody).displayName).toBe('Valentina Morales');
    expect(result.id).toBe('cust-1');
  });

  it('sends If-Match on update and delete operations', async () => {
    let capturedHeaders: Record<string, string> = {};

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      return new Response(null, { status: 204 });
    });

    await customerApi.archiveCustomer('cust-1', 5);

    expect(capturedHeaders['If-Match']).toBe('"5"');
    expect(capturedHeaders['X-Tenant-Id']).toBe('tenant-123');
  });

  it('sends Idempotency-Key on recordPurchase', async () => {
    let capturedHeaders: Record<string, string> = {};

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {};
      return new Response(
        JSON.stringify({
          id: 'purch-1',
          customerId: 'cust-1',
          purchasedAt: '2025-01-14T12:00:00Z',
          description: 'Kit Harinas',
          status: 'VALID',
          version: 0,
          createdAt: '2025-01-14T12:00:00Z',
          updatedAt: '2025-01-14T12:00:00Z',
          voidedAt: null
        }),
        {
          status: 201,
          headers: { 'Content-Type': 'application/json', 'ETag': '"0"' }
        }
      );
    });

    await customerApi.recordPurchase('cust-1', 'idem-key-99', {
      purchasedAt: '2025-01-14T12:00:00Z',
      description: 'Kit Harinas'
    });

    expect(capturedHeaders['Idempotency-Key']).toBe('idem-key-99');
    expect(capturedHeaders['X-Tenant-Id']).toBe('tenant-123');
  });
});
