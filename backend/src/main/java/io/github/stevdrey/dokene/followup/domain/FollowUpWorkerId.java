// ABOUTME: The identity of a worker instance that claims follow-up work (issue 458, ADR 0037): a short operator-chosen
// ABOUTME: label such as a host or pod name, recorded with each claim. Never a tenant, customer or secret value.
package io.github.stevdrey.dokene.followup.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record FollowUpWorkerId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public FollowUpWorkerId {
        Objects.requireNonNull(value, "Worker id is required");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Worker id must be 1-64 characters of letters, digits, '.', '_', ':' or '-'");
        }
    }
}
