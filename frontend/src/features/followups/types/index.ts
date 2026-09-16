export type FollowUpStatus = 'DUE' | 'OVERDUE' | 'NOT_YET_DUE' | 'INELIGIBLE';

export type FollowUpReason =
  | 'DO_NOT_CONTACT'
  | 'CUSTOMER_ARCHIVED'
  | 'NO_ELIGIBLE_CONTACT'
  | 'NO_PURCHASE_HISTORY'
  | 'SNOOZED'
  | 'EXPLICIT_DATE_NOT_DUE'
  | 'CADENCE_NOT_DUE'
  | 'DUE_TODAY'
  | 'OVERDUE';

export type FollowUpTimingSource =
  | 'SNOOZE'
  | 'EXPLICIT_DATE'
  | 'LAST_MANUAL_FOLLOW_UP'
  | 'LAST_DISMISSAL'
  | 'LAST_PURCHASE'
  | 'NONE';

export interface QueueItemResponse {
  customerId: string;
  displayName: string;
  primaryPhone: string | null;
  status: FollowUpStatus;
  reasons: FollowUpReason[];
  dueDate: string;
  timingSource: FollowUpTimingSource;
  policyVersion: number;
  effectiveCadenceDays: number;
  lastPurchaseAt: string | null;
  lastManualFollowUpDate: string | null;
  lastDismissedDate: string | null;
  evaluatedAt: string;
}

export interface FollowUpQueuePageResponse {
  items: QueueItemResponse[];
  nextCursor: string | null;
}

export interface EvaluationResponse {
  customerId: string;
  eligible: boolean;
  status: FollowUpStatus;
  reasons: FollowUpReason[];
  evaluatedAt: string;
  tenantDate: string;
  tenantTimeZone: string;
  nextFollowUpDate: string | null;
  timingSource: FollowUpTimingSource;
  effectiveCadenceDays: number;
  lastPurchaseAt: string | null;
}

export interface CustomerPolicyResponse {
  customerId: string;
  cadenceDays: number | null;
  explicitNextDate: string | null;
  snoozedUntil: string | null;
  lastManualFollowUpDate: string | null;
  lastDismissedDate: string | null;
}

export interface ManualFollowUpResponse {
  id: string;
  customerId: string;
  completedOn: string;
  policyVersion: number;
  notes: string | null;
}

export interface DismissalResponse {
  id: string;
  customerId: string;
  dismissedOn: string;
  policyVersion: number;
  notes: string | null;
}

export interface TenantPolicyResponse {
  cadenceDays: number;
  timeZone: string;
}
