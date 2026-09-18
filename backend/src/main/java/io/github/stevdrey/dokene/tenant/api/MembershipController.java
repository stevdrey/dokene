package io.github.stevdrey.dokene.tenant.api;

import io.github.stevdrey.dokene.tenant.application.MembershipNotFoundException;
import io.github.stevdrey.dokene.tenant.application.MembershipService;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memberships")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = Objects.requireNonNull(membershipService, "Membership service is required");
    }

    @GetMapping
    public List<MembershipResponse> listMemberships() {
        return membershipService.listMemberships().stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    public ResponseEntity<MembershipResponse> addMembership(@RequestBody AddMembershipRequest request) {
        if (request == null || request.identityId() == null) {
            throw new IllegalArgumentException("Identity ID is required");
        }
        if (request.role() == null) {
            throw new IllegalArgumentException("Tenant role is required");
        }
        TenantMembership membership = membershipService.addMembership(
                new IdentityId(request.identityId()),
                request.role()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(membership));
    }

    @PutMapping("/{identityId}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable UUID identityId,
            @RequestBody ChangeRoleRequest request
    ) {
        if (request == null || request.role() == null) {
            throw new IllegalArgumentException("Tenant role is required");
        }
        membershipService.changeRole(new IdentityId(identityId), request.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{identityId}")
    public ResponseEntity<Void> revokeMembership(@PathVariable UUID identityId) {
        membershipService.revokeMembership(new IdentityId(identityId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MembershipNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void handleMembershipNotFound() {
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void handleTenantAccessDenied() {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void handleIllegalArgument() {
    }

    @ExceptionHandler({IllegalStateException.class, OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    void handleConflict() {
    }

    private MembershipResponse toResponse(TenantMembership membership) {
        return new MembershipResponse(
                membership.id().value(),
                membership.tenantId().value(),
                membership.identityId().value(),
                membership.role().name(),
                membership.status().name(),
                membership.createdAt(),
                membership.updatedAt()
        );
    }

    public record AddMembershipRequest(UUID identityId, TenantRole role) {
    }

    public record ChangeRoleRequest(TenantRole role) {
    }

    public record MembershipResponse(
            UUID id,
            UUID tenantId,
            UUID identityId,
            String role,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
