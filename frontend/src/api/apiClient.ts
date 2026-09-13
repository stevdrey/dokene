export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly statusText: string,
    public readonly body?: unknown
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = 'ApiError';
  }
}

export class UnauthorizedError extends ApiError {
  constructor(statusText = 'Unauthorized', body?: unknown) {
    super(401, statusText, body);
    this.name = 'UnauthorizedError';
  }
}

export class ForbiddenError extends ApiError {
  constructor(statusText = 'Forbidden', body?: unknown) {
    super(403, statusText, body);
    this.name = 'ForbiddenError';
  }
}

export class AbortedTenantRequestError extends Error {
  constructor(message = 'Request cancelled due to workspace change') {
    super(message);
    this.name = 'AbortedTenantRequestError';
  }
}

type UnauthorizedHandler = () => void;

class ApiClient {
  private csrfToken: string | null = null;
  private currentTenantId: string | null = null;
  private unauthorizedHandlers = new Set<UnauthorizedHandler>();
  private activeTenantControllers = new Map<string, Set<AbortController>>();

  setCsrfToken(token: string | null): void {
    this.csrfToken = token;
  }

  setCurrentTenantId(tenantId: string | null): void {
    if (this.currentTenantId && this.currentTenantId !== tenantId) {
      this.cancelTenantRequests(this.currentTenantId);
    }
    this.currentTenantId = tenantId;
  }

  getCurrentTenantId(): string | null {
    return this.currentTenantId;
  }

  onUnauthorized(handler: UnauthorizedHandler): () => void {
    this.unauthorizedHandlers.add(handler);
    return () => {
      this.unauthorizedHandlers.delete(handler);
    };
  }

  private notifyUnauthorized(): void {
    this.unauthorizedHandlers.forEach((handler) => {
      try {
        handler();
      } catch (err) {
        console.error('Error in unauthorized handler:', err);
      }
    });
  }

  cancelTenantRequests(tenantId: string): void {
    const controllers = this.activeTenantControllers.get(tenantId);
    if (controllers) {
      controllers.forEach((controller) => {
        try {
          controller.abort();
        } catch {
          // ignore already aborted
        }
      });
      controllers.clear();
      this.activeTenantControllers.delete(tenantId);
    }
  }

  cancelAllRequests(): void {
    this.activeTenantControllers.forEach((controllers) => {
      controllers.forEach((controller) => {
        try {
          controller.abort();
        } catch {
          // ignore
        }
      });
    });
    this.activeTenantControllers.clear();
  }

  async request<T>(
    endpoint: string,
    options: RequestInit & {
      tenantScoped?: boolean;
      targetTenantId?: string;
    } = {}
  ): Promise<T> {
    const { tenantScoped = false, targetTenantId, headers: initHeaders, signal: callerSignal, ...rest } = options;

    const headers = new Headers(initHeaders);
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json');
    }

    const method = (rest.method || 'GET').toUpperCase();
    if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
      if (this.csrfToken && !headers.has('X-CSRF-TOKEN')) {
        headers.set('X-CSRF-TOKEN', this.csrfToken);
      }
      if (rest.body && !(rest.body instanceof FormData) && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
      }
    }

    const effectiveTenantId = targetTenantId || (tenantScoped ? this.currentTenantId : null);

    if (effectiveTenantId) {
      headers.set('X-Tenant-Id', effectiveTenantId);
    }

    // Per-tenant abort tracking
    const internalController = new AbortController();
    if (effectiveTenantId) {
      let set = this.activeTenantControllers.get(effectiveTenantId);
      if (!set) {
        set = new Set();
        this.activeTenantControllers.set(effectiveTenantId, set);
      }
      set.add(internalController);
    }

    // Connect caller signal if provided
    if (callerSignal) {
      if (callerSignal.aborted) {
        internalController.abort();
      } else {
        callerSignal.addEventListener('abort', () => internalController.abort(), { once: true });
      }
    }

    try {
      const response = await fetch(endpoint, {
        ...rest,
        headers,
        credentials: 'include',
        signal: internalController.signal,
      });

      // Guard: Late response from previous tenant check
      if (effectiveTenantId && this.currentTenantId !== effectiveTenantId) {
        throw new AbortedTenantRequestError(
          `Discarding late response for tenant ${effectiveTenantId}; current tenant is ${this.currentTenantId}`
        );
      }

      if (response.status === 401) {
        this.notifyUnauthorized();
        throw new UnauthorizedError(response.statusText);
      }

      if (response.status === 403) {
        throw new ForbiddenError(response.statusText);
      }

      if (!response.ok) {
        let errorBody: unknown;
        try {
          errorBody = await response.json();
        } catch {
          // not json
        }
        throw new ApiError(response.status, response.statusText, errorBody);
      }

      if (response.status === 204) {
        return undefined as unknown as T;
      }

      return (await response.json()) as T;
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        throw new AbortedTenantRequestError();
      }
      throw err;
    } finally {
      if (effectiveTenantId) {
        const set = this.activeTenantControllers.get(effectiveTenantId);
        if (set) {
          set.delete(internalController);
          if (set.size === 0) {
            this.activeTenantControllers.delete(effectiveTenantId);
          }
        }
      }
    }
  }

  get<T>(endpoint: string, options?: Omit<RequestInit, 'method'> & { tenantScoped?: boolean; targetTenantId?: string }): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'GET' });
  }

  post<T>(endpoint: string, body?: unknown, options?: Omit<RequestInit, 'method' | 'body'> & { tenantScoped?: boolean; targetTenantId?: string }): Promise<T> {
    return this.request<T>(endpoint, {
      ...options,
      method: 'POST',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  }
}

export const apiClient = new ApiClient();
