// ABOUTME: Fixtures and a fetch router for the Mensajes component tests.
// ABOUTME: Routes by method and path so each test states only the responses it needs.

import { vi } from 'vitest';
import type { MessageDetail, MessageEvent, MessageListItem } from '../types';

export const pendingItem: MessageListItem = {
  messageId: 'msg-1',
  customerId: 'cust-1',
  customerDisplayName: 'Valentina Morales Gómez',
  contactId: 'phone-1',
  recipientPhone: '+56984521190',
  status: 'PENDING_APPROVAL',
  outcomeUnknown: false,
  action: 'REPEAT_PURCHASE_FOLLOW_UP',
  templateIntent: 'REPEAT_PURCHASE',
  locale: 'es-419',
  origin: 'AI_DRAFT',
  contactRemoved: false,
  createdAt: '2026-10-10T14:05:00Z',
  updatedAt: '2026-10-10T14:05:00Z',
  version: 1
};

export const pendingDetail: MessageDetail = {
  ...pendingItem,
  channel: 'WHATSAPP',
  body: 'Hola Valentina, ¿cómo te fue con la torta de chocolate? Tenemos novedades para ti.',
  sourcePolicyVersion: 3,
  approval: null,
  cancellation: null,
  attempts: [],
  lastAttempt: null,
  sentAt: null,
  deliveredAt: null,
  readAt: null
};

export const submittedEvent: MessageEvent = {
  sequenceNumber: 1,
  eventType: 'SUBMITTED',
  statusFrom: null,
  statusTo: 'PENDING_APPROVAL',
  occurredAt: '2026-10-10T14:05:00Z',
  actorKind: 'MEMBER',
  actorMembershipId: 'mem-1',
  applied: true,
  failureCategory: null,
  attemptNumber: null
};

export const approvedEvent: MessageEvent = {
  sequenceNumber: 2,
  eventType: 'APPROVED',
  statusFrom: 'PENDING_APPROVAL',
  statusTo: 'APPROVED',
  occurredAt: '2026-10-10T15:00:00Z',
  actorKind: 'MEMBER',
  actorMembershipId: 'mem-2',
  applied: true,
  failureCategory: null,
  attemptNumber: null
};

export interface RouteResponse {
  readonly status?: number;
  readonly body?: unknown;
  readonly etag?: string;
  readonly headers?: Record<string, string>;
  /** Resolves the response later; useful for in flight states. */
  readonly delay?: Promise<void>;
}

export type RouteHandler = (init: RequestInit | undefined, url: URL) => RouteResponse | Promise<RouteResponse>;

export interface RecordedCall {
  readonly method: string;
  readonly url: URL;
  readonly headers: Record<string, string>;
  readonly body: unknown;
}

/**
 * Installs a fetch spy that answers by `METHOD /path` (query ignored). Handlers can be replaced
 * between steps with `set`, and every call is recorded with its headers and parsed body.
 */
export function installFetchRouter(initial: Record<string, RouteHandler | RouteResponse> = {}) {
  const routes = new Map<string, RouteHandler>();
  const calls: RecordedCall[] = [];

  const set = (key: string, handler: RouteHandler | RouteResponse) => {
    routes.set(key, typeof handler === 'function' ? handler : () => handler);
  };
  for (const [key, handler] of Object.entries(initial)) set(key, handler);

  const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const rawUrl = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const url = new URL(rawUrl, 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers: Record<string, string> = {};
    if (init?.headers) {
      for (const [name, value] of Object.entries(init.headers as Record<string, string>)) headers[name] = value;
    }
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    calls.push({ method, url, headers, body });

    const handler = routes.get(`${method} ${url.pathname}`);
    if (!handler) {
      throw new Error(`No route for ${method} ${url.pathname}`);
    }
    const response = await handler(init, url);
    if (response.delay) await response.delay;
    const status = response.status ?? 200;
    const responseHeaders: Record<string, string> = { ...(response.headers ?? {}) };
    if (response.etag !== undefined) responseHeaders.ETag = response.etag;
    if (response.body !== undefined) {
      responseHeaders['Content-Type'] = status >= 400 ? 'application/problem+json' : 'application/json';
      return new Response(JSON.stringify(response.body), { status, headers: responseHeaders });
    }
    return new Response(null, { status, headers: responseHeaders });
  });

  return {
    spy,
    set,
    calls,
    callsTo: (key: string) => calls.filter((call) => `${call.method} ${call.url.pathname}` === key)
  };
}

export function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
