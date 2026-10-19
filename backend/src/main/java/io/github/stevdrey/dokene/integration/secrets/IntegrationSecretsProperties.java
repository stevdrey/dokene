// ABOUTME: Bound shape of dokene.integration.secrets: the source selector and the local source's entry list.
// ABOUTME: Every field is a String with no validation annotation so Spring's binder can never print a rejected value.
package io.github.stevdrey.dokene.integration.secrets;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dokene.integration.secrets")
public record IntegrationSecretsProperties(String source, Local local) {
    public IntegrationSecretsProperties {
        if (local == null) {
            local = new Local(null);
        }
    }

    /** The local, non production source: operator supplied entries kept outside Git. */
    public record Local(List<Entry> entries) {
        public Local {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        @Override
        public String toString() {
            return "IntegrationSecretsProperties.Local[REDACTED]";
        }
    }

    /** One secret as the operator wrote it; validated in index order by the configuration's bean method. */
    public record Entry(String scope, String tenantId, String ref, String purpose, String value) {
        @Override
        public String toString() {
            return "IntegrationSecretsProperties.Entry[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "IntegrationSecretsProperties[REDACTED]";
    }
}
