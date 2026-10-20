// ABOUTME: Reads and writes the workspace WhatsApp integration and template list over the frozen v1 contract.
// ABOUTME: Narrows every response at runtime and drops members the contract does not name.
import { httpClient } from '@/shared/api/httpClient';
import { SEMANTIC_TEMPLATE_INTENTS } from '@/features/followups/types';
import {
  TEMPLATE_APPROVAL_STATUSES,
  type IntegrationRequest,
  type IntegrationResponse,
  type TemplateListResponse,
  type TemplateResponse
} from '../types';

export class InvalidIntegrationResponseError extends Error {
  constructor() {
    super('La respuesta del servidor sobre la integración no tiene el formato esperado.');
    this.name = 'InvalidIntegrationResponseError';
  }
}

/** Exactly one precondition header per write: `If-None-Match: *` to create, `If-Match` to replace. */
export type SavePrecondition = { kind: 'create' } | { kind: 'version'; etag: string };

export interface IntegrationRead {
  record: IntegrationResponse;
  etag: string;
}

const INTEGRATION_PATH = '/api/integrations/whatsapp';
const STRONG_NUMERIC_ETAG = /^"(0|[1-9][0-9]*)"$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean';
}

function isVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isOneOf<T extends string>(allowed: readonly T[], value: unknown): value is T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value);
}

function strongEtag(etag: string | null): string {
  if (etag === null || !STRONG_NUMERIC_ETAG.test(etag)) {
    throw new InvalidIntegrationResponseError();
  }
  return etag;
}

function narrowIntegration(body: unknown): IntegrationResponse {
  if (!isRecord(body)) throw new InvalidIntegrationResponseError();
  const {
    enabled,
    outboundEnabled,
    phoneNumberId,
    businessAccountId,
    displayPhoneNumber,
    secretRef,
    webhookVerified,
    version
  } = body;
  if (
    !isBoolean(enabled) ||
    !isBoolean(outboundEnabled) ||
    !isString(phoneNumberId) ||
    !isString(businessAccountId) ||
    !isNullableString(displayPhoneNumber) ||
    !isString(secretRef) ||
    !isBoolean(webhookVerified) ||
    !isVersion(version)
  ) {
    throw new InvalidIntegrationResponseError();
  }
  return {
    enabled,
    outboundEnabled,
    phoneNumberId,
    businessAccountId,
    displayPhoneNumber,
    secretRef,
    webhookVerified,
    version
  };
}

function narrowTemplate(item: unknown): TemplateResponse {
  if (!isRecord(item)) throw new InvalidIntegrationResponseError();
  const {
    templateIntent,
    mapped,
    enabled,
    providerTemplateName,
    language,
    parameterNames,
    approvalStatus,
    approvalCheckedAt,
    sendable,
    version
  } = item;
  if (
    !isOneOf(SEMANTIC_TEMPLATE_INTENTS, templateIntent) ||
    !isBoolean(mapped) ||
    !isBoolean(enabled) ||
    !isNullableString(providerTemplateName) ||
    !isNullableString(language) ||
    !Array.isArray(parameterNames) ||
    !parameterNames.every(isString) ||
    !isOneOf(TEMPLATE_APPROVAL_STATUSES, approvalStatus) ||
    !isNullableString(approvalCheckedAt) ||
    !isBoolean(sendable) ||
    !(version === null || isVersion(version))
  ) {
    throw new InvalidIntegrationResponseError();
  }
  return {
    templateIntent,
    mapped,
    enabled,
    providerTemplateName,
    language,
    parameterNames: [...parameterNames],
    approvalStatus,
    approvalCheckedAt,
    sendable,
    version
  };
}

function narrowTemplateList(body: unknown): TemplateListResponse {
  if (!isRecord(body) || !Array.isArray(body.items)) throw new InvalidIntegrationResponseError();
  return { items: body.items.map(narrowTemplate) };
}

function narrowRead(response: { data: unknown; etag: string | null }): IntegrationRead {
  return { record: narrowIntegration(response.data), etag: strongEtag(response.etag) };
}

// Built member by member so nothing beyond the five contract members can reach the wire.
function requestBody(request: IntegrationRequest): IntegrationRequest {
  const displayPhoneNumber = request.displayPhoneNumber;
  return {
    phoneNumberId: request.phoneNumberId,
    businessAccountId: request.businessAccountId,
    ...(displayPhoneNumber ? { displayPhoneNumber } : {}),
    enabled: request.enabled,
    secretRef: request.secretRef
  };
}

export const integrationApi = {
  async getIntegration(signal: AbortSignal): Promise<IntegrationRead> {
    return narrowRead(await httpClient.request<unknown>(INTEGRATION_PATH, { signal }));
  },

  async saveIntegration(
    request: IntegrationRequest,
    precondition: SavePrecondition,
    signal: AbortSignal
  ): Promise<IntegrationRead> {
    const response = await httpClient.request<unknown>(INTEGRATION_PATH, {
      method: 'PUT',
      body: requestBody(request),
      ...(precondition.kind === 'create'
        ? { headers: { 'If-None-Match': '*' } }
        : { ifMatch: precondition.etag }),
      signal
    });
    return narrowRead(response);
  },

  async verifyOwnership(etag: string, signal: AbortSignal): Promise<IntegrationRead> {
    const response = await httpClient.request<unknown>(`${INTEGRATION_PATH}/ownership-verifications`, {
      method: 'POST',
      ifMatch: etag,
      signal
    });
    return narrowRead(response);
  },

  async getTemplates(signal: AbortSignal): Promise<TemplateListResponse> {
    const response = await httpClient.request<unknown>('/api/templates', { signal });
    return narrowTemplateList(response.data);
  }
};
