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

  it('returns null on getLastPurchase when 204 No Content is returned', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(null, { status: 204 })
    );

    const result = await customerApi.getLastPurchase('cust-1');
    expect(result).toBeNull();
  });

  it('throws ApiError on getLastPurchase when 403 Forbidden is returned', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ message: 'Forbidden' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(customerApi.getLastPurchase('cust-1')).rejects.toThrow('Forbidden');
  });

  it('throws ApiError on getLastPurchase when 500 Internal Server Error is returned', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ message: 'Internal Server Error' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(customerApi.getLastPurchase('cust-1')).rejects.toThrow('Internal Server Error');
  });

  it('propagates network failure on getLastPurchase', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('Network error'));

    await expect(customerApi.getLastPurchase('cust-1')).rejects.toThrow('Network error');
  });

  it('provides descriptive conflict message for bodyless 409 responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(null, { status: 409 })
    );

    await expect(
      customerApi.createCustomer({
        displayName: 'Conflicto',
        notes: null,
        phones: [{ number: '984521190', region: 'CL', primary: true }]
      })
    ).rejects.toThrow('Conflicto: el registro o número de contacto ya existe o está en conflicto.');
  });

  it('retains JSON payload message when 409 response includes body', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ message: 'El teléfono ya está asignado a otro cliente' }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(
      customerApi.createCustomer({
        displayName: 'Conflicto',
        notes: null,
        phones: [{ number: '984521190', region: 'CL', primary: true }]
      })
    ).rejects.toThrow('El teléfono ya está asignado a otro cliente');
  });

  it('provides descriptive concurrency message for bodyless 412 responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(null, { status: 412 })
    );

    await expect(customerApi.archiveCustomer('cust-1', 1)).rejects.toThrow(
      'Conflicto de concurrencia: los datos fueron modificados por otro usuario. Por favor recarga.'
    );
  });

  it('passes AbortSignal to fetch in listCustomers', async () => {
    const controller = new AbortController();
    let capturedSignal: AbortSignal | undefined;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedSignal = init?.signal ?? undefined;
      return new Response(JSON.stringify({ customers: [], nextCursor: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    });

    await customerApi.listCustomers({ name: 'Maria' }, controller.signal);

    expect(capturedSignal).toBeDefined();
  });
});

