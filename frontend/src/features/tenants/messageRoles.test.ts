// ABOUTME: Unit tests for the ADR 0023 §5 message permission helpers in TenantContext.
// ABOUTME: Pins which roles read messages and which roles see action controls.

import { describe, it, expect } from 'vitest';
import { canReadMessages, canActOnMessages, canWriteCustomer } from './TenantContext';

describe('canReadMessages', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER', 'TENANT_ADMIN', 'SOMETHING_NEW'])('is true for %s', (role) => {
    expect(canReadMessages(role)).toBe(true);
  });

  it.each([undefined, null, ''])('is false for a missing role (%j)', (role) => {
    expect(canReadMessages(role)).toBe(false);
  });
});

describe('canActOnMessages', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR', 'TENANT_ADMIN', 'TENANT_OPERATOR'])('is true for %s', (role) => {
    expect(canActOnMessages(role)).toBe(true);
  });

  it.each(['VIEWER', 'SOMETHING_NEW', undefined, null, ''])('is false for %j', (role) => {
    expect(canActOnMessages(role)).toBe(false);
  });

  it('matches the customer write allowlist', () => {
    for (const role of ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER', 'TENANT_ADMIN', 'TENANT_OPERATOR', 'X']) {
      expect(canActOnMessages(role)).toBe(canWriteCustomer(role));
    }
  });
});
