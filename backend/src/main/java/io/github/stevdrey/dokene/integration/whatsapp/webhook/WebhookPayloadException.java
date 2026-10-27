// ABOUTME: A verified webhook body that is not a Meta delivery callback; the route answers 400 whatever the reason.
// ABOUTME: Carries a closed reason, an unexpected member's level and sanitized key name, no cause and never a value.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class WebhookPayloadException extends RuntimeException {
    /** A key name kept for the log only when it looks like a Meta member name; anything else is not echoed. */
    private static final Pattern MEMBER_NAME = Pattern.compile("^[a-z0-9_]{1,64}$");
    static final String OTHER_MEMBER = "<other>";

    private final Reason reason;
    private final Level level;
    private final String member;

    public WebhookPayloadException(Reason reason) {
        this(reason, null, null);
    }

    private WebhookPayloadException(Reason reason, Level level, String member) {
        super(message(reason, level, member), null, false, false);
        this.reason = reason;
        this.level = level;
        this.member = member;
    }

    /**
     * An allowlist refusal naming where it happened (issue 289), so the next Meta schema addition is one log line. The
     * name comes from a signature-verified body and is kept only when it matches {@code [a-z0-9_]{1,64}}.
     */
    public static WebhookPayloadException unexpectedMember(Level level, String name) {
        Objects.requireNonNull(level, "Level is required");
        String member = name != null && MEMBER_NAME.matcher(name).matches() ? name : OTHER_MEMBER;
        return new WebhookPayloadException(Reason.UNEXPECTED_MEMBER, level, member);
    }

    public Reason reason() {
        return reason;
    }

    public Optional<Level> level() {
        return Optional.ofNullable(level);
    }

    public Optional<String> member() {
        return Optional.ofNullable(member);
    }

    private static String message(Reason reason, Level level, String member) {
        String text = "Webhook payload rejected: reason=" + Objects.requireNonNull(reason, "Reason is required");
        return level == null ? text : text + " level=" + level + " member=" + member;
    }

    public enum Reason { MALFORMED_JSON, SCHEMA, UNEXPECTED_MEMBER, TOO_MANY_STATUSES }

    /** The object an unexpected member was found in. */
    public enum Level { ENVELOPE, ENTRY, CHANGE, VALUE, METADATA, STATUS }
}
