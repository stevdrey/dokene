// ABOUTME: The one open row of the delivery history: reads its detail and events, shows body, recipient, timestamps and guidance.
// ABOUTME: Owns and aborts its own reads on unmount, never renders providerMessageId or attempts, and offers no resend.
import { forwardRef, useCallback, useEffect, useId, useImperativeHandle, useRef, useState } from 'react';
import { Button } from '@/shared/components/Button';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { EventsState } from '../hooks/useMessageDetail';
import type { MessageResponse } from '../types';
import { deliveryOutcome, failureGuidance, HISTORY_COPY } from '../utils/deliveryOutcome';
import { formatDateTime, maskPhone } from '../utils/format';
import { classifyFailure } from '../utils/messageErrors';
import { COPY, failureCategoryLabel } from '../utils/messageLabels';
import { EventTimeline } from './EventTimeline';

type DetailState =
  | { kind: 'loading' }
  | { kind: 'loaded'; detail: MessageResponse; version: number }
  | { kind: 'error' }
  | { kind: 'forbidden' };

type RowEventsState = EventsState | { kind: 'forbidden' };

export interface OpenDeliveryRowHandle {
  /** A detail the row refresh read; applied under the version rule, then the events are read again. */
  applyRefresh: (read: VersionedMessage) => void;
}

interface OpenDeliveryRowCallbacks {
  onDetail: (messageId: string, read: VersionedMessage) => void;
  onNotFound: (messageId: string) => void;
  onSessionEnded: () => void;
}

interface OpenDeliveryRowProps extends OpenDeliveryRowCallbacks {
  messageId: string;
  /** The version the list row holds; when it passes the held detail (a list refresh brought a newer item) the row reads again. */
  version: number;
  onOpenMessage?: (messageId: string) => void;
}

/** The version rule: a higher version replaces, an equal one only carries `contactRemoved`, a lower one is dropped. */
function applyRead(held: DetailState, read: VersionedMessage): DetailState {
  if (held.kind !== 'loaded' || read.version > held.version) {
    return { kind: 'loaded', detail: read.detail, version: read.version };
  }
  if (read.version === held.version && read.detail.contactRemoved !== held.detail.contactRemoved) {
    return { ...held, detail: { ...held.detail, contactRemoved: read.detail.contactRemoved } };
  }
  return held;
}

export const OpenDeliveryRow = forwardRef<OpenDeliveryRowHandle, OpenDeliveryRowProps>(function OpenDeliveryRow(
  { messageId, version, onDetail, onNotFound, onSessionEnded, onOpenMessage },
  ref
) {
  const bodyHeadingId = useId();
  const timelineHeadingId = useId();
  const [detail, setDetail] = useState<DetailState>({ kind: 'loading' });
  const [events, setEvents] = useState<RowEventsState>({ kind: 'loading' });
  const mountRef = useRef<AbortController | null>(null);
  const detailSeq = useRef(0);
  const eventsSeq = useRef(0);
  const detailRef = useRef(detail);
  // The reads depend on the message only; a parent re-render with new callback identities must not read again.
  const callbacks = useRef<OpenDeliveryRowCallbacks>({ onDetail, onNotFound, onSessionEnded });
  callbacks.current = { onDetail, onNotFound, onSessionEnded };

  const commitDetail = useCallback((next: DetailState) => {
    detailRef.current = next;
    setDetail(next);
  }, []);

  const loadEvents = useCallback(() => {
    const mount = mountRef.current;
    if (!mount || mount.signal.aborted) return;
    const seq = ++eventsSeq.current;
    setEvents({ kind: 'loading' });
    messageApi.getEvents(messageId, mount.signal).then(
      (loaded) => {
        if (seq !== eventsSeq.current || mount.signal.aborted) return;
        setEvents({ kind: 'loaded', events: loaded });
      },
      (error: unknown) => {
        if (seq !== eventsSeq.current || mount.signal.aborted) return;
        const failure = classifyFailure(error);
        if (failure.kind === 'dropped') return;
        if (failure.kind === 'sessionEnded') return callbacks.current.onSessionEnded();
        setEvents(failure.kind === 'forbidden' ? { kind: 'forbidden' } : { kind: 'error' });
      }
    );
  }, [messageId]);

  const loadDetail = useCallback(() => {
    const mount = mountRef.current;
    if (!mount || mount.signal.aborted) return;
    const seq = ++detailSeq.current;
    // A refresh that lands first leaves the row loaded; this read then applies under the version rule, never over it.
    if (detailRef.current.kind !== 'loaded') commitDetail({ kind: 'loading' });
    messageApi.getDetail(messageId, mount.signal).then(
      (read) => {
        if (seq !== detailSeq.current || mount.signal.aborted) return;
        commitDetail(applyRead(detailRef.current, read));
        callbacks.current.onDetail(messageId, read);
      },
      (error: unknown) => {
        if (seq !== detailSeq.current || mount.signal.aborted) return;
        const failure = classifyFailure(error);
        if (failure.kind === 'dropped') return;
        if (failure.kind === 'sessionEnded') return callbacks.current.onSessionEnded();
        if (failure.kind === 'notFound') return callbacks.current.onNotFound(messageId);
        // A refresh that landed meanwhile keeps the panel loaded; a failed read never takes a shown detail away.
        if (detailRef.current.kind === 'loaded') return;
        commitDetail(failure.kind === 'forbidden' ? { kind: 'forbidden' } : { kind: 'error' });
      }
    );
  }, [commitDetail, messageId]);

  const retry = useCallback(() => {
    loadDetail();
    loadEvents();
  }, [loadDetail, loadEvents]);

  useImperativeHandle(
    ref,
    () => ({
      applyRefresh(read) {
        if (read.detail.messageId !== messageId) return;
        // An own read still in flight keeps running and lands under the same rule, so the server's version decides.
        commitDetail(applyRead(detailRef.current, read));
        loadEvents();
      }
    }),
    [commitDetail, loadEvents, messageId]
  );

  useEffect(() => {
    const mount = new AbortController();
    mountRef.current = mount;
    loadDetail();
    loadEvents();
    return () => mount.abort();
  }, [loadDetail, loadEvents]);

  useEffect(() => {
    // A list refresh raised the row above what the panel shows: read both again so header and panel agree.
    if (detail.kind === 'loaded' && version > detail.version) retry();
  }, [detail, retry, version]);

  if (detail.kind === 'loading') {
    return (
      <p className="message-review__muted" aria-busy="true">
        {COPY.loading}
      </p>
    );
  }
  if (detail.kind === 'forbidden') {
    // Focus stays on the row toggle while the panel loads, so the sentence that ends the loading state is announced.
    return (
      <p role="alert" className="message-review__muted">
        {HISTORY_COPY.forbidden}
      </p>
    );
  }
  if (detail.kind === 'error') {
    return (
      <div role="alert" className="message-review__inline-error">
        <span>{HISTORY_COPY.rowLoadFailed}</span>
        <Button variant="secondary" size="sm" onClick={retry}>
          {HISTORY_COPY.retry}
        </Button>
      </div>
    );
  }

  const message = detail.detail;
  const outcome = deliveryOutcome(message.status, message.outcomeUnknown);
  const timestamps: [string, string | null][] = [
    [HISTORY_COPY.acceptedAt, message.sentAt],
    [HISTORY_COPY.deliveredAt, message.deliveredAt],
    [HISTORY_COPY.readAt, message.readAt]
  ];

  return (
    <div className="delivery-row__detail">
      <section className="delivery-row__outcome" aria-label="Resultado del mensaje">
        <p className="delivery-row__outcome-label">{outcome.label}</p>
        {outcome.secondary && <p className="message-review__muted">{outcome.secondary}</p>}
        {outcome.outcome === 'failed' && (
          <div className="delivery-row__guidance">
            <p className="message-review__failure">{failureCategoryLabel(message.failureCategory ?? 'UNKNOWN')}</p>
            <p>{failureGuidance(message.failureCategory)}</p>
            <p>{HISTORY_COPY.noAutomaticResend}</p>
          </div>
        )}
      </section>
      <dl className="message-review__facts">
        <div>
          <dt>Teléfono</dt>
          <dd>{maskPhone(message.recipientPhone)}</dd>
        </div>
        {timestamps.map(
          ([label, at]) =>
            at !== null && (
              <div key={label}>
                <dt>{label}</dt>
                <dd>
                  <time dateTime={at}>{formatDateTime(at)}</time>
                </dd>
              </div>
            )
        )}
      </dl>
      <section className="message-review__section" aria-labelledby={bodyHeadingId}>
        <h3 id={bodyHeadingId} className="message-review__section-heading">
          Texto del mensaje
        </h3>
        <p className="message-review__body">{message.body}</p>
      </section>
      {onOpenMessage && (
        <div className="message-actions">
          <Button variant="secondary" size="sm" onClick={() => onOpenMessage(message.messageId)}>
            {HISTORY_COPY.openInReview}
          </Button>
        </div>
      )}
      {events.kind === 'forbidden' ? (
        <p role="alert" className="message-review__muted">
          {HISTORY_COPY.forbidden}
        </p>
      ) : (
        <EventTimeline headingId={timelineHeadingId} events={events} onRetry={loadEvents} />
      )}
    </div>
  );
});
