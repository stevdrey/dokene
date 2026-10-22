// ABOUTME: Interprets the components and parameter_format of an approved Graph API message template (issue 218).
// ABOUTME: Keeps body placeholder tokens and the fixed header/body/footer text; anything needing a value fails closed.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.BodyParameters;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unreadable;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unsupported;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Dokene sends text parameters in the body only. A header, footer or button is accepted only when it needs no value;
 * anything else, including a component type this reader does not know, makes the template unsupported. Metadata that
 * is missing or malformed makes it unreadable, which takes precedence over unsupported.
 */
final class GraphApiTemplateSchemaReader {
    /** Bounds on untrusted metadata; looser than Meta's own limits so they never reject a valid template. */
    private static final int MAX_COMPONENTS = 10;
    private static final int MAX_BUTTONS = 10;
    private static final int MAX_TEXT_LENGTH = 4096;

    private GraphApiTemplateSchemaReader() {
    }

    static ProviderTemplateSchema read(JsonNode template) {
        Optional<TemplateParameterFormat> format = format(template.path("parameter_format"));
        JsonNode components = template.path("components");
        if (format.isEmpty() || !components.isArray() || components.isEmpty()
                || components.size() > MAX_COMPONENTS) {
            return new Unreadable();
        }
        List<String> body = null;
        String bodyText = null;
        String headerText = null;
        String footerText = null;
        boolean unsupported = false;
        for (JsonNode component : components) {
            if (!component.isObject() || !component.path("type").isString()) {
                return new Unreadable();
            }
            ComponentCheck check;
            switch (component.path("type").asString()) {
                case "BODY" -> {
                    Optional<List<String>> tokens = tokens(component.path("text"));
                    if (body != null || tokens.isEmpty()) {
                        return new Unreadable();
                    }
                    body = tokens.get();
                    bodyText = component.path("text").asString();
                    check = ComponentCheck.ALLOWED;
                }
                case "HEADER" -> {
                    check = header(component);
                    headerText = check == ComponentCheck.ALLOWED ? component.path("text").asString() : null;
                }
                case "FOOTER" -> {
                    check = withoutPlaceholders(component.path("text"));
                    footerText = check == ComponentCheck.ALLOWED ? component.path("text").asString() : null;
                }
                case "BUTTONS" -> check = buttons(component.path("buttons"));
                default -> check = ComponentCheck.UNSUPPORTED;
            }
            if (check == ComponentCheck.UNREADABLE) {
                return new Unreadable();
            }
            unsupported |= check == ComponentCheck.UNSUPPORTED;
        }
        if (body == null) {
            return new Unreadable();
        }
        if (unsupported) {
            return new Unsupported();
        }
        try {
            return new BodyParameters(format.get(), body, new TemplateContent(headerText, bodyText, footerText));
        } catch (IllegalArgumentException invalidText) {
            // Blank or NUL-bearing text cannot be shown to a reviewer, so nothing can be concluded from it.
            return new Unreadable();
        }
    }

    private enum ComponentCheck {
        ALLOWED, UNSUPPORTED, UNREADABLE
    }

    /** Absent means positional, Meta's default; anything but the two documented values is unreadable. */
    private static Optional<TemplateParameterFormat> format(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Optional.of(TemplateParameterFormat.POSITIONAL);
        }
        if (!node.isString()) {
            return Optional.empty();
        }
        return switch (node.asString()) {
            case "POSITIONAL" -> Optional.of(TemplateParameterFormat.POSITIONAL);
            case "NAMED" -> Optional.of(TemplateParameterFormat.NAMED);
            default -> Optional.empty();
        };
    }

    /** A media, location or any other non-text header needs a value Dokene does not send. */
    private static ComponentCheck header(JsonNode component) {
        JsonNode format = component.path("format");
        if (!format.isString()) {
            return ComponentCheck.UNREADABLE;
        }
        return "TEXT".equals(format.asString()) ? withoutPlaceholders(component.path("text"))
                : ComponentCheck.UNSUPPORTED;
    }

    /** Quick replies and phone numbers need no value; a URL needs one only when it has a placeholder. */
    private static ComponentCheck buttons(JsonNode buttons) {
        if (!buttons.isArray() || buttons.isEmpty() || buttons.size() > MAX_BUTTONS) {
            return ComponentCheck.UNREADABLE;
        }
        ComponentCheck result = ComponentCheck.ALLOWED;
        for (JsonNode button : buttons) {
            if (!button.isObject() || !button.path("type").isString()) {
                return ComponentCheck.UNREADABLE;
            }
            ComponentCheck check = switch (button.path("type").asString()) {
                case "QUICK_REPLY", "PHONE_NUMBER" -> ComponentCheck.ALLOWED;
                case "URL" -> withoutPlaceholders(button.path("url"));
                default -> ComponentCheck.UNSUPPORTED;
            };
            if (check == ComponentCheck.UNREADABLE) {
                return ComponentCheck.UNREADABLE;
            }
            if (check == ComponentCheck.UNSUPPORTED) {
                result = ComponentCheck.UNSUPPORTED;
            }
        }
        return result;
    }

    private static ComponentCheck withoutPlaceholders(JsonNode text) {
        Optional<List<String>> tokens = tokens(text);
        if (tokens.isEmpty()) {
            return ComponentCheck.UNREADABLE;
        }
        return tokens.get().isEmpty() ? ComponentCheck.ALLOWED : ComponentCheck.UNSUPPORTED;
    }

    private static Optional<List<String>> tokens(JsonNode text) {
        if (!text.isString() || text.asString().length() > MAX_TEXT_LENGTH) {
            return Optional.empty();
        }
        return ProviderTemplateSchema.placeholders(text.asString());
    }
}
