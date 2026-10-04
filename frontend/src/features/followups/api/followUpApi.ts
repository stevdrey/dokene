import { httpClient } from '@/shared/api/httpClient';
import {
  FollowUpQueuePageResponse,
  FollowUpStatus,
  EvaluationResponse,
  CustomerPolicyResponse,
  ManualFollowUpResponse,
  DismissalResponse,
  TenantPolicyResponse,
  RecommendationResponse,
  DraftResponse,
  DraftRequestParams,
  SEMANTIC_ACTIONS,
  SEMANTIC_TEMPLATE_INTENTS,
  DRAFT_BODY_MAX_CODE_POINTS
} from '@/features/followups/types';

export interface FollowUpQueueParams {
  status?: FollowUpStatus;
  cursor?: string;
  limit?: number;
}

export class InvalidAiResponseError extends Error {
  constructor() {
    super('La respuesta del asistente IA no tiene el formato esperado.');
    this.name = 'InvalidAiResponseError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isOneOf(allowed: readonly string[], value: unknown): boolean {
  return typeof value === 'string' && allowed.includes(value);
}

function isStringArray(value: unknown): boolean {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

function hasKnownStatus(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return (
    typeof value.status === 'string' &&
    allowed.includes(value.status) &&
    typeof value.retryable === 'boolean'
  );
}

function isValidDraftBody(body: unknown): boolean {
  return (
    typeof body === 'string' &&
    body.trim() !== '' &&
    [...body].length <= DRAFT_BODY_MAX_CODE_POINTS
  );
}

const CONTENT_STATUSES: readonly string[] = ['AVAILABLE', 'NO_RECOMMENDATION', 'NO_DRAFT'];

/**
 * A valid-looking response for another customer must never reach the UI. Content-bearing
 * results must name the requested customer; the other statuses may omit it (the backend
 * returns null when there is no evaluation) but can never name a different one.
 */
function assertSameCustomer(
  data: RecommendationResponse | DraftResponse,
  requestedCustomerId: string
): void {
  const expected = requestedCustomerId.toLowerCase();
  const matches = (candidate: unknown) =>
    typeof candidate === 'string' && candidate.toLowerCase() === expected;

  if (data.customerId !== null && data.customerId !== undefined && !matches(data.customerId)) {
    throw new InvalidAiResponseError();
  }
  if (CONTENT_STATUSES.includes(data.status) && !matches(data.customerId)) {
    throw new InvalidAiResponseError();
  }
  if (data.evaluation && !matches(data.evaluation.customerId)) {
    throw new InvalidAiResponseError();
  }
}

function isRecommendationResponse(value: unknown): value is RecommendationResponse {
  if (!isRecord(value)) return false;
  if (!hasKnownStatus(value, ['AVAILABLE', 'NO_RECOMMENDATION', 'INELIGIBLE', 'STALE_STATE', 'AI_UNAVAILABLE'])) {
    return false;
  }
  if (value.status === 'AVAILABLE') {
    const rec = value.recommendation;
    return (
      isRecord(rec) &&
      isOneOf(SEMANTIC_ACTIONS, rec.action) &&
      isOneOf(SEMANTIC_TEMPLATE_INTENTS, rec.templateIntent) &&
      typeof rec.rationale === 'string' &&
      typeof rec.confidence === 'number'
    );
  }
  if (value.status === 'NO_RECOMMENDATION') {
    return isRecord(value.refusal) && typeof value.refusal.rationale === 'string';
  }
  return true;
}

function isDraftResponse(value: unknown): value is DraftResponse {
  if (!isRecord(value)) return false;
  if (!hasKnownStatus(value, ['AVAILABLE', 'NO_DRAFT', 'INELIGIBLE', 'STALE_STATE', 'AI_UNAVAILABLE'])) {
    return false;
  }
  if (value.status === 'AVAILABLE') {
    const draft = value.draft;
    return (
      isRecord(draft) &&
      isOneOf(SEMANTIC_ACTIONS, draft.action) &&
      isOneOf(SEMANTIC_TEMPLATE_INTENTS, draft.templateIntent) &&
      isValidDraftBody(draft.body) &&
      isStringArray(draft.evidence) &&
      isStringArray(draft.warnings)
    );
  }
  if (value.status === 'NO_DRAFT') {
    return isRecord(value.refusal) && typeof value.refusal.rationale === 'string';
  }
  return true;
}

export const followUpApi = {
  async getFollowUpQueue(
    params: FollowUpQueueParams = {},
    signal?: AbortSignal
  ): Promise<FollowUpQueuePageResponse> {
    const searchParams = new URLSearchParams();
    if (params.status) {
      searchParams.set('status', params.status);
    }
    if (params.cursor) {
      searchParams.set('cursor', params.cursor);
    }
    if (params.limit) {
      searchParams.set('limit', String(params.limit));
    }

    const qs = searchParams.toString();
    const endpoint = `/api/follow-up-queue${qs ? `?${qs}` : ''}`;
    const { data } = await httpClient.request<FollowUpQueuePageResponse>(endpoint, {
      method: 'GET',
      signal
    });
    return data;
  },

  async getCustomerFollowUpPolicy(
    customerId: string,
    signal?: AbortSignal
  ): Promise<{ policy: CustomerPolicyResponse; version: number }> {
    const { data, etag } = await httpClient.request<CustomerPolicyResponse>(
      `/api/customers/${customerId}/follow-up-policy`,
      { method: 'GET', signal }
    );
    const version = etag ? parseVersionFromEtag(etag) : 0;
    return { policy: data, version };
  },

  async getFollowUpEligibility(
    customerId: string,
    signal?: AbortSignal
  ): Promise<EvaluationResponse> {
    const { data } = await httpClient.request<EvaluationResponse>(
      `/api/customers/${customerId}/follow-up-eligibility`,
      { method: 'GET', signal }
    );
    return data;
  },

  async snoozeFollowUp(
    customerId: string,
    until: string,
    version: number
  ): Promise<{ policy: CustomerPolicyResponse; version: number }> {
    const { data, etag } = await httpClient.request<CustomerPolicyResponse>(
      `/api/customers/${customerId}/follow-up-snooze`,
      {
        method: 'PUT',
        ifMatch: version,
        body: { until }
      }
    );
    const updatedVersion = etag ? parseVersionFromEtag(etag) : version;
    return { policy: data, version: updatedVersion };
  },

  async dismissFollowUp(
    customerId: string,
    version: number,
    idempotencyKey: string,
    notes?: string
  ): Promise<{ dismissal: DismissalResponse; version: number }> {
    const body = notes !== undefined && notes !== null && notes.trim() !== ''
      ? { notes: notes.trim() }
      : {};

    const { data, etag } = await httpClient.request<DismissalResponse>(
      `/api/customers/${customerId}/follow-up-dismissals`,
      {
        method: 'POST',
        ifMatch: version,
        idempotencyKey,
        body
      }
    );
    const updatedVersion = etag ? parseVersionFromEtag(etag) : data.policyVersion;
    return { dismissal: data, version: updatedVersion };
  },

  async recordManualFollowUp(
    customerId: string,
    version: number,
    idempotencyKey: string,
    notes?: string
  ): Promise<{ completion: ManualFollowUpResponse; version: number }> {
    const body = notes !== undefined && notes !== null && notes.trim() !== ''
      ? { notes: notes.trim() }
      : {};

    const { data, etag } = await httpClient.request<ManualFollowUpResponse>(
      `/api/customers/${customerId}/manual-follow-ups`,
      {
        method: 'POST',
        ifMatch: version,
        idempotencyKey,
        body
      }
    );
    const updatedVersion = etag ? parseVersionFromEtag(etag) : data.policyVersion;
    return { completion: data, version: updatedVersion };
  },

  async requestRecommendation(
    customerId: string,
    version: number,
    signal?: AbortSignal
  ): Promise<{ data: RecommendationResponse; version: number }> {
    const { data, etag } = await httpClient.request<unknown>(
      `/api/customers/${customerId}/recommendation`,
      { method: 'POST', ifMatch: version, signal }
    );
    if (!isRecommendationResponse(data)) {
      throw new InvalidAiResponseError();
    }
    assertSameCustomer(data, customerId);
    return { data, version: etag ? parseVersionFromEtag(etag) : version };
  },

  async requestDraft(
    customerId: string,
    params: DraftRequestParams,
    version: number,
    signal?: AbortSignal
  ): Promise<{ data: DraftResponse; version: number }> {
    const { data, etag } = await httpClient.request<unknown>(
      `/api/customers/${customerId}/draft`,
      {
        method: 'POST',
        ifMatch: version,
        body: { action: params.action, templateIntent: params.templateIntent },
        signal
      }
    );
    if (!isDraftResponse(data)) {
      throw new InvalidAiResponseError();
    }
    assertSameCustomer(data, customerId);
    return { data, version: etag ? parseVersionFromEtag(etag) : version };
  },

  async getTenantFollowUpPolicy(
    signal?: AbortSignal
  ): Promise<{ policy: TenantPolicyResponse; version: number }> {
    const { data, etag } = await httpClient.request<TenantPolicyResponse>(
      '/api/follow-up-policy',
      { method: 'GET', signal }
    );
    const version = etag ? parseVersionFromEtag(etag) : 0;
    return { policy: data, version };
  }
};

function parseVersionFromEtag(etag: string): number {
  const clean = etag.replace(/"/g, '').trim();
  const parsed = parseInt(clean, 10);
  return isNaN(parsed) ? 0 : parsed;
}
