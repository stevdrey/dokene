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

export type RecommendationStatus =
  | 'AVAILABLE'
  | 'NO_RECOMMENDATION'
  | 'INELIGIBLE'
  | 'STALE_STATE'
  | 'AI_UNAVAILABLE';

export type DraftStatus = 'AVAILABLE' | 'NO_DRAFT' | 'INELIGIBLE' | 'STALE_STATE' | 'AI_UNAVAILABLE';

export const SEMANTIC_ACTIONS = [
  'REPEAT_PURCHASE_FOLLOW_UP',
  'GENERAL_CHECK_IN',
  'RELATED_PRODUCT_OFFER',
  'DORMANT_REENGAGEMENT',
  'SEASONAL_GREETING'
] as const;
export type SemanticAction = (typeof SEMANTIC_ACTIONS)[number];

export const SEMANTIC_TEMPLATE_INTENTS = [
  'GENERAL_FOLLOW_UP',
  'REPEAT_PURCHASE',
  'RELATED_PRODUCT',
  'SEASONAL_EVENT',
  'DORMANT_CUSTOMER'
] as const;
export type SemanticTemplateIntent = (typeof SEMANTIC_TEMPLATE_INTENTS)[number];

/** Backend bound for a draft body, counted in Unicode code points. */
export const DRAFT_BODY_MAX_CODE_POINTS = 1000;

export type NoRecommendationReason =
  | 'INSUFFICIENT_HISTORY'
  | 'RECENTLY_CONTACTED'
  | 'NO_RELEVANT_OFFER'
  | 'UNCERTAIN_INTENT'
  | 'MANUAL_REVIEW_REQUIRED';

export type NoDraftReason =
  | 'INSUFFICIENT_HISTORY'
  | 'UNSUPPORTED_ACTION'
  | 'MISSING_TRUSTED_FACTS'
  | 'SAFETY_VIOLATION'
  | 'MANUAL_REVIEW_REQUIRED';

export type ActionGateRejectionReason =
  | 'NO_TENANT_CONTEXT'
  | 'UNAUTHORIZED'
  | 'CUSTOMER_NOT_FOUND'
  | 'CUSTOMER_ARCHIVED'
  | 'DO_NOT_CONTACT'
  | 'NO_CONTACT_CONSENT'
  | 'FOLLOW_UP_INELIGIBLE'
  | 'STALE_STATE'
  | 'DISALLOWED_ACTION'
  | 'DISALLOWED_TEMPLATE_INTENT'
  | 'INVALID_RECOMMENDATION';

export type AiUnavailableReason =
  | 'TIMEOUT'
  | 'THROTTLED'
  | 'UNAVAILABLE'
  | 'INVALID_STRUCTURED_RESPONSE'
  | 'REJECTED_REQUEST'
  | 'CANCELLED'
  | 'NOT_AVAILABLE'
  | 'REFUSED'
  | 'DISALLOWED_ACTION'
  | 'DISALLOWED_TEMPLATE_INTENT'
  | 'INVALID_RECOMMENDATION'
  | 'CONTEXT_TOO_LARGE'
  | 'CONTEXT_UNSUPPORTED';

export interface DraftVariable {
  key: string;
  value: string;
}

export interface ActionRecommendation {
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  rationale: string;
  confidence: number;
  draftVariables: DraftVariable[];
}

export interface RecommendationRefusal {
  reason: NoRecommendationReason;
  rationale: string;
  confidence: number;
}

export interface RecommendationResponse {
  status: RecommendationStatus;
  customerId: string | null;
  evaluation: EvaluationResponse | null;
  recommendation: ActionRecommendation | null;
  refusal: RecommendationRefusal | null;
  refusalReason: NoRecommendationReason | null;
  rejectionReason: ActionGateRejectionReason | null;
  unavailableReason: AiUnavailableReason | null;
  retryable: boolean;
}

export interface MessageDraft {
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  body: string;
  draftVariables: DraftVariable[];
  locale: string;
  evidence: string[];
  warnings: string[];
  rationale: string;
  confidence: number;
}

export interface DraftRefusal {
  reason: NoDraftReason;
  rationale: string;
  confidence: number;
}

export interface DraftResponse {
  status: DraftStatus;
  customerId: string | null;
  evaluation: EvaluationResponse | null;
  draft: MessageDraft | null;
  refusal: DraftRefusal | null;
  refusalReason: NoDraftReason | null;
  rejectionReason: ActionGateRejectionReason | null;
  unavailableReason: AiUnavailableReason | null;
  retryable: boolean;
}

export interface DraftRequestParams {
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
}
