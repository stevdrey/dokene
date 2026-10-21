// ABOUTME: Fake IntegrationSecretResolver that records every call and every secret it handed out (spec 0007 AC-5).
// ABOUTME: Lets a test assert the exact call count, scope and reference, and that each ResolvedSecret was closed.
package io.github.stevdrey.dokene.integration.testsupport;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RecordingSecretResolver implements IntegrationSecretResolver {
    public record Call(SecretScope scope, SecretReference reference) {
    }

    private record Key(SecretPurpose purpose, SecretReference reference) {
    }

    private final Map<Key, String> values = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<ResolvedSecret> issued = new ArrayList<>();
    private Category denial;
    private RuntimeException failure;

    /** Serves {@code value} for a deployment reference of {@code purpose}; anything else is {@code MISSING}. */
    public RecordingSecretResolver holding(SecretPurpose purpose, String ref, String value) {
        values.put(new Key(purpose, new SecretReference(ref)), Objects.requireNonNull(value));
        return this;
    }

    /** Every call is denied with {@code category}, after being recorded. */
    public RecordingSecretResolver denying(Category category) {
        this.denial = category;
        return this;
    }

    /** Every call throws {@code failure}, after being recorded. */
    public RecordingSecretResolver throwing(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public ResolvedSecret resolve(SecretScope scope, SecretReference reference) {
        calls.add(new Call(scope, reference));
        if (failure != null) {
            throw failure;
        }
        if (denial != null) {
            throw new SecretUnavailableException(denial, scope);
        }
        String value = values.get(new Key(scope.purpose(), reference));
        if (value == null) {
            throw new SecretUnavailableException(Category.MISSING, scope);
        }
        ResolvedSecret secret = new ResolvedSecret(value.toCharArray());
        issued.add(secret);
        return secret;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<ResolvedSecret> issued() {
        return List.copyOf(issued);
    }

    /** True when every secret handed out has been closed; a closed secret refuses {@code withValue}. */
    public boolean allIssuedClosed() {
        for (ResolvedSecret secret : issued) {
            try {
                secret.withValue(chars -> null);
                return false;
            } catch (IllegalStateException closed) {
                // expected
            }
        }
        return true;
    }
}
