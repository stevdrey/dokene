// ABOUTME: The read only history of one customer's messages: collapsed rows with their delivery outcome, one open row at a time.
// ABOUTME: Renders nothing for an inactive tenant or a missing role, remounts on a tenant or customer change, and only ever reads.
import { useCallback, useId, useRef, type MouseEvent, type ReactNode, type Ref } from 'react';
import { httpClient } from '@/shared/api/httpClient';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { useSettleIntent } from '@/shared/hooks/useSettleIntent';
import { isFocusLost } from '@/shared/utils/focus';
import { actionLabel } from '@/features/followups/utils/aiLabels';
import type { VersionedMessage } from '../api/messageApi';
import { useDeliveryHistory, type HistoryRow, type ReadFailure } from '../hooks/useDeliveryHistory';
import { deliveryOutcome, HISTORY_COPY } from '../utils/deliveryOutcome';
import { formatDateTime } from '../utils/format';
import { COPY, originLabel } from '../utils/messageLabels';
import { canReadMessages } from '../utils/permissions';
import { OpenDeliveryRow, type OpenDeliveryRowHandle } from './OpenDeliveryRow';
import './MessageReview.css';
import './DeliveryHistory.css';

export interface DeliveryHistoryProps {
  tenantId: string;
  customerId: string;
  role: string | null;
  onOpenMessage?: (messageId: string) => void;
}

export function DeliveryHistory(props: DeliveryHistoryProps) {
  const activeTenant = httpClient.getTenantId();
  if (activeTenant === null || activeTenant !== props.tenantId || !canReadMessages(props.role)) return null;
  // Keying by tenant and customer remounts everything, so no state of a previous value renders even once.
  return <DeliveryHistoryList key={`${props.tenantId}|${props.customerId}`} {...props} />;
}

const panelId = (messageId: string) => `delivery-row-${messageId}`;

function failureSentence(failure: ReadFailure, plain: string): string {
  return failure === 'forbidden' ? HISTORY_COPY.forbidden : plain;
}

interface BusyButtonProps {
  busy: boolean;
  onClick: (event: MouseEvent<HTMLButtonElement>) => void;
  children: string;
  variant?: 'primary' | 'secondary' | 'ghost';
  buttonRef?: Ref<HTMLButtonElement>;
}

/** Disabled through aria while its read is in flight, so keyboard focus stays where it was. */
function BusyButton({ busy, onClick, children, variant = 'secondary', buttonRef }: BusyButtonProps) {
  const handle = (event: MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    if (!busy) onClick(event);
  };
  return (
    <Button ref={buttonRef} type="button" variant={variant} size="sm" aria-disabled={busy || undefined} onClick={handle}>
      {children}
    </Button>
  );
}

function DeliveryHistoryList({ customerId, onOpenMessage }: DeliveryHistoryProps) {
  const headingId = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const loadMoreRef = useRef<HTMLButtonElement>(null);
  const openRowRef = useRef<OpenDeliveryRowHandle>(null);

  const onRowRefreshed = useCallback((_messageId: string, read: VersionedMessage) => {
    openRowRef.current?.applyRefresh(read);
  }, []);

  const history = useDeliveryHistory(customerId, { onRowRefreshed });
  const { state, openMessageId, announcement } = history;

  // Which read the focused control started, so focus has somewhere to go when that control leaves with the reply.
  const reading = state.kind === 'loading' || (state.kind === 'ready' && state.loadingMore);
  const armFocusedRead = useSettleIntent<'firstPage' | 'more'>(reading, (settled) => {
    if (!isFocusLost()) return;
    // Reintentar unmounts with its alert and Cargar más with the last page; focus moves on instead of vanishing.
    if (settled === 'more' && loadMoreRef.current) loadMoreRef.current.focus();
    else headingRef.current?.focus();
  });

  const startRead = (kind: 'firstPage' | 'more', start: () => void) => (event: MouseEvent<HTMLButtonElement>) => {
    armFocusedRead(document.activeElement === event.currentTarget ? kind : null);
    start();
  };

  if (state.kind === 'sessionEnded') {
    return (
      <section className="delivery-history" aria-labelledby={headingId}>
        <h2 id={headingId} className="delivery-history__heading">
          {HISTORY_COPY.heading}
        </h2>
        <p className="message-review__placeholder">{COPY.sessionEnded}</p>
      </section>
    );
  }

  const liveText = state.kind === 'loading' ? HISTORY_COPY.loading : announcement?.text ?? '';
  const liveKey = state.kind === 'loading' ? 'loading' : announcement?.seq ?? 0;

  return (
    <section className="delivery-history" aria-labelledby={headingId} aria-busy={state.kind === 'loading' || undefined}>
      <header className="delivery-history__header">
        <h2 id={headingId} ref={headingRef} tabIndex={-1} className="delivery-history__heading">
          {HISTORY_COPY.heading}
        </h2>
        {state.kind === 'ready' && (
          <BusyButton busy={state.refreshing} onClick={history.refreshList}>
            {HISTORY_COPY.refreshList}
          </BusyButton>
        )}
      </header>
      <p role="status" className="delivery-history__live">
        <span key={liveKey}>{liveText}</span>
      </p>
      {state.kind === 'forbidden' && (
        // Nothing takes focus and the status region is empty here, so the denial announces itself.
        <p role="alert" className="message-review__placeholder">
          {HISTORY_COPY.forbidden}
        </p>
      )}
      {state.kind === 'error' && (
        <div role="alert" className="message-review__inline-error">
          <span>{HISTORY_COPY.loadFailed}</span>
          <Button variant="secondary" size="sm" onClick={startRead('firstPage', history.retryFirstPage)}>
            {HISTORY_COPY.retry}
          </Button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          {state.refreshFailure && (
            <p role="alert" className="message-review__notice message-review__notice--error">
              {failureSentence(state.refreshFailure, HISTORY_COPY.refreshFailed)}
            </p>
          )}
          {state.rows.length === 0 ? (
            <p className="message-review__placeholder">{HISTORY_COPY.empty}</p>
          ) : (
            <ol className="delivery-history__rows" aria-label="Mensajes del cliente">
              {state.rows.map((row) => (
                <DeliveryRow
                  key={row.item.messageId}
                  row={row}
                  open={openMessageId === row.item.messageId}
                  onToggle={history.toggleRow}
                  onRefresh={history.refreshRow}
                >
                  {openMessageId === row.item.messageId && (
                    <OpenDeliveryRow
                      key={row.item.messageId}
                      ref={openRowRef}
                      messageId={row.item.messageId}
                      version={row.item.version}
                      onDetail={history.applyDetail}
                      onNotFound={history.markNotFound}
                      onSessionEnded={history.endSession}
                      onOpenMessage={onOpenMessage}
                    />
                  )}
                </DeliveryRow>
              ))}
            </ol>
          )}
          {state.moreFailure && (
            <div role="alert" className="message-review__inline-error">
              <span>
                {failureSentence(state.moreFailure, HISTORY_COPY.moreFailed)}
                {state.moreFailures >= 2 && ` ${HISTORY_COPY.moreKeepsFailing}`}
              </span>
              <BusyButton busy={state.loadingMore || state.refreshing} onClick={startRead('more', history.loadMore)}>
                {HISTORY_COPY.retry}
              </BusyButton>
            </div>
          )}
          {state.nextCursor !== null && (
            <div className="delivery-history__more">
              <BusyButton
                busy={state.loadingMore || state.refreshing}
                buttonRef={loadMoreRef}
                onClick={startRead('more', history.loadMore)}
              >
                {HISTORY_COPY.loadMore}
              </BusyButton>
            </div>
          )}
        </>
      )}
    </section>
  );
}

interface DeliveryRowProps {
  row: HistoryRow;
  open: boolean;
  onToggle: (messageId: string) => void;
  onRefresh: (messageId: string) => void;
  children?: ReactNode;
}

function DeliveryRow({ row, open, onToggle, onRefresh, children }: DeliveryRowProps) {
  const { item } = row;
  const outcome = deliveryOutcome(item.status, item.outcomeUnknown);
  const id = panelId(item.messageId);
  const showRefresh = !row.notFound && (open || outcome.outcome === 'uncertain');
  const toggleRef = useRef<HTMLButtonElement>(null);
  // Actualizar can leave with its own reply (an uncertain row resolved, a 404); the toggle then takes its focus.
  const armRefreshFocused = useSettleIntent<true>(row.refreshing, () => {
    if (!showRefresh && isFocusLost()) toggleRef.current?.focus();
  });
  const classes = ['delivery-row', `delivery-row--${outcome.outcome}`, open ? 'delivery-row--open' : ''].join(' ').trim();

  return (
    <li className={classes}>
      <div className="delivery-row__header">
        <button
          ref={toggleRef}
          type="button"
          className="delivery-row__toggle"
          aria-expanded={open}
          aria-controls={id}
          aria-disabled={row.notFound || undefined}
          onClick={() => {
            if (!row.notFound) onToggle(item.messageId);
          }}
        >
          <span className="delivery-row__line">
            <Badge variant={outcome.variant}>{outcome.label}</Badge>
            <span className="delivery-row__meta">
              <span>{actionLabel(item.action)}</span>
              <span aria-hidden="true">·</span>
              <span>{originLabel(item.origin)}</span>
              <span aria-hidden="true">·</span>
              <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
            </span>
          </span>
          {outcome.secondary && <span className="delivery-row__secondary">{outcome.secondary}</span>}
          {item.contactRemoved && <span className="delivery-row__tag">{HISTORY_COPY.contactRemoved}</span>}
        </button>
        {showRefresh && (
          <BusyButton
            busy={row.refreshing}
            onClick={(event) => {
              armRefreshFocused(document.activeElement === event.currentTarget ? true : null);
              onRefresh(item.messageId);
            }}
          >
            {HISTORY_COPY.refreshRow}
          </BusyButton>
        )}
      </div>
      {row.refreshFailure && (
        <p role="alert" className="message-review__notice message-review__notice--error">
          {failureSentence(row.refreshFailure, COPY.refreshFailed)}
        </p>
      )}
      {row.notFound && (
        <p role="alert" className="message-review__notice message-review__notice--warning">
          {COPY.notFound}
        </p>
      )}
      <div id={id} className="delivery-row__panel" hidden={!open}>
        {children}
      </div>
    </li>
  );
}
