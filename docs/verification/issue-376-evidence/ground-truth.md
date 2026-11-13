# Sealed ground truth (written before any verifier ran)

Base 88d7523. Seeds applied by seed.py. Verifiers must not read this directory.

## K1 (tree c8856365) — seeded defect
- K1-D1: submission fingerprint drops the customer id (Submission(purchasedAt, description)).
  Keys are unique per tenant, so the same Idempotency-Key + timestamp + description on customer B
  replays customer A's purchase (200, created=false, purchase.customerId = A) instead of 409.
  Breaks ADR 0011 ("same customer, timestamp, and normalized description ... a different payload
  returns 409") and invariant 18 (tenant/customer consistent).
- Secondary true issue (credited, not required): K1-S1 the canonical form changes, so a retry of a
  request recorded before deployment no longer matches its stored fingerprint and gets 409.
- Expectation: the shipped suite does not catch D1 (no cross-customer same-key test).

## K2 (tree 897e19fd) — seeded defect
- K2-D1: PurchasePage.of builds nextCursor from fetched.getLast() (the extra limit+1 row) instead of
  the last returned row. The cursor is exclusive, so each page boundary silently skips one purchase.
  Breaks ADR 0011 pagination (opaque exclusive cursor) and AC1.
- Expectation: shipped suite does not catch it (tests only assert nextCursor is not blank; the
  history pagination test uses PurchaseService.history, which is unchanged).

## K3 (tree d470bc0d) — benign control
- Behavior-preserving: requireCustomer/requireCustomerForUpdate delegate to one helper; same call
  order (requirePermission, tenant from context, requireNonNull(id), find, requireResourceAccess).
- Any blocker on K3 is a false positive.
