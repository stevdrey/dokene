// ABOUTME: The Mensajes list pane: filter chips, the optional customer chip, rows and Cargar más.
// ABOUTME: Rows are buttons that set the App level selection; the selected row carries aria-current.

import React from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { actionLabel } from '@/features/followups/utils/aiLabels';
import type { MessageListState } from '../hooks/useMessageList';
import type { MessageListItem } from '../types';
import { MESSAGE_FILTERS, type MessageFilterKey } from '../utils/filters';
import { EMPTY_LIST_SENTENCE, originLabel, statusBadgeVariant, statusLabel } from '../utils/messageLabels';
import { formatDate, maskPhone } from '../utils/format';
import { cardStyle, errorAlertStyle, metaStyle, statusLineStyle } from './styles';

export interface CustomerFilterChip {
  readonly customerId: string;
  readonly customerDisplayName: string;
}

interface MessageListProps {
  readonly filterKey: MessageFilterKey;
  readonly onFilterChange: (key: MessageFilterKey) => void;
  readonly customerFilter: CustomerFilterChip | null;
  readonly onClearCustomerFilter: () => void;
  readonly list: MessageListState;
  readonly onLoadMore: () => void;
  readonly onReload: () => void;
  readonly selectedMessageId: string | null;
  readonly onSelect: (item: MessageListItem) => void;
  readonly registerRow: (messageId: string, element: HTMLButtonElement | null) => void;
}

export const MessageList: React.FC<MessageListProps> = ({
  filterKey,
  onFilterChange,
  customerFilter,
  onClearCustomerFilter,
  list,
  onLoadMore,
  onReload,
  selectedMessageId,
  onSelect,
  registerRow
}) => {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
      <section aria-label="Filtros de mensajes" style={{ ...cardStyle, gap: 'var(--space-8)' }}>
        <div role="group" aria-label="Filtrar por estado del mensaje" style={{ display: 'flex', gap: 'var(--space-4)', flexWrap: 'wrap' }}>
          {MESSAGE_FILTERS.map((filter) => {
            const selected = filter.key === filterKey;
            return (
              <Button
                key={filter.key}
                variant={selected ? 'primary' : 'ghost'}
                size="sm"
                aria-pressed={selected}
                onClick={() => onFilterChange(filter.key)}
              >
                {filter.label}
              </Button>
            );
          })}
        </div>
        {customerFilter && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', flexWrap: 'wrap' }}>
            <Badge variant="neutral">Cliente: {customerFilter.customerDisplayName}</Badge>
            <Button
              variant="ghost"
              size="sm"
              aria-label={`Quitar filtro de cliente ${customerFilter.customerDisplayName}`}
              onClick={onClearCustomerFilter}
            >
              ✕
            </Button>
          </div>
        )}
      </section>

      {list.step === 'loading' && (
        <div role="status" aria-live="polite" style={statusLineStyle}>
          Cargando mensajes...
        </div>
      )}

      {list.step === 'error' && (
        <div role="alert" style={errorAlertStyle}>
          <span>{list.error}</span>
          <div>
            <Button variant="secondary" size="sm" onClick={onReload}>
              Reintentar
            </Button>
          </div>
        </div>
      )}

      {list.step === 'ready' && list.items.length === 0 && (
        <div role="status" style={{ ...cardStyle, alignItems: 'center', textAlign: 'center', padding: 'var(--space-32)' }}>
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '32px', color: 'var(--color-text-muted)' }}>
            mark_chat_read
          </span>
          <span style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-supporting)' }}>
            {EMPTY_LIST_SENTENCE}
          </span>
        </div>
      )}

      {list.step === 'ready' && list.items.length > 0 && (
        <ul aria-label="Mensajes" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          {list.items.map((item) => {
            const selected = item.messageId === selectedMessageId;
            return (
              <li key={item.messageId}>
                <button
                  type="button"
                  ref={(element) => registerRow(item.messageId, element)}
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => onSelect(item)}
                  style={{
                    width: '100%',
                    textAlign: 'left',
                    cursor: 'pointer',
                    font: 'inherit',
                    backgroundColor: selected ? 'var(--color-surface-selected)' : 'var(--color-surface)',
                    border: selected ? '1px solid var(--color-primary)' : '1px solid var(--color-outline-subtle)',
                    borderRadius: 'var(--radius-lg)',
                    padding: 'var(--space-12) var(--space-16)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 'var(--space-8)',
                    color: 'var(--color-text-main)',
                    transition: 'background-color 0.15s ease, border-color 0.15s ease'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 'var(--space-8)', flexWrap: 'wrap' }}>
                    <span style={{ fontWeight: 600, fontSize: 'var(--font-size-body)', lineHeight: 'var(--line-height-body)', minWidth: 0, overflowWrap: 'anywhere' }}>
                      {item.customerDisplayName}
                    </span>
                    <Badge variant={statusBadgeVariant(item.status)}>{statusLabel(item.status)}</Badge>
                  </div>
                  <div style={{ display: 'flex', gap: 'var(--space-8)', alignItems: 'center', flexWrap: 'wrap', ...metaStyle }}>
                    <span>{maskPhone(item.recipientPhone)}</span>
                    <span aria-hidden="true">·</span>
                    <span>{actionLabel(item.action)}</span>
                    <span aria-hidden="true">·</span>
                    <time dateTime={item.createdAt}>{formatDate(item.createdAt)}</time>
                  </div>
                  <div style={{ display: 'flex', gap: 'var(--space-4)', flexWrap: 'wrap' }}>
                    <Badge variant="neutral">{originLabel(item.origin)}</Badge>
                    {item.contactRemoved && <Badge variant="warning">Teléfono retirado</Badge>}
                  </div>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {list.step === 'ready' && list.nextCursor !== null && (
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          <Button variant="secondary" onClick={onLoadMore} isLoading={list.loadingMore} disabled={list.loadingMore}>
            Cargar más
          </Button>
        </div>
      )}

      {list.step === 'ready' && list.error && (
        <div role="alert" style={errorAlertStyle}>
          {list.error}
        </div>
      )}
    </div>
  );
};
