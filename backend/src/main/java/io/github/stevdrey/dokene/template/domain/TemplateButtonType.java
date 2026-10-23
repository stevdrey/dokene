// ABOUTME: The button kinds an approved template may carry and still be sent without values (ADR 0025 §2a).
// ABOUTME: Quick replies have only a label; phone and URL buttons also have a fixed target.
package io.github.stevdrey.dokene.template.domain;

public enum TemplateButtonType {
    QUICK_REPLY, PHONE_NUMBER, URL
}
