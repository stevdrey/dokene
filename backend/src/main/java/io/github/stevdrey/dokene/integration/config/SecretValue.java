// ABOUTME: A resolved secret, alive only on the server between a resolver call and the provider call.
// ABOUTME: Prints as SecretValue[redacted], compares by content, is not a record so no accessor serializes it.
package io.github.stevdrey.dokene.integration.config;

import java.util.Objects;

public final class SecretValue {
    private final String characters;

    public SecretValue(String characters) {
        this.characters = Objects.requireNonNull(characters, "Secret characters are required");
    }

    /** Plain method name on purpose: not a bean getter, so no serializer picks it up. */
    public String characters() {
        return characters;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretValue that && characters.equals(that.characters);
    }

    @Override
    public int hashCode() {
        return characters.hashCode();
    }

    @Override
    public String toString() {
        return "SecretValue[redacted]";
    }
}
