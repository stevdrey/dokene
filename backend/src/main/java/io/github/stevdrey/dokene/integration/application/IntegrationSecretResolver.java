// ABOUTME: Port resolving an opaque secret reference into secret material inside the integration module (ADR 0024 §3).
// ABOUTME: Secret values never leave the adapter: no business table, log, audit row, HTTP response or messaging type.
package io.github.stevdrey.dokene.integration.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

public interface IntegrationSecretResolver {
    /** Throws when the reference cannot be resolved; there is no default or empty secret (fail closed). */
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

    /** Secret material held as characters so the caller can wipe it once the provider call is done. */
    final class ResolvedSecret {
        private final char[] value;

        public ResolvedSecret(char[] value) {
            Objects.requireNonNull(value, "Secret value is required");
            if (value.length == 0) {
                throw new IllegalArgumentException("Secret value is empty");
            }
            this.value = value.clone();
        }

        public char[] value() {
            return value.clone();
        }

        public void clear() {
            Arrays.fill(value, '\0');
        }

        @Override
        public String toString() {
            return "ResolvedSecret[REDACTED]";
        }
    }
}
