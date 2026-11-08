// ABOUTME: The single transactional path of every membership change: locks the tenant, actor and target, re-decides the
// ABOUTME: actor's authority against its current membership, and audits a refusal after the rollback (issue 325).
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The request's {@link TenantContext} carries the role resolved when the request started. A membership change (an
 * invitation, a reactivation, a role change, a revocation) must instead be decided against the actor's membership as
 * it is when the change is written, so every one of them runs through {@code change}:
 *
 * <ol>
 *   <li>the request's own context is checked first, before any transaction, so a caller who never had the permission
 *       is refused without taking a lock;</li>
 *   <li>a transaction locks the tenant, the actor and the target
 *       ({@link TenantMembershipRepository#lockForMembershipChange}) and re-decides the actor's authority. A demotion
 *       or revocation that committed first makes this wait and then refuses; one that starts later waits for this
 *       change;</li>
 *   <li>a refusal rolls back first and is audited afterwards, so the audit's independent transaction never needs a
 *       second pooled connection while this one still holds its own and its locks.</li>
 * </ol>
 */
@Component
public class MembershipChangeAuthorizer {
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final CurrentMembershipAuthorizer currentMembers;
    private final AuthorizationAuditListener audits;
    private final TransactionOperations transactions;

    public MembershipChangeAuthorizer(TenantAuthorizationService authorization, TenantContextProvider contexts,
            TenantMembershipRepository memberships, CurrentMembershipAuthorizer currentMembers,
            AuthorizationAuditListener audits, TransactionOperations transactions) {
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Membership repository is required");
        this.currentMembers = Objects.requireNonNull(currentMembers, "Current membership authorizer is required");
        this.audits = Objects.requireNonNull(audits, "Authorization audit listener is required");
        this.transactions = Objects.requireNonNull(transactions, "Transaction operations are required");
    }

    /**
     * Runs {@code mutation} in a transaction, after the actor's current membership proved to hold {@code permission}
     * under the locks; otherwise the refusal is audited and {@link TenantAccessDeniedException} is thrown. The caller
     * must not already be in a transaction, or the refusal would be audited while that one holds its connection.
     */
    public <T> T change(TenantPermission permission, IdentityId target, Supplier<T> mutation) {
        authorization.requirePermission(permission);
        Objects.requireNonNull(target, "Target identity is required");
        Objects.requireNonNull(mutation, "Membership mutation is required");
        TenantContext request = contexts.requireCurrent();
        try {
            return transactions.execute(status -> {
                memberships.lockForMembershipChange(request.tenantId(), List.of(request.identityId(), target));
                currentMembers.denial(permission, request::tenantId).ifPresent(denial -> {
                    throw new RefusedUnderLock(denial);
                });
                return mutation.get();
            });
        } catch (RefusedUnderLock refused) {
            audits.onAuthorizationDenied(refused.denial);
            throw new TenantAccessDeniedException();
        }
    }

    /** {@link #change(TenantPermission, IdentityId, Supplier)} for a mutation with no result. */
    public void change(TenantPermission permission, IdentityId target, Runnable mutation) {
        Objects.requireNonNull(mutation, "Membership mutation is required");
        change(permission, target, () -> {
            mutation.run();
            return null;
        });
    }

    /** Carries the refusal out of the transaction so it rolls back before the denial is audited. */
    private static final class RefusedUnderLock extends RuntimeException {
        private final transient AuthorizationDeniedEvent denial;

        private RefusedUnderLock(AuthorizationDeniedEvent denial) {
            super(null, null, false, false);
            this.denial = denial;
        }
    }
}
