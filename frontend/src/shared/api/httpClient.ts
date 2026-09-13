import { ApiErrorPayload } from '@/shared/types';

export class ApiError extends Error {
  readonly status: number;
  readonly payload?: ApiErrorPayload;

  constructor(status: number, message: string, payload?: ApiErrorPayload) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
  }
}

class HttpClient {
  private activeTenantId: string | null = null;
  private csrfToken: string | null = null;
  private onUnauthorizedCallback: (() => void) | null = null;

  setTenantId(tenantId: string | null) {
    this.activeTenantId = tenantId;
  }

  getTenantId(): string | null {
    return this.activeTenantId;
  }

  setCsrfToken(token: string | null) {
    this.csrfToken = token;
  }

  getCsrfToken(): string | null {
    return this.csrfToken;
  }

  onUnauthorized(callback: () => void) {
    this.onUnauthorizedCallback = callback;
  }

  async request<T>(
    endpoint: string,
    options: {
      method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
      body?: unknown;
      headers?: Record<string, string>;
      ifMatch?: string | number;
      idempotencyKey?: string;
      tenantScoped?: boolean;
    } = {}
  ): Promise<{ data: T; etag: string | null }> {
    const {
      method = 'GET',
      body,
      headers = {},
      ifMatch,
      idempotencyKey,
      tenantScoped = true
    } = options;

    const requestHeaders: Record<string, string> = {
      'Accept': 'application/json',
      ...headers
    };

    if (body !== undefined && !(body instanceof FormData)) {
      requestHeaders['Content-Type'] = 'application/json';
    }

    if (tenantScoped && this.activeTenantId) {
      requestHeaders['X-Tenant-Id'] = this.activeTenantId;
    }

    // Include CSRF token for state-changing HTTP methods
    if (['POST', 'PUT', 'DELETE'].includes(method) && this.csrfToken) {
      requestHeaders['X-CSRF-TOKEN'] = this.csrfToken;
    }

    if (ifMatch !== undefined && ifMatch !== null) {
      const formattedMatch = String(ifMatch).startsWith('"')
        ? String(ifMatch)
        : `"${ifMatch}"`;
      requestHeaders['If-Match'] = formattedMatch;
    }

    if (idempotencyKey) {
      requestHeaders['Idempotency-Key'] = idempotencyKey;
    }

    const response = await fetch(endpoint, {
      method,
      headers: requestHeaders,
      credentials: 'same-origin',
      body: body !== undefined ? JSON.stringify(body) : undefined
    });

    if (response.status === 401) {
      if (this.onUnauthorizedCallback) {
        this.onUnauthorizedCallback();
      }
      throw new ApiError(401, 'Sesión no autorizada o expirada');
    }

    if (!response.ok) {
      let errorPayload: ApiErrorPayload | undefined;
      let errorMessage = `Error HTTP ${response.status}`;
      try {
        errorPayload = await response.json();
        if (errorPayload?.message) {
          errorMessage = errorPayload.message;
        }
      } catch {
        // Response is not JSON
      }

      if (response.status === 409) {
        errorMessage = errorMessage || 'Conflicto: el registro o número de contacto ya existe o está en conflicto.';
      } else if (response.status === 412) {
        errorMessage = 'Conflicto de concurrencia: los datos fueron modificados por otro usuario. Por favor recarga.';
      } else if (response.status === 403) {
        errorMessage = errorMessage || 'Acceso denegado en este espacio de trabajo.';
      }

      throw new ApiError(response.status, errorMessage, errorPayload);
    }

    const etag = response.headers.get('ETag');

    if (response.status === 204 || response.headers.get('content-length') === '0') {
      return { data: undefined as unknown as T, etag };
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      const data = await response.json();
      return { data, etag };
    }

    return { data: undefined as unknown as T, etag };
  }
}

export const httpClient = new HttpClient();
