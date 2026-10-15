/**
 * Spike evidence for Issue #180 (no production code): where the frontend must send an explicit AI cancel so that it
 * still carries the authorized context. It exercises the real apiClient/httpClient state transitions that
 * TenantContext.switchWorkspace and SessionContext.logout perform today. The cancel endpoint is a proposal.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { apiClient } from '@/api/apiClient';
import { httpClient } from '@/shared/api/httpClient';

const invocationId = '6f1c2a52-6a3e-4f0c-9d55-0d3f6f6b7a10';

function sendCancel(signal?: AbortSignal) {
  return httpClient.request<void>(`/api/ai-invocations/${invocationId}/cancel`, { method: 'POST', signal });
}

function captureFetch() {
  const calls: { url: string; headers: Record<string, string>; signal?: AbortSignal | null }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    calls.push({
      url: String(input),
      headers: (init?.headers ?? {}) as Record<string, string>,
      signal: init?.signal
    });
    return new Response(null, { status: 204 });
  });
  return calls;
}

describe('AI cancellation ordering (Issue #180 spike)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId(null);
    httpClient.setCsrfToken(null);
    // TenantContext and SessionContext drive the shared apiClient directly.
    apiClient.setCsrfToken('csrf-a');
    apiClient.setCurrentTenantId('tenant-a');
  });

  it('a cancel sent before the workspace switch carries the original tenant and CSRF token', async () => {
    const calls = captureFetch();

    await sendCancel();
    apiClient.setCurrentTenantId('tenant-b');

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(`/api/ai-invocations/${invocationId}/cancel`);
    expect(calls[0].headers['X-Tenant-Id']).toBe('tenant-a');
    expect(calls[0].headers['X-CSRF-TOKEN']).toBe('csrf-a');
  });

  it('a cancel sent after the workspace switch targets the new tenant and would silently miss', async () => {
    const calls = captureFetch();

    apiClient.setCurrentTenantId('tenant-b');
    await sendCancel();

    expect(calls[0].headers['X-Tenant-Id']).toBe('tenant-b');
  });

  it('a cancel sent after logout has neither tenant nor CSRF token', async () => {
    const calls = captureFetch();

    // Same state transitions as SessionContext.logout after POST /logout succeeds.
    apiClient.setCsrfToken(null);
    apiClient.setCurrentTenantId(null);
    apiClient.invalidateSession();
    await sendCancel();

    expect(calls[0].headers['X-Tenant-Id']).toBeUndefined();
    expect(calls[0].headers['X-CSRF-TOKEN']).toBeUndefined();
  });

  it('a fire-and-forget cancel is itself aborted by the switch, so the switch must await it', async () => {
    let seenSignal: AbortSignal | null | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          seenSignal = init?.signal;
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('The operation was aborted.', 'AbortError'))
          );
        })
    );

    const pending = sendCancel();
    apiClient.setCurrentTenantId('tenant-b');

    await expect(pending).rejects.toThrow();
    expect(seenSignal?.aborted).toBe(true);
  });
});
