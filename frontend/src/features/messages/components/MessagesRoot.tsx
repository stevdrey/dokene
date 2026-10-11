// ABOUTME: The Mensajes tab root: page header, master detail layout and the tenant wide send pause.
// ABOUTME: Selection and the customer filter live in App so the AI panel can open a message here.

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTenant, canActOnMessages, canReadMessages } from '@/features/tenants/TenantContext';
import { useMessageList } from '../hooks/useMessageList';
import type { MessageListItem, MessageStatus } from '../types';
import { DEFAULT_FILTER, type MessageFilterKey } from '../utils/filters';
import { NOT_FOUND_SENTENCE } from '../utils/messageLabels';
import { MessageList, type CustomerFilterChip } from './MessageList';
import { MessageDetailPanel } from './MessageDetailPanel';
import { cardStyle, secondaryTextStyle, statusLineStyle, warningAlertStyle } from './styles';

export const MOBILE_BREAKPOINT_PX = 768;

interface MessagesRootProps {
  readonly selectedMessageId: string | null;
  readonly onSelectMessage: (messageId: string | null) => void;
  readonly customerFilter: CustomerFilterChip | null;
  readonly onClearCustomerFilter: () => void;
}

function readIsMobile(): boolean {
  return typeof window !== 'undefined' ? window.innerWidth < MOBILE_BREAKPOINT_PX : false;
}

export const MessagesRoot: React.FC<MessagesRootProps> = ({
  selectedMessageId,
  onSelectMessage,
  customerFilter,
  onClearCustomerFilter
}) => {
  const { activeWorkspace } = useTenant();
  const role = activeWorkspace?.role ?? null;
  const canRead = canReadMessages(role);
  const canAct = canActOnMessages(role);

  const [filterKey, setFilterKey] = useState<MessageFilterKey>(customerFilter ? 'all' : DEFAULT_FILTER);
  const [notice, setNotice] = useState<string | null>(null);
  const [isMobile, setIsMobile] = useState(readIsMobile);
  const [rateLimitedUntil, setRateLimitedUntil] = useState<number | null>(null);
  const [sendBlockedSeconds, setSendBlockedSeconds] = useState(0);

  const list = useMessageList({ filterKey, customerId: customerFilter?.customerId ?? null });

  const rowRefs = useRef(new Map<string, HTMLButtonElement>());
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const pendingFocusRef = useRef<{ kind: 'detail' } | { kind: 'row'; messageId: string } | null>(null);

  useEffect(() => {
    const handleResize = () => setIsMobile(readIsMobile());
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // A link from the AI panel arrives as a customer filter: it reads on Todos so every message shows.
  useEffect(() => {
    if (customerFilter) setFilterKey('all');
  }, [customerFilter?.customerId]);

  useEffect(() => {
    if (rateLimitedUntil === null) {
      setSendBlockedSeconds(0);
      return;
    }
    const update = () => {
      const remaining = Math.max(0, Math.ceil((rateLimitedUntil - Date.now()) / 1000));
      setSendBlockedSeconds(remaining);
      if (remaining === 0) setRateLimitedUntil(null);
    };
    update();
    const timer = setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [rateLimitedUntil]);

  useEffect(() => {
    const pending = pendingFocusRef.current;
    if (!pending) return;
    if (pending.kind === 'detail' && selectedMessageId !== null) {
      // The heading mounts after the detail loads; retry on the next frames until it exists.
      let attempts = 0;
      const tryFocus = () => {
        if (headingRef.current) {
          headingRef.current.focus();
          pendingFocusRef.current = null;
        } else if (attempts < 30) {
          attempts += 1;
          setTimeout(tryFocus, 50);
        }
      };
      tryFocus();
    } else if (pending.kind === 'row' && selectedMessageId === null) {
      rowRefs.current.get(pending.messageId)?.focus();
      pendingFocusRef.current = null;
    }
  }, [selectedMessageId]);

  const registerRow = useCallback((messageId: string, element: HTMLButtonElement | null) => {
    if (element) rowRefs.current.set(messageId, element);
    else rowRefs.current.delete(messageId);
  }, []);

  const handleSelect = useCallback(
    (item: MessageListItem) => {
      setNotice(null);
      pendingFocusRef.current = { kind: 'detail' };
      onSelectMessage(item.messageId);
    },
    [onSelectMessage]
  );

  const handleBack = useCallback(() => {
    if (selectedMessageId) pendingFocusRef.current = { kind: 'row', messageId: selectedMessageId };
    onSelectMessage(null);
  }, [onSelectMessage, selectedMessageId]);

  const handleNotFound = useCallback(() => {
    setNotice(NOT_FOUND_SENTENCE);
    onSelectMessage(null);
  }, [onSelectMessage]);

  const handleRateLimited = useCallback((seconds: number) => {
    setRateLimitedUntil(Date.now() + seconds * 1000);
  }, []);

  const handleFilterChange = useCallback(
    (key: MessageFilterKey) => {
      setFilterKey(key);
      setNotice(null);
    },
    []
  );

  const showList = !isMobile || selectedMessageId === null;
  const showDetail = selectedMessageId !== null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-24)', paddingBottom: 'var(--space-32)' }}>
      <header style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px', color: 'var(--color-brand)' }}>
            forum
          </span>
          <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)', letterSpacing: '0.05em', textTransform: 'uppercase', fontWeight: 600 }}>
            Aprobación y envío
          </span>
        </div>
        <h1
          style={{
            fontSize: 'var(--font-size-display)',
            lineHeight: 'var(--line-height-display)',
            fontWeight: 600,
            color: 'var(--color-text-main)',
            margin: 0,
            letterSpacing: '-0.02em'
          }}
        >
          Mensajes
        </h1>
        <p style={{ ...secondaryTextStyle, margin: 0 }}>
          Revisa cada borrador antes de que llegue al cliente. Nada se envía sin una aprobación.
        </p>
      </header>

      {notice && (
        <div role="alert" style={warningAlertStyle}>
          {notice}
        </div>
      )}

      {!canRead ? (
        <div role="status" style={statusLineStyle}>
          Tu rol no permite consultar mensajes.
        </div>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: isMobile ? '1fr' : 'minmax(0, 5fr) minmax(0, 7fr)',
            gap: 'var(--space-24)',
            alignItems: 'start'
          }}
        >
          {showList && (
            <MessageList
              filterKey={filterKey}
              onFilterChange={handleFilterChange}
              customerFilter={customerFilter}
              onClearCustomerFilter={onClearCustomerFilter}
              list={list}
              onLoadMore={list.loadMore}
              onReload={list.reload}
              selectedMessageId={selectedMessageId}
              onSelect={handleSelect}
              registerRow={registerRow}
            />
          )}

          {showDetail && selectedMessageId ? (
            <MessageDetailPanel
              key={selectedMessageId}
              messageId={selectedMessageId}
              canAct={canAct}
              isMobile={isMobile}
              headingRef={headingRef}
              onBack={handleBack}
              onNotFound={handleNotFound}
              onRowPatch={list.patchItem}
              sendBlockedSeconds={sendBlockedSeconds}
              onRateLimited={handleRateLimited}
            />
          ) : (
            !isMobile && (
              <section
                aria-label="Detalle del mensaje"
                style={{ ...cardStyle, alignItems: 'center', textAlign: 'center', padding: 'var(--space-32)', minHeight: '240px', justifyContent: 'center' }}
              >
                <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '40px', color: 'var(--color-outline)' }}>
                  chat_bubble
                </span>
                <p style={{ ...secondaryTextStyle, margin: 0 }}>
                  Selecciona un mensaje para leerlo completo y decidir.
                </p>
              </section>
            )
          )}
        </div>
      )}
    </div>
  );
};
