// ABOUTME: Test support for the message review suites: the frozen v1 fixtures and a fetch stub keyed by method and path.
// ABOUTME: Replies can be immediate, pending until resolved, or late (ignoring abort), and unexpected requests fail the test.
import messagesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/messages.json?raw';
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import requestsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/requests.json?raw';
import templatesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/templates.json?raw';
import { vi } from 'vitest';
import type { TemplateResponse } from '@/features/integrations/types';
import type {
  MessageEventItem,
  MessageEventsResponse,
  MessageLocalesResponse,
  MessagePageResponse,
  MessageResponse
} from '../types';

const messages = JSON.parse(messagesJson);
const problems = JSON.parse(problemsJson);

export const requests = JSON.parse(requestsJson);
export const details: Record<string, MessageResponse> = messages.details;
export const eventsFixtures: Record<string, MessageEventsResponse> = messages.events;
export const pages: Record<string, MessagePageResponse> = messages.pages;
export const localesFixtures: Record<string, MessageLocalesResponse> = messages.locales;
export const templateFixtures: Record<string, TemplateResponse> = JSON.parse(templatesJson).response.valid;

export interface Reply {
  status: number;
  body?: unknown;
  etag?: string;
  /** Rejects with this instead of answering, as fetch does on a network failure. */
  error?: Error;
  /** Sent as a delta-seconds `Retry-After` header. */
  retryAfter?: number;
  /** Raw body text, for a reply whose JSON must not parse. */
  rawBody?: string;
}

export interface Pending {
  reply: () => Promise<Reply>;
  resolve: (reply: Reply) => void;
  late: boolean;
}

type Responder = Reply | Pending;

export interface Captured {
  key: string;
  headers: Record<string, string>;
  body: string | undefined;
  /** The request's own signal, so a test can prove a read was aborted rather than merely ignored. */
  signal: AbortSignal | undefined;
}

export const ok = (body: unknown, etag?: string): Reply => ({ status: 200, body, etag });
export const created = (detail: MessageResponse): Reply => ({ status: 201, body: detail, etag: `"${detail.version}"` });
export const detailReply = (detail: MessageResponse): Reply => ok(detail, `"${detail.version}"`);
export const eventsReply = (detail: MessageResponse, events: MessageEventItem[] = []): Reply =>
  ok({ messageId: detail.messageId, events });
export const problem = (code: string): Reply => ({ status: problems.valid[code].status, body: problems.valid[code] });
export const networkFailure = (): Reply => ({ status: 0, error: new TypeError('Failed to fetch') });
export const status = (code: number): Reply => ({ status: code });

/** A copy of a fixture with other members replaced, for versions and statuses the fixtures do not carry. */
export function variant(detail: MessageResponse, changes: Partial<MessageResponse>): MessageResponse {
  return { ...detail, ...changes };
}

export function pending(late = false): Pending {
  let resolve: (reply: Reply) => void = () => {};
  const promise = new Promise<Reply>((settle) => {
    resolve = settle;
  });
  return { reply: () => promise, resolve, late };
}

function toResponse(reply: Reply): Response {
  if (reply.error) throw reply.error;
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (reply.etag) headers.ETag = reply.etag;
  if (reply.retryAfter !== undefined) headers['Retry-After'] = String(reply.retryAfter);
  const body = reply.rawBody ?? (reply.body === undefined ? null : JSON.stringify(reply.body));
  return new Response(body, { status: reply.status, headers });
}

export const historyPath = (customerId: string, cursor: string | null = null) => {
  const query = new URLSearchParams({ customerId, limit: '20' });
  if (cursor !== null) query.set('cursor', cursor);
  return `GET /api/messages?${query.toString()}`;
};
export const detailPath = (id: string) => `GET /api/messages/${id}`;
export const eventsPath = (id: string) => `GET /api/messages/${id}/events`;
export const approvalsPath = (id: string) => `POST /api/messages/${id}/approvals`;
export const cancellationsPath = (id: string) => `POST /api/messages/${id}/cancellations`;
export const sendsPath = (id: string) => `POST /api/messages/${id}/sends`;
export const submitPath = (customerId: string) => `POST /api/customers/${customerId}/messages`;
export const localesPath = 'GET /api/messages/locales';
export const templatesPath = 'GET /api/templates';
/** The template list compose reads, with the given slots (issue 268). */
export const templateList = (...items: TemplateResponse[]): Reply => ok({ items });

export class FakeServer {
  readonly calls: Captured[] = [];
  readonly unexpected: string[] = [];
  private readonly routes = new Map<string, Responder[]>();

  /**
   * Compose reads the accepted locales and the template list when it opens (issue 268). By default the deployment
   * accepts the default locale only and the draft's intent is mapped to a sendable Spanish template; a test that needs
   * another answer replaces these routes with {@link on}.
   */
  constructor() {
    this.routes.set(localesPath, [ok(localesFixtures.defaultOnly)]);
    this.routes.set(templatesPath, [templateList(templateFixtures.approvedAndSendable)]);
  }

  /** Replies are used in order; the last one repeats. */
  on(key: string, ...replies: Responder[]): this {
    this.routes.set(key, replies);
    return this;
  }

  count(key: string): number {
    return this.calls.filter((call) => call.key === key).length;
  }

  all(key: string): Captured[] {
    return this.calls.filter((call) => call.key === key);
  }

  last(key: string): Captured {
    const matching = this.all(key);
    return matching[matching.length - 1];
  }

  install() {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const key = `${init?.method ?? 'GET'} ${String(input)}`;
      const signal = init?.signal ?? undefined;
      this.calls.push({
        key,
        headers: (init?.headers as Record<string, string>) ?? {},
        body: init?.body as string | undefined,
        signal
      });
      const queue = this.routes.get(key);
      if (!queue || queue.length === 0) {
        this.unexpected.push(key);
        throw new TypeError(`unexpected request ${key}`);
      }
      const responder = queue.length > 1 ? queue.shift()! : queue[0];
      if (!('reply' in responder)) return toResponse(responder);
      if (responder.late) return toResponse(await responder.reply());
      return new Promise<Response>((resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
        void responder.reply().then((reply) => {
          try {
            resolve(toResponse(reply));
          } catch (error) {
            reject(error);
          }
        });
      });
    });
  }
}
