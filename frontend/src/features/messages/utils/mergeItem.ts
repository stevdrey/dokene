// ABOUTME: The one version rule of the delivery history: a reply replaces a held list item only with a higher version.
// ABOUTME: An equal version may still update contactRemoved, which the backend changes without a version bump.
import type { MessageListItem, MessageResponse } from '../types';

/** Returns the held reference when nothing changes, so callers can tell a no op apart from an update. */
export function mergeItem(held: MessageListItem, incoming: MessageListItem): MessageListItem {
  if (incoming.version > held.version) return incoming;
  if (incoming.version === held.version && incoming.contactRemoved !== held.contactRemoved) {
    return { ...held, contactRemoved: incoming.contactRemoved };
  }
  return held;
}

/** Copies only the list members of a detail, so an opened or refreshed detail can go through mergeItem. */
export function listItemFromDetail(detail: MessageResponse): MessageListItem {
  return {
    messageId: detail.messageId,
    customerId: detail.customerId,
    contactRemoved: detail.contactRemoved,
    status: detail.status,
    outcomeUnknown: detail.outcomeUnknown,
    action: detail.action,
    templateIntent: detail.templateIntent,
    origin: detail.origin,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    version: detail.version
  };
}
