// ABOUTME: A 403 keeps its problem+json body on ForbiddenError, so the provisioning cap refusal can be told apart
// ABOUTME: (issue 312); a 403 without a body still yields a plain ForbiddenError.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient, ForbiddenError, isWorkspaceQuotaExceeded } from '../apiClient';

describe('apiClient 403 handling', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps the problem body of a 403 and recognises the workspace cap code', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({ type: 'about:blank', title: 'Forbidden', status: 403, code: 'WORKSPACE_QUOTA_EXCEEDED' }),
        { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': 'application/problem+json' } }
      )
    );

    const error = await apiClient.post('/api/tenants', { displayName: 'Acme' }).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ForbiddenError);
    expect((error as ForbiddenError).body).toEqual(expect.objectContaining({ code: 'WORKSPACE_QUOTA_EXCEEDED' }));
    expect(isWorkspaceQuotaExceeded(error)).toBe(true);
  });

  it('still throws a ForbiddenError for a 403 without a body', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 403, statusText: 'Forbidden' }));

    const error = await apiClient.post('/api/tenants', { displayName: 'Acme' }).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ForbiddenError);
    expect((error as ForbiddenError).body).toBeUndefined();
    expect(isWorkspaceQuotaExceeded(error)).toBe(false);
  });

  it('does not treat the policy refusal as the cap', () => {
    expect(isWorkspaceQuotaExceeded(new ForbiddenError('Forbidden', { code: 'PROVISIONING_NOT_PERMITTED' }))).toBe(false);
  });
});
