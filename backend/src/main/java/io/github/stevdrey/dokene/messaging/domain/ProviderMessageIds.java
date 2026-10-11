// ABOUTME: The one validation rule for provider message ids, shared by every value object that carries one.
// ABOUTME: Rejects U+0000 up front because Postgres cannot store it and the provider side effect would already be done.
package io.github.stevdrey.dokene.messaging.domain;

final class ProviderMessageIds {
    static final int MAX_LENGTH = 128;

    private ProviderMessageIds() {
    }

    static boolean malformed(String id) {
        return id == null || id.isBlank() || id.length() > MAX_LENGTH || id.indexOf('\0') >= 0;
    }
}
