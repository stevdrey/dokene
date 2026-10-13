// Frozen v1 messaging HTTP contract (ADR 0023 §3.1, ADR 0024 §1). Mirrors
// backend messaging.api.MessagingApiContract; both sides are pinned by the shared fixtures in
// backend/src/test/resources/contracts/messaging/v1. Do not edit without updating those fixtures.
import type { SemanticAction, SemanticTemplateIntent } from '../../followups/types';

export const MESSAGE_STATUSES = [
  'PENDING_APPROVAL',
  'APPROVED',
  'QUEUED',
  'SENDING',
  'SENT',
  'DELIVERED',
  'READ',
  'REJECTED',
  'CANCELLED',
  'FAILED'
] as const;
export type MessageStatus = (typeof MESSAGE_STATUSES)[number];

export const MESSAGE_CHANNELS = ['WHATSAPP'] as const;
export type MessageChannel = (typeof MESSAGE_CHANNELS)[number];

export const MESSAGE_ORIGINS = ['AI_DRAFT', 'MANUAL'] as const;
export type MessageOrigin = (typeof MESSAGE_ORIGINS)[number];

export const APPROVAL_DECISIONS = ['APPROVE', 'REJECT'] as const;
export type ApprovalDecision = (typeof APPROVAL_DECISIONS)[number];

/** A human may only declare an unknown send outcome failed; SENT needs provider evidence. */
export const RESOLUTION_OUTCOMES = ['FAILED'] as const;
export type ResolutionOutcome = (typeof RESOLUTION_OUTCOMES)[number];

export const SEND_ATTEMPT_OUTCOMES = [
  'STARTED',
  'ACCEPTED',
  'FAILED_PERMANENT',
  'FAILED_TRANSIENT',
  'OUTCOME_UNKNOWN'
] as const;
export type SendAttemptOutcome = (typeof SEND_ATTEMPT_OUTCOMES)[number];

export const FAILURE_CATEGORIES = [
  'INVALID_RECIPIENT',
  'TEMPLATE_REJECTED',
  'RATE_LIMITED',
  'PROVIDER_UNAVAILABLE',
  'AUTHENTICATION',
  'POLICY_VIOLATION',
  'UNKNOWN'
] as const;
export type FailureCategory = (typeof FAILURE_CATEGORIES)[number];

export const MESSAGE_EVENT_TYPES = [
  'SUBMITTED',
  'APPROVED',
  'REJECTED',
  'CANCELLED',
  'SEND_REQUESTED',
  'SEND_ATTEMPT_STARTED',
  'SENT',
  'SEND_FAILED',
  'SEND_OUTCOME_UNKNOWN',
  'DELIVERY_UPDATED'
] as const;
export type MessageEventType = (typeof MESSAGE_EVENT_TYPES)[number];

export const MESSAGE_ACTOR_KINDS = ['MEMBER', 'SYSTEM', 'PROVIDER'] as const;
export type MessageActorKind = (typeof MESSAGE_ACTOR_KINDS)[number];

/** HTTP status fixed by each refusal code (ADR 0023 §4.4); the UI maps codes, not statuses, to copy. */
export const MESSAGING_ERROR_STATUS = {
  INVALID_INPUT: 400,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  STALE_VERSION: 409,
  IDEMPOTENCY_KEY_REUSED: 409,
  INVALID_TRANSITION: 409,
  CUSTOMER_ARCHIVED: 409,
  DO_NOT_CONTACT: 409,
  NO_CONTACT_CONSENT: 409,
  CONTACT_NOT_OWNED: 409,
  FOLLOW_UP_INELIGIBLE: 409,
  MESSAGE_ALREADY_OPEN: 409,
  CONTACT_FREQUENCY_EXCEEDED: 409,
  TEMPLATE_NOT_MAPPED: 409,
  BODY_REJECTED: 409,
  OUTBOUND_DISABLED_GLOBALLY: 409,
  OUTBOUND_DISABLED_FOR_TENANT: 409,
  INTEGRATION_DISABLED: 409,
  SEND_ATTEMPTS_EXHAUSTED: 409,
  RATE_LIMITED: 429,
  PROVIDER_UNAVAILABLE: 503,
  APPROVAL_INVALIDATED: 409
} as const;
export type MessagingErrorCode = keyof typeof MESSAGING_ERROR_STATUS;
export const MESSAGING_ERROR_CODES = Object.keys(MESSAGING_ERROR_STATUS) as MessagingErrorCode[];

export const MESSAGE_NOTE_MAX_LENGTH = 500;

export interface SubmitMessageRequest {
  contactId: string;
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  body: string;
  locale: string;
  origin: MessageOrigin;
}

export interface ApprovalRequest {
  decision: ApprovalDecision;
  note?: string | null;
}

export interface CancellationRequest {
  note?: string | null;
}

export interface ResolutionRequest {
  outcome: ResolutionOutcome;
  note?: string | null;
}

export interface ApprovalSummary {
  decision: ApprovalDecision;
  decidedAt: string;
  decidedByMembershipId: string;
}

/** The last element is the "last attempt"; a provider failure after it is a 201, not an HTTP error. */
export interface AttemptSummary {
  attemptNumber: number;
  startedAt: string;
  outcome: SendAttemptOutcome;
  failureCategory: FailureCategory | null;
}

export interface MessageResponse {
  messageId: string;
  customerId: string;
  contactId: string;
  /** True when the customer no longer owns the contact; the message cannot advance and should be cancelled. */
  contactRemoved: boolean;
  recipientPhone: string;
  channel: MessageChannel;
  status: MessageStatus;
  outcomeUnknown: boolean;
  failureCategory: FailureCategory | null;
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  locale: string;
  origin: MessageOrigin;
  body: string;
  sourcePolicyVersion: number;
  approval: ApprovalSummary | null;
  attempts: AttemptSummary[];
  providerMessageId: string | null;
  sentAt: string | null;
  deliveredAt: string | null;
  readAt: string | null;
  createdByMembershipId: string;
  createdAt: string;
  updatedAt: string;
  /** Strong ETag value; send it back as If-Match. */
  version: number;
}

export interface MessageListItem {
  messageId: string;
  customerId: string;
  contactRemoved: boolean;
  status: MessageStatus;
  outcomeUnknown: boolean;
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  origin: MessageOrigin;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface MessagePageResponse {
  items: MessageListItem[];
  nextCursor: string | null;
}

export interface MessageEventItem {
  sequenceNumber: number;
  type: MessageEventType;
  statusFrom: MessageStatus | null;
  statusTo: MessageStatus;
  applied: boolean;
  actorKind: MessageActorKind;
  failureCategory: FailureCategory | null;
  attemptNumber: number | null;
  occurredAt: string;
}

export interface MessageEventsResponse {
  messageId: string;
  events: MessageEventItem[];
}

/** application/problem+json with exactly these members; no detail or free text ever. */
export interface MessagingProblem {
  type: 'about:blank';
  title: string;
  status: number;
  code: MessagingErrorCode;
}

const PROBLEM_TITLES: Record<number, string> = {
  400: 'Bad Request',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  429: 'Too Many Requests',
  503: 'Service Unavailable'
};

const PROBLEM_MEMBERS = ['type', 'title', 'status', 'code'];

/** Narrows an error body to a known problem; anything else (extra members, unknown code) is null. */
export function parseMessagingProblem(body: unknown): MessagingProblem | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return null;
  }
  const record = body as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== PROBLEM_MEMBERS.length || !PROBLEM_MEMBERS.every((member) => keys.includes(member))) {
    return null;
  }
  const { type, title, status, code } = record;
  if (type !== 'about:blank' || typeof code !== 'string' || !Object.hasOwn(MESSAGING_ERROR_STATUS, code)) {
    return null;
  }
  const knownCode = code as MessagingErrorCode;
  const expectedStatus = MESSAGING_ERROR_STATUS[knownCode];
  if (status !== expectedStatus || title !== PROBLEM_TITLES[expectedStatus]) {
    return null;
  }
  return { type, title: PROBLEM_TITLES[expectedStatus], status: expectedStatus, code: knownCode };
}
