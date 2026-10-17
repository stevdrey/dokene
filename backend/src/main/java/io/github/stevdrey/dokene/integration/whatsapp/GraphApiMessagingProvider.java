// ABOUTME: Sends one approved WhatsApp template through Meta's Cloud API with the tenant's own credential (ADR 0027).
// ABOUTME: Transport only: no policy or approval authority, one POST and never a retry; exceptions never cross the port.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.application.WhatsAppOutboundSafetyControl;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.NotSent;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.PostOutcome;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.Responded;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.Unknown;
import io.github.stevdrey.dokene.messaging.application.MessagingProvider;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.Accepted;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.FailedTransiently;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.OutcomeUnknown;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.RejectedPermanently;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateParameterValues;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GraphApiMessagingProvider implements MessagingProvider {
    /**
     * Meta documents the send response id only as a string ("WhatsApp message ID" in status webhooks); the
     * {@code wamid.} prefix appears in examples, not in the contract. The id is therefore treated as opaque: this
     * pattern only guarantees it can be stored and matched by exact equality, never its provider format (ADR 0027).
     */
    private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9._:=+/-]{1,128}$");
    /** Statuses that mean Meta took the message; acceptance is never delivery, which only a webhook reports. */
    private static final Set<String> ACCEPTED_STATUSES = Set.of("accepted", "held_for_quality_assessment");

    private final WhatsAppIntegrationRepository integrations;
    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantContextProvider contexts;
    private final GraphApiHttp http;
    private final JsonMapper json = JsonMapper.builder().build();

    public GraphApiMessagingProvider(WhatsAppIntegrationRepository integrations,
            ObjectProvider<IntegrationSecretResolver> secrets, TenantContextProvider contexts,
            GraphApiHttp http) {
        this.integrations = Objects.requireNonNull(integrations, "Integrations are required");
        this.secrets = Objects.requireNonNull(secrets, "Secrets are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.http = Objects.requireNonNull(http, "Graph API HTTP is required");
    }

    /** The outcome of everything done before the POST: a refusal, or what the single POST needs. */
    private sealed interface Preparation {
    }

    private record Refused(ProviderSendResult result) implements Preparation {
    }

    /** The open secret is owned by {@link #dispatch}, which closes it. */
    private record Prepared(URI uri, byte[] payload, long deadline, ResolvedSecret secret) implements Preparation {
    }

    @Override
    public ProviderSendResult send(OutboundSendCommand command) {
        Preparation preparation;
        try {
            preparation = prepare(command);
        } catch (RuntimeException | Error failure) {
            // Nothing can have left yet. No exception detail crosses the port.
            return new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
        }
        return switch (preparation) {
            case Refused refused -> refused.result();
            case Prepared prepared -> dispatch(prepared);
        };
    }

    /** Checks the command, the sender and the credential; resolving the secret is the last step, so none leaks. */
    private Preparation prepare(OutboundSendCommand command) {
        Optional<FailureCategory> invalid = validate(command);
        if (invalid.isPresent()) {
            return new Refused(new RejectedPermanently(invalid.get()));
        }
        TenantId tenantId = contexts.requireCurrent().tenantId();
        Optional<WhatsAppIntegration> found = integrations.findByTenant(tenantId);
        // Never send from a sender other than the one the approval bound, even if it was re-proven since.
        if (found.isEmpty() || !found.get().enabled() || !found.get().ownershipVerified()
                || !WhatsAppIntegration.isMetaId(found.get().phoneNumberId())
                || !WhatsAppOutboundSafetyControl.senderRevision(found.get()).equals(command.expectedSender())) {
            return new Refused(new RejectedPermanently(FailureCategory.POLICY_VIOLATION));
        }
        WhatsAppIntegration integration = found.get();
        IntegrationSecretResolver resolver = secrets.getIfAvailable();
        if (resolver == null) {
            return new Refused(new RejectedPermanently(FailureCategory.AUTHENTICATION));
        }
        URI uri = http.uri(integration.phoneNumberId() + "/messages");
        byte[] payload = json.writeValueAsBytes(payload(command));
        long deadline = http.deadline();
        try {
            return new Prepared(uri, payload, deadline,
                    resolver.resolve(new Tenant(tenantId, SecretPurpose.WHATSAPP_ACCESS_TOKEN),
                            new SecretReference(integration.secretRef())));
        } catch (RuntimeException unresolvable) {
            return new Refused(new RejectedPermanently(FailureCategory.AUTHENTICATION));
        }
    }

    /** The single POST; from here on any failure may follow a transmitted request, so it is uncertain. */
    private ProviderSendResult dispatch(Prepared prepared) {
        try (ResolvedSecret secret = prepared.secret()) {
            return classify(secret.withValue(
                    token -> http.post(prepared.uri(), token, prepared.payload(), prepared.deadline())));
        } catch (RuntimeException | Error failure) {
            return new OutcomeUnknown(Optional.empty());
        }
    }

    /** Re-checks the shape Meta accepts with the contract's own rules, so a malformed command is refused, not sent. */
    private Optional<FailureCategory> validate(OutboundSendCommand command) {
        if (!OutboundMessage.E164.matcher(command.recipientPhone()).matches()) {
            return Optional.of(FailureCategory.INVALID_RECIPIENT);
        }
        boolean templateValid = TemplateMapping.PROVIDER_TEMPLATE_NAME.matcher(command.providerTemplateName()).matches()
                && TemplateMapping.LANGUAGE.matcher(command.language()).matches()
                && command.parameters().size() <= TemplateMapping.MAX_PARAMETERS
                && command.parameters().stream().allMatch(TemplateParameterValues::acceptable);
        return templateValid ? Optional.empty() : Optional.of(FailureCategory.TEMPLATE_REJECTED);
    }

    /** The only request this adapter sends: an approved template with its bound parameters, in order. */
    private ObjectNode payload(OutboundSendCommand command) {
        ObjectNode root = json.createObjectNode();
        root.put("messaging_product", "whatsapp");
        root.put("recipient_type", "individual");
        root.put("to", command.recipientPhone());
        root.put("type", "template");
        ObjectNode template = root.putObject("template");
        template.put("name", command.providerTemplateName());
        template.putObject("language").put("code", command.language());
        if (!command.parameters().isEmpty()) {
            ObjectNode body = template.putArray("components").addObject();
            body.put("type", "body");
            ArrayNode parameters = body.putArray("parameters");
            for (String value : command.parameters()) {
                parameters.addObject().put("type", "text").put("text", value);
            }
        }
        return root;
    }

    private ProviderSendResult classify(PostOutcome outcome) {
        return switch (outcome) {
            case NotSent _ -> new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
            case Unknown _ -> new OutcomeUnknown(Optional.empty());
            case Responded responded when responded.status() >= 200 && responded.status() < 300 ->
                    accepted(responded.body());
            case Responded responded -> GraphApiSendErrors.classify(responded.status(), errorCode(responded.body()));
        };
    }

    /** A 2xx without a usable id is uncertain: Meta may have taken the message even though the answer is unusable. */
    private ProviderSendResult accepted(byte[] body) {
        JsonNode message = parse(body).path("messages").path(0);
        JsonNode idNode = message.path("id");
        String id = idNode.isString() ? idNode.asString() : null;
        if (id == null || !MESSAGE_ID.matcher(id).matches()) {
            return new OutcomeUnknown(Optional.empty());
        }
        JsonNode status = message.path("message_status");
        if (status.isMissingNode() || status.isNull() || ACCEPTED_STATUSES.contains(status.asString(""))) {
            return new Accepted(id);
        }
        // An id with another status (for example paused) is not acceptance, but webhooks may still report it.
        return new OutcomeUnknown(Optional.of(id));
    }

    /** Only the integer {@code error.code} is read; message, details and trace ids are provider text and dropped. */
    private OptionalInt errorCode(byte[] body) {
        JsonNode code = parse(body).path("error").path("code");
        return code.isInt() ? OptionalInt.of(code.intValue()) : OptionalInt.empty();
    }

    private JsonNode parse(byte[] body) {
        if (body == null) {
            return json.missingNode();
        }
        try {
            return json.readTree(body);
        } catch (JacksonException malformed) {
            return json.missingNode();
        }
    }
}
