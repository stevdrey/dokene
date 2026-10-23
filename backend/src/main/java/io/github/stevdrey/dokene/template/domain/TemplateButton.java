// ABOUTME: One fixed button of an approved template: its kind, label and, for phone and URL buttons, its target.
// ABOUTME: Part of the template content snapshot, so a button changed at Meta voids open approvals (issue 268).
package io.github.stevdrey.dokene.template.domain;

import java.util.Objects;

/** {@code value} is the phone number or URL, and null for a quick reply. Bounded like the rest of the content. */
public record TemplateButton(TemplateButtonType type, String text, String value) {
    public TemplateButton {
        Objects.requireNonNull(type, "Button type is required");
        TemplateContent.requireText(text, "Button text");
        if (type == TemplateButtonType.QUICK_REPLY) {
            if (value != null) {
                throw new IllegalArgumentException("A quick reply has no target");
            }
        } else {
            TemplateContent.requireText(value, "Button target");
        }
    }

    @Override
    public String toString() {
        return "TemplateButton[type=" + type + "]";
    }
}
