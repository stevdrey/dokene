// ABOUTME: Delivery statuses a provider can report for a sent message.
// ABOUTME: Input to the T13 to T16 transitions.
package io.github.stevdrey.dokene.messaging.domain;

public enum DeliveryStatus { SENT, DELIVERED, READ, FAILED }
