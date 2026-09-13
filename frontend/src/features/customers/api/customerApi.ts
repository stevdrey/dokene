import { httpClient } from '@/shared/api/httpClient';
import {
  CustomerResponse,
  CustomerPageResponse,
  CustomerWriteRequest,
  CustomerStatus,
  ContactPolicyResponse,
  ContactChannel,
  ConsentStatus,
  ContactIntentSource,
  EligibilityResponse,
  PolicyHistoryResponse,
  PurchaseResponse,
  PurchasePageResponse,
  PurchaseRequest,
  PurchaseHistoryResponse,
  PurchaseStatus
} from '@/features/customers/types';

export interface ListCustomersParams {
  status?: CustomerStatus | 'ALL';
  name?: string;
  phone?: string;
  region?: string;
  cursor?: string;
  limit?: number;
}

export const customerApi = {
  // Customer CRUD & Search
  async listCustomers(params: ListCustomersParams = {}): Promise<CustomerPageResponse> {
    const searchParams = new URLSearchParams();
    if (params.status) searchParams.set('status', params.status);
    if (params.name && params.name.trim()) searchParams.set('name', params.name.trim());
    if (params.phone && params.phone.trim()) {
      searchParams.set('phone', params.phone.trim());
      if (params.region) searchParams.set('region', params.region.trim());
    }
    if (params.cursor) searchParams.set('cursor', params.cursor);
    if (params.limit) searchParams.set('limit', String(params.limit));

    const qs = searchParams.toString();
    const endpoint = `/api/customers${qs ? `?${qs}` : ''}`;
    const { data } = await httpClient.request<CustomerPageResponse>(endpoint, { method: 'GET' });
    return data;
  },

  async getCustomer(customerId: string): Promise<{ customer: CustomerResponse; version: number }> {
    const { data, etag } = await httpClient.request<CustomerResponse>(`/api/customers/${customerId}`, {
      method: 'GET'
    });
    const version = data.version ?? (etag ? parseVersionFromEtag(etag) : 0);
    return { customer: data, version };
  },

  async createCustomer(request: CustomerWriteRequest): Promise<CustomerResponse> {
    const { data } = await httpClient.request<CustomerResponse>('/api/customers', {
      method: 'POST',
      body: request
    });
    return data;
  },

  async updateCustomer(
    customerId: string,
    version: number,
    request: CustomerWriteRequest
  ): Promise<CustomerResponse> {
    const { data } = await httpClient.request<CustomerResponse>(`/api/customers/${customerId}`, {
      method: 'PUT',
      ifMatch: version,
      body: { ...request, version }
    });
    return data;
  },

  async archiveCustomer(customerId: string, version: number): Promise<void> {
    await httpClient.request<void>(`/api/customers/${customerId}`, {
      method: 'DELETE',
      ifMatch: version
    });
  },

  // Contact Policy & Consent
  async getContactPolicy(customerId: string): Promise<{ policy: ContactPolicyResponse; version: number }> {
    const { data, etag } = await httpClient.request<ContactPolicyResponse>(
      `/api/customers/${customerId}/contact-policy`,
      { method: 'GET' }
    );
    const version = data.version ?? (etag ? parseVersionFromEtag(etag) : 0);
    return { policy: data, version };
  },

  async changeConsent(
    customerId: string,
    contactId: string,
    channel: ContactChannel,
    version: number,
    status: Exclude<ConsentStatus, 'UNKNOWN'>,
    source: ContactIntentSource
  ): Promise<ContactPolicyResponse> {
    const { data } = await httpClient.request<ContactPolicyResponse>(
      `/api/customers/${customerId}/contacts/${contactId}/consents/${channel}`,
      {
        method: 'PUT',
        ifMatch: version,
        body: { status, source }
      }
    );
    return data;
  },

  async changeDoNotContact(
    customerId: string,
    version: number,
    enabled: boolean,
    source: ContactIntentSource
  ): Promise<ContactPolicyResponse> {
    const { data } = await httpClient.request<ContactPolicyResponse>(
      `/api/customers/${customerId}/do-not-contact`,
      {
        method: 'PUT',
        ifMatch: version,
        body: { enabled, source }
      }
    );
    return data;
  },

  async getContactEligibility(
    customerId: string,
    channel: ContactChannel,
    contactId: string
  ): Promise<EligibilityResponse> {
    const { data } = await httpClient.request<EligibilityResponse>(
      `/api/customers/${customerId}/contact-eligibility?channel=${encodeURIComponent(
        channel
      )}&contactId=${encodeURIComponent(contactId)}`,
      { method: 'GET' }
    );
    return data;
  },

  async getContactPolicyHistory(
    customerId: string,
    cursor?: string,
    limit = 50
  ): Promise<PolicyHistoryResponse> {
    const searchParams = new URLSearchParams();
    if (cursor) searchParams.set('cursor', cursor);
    if (limit) searchParams.set('limit', String(limit));
    const qs = searchParams.toString();
    const endpoint = `/api/customers/${customerId}/contact-policy/history${qs ? `?${qs}` : ''}`;
    const { data } = await httpClient.request<PolicyHistoryResponse>(endpoint, { method: 'GET' });
    return data;
  },

  // Purchases
  async listPurchases(
    customerId: string,
    status?: PurchaseStatus,
    cursor?: string,
    limit = 50
  ): Promise<PurchasePageResponse> {
    const searchParams = new URLSearchParams();
    if (status) searchParams.set('status', status);
    if (cursor) searchParams.set('cursor', cursor);
    if (limit) searchParams.set('limit', String(limit));
    const qs = searchParams.toString();
    const endpoint = `/api/customers/${customerId}/purchases${qs ? `?${qs}` : ''}`;
    const { data } = await httpClient.request<PurchasePageResponse>(endpoint, { method: 'GET' });
    return data;
  },

  async getLastPurchase(customerId: string): Promise<PurchaseResponse | null> {
    try {
      const { data } = await httpClient.request<PurchaseResponse>(
        `/api/customers/${customerId}/purchases/last`,
        { method: 'GET' }
      );
      return data || null;
    } catch (err: unknown) {
      // 204 No Content is converted to undefined/null by httpClient
      return null;
    }
  },

  async recordPurchase(
    customerId: string,
    idempotencyKey: string,
    request: PurchaseRequest
  ): Promise<PurchaseResponse> {
    const { data } = await httpClient.request<PurchaseResponse>(
      `/api/customers/${customerId}/purchases`,
      {
        method: 'POST',
        idempotencyKey,
        body: request
      }
    );
    return data;
  },

  async correctPurchase(
    customerId: string,
    purchaseId: string,
    version: number,
    request: PurchaseRequest
  ): Promise<PurchaseResponse> {
    const { data } = await httpClient.request<PurchaseResponse>(
      `/api/customers/${customerId}/purchases/${purchaseId}`,
      {
        method: 'PUT',
        ifMatch: version,
        body: request
      }
    );
    return data;
  },

  async voidPurchase(customerId: string, purchaseId: string, version: number): Promise<void> {
    await httpClient.request<void>(
      `/api/customers/${customerId}/purchases/${purchaseId}`,
      {
        method: 'DELETE',
        ifMatch: version
      }
    );
  },

  async getPurchaseHistory(
    customerId: string,
    purchaseId: string,
    cursor?: string,
    limit = 50
  ): Promise<PurchaseHistoryResponse> {
    const searchParams = new URLSearchParams();
    if (cursor) searchParams.set('cursor', cursor);
    if (limit) searchParams.set('limit', String(limit));
    const qs = searchParams.toString();
    const endpoint = `/api/customers/${customerId}/purchases/${purchaseId}/history${qs ? `?${qs}` : ''}`;
    const { data } = await httpClient.request<PurchaseHistoryResponse>(endpoint, { method: 'GET' });
    return data;
  }
};

function parseVersionFromEtag(etag: string): number {
  const clean = etag.replace(/"/g, '').trim();
  const parsed = parseInt(clean, 10);
  return isNaN(parsed) ? 0 : parsed;
}
