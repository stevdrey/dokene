// ABOUTME: Raw approve or reject inputs; the decision is the service method called.
// ABOUTME: The note is stored on the approval row only. ifMatch is the parsed version, as digits.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

/**
 * {@code templateRevision} is the digest of the template preview the reviewer approved (issue 268). The HTTP contract
 * requires it for an approval; when present, approval refuses with {@code STALE_VERSION} unless the template it binds
 * is still exactly that revision.
 */
public record ApprovalCommand(UUID messageId, String note, String ifMatch, String idempotencyKey,
        String templateRevision) {
    /** A decision that names no template revision: a rejection, or an internal caller. */
    public ApprovalCommand(UUID messageId, String note, String ifMatch, String idempotencyKey) {
        this(messageId, note, ifMatch, idempotencyKey, null);
    }
}
