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
  // Two owners per entry: the request (track/untrack) and a pending supersede cancel. It is deleted only when
  // neither still needs it, so a switch or logout meanwhile can resend the cancel.
  const active = new Map<string, TrackedInvocation & { requestDone: boolean; cancelPending: boolean }>();
  // Tenants whose cancels are being awaited by a transition; invocations tracked meanwhile join the drain.
  const drains = new Map<string, Promise<void>[]>();
  const release = (invocationId: string) => {
    const entry = active.get(invocationId);
    if (entry && entry.requestDone && !entry.cancelPending) active.delete(invocationId);
  };
  const sendCancel = (invocation: TrackedInvocation) =>
    httpClient
      .request<void>(`/api/ai-invocations/${invocation.invocationId}/cancel`, { method: 'POST' })
      .then(() => undefined)
      .catch(() => undefined);
  return {
    track(invocation: TrackedInvocation): () => void {
      active.set(invocation.invocationId, { ...invocation, requestDone: false, cancelPending: false });
      drains.get(invocation.tenantId)?.push(sendCancel(invocation));
      return () => {
        const entry = active.get(invocation.invocationId);
        if (entry) entry.requestDone = true;
        release(invocation.invocationId);
      };
    },
    /**
     * Local supersession (a new recommendation or draft replaces an older one): cancel on the server first, and
     * keep tracking until that cancel settles, even if the aborted request untracks first.
     */
    supersede(invocationId: string): Promise<void> {
      const entry = active.get(invocationId);
      if (!entry) return Promise.resolve();
      entry.cancelPending = true;
      return sendCancel(entry).finally(() => {
        entry.cancelPending = false;
        release(invocationId);
      });
    },
    /**
     * Awaits the cancels for one tenant, bounded by timeoutMs; failures never block the switch or logout. While it
     * drains, newly tracked invocations of that tenant are cancelled too, until no new cancel appears.
     */
    async cancelTenant(tenantId: string, timeoutMs: number): Promise<void> {
      const pending = [...active.values()]
        .filter((invocation) => invocation.tenantId === tenantId)
        .map(sendCancel);
      drains.set(tenantId, pending);
      let timer: ReturnType<typeof setTimeout> | undefined;
      const timeout = new Promise<void>((resolve) => {
        timer = setTimeout(resolve, timeoutMs);
      });
      const drained = (async () => {
        let settled = -1;
        while (settled !== pending.length) {
          settled = pending.length;
          await Promise.all(pending.slice());
        }
      })();
      try {
        await Promise.race([drained, timeout]);
      } finally {
        clearTimeout(timer);
        drains.delete(tenantId);
      }
    }
  };
}

type AiInvocationTracker = ReturnType<typeof createAiInvocationTracker>;

/**
 * Prototype of the provider-side transitions. Every user-initiated tenant change (switch, provisioning, logout) goes
 * through it. A generation counter serializes them: a tenant change applies only if no later transition started
 * while its cancels were pending, and nothing restores a tenant after a successful logout.
 */
function createWorkspaceTransitions(tracker: AiInvocationTracker) {
  let generation = 0;
  let loggedOut = false;
  const apply = async (mine: number, next: string): Promise<boolean> => {
    const current = apiClient.getCurrentTenantId();
    if (current) await tracker.cancelTenant(current, 1_000);
    if (mine !== generation || loggedOut) return false;
    apiClient.setCurrentTenantId(next);
    return true;
  };
  return {
    switchTo(next: string): Promise<boolean> {
      return apply(++generation, next);
    },
    /**
     * Like TenantContext.provisionWorkspace: create first, then cancel and apply. A provisioning superseded while
     * create() was pending stops before cancelling anything, so it can never cancel another tenant's invocations.
     */
    async provision(create: () => Promise<{ tenantId: string }>): Promise<boolean> {
      const mine = ++generation;
      const created = await create();
      if (mine !== generation || loggedOut) return false;
      return apply(mine, created.tenantId);
    },
    async logout(): Promise<void> {
      // Supersede pending transitions now, but become terminal only once /logout succeeded: a failed logout leaves
      // the session (and workspace switching) usable, as SessionContext.logout does today.
      generation++;
      const current = apiClient.getCurrentTenantId();
      if (current) await tracker.cancelTenant(current, 1_000);
      await apiClient.post<void>('/logout');
      loggedOut = true;
      apiClient.setCsrfToken(null);
      apiClient.setCurrentTenantId(null);
      apiClient.invalidateSession();
    }
  };
}

/** fetch mock whose AI cancels stay pending until released, in any order; everything else answers 204. */
function controllableFetch(statusFor: (url: string) => number = () => 204) {
  const calls: { url: string; tenant?: string; release: () => void }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url = String(input);
    const tenant = ((init?.headers ?? {}) as Record<string, string>)['X-Tenant-Id'];
    if (!url.includes('/api/ai-invocations/')) {
      calls.push({ url, tenant, release: () => undefined });
      return Promise.resolve(new Response(null, { status: statusFor(url) }));
    }
    return new Promise<Response>((resolve, reject) => {
      calls.push({ url, tenant, release: () => resolve(new Response(null, { status: 204 })) });
      init?.signal?.addEventListener('abort', () =>
        reject(new DOMException('The operation was aborted.', 'AbortError'))
      );
    });
  });
  return calls;
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

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

  describe('serialized workspace transitions', () => {
    it('the latest selection wins even when the cancels resolve out of order', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });

      const toB = transitions.switchTo('tenant-b');
      const toC = transitions.switchTo('tenant-c');
      await flush();
      expect(calls).toHaveLength(2);
      expect(calls.every((call) => call.tenant === 'tenant-a')).toBe(true);

      calls[1].release();
      expect(await toC).toBe(true);
      calls[0].release();
      expect(await toB).toBe(false);

      expect(apiClient.getCurrentTenantId()).toBe('tenant-c');
    });

    it('a switch still waiting on its cancel never restores a tenant after logout', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'MESSAGE_DRAFT' });

      const toB = transitions.switchTo('tenant-b');
      const logout = transitions.logout();
      await flush();
      calls.filter((call) => call.url.includes('/cancel')).forEach((call) => call.release());
      await logout;
      expect(await toB).toBe(false);

      expect(apiClient.getCurrentTenantId()).toBeNull();
      expect(calls.filter((call) => call.url === '/logout')).toHaveLength(1);
    });
  });

  describe('provisioning and failed logout', () => {
    it('provisioning a workspace cancels the old tenant before applying the new one', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });

      const provisioned = transitions.provision(async () => ({ tenantId: 'tenant-new' }));
      await flush();
      expect(calls.map((call) => [call.url, call.tenant])).toEqual([
        [`/api/ai-invocations/${invocationId}/cancel`, 'tenant-a']
      ]);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-a');

      calls[0].release();
      expect(await provisioned).toBe(true);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-new');
    });

    it('a switch made while provisioning is pending wins', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });

      const provisioned = transitions.provision(async () => ({ tenantId: 'tenant-new' }));
      const toC = transitions.switchTo('tenant-c');
      await flush();
      calls.forEach((call) => call.release());

      expect(await toC).toBe(true);
      expect(await provisioned).toBe(false);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-c');
    });

    it('provisioning never restores a tenant after logout', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId, operation: 'NEXT_BEST_ACTION' });

      const provisioned = transitions.provision(async () => ({ tenantId: 'tenant-new' }));
      const logout = transitions.logout();
      await flush();
      calls.forEach((call) => call.release());
      await logout;

      expect(await provisioned).toBe(false);
      expect(apiClient.getCurrentTenantId()).toBeNull();
    });

    it('a failed logout keeps the session usable for later switches', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      controllableFetch((url) => (url === '/logout' ? 500 : 204));

      await expect(transitions.logout()).rejects.toThrow();
      expect(apiClient.getCurrentTenantId()).toBe('tenant-a');

      expect(await transitions.switchTo('tenant-b')).toBe(true);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-b');
    });
  });

  describe('fifth review: stale provisioning, two owners, drain', () => {
    it('a provisioning superseded while create() is pending never cancels the newer tenant', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-c', invocationId: 'c-invocation', operation: 'NEXT_BEST_ACTION' });
      let finishCreate: (value: { tenantId: string }) => void = () => undefined;

      const provisioned = transitions.provision(
        () => new Promise<{ tenantId: string }>((resolve) => (finishCreate = resolve))
      );
      expect(await transitions.switchTo('tenant-c')).toBe(true);
      finishCreate({ tenantId: 'tenant-new' });

      expect(await provisioned).toBe(false);
      expect(calls.filter((call) => call.url.includes('c-invocation'))).toHaveLength(0);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-c');
    });

    it('an aborted request that untracks first never drops its pending supersede cancel', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      const untrack = tracker.track({ tenantId: 'tenant-a', invocationId: 'rec-1', operation: 'NEXT_BEST_ACTION' });

      void tracker.supersede('rec-1');
      untrack(); // the aborted request settles before the server cancel does
      const toB = transitions.switchTo('tenant-b');
      await flush();

      expect(calls.map((call) => [call.url, call.tenant])).toEqual([
        ['/api/ai-invocations/rec-1/cancel', 'tenant-a'],
        ['/api/ai-invocations/rec-1/cancel', 'tenant-a']
      ]);
      calls[1].release();
      expect(await toB).toBe(true);
    });

    it('an invocation tracked while the switch drains is cancelled before the switch applies', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId: 'rec-1', operation: 'NEXT_BEST_ACTION' });

      const toB = transitions.switchTo('tenant-b');
      await flush();
      tracker.track({ tenantId: 'tenant-a', invocationId: 'draft-2', operation: 'MESSAGE_DRAFT' });
      await flush();
      calls[0].release();
      await flush();
      expect(apiClient.getCurrentTenantId()).toBe('tenant-a');

      expect(calls.map((call) => [call.url, call.tenant])).toEqual([
        ['/api/ai-invocations/rec-1/cancel', 'tenant-a'],
        ['/api/ai-invocations/draft-2/cancel', 'tenant-a']
      ]);
      calls[1].release();
      expect(await toB).toBe(true);
      expect(apiClient.getCurrentTenantId()).toBe('tenant-b');
    });
  });

  describe('local supersession', () => {
    it('a new recommendation cancels the previous recommendation and draft before it is sent', async () => {
      const tracker = createAiInvocationTracker();
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId: 'rec-1', operation: 'NEXT_BEST_ACTION' });
      tracker.track({ tenantId: 'tenant-a', invocationId: 'draft-1', operation: 'MESSAGE_DRAFT' });

      // Prototype of useFollowUpAssistant.requestRecommendation: supersede, abort locally, then send.
      void tracker.supersede('rec-1');
      void tracker.supersede('draft-1');
      tracker.track({ tenantId: 'tenant-a', invocationId: 'rec-2', operation: 'NEXT_BEST_ACTION' });
      void httpClient.request('/api/customers/c-1/recommendation', {
        method: 'POST',
        headers: { 'X-Ai-Invocation-Id': 'rec-2' }
      });
      await flush();

      expect(calls.map((call) => call.url)).toEqual([
        '/api/ai-invocations/rec-1/cancel',
        '/api/ai-invocations/draft-1/cancel',
        '/api/customers/c-1/recommendation'
      ]);
    });

    it('a new draft cancels the previous draft', async () => {
      const tracker = createAiInvocationTracker();
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId: 'draft-1', operation: 'MESSAGE_DRAFT' });

      void tracker.supersede('draft-1');
      await flush();

      expect(calls.map((call) => call.url)).toEqual(['/api/ai-invocations/draft-1/cancel']);
    });

    it('a switch while a superseding cancel is in flight sends it again with the old tenant', async () => {
      const tracker = createAiInvocationTracker();
      const transitions = createWorkspaceTransitions(tracker);
      const calls = controllableFetch();
      tracker.track({ tenantId: 'tenant-a', invocationId: 'rec-1', operation: 'NEXT_BEST_ACTION' });

      const superseded = tracker.supersede('rec-1');
      const toB = transitions.switchTo('tenant-b');
      await flush();
      expect(calls.map((call) => [call.url, call.tenant])).toEqual([
        ['/api/ai-invocations/rec-1/cancel', 'tenant-a'],
        ['/api/ai-invocations/rec-1/cancel', 'tenant-a']
      ]);

      calls[1].release();
      expect(await toB).toBe(true);
      await superseded;
      expect(apiClient.getCurrentTenantId()).toBe('tenant-b');
    });
  });
});
