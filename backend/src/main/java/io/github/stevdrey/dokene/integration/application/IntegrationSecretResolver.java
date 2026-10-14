// ABOUTME: Port resolving a scoped secret reference into secret material inside the integration module (ADR 0024 §3).
// ABOUTME: Secret values never leave the adapter: no business table, log, audit row, HTTP response or messaging type.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public interface IntegrationSecretResolver {
    /**
     * Resolves {@code reference} inside {@code scope} only: a tenant scope never reaches a deployment secret or another
     * tenant's secret, whatever key the tenant stored. Throws when unresolvable; there is no default or empty secret
     * (fail closed). The caller closes the result.
     */
    ResolvedSecret resolve(SecretScope scope, SecretReference reference);

    /** The namespace a reference is resolved in. */
    sealed interface SecretScope permits Tenant, Deployment {
    }

    /** References stored in a tenant's integration configuration (tenant-supplied, so tenant-confined). */
    record Tenant(TenantId tenantId) implements SecretScope {
        public Tenant {
            Objects.requireNonNull(tenantId, "Tenant id is required");
        }
    }

    /** References read from deployment configuration properties only, never from tenant input. */
    record Deployment() implements SecretScope {
    }

    /** A bare key within a scope: no path, scheme or separator, so it cannot escape its namespace. */
    record SecretReference(String value) {
        public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

        public SecretReference {
            Objects.requireNonNull(value, "Secret reference is required");
            if (!FORMAT.matcher(value).matches()) {
                throw new IllegalArgumentException("Secret reference is malformed");
            }
        }
    }

    /**
     * Secret material held as characters. The instance owns its buffer: the constructor wipes the caller's array.
     * Consumers read it only through {@link #withValue}, which hands out a copy and wipes that copy when the callback
     * returns or throws; {@link #close()} wipes the held array.
     */
    final class ResolvedSecret implements AutoCloseable {
        private final char[] value;
        private boolean closed;

        /** Copies then zero-fills {@code value}, so the plaintext lives in exactly one place. */
        public ResolvedSecret(char[] value) {
            Objects.requireNonNull(value, "Secret value is required");
            if (value.length == 0) {
                throw new IllegalArgumentException("Secret value is empty");
            }
            this.value = value.clone();
            Arrays.fill(value, '\0');
        }

        /**
         * Pass the characters straight to the consuming API. Do not keep the array, and do not build a {@code String}
         * unless that API accepts nothing else; then keep it local to the call, since a String cannot be wiped.
         */
        public synchronized <T> T withValue(Function<char[], T> use) {
            Objects.requireNonNull(use, "Callback is required");
            if (closed) {
                throw new IllegalStateException("Secret already closed");
            }
            char[] copy = value.clone();
            try {
                return use.apply(copy);
            } finally {
                Arrays.fill(copy, '\0');
            }
        }

        @Override
        public synchronized void close() {
            Arrays.fill(value, '\0');
            closed = true;
        }

        @Override
        public String toString() {
            return "ResolvedSecret[REDACTED]";
        }
    }
}
