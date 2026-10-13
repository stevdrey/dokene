// ABOUTME: Who caused a message event: a tenant member, the system, or the provider via webhook.
// ABOUTME: Only MEMBER events carry a membership id.
package io.github.stevdrey.dokene.messaging.domain;

public enum MessageActorKind { MEMBER, SYSTEM, PROVIDER }
