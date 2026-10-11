// ABOUTME: The filter chip map for the Mensajes list, each chip naming the statuses it queries.
// ABOUTME: Decided in spec 0004 AC-1; the list opens on Pendientes.

import type { MessageStatus } from '../types';

export type MessageFilterKey = 'pending' | 'approved' | 'sent' | 'failed' | 'closed' | 'all';

export interface MessageFilter {
  readonly key: MessageFilterKey;
  readonly label: string;
  readonly statuses: readonly MessageStatus[];
}

export const MESSAGE_FILTERS: readonly MessageFilter[] = [
  { key: 'pending', label: 'Pendientes', statuses: ['PENDING_APPROVAL'] },
  { key: 'approved', label: 'Aprobados', statuses: ['APPROVED'] },
  { key: 'sent', label: 'Enviados', statuses: ['QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ'] },
  { key: 'failed', label: 'Fallidos', statuses: ['FAILED'] },
  { key: 'closed', label: 'Rechazados y cancelados', statuses: ['REJECTED', 'CANCELLED'] },
  { key: 'all', label: 'Todos', statuses: [] }
];

export const DEFAULT_FILTER: MessageFilterKey = 'pending';

export function filterByKey(key: MessageFilterKey): MessageFilter {
  return MESSAGE_FILTERS.find((filter) => filter.key === key) ?? MESSAGE_FILTERS[0];
}
