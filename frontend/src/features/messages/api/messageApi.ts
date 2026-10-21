// ABOUTME: Reads and writes one message over the frozen v1 routes of specs 0002 and 0003 through the shared client.
// ABOUTME: Narrows every response at runtime and takes the version only from a strong numeric ETag that matches the body.
import { httpClient } from '@/shared/api/httpClient';
import { SEMANTIC_ACTIONS, SEMANTIC_TEMPLATE_INTENTS } from '@/features/followups/types';
import {
  FAILURE_CATEGORIES,
  MESSAGE_ACTOR_KINDS,
  MESSAGE_CHANNELS,
  MESSAGE_EVENT_TYPES,
  MESSAGE_ORIGINS,
  MESSAGE_STATUSES,
  type ApprovalDecision,
  type MessageEventItem,
  type MessageListItem,
  type MessagePageResponse,
  type MessageResponse,
  type SubmitMessageRequest
} from '../types';

export const HISTORY_PAGE_SIZE = 20;

export class InvalidMessageResponseError extends Error {
  constructor() {
    super('La respuesta del servidor sobre el mensaje no tiene el formato esperado.');
    this.name = 'InvalidMessageResponseError';
  }
}

export interface VersionedMessage {
  detail: MessageResponse;
  version: number;
}

const STRONG_NUMERIC_ETAG = /^"(0|[1-9][0-9]*)"$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || isString(value);
}

function isOneOf<T extends string>(allowed: readonly T[], value: unknown): value is T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value);
}

/** The version is the strong numeric ETag; a weak, missing or mismatched one is never read as version 0. */
export function parseMessageVersion(etag: string | null, bodyVersion: unknown): number {
  const match = etag === null ? null : STRONG_NUMERIC_ETAG.exec(etag);
  if (!match) throw new InvalidMessageResponseError();
  const version = Number(match[1]);
  if (!Number.isSafeInteger(version) || version !== bodyVersion) throw new InvalidMessageResponseError();
  return version;
}

function narrowDetail(body: unknown): MessageResponse {
  if (!isRecord(body)) throw new InvalidMessageResponseError();
  const valid =
    isString(body.messageId) &&
    isString(body.customerId) &&
    isString(body.contactId) &&
    typeof body.contactRemoved === 'boolean' &&
    isString(body.recipientPhone) &&
    isOneOf(MESSAGE_CHANNELS, body.channel) &&
    isOneOf(MESSAGE_STATUSES, body.status) &&
    typeof body.outcomeUnknown === 'boolean' &&
    (body.failureCategory === null || isOneOf(FAILURE_CATEGORIES, body.failureCategory)) &&
    isOneOf(SEMANTIC_ACTIONS, body.action) &&
    isOneOf(SEMANTIC_TEMPLATE_INTENTS, body.templateIntent) &&
    isString(body.locale) &&
    isOneOf(MESSAGE_ORIGINS, body.origin) &&
    isString(body.body) &&
    isCount(body.sourcePolicyVersion) &&
    Array.isArray(body.attempts) &&
    body.attempts.every((attempt) => isRecord(attempt) && isCount(attempt.attemptNumber) && isString(attempt.outcome)) &&
    isNullableString(body.sentAt) &&
    isNullableString(body.deliveredAt) &&
    isNullableString(body.readAt) &&
    isCount(body.version);
  if (!valid) throw new InvalidMessageResponseError();
  return body as unknown as MessageResponse;
}

function isEvent(value: unknown): value is MessageEventItem {
  return (
    isRecord(value) &&
    isCount(value.sequenceNumber) &&
    isOneOf(MESSAGE_EVENT_TYPES, value.type) &&
    (value.statusFrom === null || isOneOf(MESSAGE_STATUSES, value.statusFrom)) &&
    isOneOf(MESSAGE_STATUSES, value.statusTo) &&
    typeof value.applied === 'boolean' &&
    isOneOf(MESSAGE_ACTOR_KINDS, value.actorKind) &&
    isString(value.occurredAt)
  );
}

/** `status` is any string so a value the contract does not know yet renders raw in the history instead of failing the page. */
function isListItem(value: unknown): value is MessageListItem {
  return (
    isRecord(value) &&
    isString(value.messageId) &&
    isString(value.customerId) &&
    typeof value.contactRemoved === 'boolean' &&
    isString(value.status) &&
    typeof value.outcomeUnknown === 'boolean' &&
    isOneOf(SEMANTIC_ACTIONS, value.action) &&
    isOneOf(SEMANTIC_TEMPLATE_INTENTS, value.templateIntent) &&
    isOneOf(MESSAGE_ORIGINS, value.origin) &&
    isString(value.createdAt) &&
    isString(value.updatedAt) &&
    isCount(value.version)
  );
}

function narrowPage(body: unknown): MessagePageResponse {
  if (!isRecord(body) || !Array.isArray(body.items) || !body.items.every(isListItem) || !isNullableString(body.nextCursor)) {
    throw new InvalidMessageResponseError();
  }
  return { items: body.items, nextCursor: body.nextCursor };
}

function versioned(data: unknown, etag: string | null): VersionedMessage {
  const detail = narrowDetail(data);
  return { detail, version: parseMessageVersion(etag, detail.version) };
}

function messagePath(messageId: string, suffix = ''): string {
  return `/api/messages/${encodeURIComponent(messageId)}${suffix}`;
}

/** An empty or blank note is left out, so the body matches the contract's `cancellation.valid.empty`. */
function withNote<T extends object>(base: T, note: string | undefined): T & { note?: string } {
  return note && note.trim() !== '' ? { ...base, note } : base;
}

export const messageApi = {
  /** One page of a customer's history, every status included; an item of another customer is dropped, never shown. */
  async listCustomerHistory(customerId: string, cursor: string | null, signal?: AbortSignal): Promise<MessagePageResponse> {
    const query = new URLSearchParams();
    query.set('customerId', customerId);
    query.set('limit', String(HISTORY_PAGE_SIZE));
    if (cursor !== null) query.set('cursor', cursor);
    const { data } = await httpClient.request<unknown>(`/api/messages?${query.toString()}`, { signal });
    const page = narrowPage(data);
    return { items: page.items.filter((item) => item.customerId === customerId), nextCursor: page.nextCursor };
  },

  async getDetail(messageId: string, signal?: AbortSignal): Promise<VersionedMessage> {
    const { data, etag } = await httpClient.request<unknown>(messagePath(messageId), { signal });
    return versioned(data, etag);
  },

  async getEvents(messageId: string, signal?: AbortSignal): Promise<MessageEventItem[]> {
    const { data } = await httpClient.request<unknown>(messagePath(messageId, '/events'), { signal });
    if (!isRecord(data) || !Array.isArray(data.events) || !data.events.every(isEvent)) {
      throw new InvalidMessageResponseError();
    }
    return [...data.events].sort((a, b) => a.sequenceNumber - b.sequenceNumber);
  },

  async submit(
    customerId: string,
    request: SubmitMessageRequest,
    policyVersion: number,
    idempotencyKey: string,
    signal?: AbortSignal
  ): Promise<VersionedMessage> {
    const { data, etag } = await httpClient.request<unknown>(
      `/api/customers/${encodeURIComponent(customerId)}/messages`,
      { method: 'POST', body: request, ifMatch: policyVersion, idempotencyKey, signal }
    );
    return versioned(data, etag);
  },

  async decide(
    messageId: string,
    decision: ApprovalDecision,
    note: string | undefined,
    version: number,
    idempotencyKey: string,
    signal?: AbortSignal
  ): Promise<VersionedMessage> {
    const { data, etag } = await httpClient.request<unknown>(messagePath(messageId, '/approvals'), {
      method: 'POST',
      body: withNote({ decision }, note),
      ifMatch: version,
      idempotencyKey,
      signal
    });
    return versioned(data, etag);
  },

  async cancel(
    messageId: string,
    note: string | undefined,
    version: number,
    idempotencyKey: string,
    signal?: AbortSignal
  ): Promise<VersionedMessage> {
    const { data, etag } = await httpClient.request<unknown>(messagePath(messageId, '/cancellations'), {
      method: 'POST',
      body: withNote({}, note),
      ifMatch: version,
      idempotencyKey,
      signal
    });
    return versioned(data, etag);
  },

  /** No body, so the shared client sends no Content-Type either. */
  async send(messageId: string, version: number, idempotencyKey: string, signal?: AbortSignal): Promise<VersionedMessage> {
    const { data, etag } = await httpClient.request<unknown>(messagePath(messageId, '/sends'), {
      method: 'POST',
      ifMatch: version,
      idempotencyKey,
      signal
    });
    return versioned(data, etag);
  }
};
