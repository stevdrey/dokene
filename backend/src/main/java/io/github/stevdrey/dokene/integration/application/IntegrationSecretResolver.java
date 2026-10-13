// ABOUTME: Port resolving an opaque secret reference into secret material inside the integration module (ADR 0024 §3).
// ABOUTME: Secret values never leave the adapter: no business table, log, audit row, HTTP response or messaging type.
package io.github.stevdrey.dokene.integration.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public interface IntegrationSecretResolver {
    /** Throws when unresolvable; there is no default or empty secret (fail closed). The caller closes the result. */
    ResolvedSecret resolve(SecretReference reference);

    /** An opaque key such as an environment variable or secret-manager name; safe to store and display. */
    record SecretReference(String value) {
        public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._:/-]{1,256}$");

        public SecretReference {
            Objects.requireNonNull(value, "Secret reference is required");
            if (!FORMAT.matcher(value).matches()) {
                throw new IllegalArgumentException("Secret reference is malformed");
            }
        }
    }

    /**
     * Secret material held as characters. Consumers read it only through {@link #withValue}, which hands out a copy
     * and wipes that copy when the callback returns or throws; {@link #close()} wipes the held array.
     */
    final class ResolvedSecret implements AutoCloseable {
        private final char[] value;
        private boolean closed;

        public ResolvedSecret(char[] value) {
            Objects.requireNonNull(value, "Secret value is required");
            if (value.length == 0) {
                throw new IllegalArgumentException("Secret value is empty");
            }
            this.value = value.clone();
        }

        /** The callback must not keep the array or anything derived from it beyond its own return. */
        public <T> T withValue(Function<char[], T> use) {
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
        public void close() {
            Arrays.fill(value, '\0');
            closed = true;
        }

        @Override
        public String toString() {
            return "ResolvedSecret[REDACTED]";
        }
    }
}
