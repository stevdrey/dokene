// Consumer side of the frozen integration v1 contract (ADR 0023 §3.3, ADR 0024 §1). Mirrors
// backend integration.api.IntegrationApiContract; pinned by contracts/messaging/v1/integration.json.

export interface IntegrationRequest {
  phoneNumberId: string;
  businessAccountId: string;
  displayPhoneNumber?: string | null;
  enabled: boolean;
  secretRef: string;
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
