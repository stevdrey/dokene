import type { SemanticTemplateIntent } from '../../followups/types';

// Consumer side of the frozen integration v1 contract (ADR 0023 §3.3, ADR 0024 §1). Mirrors
// backend integration.api.IntegrationApiContract; pinned by contracts/messaging/v1/integration.json.

export interface IntegrationRequest {
  phoneNumberId: string;
  businessAccountId: string;
  displayPhoneNumber?: string | null;
  enabled: boolean;
  secretRef: string;
}

// Body of PUT /api/integrations/whatsapp/outbound, the tenant outbound kill switch (spec 0008).
export interface OutboundRequest {
  enabled: boolean;
}

export interface IntegrationResponse {
  enabled: boolean;
  outboundEnabled: boolean;
  phoneNumberId: string;
  businessAccountId: string;
  displayPhoneNumber: string | null;
  secretRef: string;
  webhookVerified: boolean;
  version: number;
}

// Consumer side of the frozen template registry v1 contract (ADR 0023 §3.2, ADR 0025). Mirrors backend
// template.api.TemplateApiContract; pinned by contracts/messaging/v1/templates.json.
export const TEMPLATE_APPROVAL_STATUSES = [
  'UNVERIFIED',
  'APPROVED',
  'PENDING',
  'REJECTED',
  'PAUSED',
  'DISABLED',
  'DELETED',
  'NOT_FOUND',
  'SCHEMA_MISMATCH',
  'SCHEMA_UNSUPPORTED',
  'SCHEMA_UNREADABLE'
] as const;
export type TemplateApprovalStatus = (typeof TEMPLATE_APPROVAL_STATUSES)[number];

// Approval state is never part of a request: only the provider verification path records it.
export interface TemplateRequest {
  providerTemplateName: string;
  language: string;
  parameterNames: string[];
  enabled: boolean;
}

export interface TemplateResponse {
  templateIntent: SemanticTemplateIntent;
  mapped: boolean;
  enabled: boolean;
  providerTemplateName: string | null;
  language: string | null;
  parameterNames: string[];
  approvalStatus: TemplateApprovalStatus;
  approvalCheckedAt: string | null;
  sendable: boolean;
  version: number | null;
}

export interface TemplateListResponse {
  items: TemplateResponse[];
}
