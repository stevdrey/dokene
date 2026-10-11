// ABOUTME: Readonly types mirroring the spec 0002 and 0003 message representations.
// ABOUTME: Enum members are known names plus string so unknown values render through label fallbacks.

export type MessageStatus =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'QUEUED'
  | 'SENDING'
  | 'SENT'
  | 'DELIVERED'
  | 'READ'
  | 'REJECTED'
  | 'CANCELLED'
  | 'FAILED'
  | string;

export type MessageOrigin = 'AI_DRAFT' | 'MANUAL' | string;

export type FailureCategory =
  | 'INVALID_RECIPIENT'
  | 'TEMPLATE_REJECTED'
  | 'RATE_LIMITED'
  | 'PROVIDER_UNAVAILABLE'
  | 'AUTHENTICATION'
  | 'POLICY_VIOLATION'
  | 'UNKNOWN'
  | string;

export type SendAttemptOutcome =
  | 'STARTED'
  | 'ACCEPTED'
  | 'FAILED_PERMANENT'
  | 'FAILED_TRANSIENT'
  | 'OUTCOME_UNKNOWN'
  | string;

export type MessageEventType =
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'SEND_REQUESTED'
  | 'SEND_ATTEMPT_STARTED'
  | 'SEND_FAILED'
  | 'SEND_OUTCOME_UNKNOWN'
  | 'DELIVERY_UPDATED'
  | string;

export type ActorKind = 'MEMBER' | 'SYSTEM' | 'PROVIDER' | string;

export interface MessageListItem {
  readonly messageId: string;
  readonly customerId: string;
  readonly customerDisplayName: string;
  readonly contactId: string;
  readonly recipientPhone: string;
  readonly status: MessageStatus;
  readonly outcomeUnknown: boolean;
  readonly action: string;
  readonly templateIntent: string;
  readonly locale: string;
  readonly origin: MessageOrigin;
  readonly contactRemoved: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface MessageListPage {
  readonly items: readonly MessageListItem[];
  readonly nextCursor: string | null;
}

export interface ApprovalInfo {
  readonly decision: string;
  readonly note: string | null;
  readonly decidedAt: string;
  readonly decidedByMembershipId: string | null;
}

export interface CancellationInfo {
  readonly statusFrom: MessageStatus;
  readonly note: string | null;
  readonly cancelledAt: string;
  readonly cancelledByMembershipId: string | null;
}

export interface SendAttempt {
  readonly attemptNumber: number;
  readonly startedAt: string;
  readonly completedAt: string | null;
  readonly outcome: SendAttemptOutcome;
  readonly failureCategory: FailureCategory | null;
}

export interface MessageDetail {
  readonly messageId: string;
  readonly customerId: string;
  readonly customerDisplayName: string;
  readonly contactId: string;
  readonly recipientPhone: string;
  readonly channel: string;
  readonly status: MessageStatus;
  readonly outcomeUnknown: boolean;
  readonly action: string;
  readonly templateIntent: string;
  readonly locale: string;
  readonly origin: MessageOrigin;
  readonly body: string;
  readonly sourcePolicyVersion: number;
  readonly approval: ApprovalInfo | null;
  readonly cancellation: CancellationInfo | null;
  readonly attempts: readonly SendAttempt[];
  readonly lastAttempt: SendAttempt | null;
  readonly sentAt: string | null;
  readonly deliveredAt: string | null;
  readonly readAt: string | null;
  readonly contactRemoved: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface MessageEvent {
  readonly sequenceNumber: number;
  readonly eventType: MessageEventType;
  readonly statusFrom: MessageStatus | null;
  readonly statusTo: MessageStatus;
  readonly occurredAt: string;
  readonly actorKind: ActorKind;
  readonly actorMembershipId: string | null;
  readonly applied: boolean;
  readonly failureCategory: FailureCategory | null;
  readonly attemptNumber: number | null;
}

export interface ProblemBody {
  readonly type?: string;
  readonly title?: string;
  readonly status: number;
  readonly code?: string;
}

/** A detail together with the version read from its strong ETag. */
export interface VersionedDetail {
  readonly detail: MessageDetail;
  readonly version: number;
}

export type ApprovalDecision = 'APPROVE' | 'REJECT';

export interface SubmitMessageRequest {
  readonly contactId: string;
  readonly action: string;
  readonly templateIntent: string;
  readonly body: string;
  readonly locale: string;
  readonly origin: MessageOrigin;
}

export const TERMINAL_STATUSES: readonly MessageStatus[] = ['DELIVERED', 'READ', 'FAILED', 'REJECTED', 'CANCELLED'];

export function isTerminalStatus(status: MessageStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}
