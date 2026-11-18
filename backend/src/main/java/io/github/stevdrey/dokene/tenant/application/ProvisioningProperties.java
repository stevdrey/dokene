// ABOUTME: Workspace provisioning settings (spec 0021): the switch, the allowlist, the open setting and the cap per
// ABOUTME: identity. Enabled provisioning without one stated policy, or a cap out of range, stops startup.
package io.github.stevdrey.dokene.tenant.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dokene.provisioning")
public record ProvisioningProperties(
        @DefaultValue("false") boolean enabled,
        List<String> allowedIdentities,
        @DefaultValue("false") boolean allowAllIdentities,
        @DefaultValue("3") int maxWorkspacesPerIdentity
) {
    // UUID.fromString alone accepts short groups such as "1-2-3-4-5" and would bind them as some other identity.
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Who may provision. Enabled provisioning is always exactly one of the last two. */
    public enum Mode { DISABLED, ALLOWLIST, OPEN }

    public ProvisioningProperties {
        if (maxWorkspacesPerIdentity < 1 || maxWorkspacesPerIdentity > 10_000) {
            throw new IllegalArgumentException("dokene.provisioning.max-workspaces-per-identity must be 1 to 10000");
        }
        allowedIdentities = normalize(allowedIdentities);
        if (enabled && allowedIdentities.isEmpty() && !allowAllIdentities) {
            throw new IllegalArgumentException(
                    "dokene.provisioning.enabled is true without a stated policy: list identities in "
                            + "dokene.provisioning.allowed-identities or set "
                            + "dokene.provisioning.allow-all-identities to true");
        }
        if (enabled && allowAllIdentities && !allowedIdentities.isEmpty()) {
            throw new IllegalArgumentException(
                    "dokene.provisioning.allow-all-identities is true while "
                            + "dokene.provisioning.allowed-identities lists identities: keep only one of them");
        }
    }

    public Mode mode() {
        if (!enabled) {
            return Mode.DISABLED;
        }
        return allowAllIdentities ? Mode.OPEN : Mode.ALLOWLIST;
    }

    public Set<UUID> allowedIdentityIds() {
        return allowedIdentities.stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    // Trimmed, blank entries dropped, duplicates removed, so "," counts as empty. The refusal never echoes the entry.
    private static List<String> normalize(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        Set<String> identities = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String candidate = entry.trim();
            if (!CANONICAL_UUID.matcher(candidate).matches()) {
                throw new IllegalArgumentException(
                        "dokene.provisioning.allowed-identities must hold only identity UUIDs");
            }
            identities.add(UUID.fromString(candidate).toString());
        }
        return List.copyOf(identities);
    }
}
