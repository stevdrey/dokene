import { httpClient } from '@/shared/api/httpClient';
import {
  FollowUpQueuePageResponse,
  FollowUpStatus,
  EvaluationResponse,
  CustomerPolicyResponse,
  ManualFollowUpResponse,
  DismissalResponse,
  TenantPolicyResponse
} from '@/features/followups/types';

export interface FollowUpQueueParams {
  status?: FollowUpStatus;
  cursor?: string;
  limit?: number;
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
