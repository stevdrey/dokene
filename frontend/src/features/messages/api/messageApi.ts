// ABOUTME: Typed access to the spec 0002 and 0003 message routes through the shared HTTP client.
// ABOUTME: Checks the members the UI needs and reads the strong ETag as the message version.

import { httpClient } from '@/shared/api/httpClient';
import { parseStrongNumericEtag } from '@/shared/api/etag';
import type {
  ApprovalDecision,
  MessageDetail,
  MessageEvent,
  MessageListItem,
  MessageListPage,
  MessageStatus,
  SubmitMessageRequest,
  VersionedDetail
} from '../types';

/** The backend answered with a shape the UI cannot trust: a missing member or a non strong ETag. */
export class InvalidMessageResponseError extends Error {
  constructor(message = 'La respuesta del servidor no tiene el formato esperado.') {
    super(message);
    this.name = 'InvalidMessageResponseError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasStrings(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return keys.every((key) => typeof value[key] === 'string');
}

function isListItem(value: unknown): value is MessageListItem {
  return (
    isRecord(value) &&
    hasStrings(value, ['messageId', 'customerId', 'customerDisplayName', 'recipientPhone', 'status', 'createdAt']) &&
    typeof value.version === 'number'
  );
}

function isDetail(value: unknown): value is MessageDetail {
  return (
    isRecord(value) &&
    hasStrings(value, ['messageId', 'customerId', 'customerDisplayName', 'recipientPhone', 'status', 'body']) &&
    typeof value.version === 'number' &&
    typeof value.outcomeUnknown === 'boolean' &&
    Array.isArray(value.attempts)
  );
}

function isEvent(value: unknown): value is MessageEvent {
  return (
    isRecord(value) &&
    typeof value.sequenceNumber === 'number' &&
    hasStrings(value, ['eventType', 'statusTo', 'occurredAt', 'actorKind']) &&
    typeof value.applied === 'boolean'
  );
}

function toVersionedDetail(data: unknown, etag: string | null): VersionedDetail {
  if (!isDetail(data)) {
    throw new InvalidMessageResponseError();
  }
  const version = parseStrongNumericEtag(etag);
  if (version === null) {
    throw new InvalidMessageResponseError();
  }
  return { detail: data, version };
}

export interface ListMessagesParams {
  readonly statuses: readonly MessageStatus[];
  readonly customerId?: string;
  readonly cursor?: string | null;
  readonly limit?: number;
}

export const messageApi = {
  async list(params: ListMessagesParams, signal?: AbortSignal): Promise<MessageListPage> {
    const query = new URLSearchParams();
    for (const status of params.statuses) {
      query.append('status', status);
    }
    if (params.customerId) query.set('customerId', params.customerId);
    if (params.cursor) query.set('cursor', params.cursor);
    query.set('limit', String(params.limit ?? 50));

    const { data } = await httpClient.request<unknown>(`/api/messages?${query.toString()}`, { signal });
    if (!isRecord(data) || !Array.isArray(data.items) || !data.items.every(isListItem)) {
      throw new InvalidMessageResponseError();
    }
    const nextCursor = typeof data.nextCursor === 'string' ? data.nextCursor : null;
    return { items: data.items, nextCursor };
  },

  async getDetail(messageId: string, signal?: AbortSignal): Promise<VersionedDetail> {
    const { data, etag } = await httpClient.request<unknown>(`/api/messages/${encodeURIComponent(messageId)}`, {
      signal
    });
    return toVersionedDetail(data, etag);
  },

  async getEvents(messageId: string, signal?: AbortSignal): Promise<readonly MessageEvent[]> {
    const { data } = await httpClient.request<unknown>(
      `/api/messages/${encodeURIComponent(messageId)}/events`,
      { signal }
    );
    if (!isRecord(data) || !Array.isArray(data.events) || !data.events.every(isEvent)) {
      throw new InvalidMessageResponseError();
    }
    return [...data.events].sort((a, b) => a.sequenceNumber - b.sequenceNumber);
  },

  async decide(
    messageId: string,
    input: { decision: ApprovalDecision; note?: string; version: number; idempotencyKey: string },
    signal?: AbortSignal
  ): Promise<VersionedDetail> {
    const body = input.note ? { decision: input.decision, note: input.note } : { decision: input.decision };
    const { data, etag } = await httpClient.request<unknown>(
      `/api/messages/${encodeURIComponent(messageId)}/approvals`,
      { method: 'POST', body, ifMatch: input.version, idempotencyKey: input.idempotencyKey, signal }
    );
    return toVersionedDetail(data, etag);
  },

  async cancel(
    messageId: string,
    input: { note?: string; version: number; idempotencyKey: string },
    signal?: AbortSignal
  ): Promise<VersionedDetail> {
    const body = input.note ? { note: input.note } : {};
    const { data, etag } = await httpClient.request<unknown>(
      `/api/messages/${encodeURIComponent(messageId)}/cancellations`,
      { method: 'POST', body, ifMatch: input.version, idempotencyKey: input.idempotencyKey, signal }
    );
    return toVersionedDetail(data, etag);
  },

  async send(
    messageId: string,
    input: { version: number; idempotencyKey: string },
    signal?: AbortSignal
  ): Promise<VersionedDetail> {
    const { data, etag } = await httpClient.request<unknown>(
      `/api/messages/${encodeURIComponent(messageId)}/sends`,
      { method: 'POST', ifMatch: input.version, idempotencyKey: input.idempotencyKey, signal }
    );
    return toVersionedDetail(data, etag);
  },

  async submit(
    customerId: string,
    input: { request: SubmitMessageRequest; policyVersion: number; idempotencyKey: string },
    signal?: AbortSignal
  ): Promise<VersionedDetail> {
    const { data, etag } = await httpClient.request<unknown>(
      `/api/customers/${encodeURIComponent(customerId)}/messages`,
      {
        method: 'POST',
        body: input.request,
        ifMatch: input.policyVersion,
        idempotencyKey: input.idempotencyKey,
        signal
      }
    );
    return toVersionedDetail(data, etag);
  }
};
