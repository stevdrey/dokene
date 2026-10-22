// ABOUTME: The fixed content of an approved provider template (header, body, footer, buttons) as recipients see it.
// ABOUTME: Snapshotted at approval refresh so review shows, and the approval binds, the content actually sent.
package io.github.stevdrey.dokene.template.domain;

import java.util.List;
import java.util.Objects;

/**
 * Placeholders stay as written by the provider ({@code {{1}}}, {@code {{customer_name}}}); the values that replace
 * them are bound separately. Provider metadata is untrusted, so every part is bounded and may not contain U+0000,
 * which PostgreSQL cannot store. {@link #toString()} omits the text, which may be tenant-authored.
 */
public record TemplateContent(String header, String body, String footer, List<TemplateButton> buttons) {
    /** Looser than Meta's own limits (60/1024/60), so a valid template is never refused. */
    public static final int MAX_TEXT_LENGTH = 4096;
    /** Meta allows at most ten buttons on a template. */
    public static final int MAX_BUTTONS = 10;

    /** Content without buttons. */
    public TemplateContent(String header, String body, String footer) {
        this(header, body, footer, List.of());
    }

    public TemplateContent {
        buttons = List.copyOf(Objects.requireNonNull(buttons, "Buttons are required"));
        if (buttons.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException("Too many buttons");
        }
        requireText(body, "Body");
        if (header != null) {
            requireText(header, "Header");
        }
        if (footer != null) {
            requireText(footer, "Footer");
        }
    }

    static void requireText(String text, String part) {
        Objects.requireNonNull(text, part + " is required");
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(part + " text is invalid");
        }
    }

    @Override
    public String toString() {
        return "TemplateContent[header=" + (header != null) + ", footer=" + (footer != null) + ", buttons="
                + buttons.size() + "]";
    }
}
