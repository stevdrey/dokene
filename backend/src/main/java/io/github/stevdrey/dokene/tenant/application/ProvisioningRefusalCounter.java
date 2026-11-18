// ABOUTME: Counts refused workspace provisioning requests (spec 0021) by reason, the only tag.
// ABOUTME: Called before the denial audit write, so suppressed and failed audit rows are still counted.
package io.github.stevdrey.dokene.tenant.application;

import java.util.Locale;

@FunctionalInterface
public interface ProvisioningRefusalCounter {

    void refused(Reason reason);

    enum Reason {
        POLICY;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
