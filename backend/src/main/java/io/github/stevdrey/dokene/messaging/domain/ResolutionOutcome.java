// ABOUTME: The outcomes a member may declare for an unknown send (T12). Only FAILED: a message is never marked sent
// ABOUTME: without a provider message id, so a human can never declare success.
package io.github.stevdrey.dokene.messaging.domain;

public enum ResolutionOutcome { FAILED }
