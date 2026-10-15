/**
 * Spike evidence for Issue #180 (no production code): where the frontend must send an explicit AI cancel so that it
 * still carries the authorized context. It exercises the real apiClient/httpClient state transitions that
 * TenantContext.switchWorkspace and SessionContext.logout perform today. The cancel endpoint is a proposal.
 *
 * Ownership boundary (ADR 0026): the in-flight invocation belongs to the useFollowUpAssistant hook, a descendant of
 * TenantProvider/SessionProvider, so the providers cannot read it. A non-React singleton tracker (like apiClient)
 * is the shared coordinator: the hook tracks/untracks, and switchWorkspace/logout await cancelTenant() before they
 * change the tenant or call /logout. The tracker below is a prototype; wiring the real providers is implementation.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { apiClient } from '@/api/apiClient';
import { httpClient } from '@/shared/api/httpClient';

const invocationId = '6f1c2a52-6a3e-4f0c-9d55-0d3f6f6b7a10';

type TrackedInvocation = { tenantId: string; invocationId: string; operation: 'NEXT_BEST_ACTION' | 'MESSAGE_DRAFT' };

function createAiInvocationTracker() {
  const active = new Map<string, TrackedInvocation>();
  return {
    track(invocation: TrackedInvocation): () => void {
      active.set(invocation.invocationId, invocation);
      return () => {
        active.delete(invocation.invocationId);
      };
    },
    /** Awaits the cancels for one tenant, bounded by timeoutMs; failures never block the switch or logout. */
    async cancelTenant(tenantId: string, timeoutMs: number): Promise<void> {
      const cancels = [...active.values()]
        .filter((invocation) => invocation.tenantId === tenantId)
        .map((invocation) =>
          httpClient
            .request<void>(`/api/ai-invocations/${invocation.invocationId}/cancel`, { method: 'POST' })
            .catch(() => undefined)
        );
      let timer: ReturnType<typeof setTimeout> | undefined;
      const timeout = new Promise<void>((resolve) => {
        timer = setTimeout(resolve, timeoutMs);
      });
      await Promise.race([Promise.all(cancels).then(() => undefined), timeout]);
      clearTimeout(timer);
    }
  };
}

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

  describe('with a shared tracker as the coordinator', () => {
    it('switchWorkspace awaits the cancel of the old tenant before switching', async () => {
      const tracker = createAiInvocationTracker();
      const tenantAtCancel: (string | null)[] = [];
      const calls = captureFetch();
      vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
        tenantAtCancel.push(apiClient.getCurrentTenantId());
        calls.push({ url: String(input), headers: (init?.headers ?? {}) as Record<string, string> });
        return new Response(null, { status: 204 });
      });
      // The hook (descendant) tracks its request; the provider (ancestor) only knows the current tenant.
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });
      tracker.track({ tenantId: 'tenant-c', invocationId: 'other-tenant-invocation', operation: 'MESSAGE_DRAFT' });

      // Prototype of an async switchWorkspace.
      await tracker.cancelTenant('tenant-a', 1_000);
      apiClient.setCurrentTenantId('tenant-b');

      expect(calls.map((call) => call.url)).toEqual([`/api/ai-invocations/${invocationId}/cancel`]);
      expect(calls[0].headers['X-Tenant-Id']).toBe('tenant-a');
      expect(calls[0].headers['X-CSRF-TOKEN']).toBe('csrf-a');
      expect(tenantAtCancel).toEqual(['tenant-a']);
    });

    it('logout awaits the cancel before POST /logout clears the session', async () => {
      const tracker = createAiInvocationTracker();
      const calls = captureFetch();
      const untrack = tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'MESSAGE_DRAFT' });

      // Prototype of SessionContext.logout with the cancel first.
      await tracker.cancelTenant('tenant-a', 1_000);
      await apiClient.post<void>('/logout');
      untrack();

      expect(calls.map((call) => call.url)).toEqual([`/api/ai-invocations/${invocationId}/cancel`, '/logout']);
      expect(calls[0].headers['X-CSRF-TOKEN']).toBe('csrf-a');
    });

    it('a cancel that never answers delays the switch only up to the bound', async () => {
      vi.useFakeTimers();
      try {
        const tracker = createAiInvocationTracker();
        vi.spyOn(globalThis, 'fetch').mockImplementation(
          (_input, init) =>
            new Promise<Response>((_resolve, reject) => {
              init?.signal?.addEventListener('abort', () =>
                reject(new DOMException('The operation was aborted.', 'AbortError'))
              );
            })
        );
        tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });

        let settled = false;
        const pending = tracker.cancelTenant('tenant-a', 1_000).then(() => {
          settled = true;
        });
        await vi.advanceTimersByTimeAsync(999);
        expect(settled).toBe(false);
        await vi.advanceTimersByTimeAsync(1);
        await pending;
        expect(settled).toBe(true);

        apiClient.setCurrentTenantId('tenant-b');
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
