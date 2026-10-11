// ABOUTME: The message history: one row per applied event, ordered by sequence number.
// ABOUTME: Attaches approval and cancellation notes by event type and offers a retry on fetch failure.

import React from 'react';
import { Button } from '@/shared/components/Button';
import type { MessageDetail, MessageEvent } from '../types';
import {
  actorKindLabel,
  eventTypeLabel,
  failureCategoryLabel,
  statusLabel
} from '../utils/messageLabels';
import { formatDateTime } from '../utils/format';
import { errorAlertStyle, metaStyle, sectionHeadingStyle } from './styles';

interface EventTimelineProps {
  readonly detail: MessageDetail;
  readonly events: readonly MessageEvent[];
  readonly eventsError: boolean;
  readonly onRetry: () => void;
}

export function timelineRowText(event: MessageEvent): string {
  const actor = actorKindLabel(event.actorKind);
  if (event.statusFrom === null || event.statusFrom === event.statusTo) {
    return `${actor} · ${eventTypeLabel(event.eventType)}`;
  }
  return `${actor} · ${statusLabel(event.statusFrom)} → ${statusLabel(event.statusTo)}`;
}

export function noteForEvent(detail: MessageDetail, event: MessageEvent): string | null {
  if ((event.eventType === 'APPROVED' || event.eventType === 'REJECTED') && detail.approval?.note) {
    return detail.approval.note;
  }
  if (event.eventType === 'CANCELLED' && detail.cancellation?.note) {
    return detail.cancellation.note;
  }
  return null;
}

export const EventTimeline: React.FC<EventTimelineProps> = ({ detail, events, eventsError, onRetry }) => {
  const visible = events.filter((event) => event.applied);

  return (
    <section aria-labelledby="message-timeline-heading" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
      <h3 id="message-timeline-heading" style={sectionHeadingStyle}>
        Historial
      </h3>

      {eventsError ? (
        <div role="alert" style={errorAlertStyle}>
          <span>No se pudo cargar el historial del mensaje.</span>
          <div>
            <Button variant="secondary" size="sm" onClick={onRetry}>
              Reintentar
            </Button>
          </div>
        </div>
      ) : visible.length === 0 ? (
        <p role="status" style={{ ...metaStyle, margin: 0 }}>
          Sin eventos registrados todavía.
        </p>
      ) : (
        <ol aria-label="Historial del mensaje" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column' }}>
          {visible.map((event, index) => {
            const note = noteForEvent(detail, event);
            const isLast = index === visible.length - 1;
            return (
              <li
                key={event.sequenceNumber}
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'var(--space-16) 1fr',
                  columnGap: 'var(--space-12)',
                  paddingBottom: isLast ? 0 : 'var(--space-12)'
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }} aria-hidden="true">
                  <span
                    style={{
                      width: 'var(--space-8)',
                      height: 'var(--space-8)',
                      marginTop: 'var(--space-8)',
                      borderRadius: 'var(--radius-full)',
                      backgroundColor: event.failureCategory ? 'var(--color-error-text)' : 'var(--color-primary)',
                      flexShrink: 0
                    }}
                  />
                  {!isLast && <span style={{ flex: 1, width: '1px', backgroundColor: 'var(--color-outline-subtle)', marginTop: 'var(--space-4)' }} />}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)', minWidth: 0 }}>
                  <span style={{ fontSize: 'var(--font-size-secondary)', lineHeight: 'var(--line-height-secondary)', color: 'var(--color-text-main)' }}>
                    {timelineRowText(event)}
                  </span>
                  <time dateTime={event.occurredAt} style={metaStyle}>
                    {formatDateTime(event.occurredAt)}
                  </time>
                  {event.failureCategory && (
                    <span style={{ ...metaStyle, color: 'var(--color-error-text)' }}>
                      {failureCategoryLabel(event.failureCategory)}
                    </span>
                  )}
                  {note && (
                    <blockquote
                      style={{
                        margin: 0,
                        padding: 'var(--space-8) var(--space-12)',
                        borderLeft: '2px solid var(--color-outline)',
                        backgroundColor: 'var(--color-bg-inset)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: 'var(--font-size-secondary)',
                        lineHeight: 'var(--line-height-secondary)',
                        color: 'var(--color-text-supporting)',
                        whiteSpace: 'pre-wrap',
                        overflowWrap: 'anywhere'
                      }}
                    >
                      {note}
                    </blockquote>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
};
