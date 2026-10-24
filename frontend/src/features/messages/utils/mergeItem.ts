// ABOUTME: The one version rule of the delivery history: a reply replaces a held list item only with a higher version.
// ABOUTME: An equal version may still update contactRemoved, which the backend changes without a version bump, and
// ABOUTME: fill in a locale an older backend left out.
import type { MessageListItem, MessageResponse } from '../types';

/** Returns the held reference when nothing changes, so callers can tell a no op apart from an update. */
export function mergeItem(held: MessageListItem, incoming: MessageListItem): MessageListItem {
  if (incoming.version > held.version) return incoming;
  if (incoming.version !== held.version) return held;
  const contactChanged = incoming.contactRemoved !== held.contactRemoved;
  // A locale is immutable, so one that only a newer backend reported completes the held row (issue 268).
  const localeAdded = held.locale === undefined && incoming.locale !== undefined;
  if (!contactChanged && !localeAdded) return held;
  return { ...held, contactRemoved: incoming.contactRemoved, locale: held.locale ?? incoming.locale };
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
    locale: detail.locale,
    origin: detail.origin,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    version: detail.version
  };
}
