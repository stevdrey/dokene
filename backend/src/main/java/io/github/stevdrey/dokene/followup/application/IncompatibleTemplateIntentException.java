package io.github.stevdrey.dokene.followup.application;

/**
 * The requested template intent is not compatible with the requested action. Remains an
 * {@link IllegalArgumentException} so the API still maps it to a client error; the dedicated type lets callers (and
 * the AI evaluation) tell this deliberate validation apart from any other illegal argument.
 */
public class IncompatibleTemplateIntentException extends IllegalArgumentException {
    public IncompatibleTemplateIntentException() {
        super("Template intent is incompatible with the requested action");
    }
}
