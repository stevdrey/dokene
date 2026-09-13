export type CustomerStatus = 'ACTIVE' | 'ARCHIVED';

export interface PhoneResponse {
  id: string;
  e164: string;
  primary: boolean;
}

export interface CustomerResponse {
  id: string;
  displayName: string;
  notes: string | null;
  phones: PhoneResponse[];
  status: CustomerStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
}

export interface CustomerPageResponse {
  customers: CustomerResponse[];
  nextCursor: string | null;
}

export interface PhoneRequest {
  number: string;
  region: string;
  primary: boolean;
}

export interface CustomerWriteRequest {
  displayName: string;
  notes: string | null;
  phones: PhoneRequest[];
  version?: number;
}

/* Contact Policy & Consent */
export type ContactChannel = 'WHATSAPP';
export type ConsentStatus = 'UNKNOWN' | 'GRANTED' | 'REVOKED';
export type ContactIntentSource = 'CUSTOMER_VERBAL' | 'CUSTOMER_WRITTEN' | 'OPERATOR_CORRECTION';
export type ContactEligibilityReason =
  | 'DO_NOT_CONTACT'
  | 'CONSENT_UNKNOWN'
  | 'CONSENT_REVOKED'
  | 'CONTACT_NOT_ACTIVE'
  | 'CUSTOMER_ARCHIVED';

export interface ConsentResponse {
  contactId: string;
  channel: ContactChannel;
  status: ConsentStatus;
  source: ContactIntentSource;
  changedAt: string;
}

export interface ContactPolicyResponse {
  customerId: string;
  version: number;
  doNotContact: boolean;
  doNotContactSource: ContactIntentSource | null;
  doNotContactChangedAt: string | null;
  consents: ConsentResponse[];
}

export interface EligibilityResponse {
  eligible: boolean;
  reasons: ContactEligibilityReason[];
}

export interface PolicyEventResponse {
  id: string;
  type: string;
  contactId: string;
  channel: ContactChannel;
  consentStatus: ConsentStatus;
  doNotContact: boolean | null;
  source: ContactIntentSource;
  occurredAt: string;
  actorId: string;
  membershipId: string;
  policyVersion: number;
}

export interface PolicyHistoryResponse {
  events: PolicyEventResponse[];
  nextCursor: string | null;
}

/* Purchases */
export type PurchaseStatus = 'VALID' | 'VOID';

export interface PurchaseResponse {
  id: string;
  customerId: string;
  purchasedAt: string;
  description: string;
  status: PurchaseStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  voidedAt: string | null;
}

export interface PurchasePageResponse {
  purchases: PurchaseResponse[];
  nextCursor: string | null;
}

export interface PurchaseRequest {
  purchasedAt: string;
  description: string;
}

export interface PurchaseEventResponse {
  id: string;
  type: string;
  purchasedAt: string;
  description: string;
  occurredAt: string;
  actorId: string;
  membershipId: string;
  purchaseVersion: number;
}

export interface PurchaseHistoryResponse {
  events: PurchaseEventResponse[];
  nextCursor: string | null;
}
