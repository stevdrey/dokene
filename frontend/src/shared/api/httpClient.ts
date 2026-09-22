import { apiClient, AbortedTenantRequestError, StaleSessionError } from '@/api/apiClient';
import { ApiErrorPayload } from '@/shared/types';

export { AbortedTenantRequestError, StaleSessionError };

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

export function getFriendlyErrorMessage(status: number, method?: string): string {
  if (status === 400) {
    return 'La solicitud no pudo ser procesada. Revisa los datos ingresados.';
  }
  if (status === 401) {
    return 'Sesión no autorizada o expirada.';
  }
  if (status === 403) {
    return 'Acceso denegado en este espacio de trabajo.';
  }
  if (status === 404) {
    return 'El recurso solicitado no fue encontrado.';
  }
  if (status === 409) {
    const isMutation = method === 'PUT' || method === 'DELETE' || method === 'PATCH';
    return isMutation
      ? 'Conflicto de concurrencia: los datos fueron modificados por otro usuario. Por favor recarga.'
      : 'Conflicto: el registro o número de contacto ya existe o está en conflicto.';
  }
  if (status === 412) {
    return 'Conflicto de concurrencia: los datos fueron modificados por otro usuario. Por favor recarga.';
  }
  if (status === 422) {
    return 'Los datos enviados contienen errores o no cumplen con las reglas requeridas.';
  }
  if (status === 429) {
    return 'Demasiadas solicitudes. Por favor espera un momento antes de reintentar.';
  }
  if (status === 502 || status === 503 || status === 504) {
    return 'El servicio no está disponible temporalmente. Por favor intenta de nuevo en unos momentos.';
  }
  if (status >= 500) {
    return 'Ocurrió un problema en el servidor al procesar la solicitud. Por favor intenta nuevamente.';
  }
  return 'Ocurrió un error inesperado al procesar la solicitud. Por favor intenta nuevamente.';
}

class HttpClient {
  private activeTenantId: string | null = null;
  private csrfToken: string | null = null;
  private onUnauthorizedCallback: (() => void) | null = null;

  setTenantId(tenantId: string | null) {
    this.activeTenantId = tenantId;
    apiClient.setCurrentTenantId(tenantId);
  }

  getTenantId(): string | null {
    return this.activeTenantId || apiClient.getCurrentTenantId();
  }

  setCsrfToken(token: string | null) {
    this.csrfToken = token;
    apiClient.setCsrfToken(token);
  }

  getCsrfToken(): string | null {
    return this.csrfToken || apiClient.getCsrfToken();
  }

  onUnauthorized(callback: () => void) {
    this.onUnauthorizedCallback = callback;
  }

  async request<T>(
    endpoint: string,
    options: {
      method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
      body?: unknown;
      headers?: Record<string, string>;
      ifMatch?: string | number;
      idempotencyKey?: string;
      tenantScoped?: boolean;
      signal?: AbortSignal;
    } = {}
  ): Promise<{ data: T; etag: string | null }> {
    const {
      method = 'GET',
      body,
      headers = {},
      ifMatch,
      idempotencyKey,
      tenantScoped = true,
      signal
    } = options;

    const requestHeaders: Record<string, string> = {
      'Accept': 'application/json',
      ...headers
    };

    if (body !== undefined && !(body instanceof FormData)) {
      requestHeaders['Content-Type'] = 'application/json';
    }

    const effectiveTenantId = this.getTenantId();
    if (tenantScoped && effectiveTenantId) {
      requestHeaders['X-Tenant-Id'] = effectiveTenantId;
    }

    const effectiveCsrfToken = this.getCsrfToken();
    if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method) && effectiveCsrfToken) {
      requestHeaders['X-CSRF-TOKEN'] = effectiveCsrfToken;
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

    const internalController = new AbortController();
    const unregister = apiClient.registerRequest(internalController, effectiveTenantId);
    const requestSessionGeneration = apiClient.getSessionGeneration();
    const requestTenantId = effectiveTenantId;

    let abortListener: (() => void) | undefined;
    if (signal) {
      if (signal.aborted) {
        internalController.abort();
      } else {
        abortListener = () => internalController.abort();
        signal.addEventListener('abort', abortListener, { once: true });
      }
    }

    try {
      const response = await fetch(endpoint, {
        method,
        headers: requestHeaders,
        credentials: 'include',
        signal: internalController.signal,
        body: body !== undefined ? JSON.stringify(body) : undefined
      });

      if (apiClient.getSessionGeneration() !== requestSessionGeneration) {
        throw new StaleSessionError();
      }

      const currentTenant = apiClient.getCurrentTenantId() ?? this.activeTenantId;
      if (requestTenantId && currentTenant && currentTenant !== requestTenantId) {
        throw new AbortedTenantRequestError();
      }

      if (response.status === 401) {
        apiClient.notifyUnauthorized();
        if (this.onUnauthorizedCallback) {
          this.onUnauthorizedCallback();
        }
        throw new ApiError(401, 'Sesión no autorizada o expirada');
      }

      if (!response.ok) {
        let errorPayload: ApiErrorPayload | undefined;
        let errorMessage: string | undefined;
        try {
          errorPayload = await response.json();
          if (errorPayload?.message) {
            errorMessage = errorPayload.message;
          }
        } catch {
          // Response is not JSON
        }

        if (!errorMessage) {
          errorMessage = getFriendlyErrorMessage(response.status, method);
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
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        if (signal?.aborted) {
          throw err;
        }
        if (apiClient.getSessionGeneration() !== requestSessionGeneration) {
          throw new StaleSessionError();
        }
        throw new AbortedTenantRequestError();
      }
      throw err;
    } finally {
      if (signal && abortListener) {
        signal.removeEventListener('abort', abortListener);
      }
      unregister();
    }
  }
}

export const httpClient = new HttpClient();

