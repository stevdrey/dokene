// ABOUTME: The message history: one row per applied event, ordered by sequence number, read from the event's type.
// ABOUTME: v1 notes are write only, so no row carries a note; a failed read offers a retry and leaves the detail intact.
import { Button } from '@/shared/components/Button';
import type { MessageEventItem } from '../types';
import type { EventsState } from '../hooks/useMessageDetail';
import { formatDateTime } from '../utils/format';
import {
  COPY,
  actorKindLabel,
  eventTypeLabel,
  failureCategoryLabel,
  statusLabel
} from '../utils/messageLabels';

export function timelineRowText(event: MessageEventItem): string {
  const actor = actorKindLabel(event.actorKind);
  if (event.statusFrom === null || event.statusFrom === event.statusTo) {
    return `${actor} · ${eventTypeLabel(event.type)}`;
  }
  return `${actor} · ${statusLabel(event.statusFrom)} → ${statusLabel(event.statusTo)}`;
}

interface EventTimelineProps {
  headingId: string;
  events: EventsState;
  onRetry: () => void;
}

export function EventTimeline({ headingId, events, onRetry }: EventTimelineProps) {
  return (
    <section className="message-review__section" aria-labelledby={headingId}>
      <h3 id={headingId} className="message-review__section-heading">
        Historial
      </h3>
      {events.kind === 'loading' && (
        <p className="message-review__muted" aria-busy="true">
          {COPY.eventsLoading}
        </p>
      )}
      {events.kind === 'error' && (
        <div role="alert" className="message-review__inline-error">
          <span>{COPY.eventsFailed}</span>
          <Button variant="secondary" size="sm" onClick={onRetry}>
            Reintentar
          </Button>
        </div>
      )}
      {events.kind === 'loaded' && <EventRows events={events.events.filter((event) => event.applied)} />}
    </section>
  );
}

function EventRows({ events }: { events: readonly MessageEventItem[] }) {
  if (events.length === 0) return <p className="message-review__muted">{COPY.eventsEmpty}</p>;
  return (
    <ol className="message-timeline" aria-label="Historial del mensaje">
      {events.map((event) => (
        <li
          key={event.sequenceNumber}
          className={`message-timeline__row${event.failureCategory ? ' message-timeline__row--failed' : ''}`}
        >
          <span className="message-timeline__text">{timelineRowText(event)}</span>
          <time className="message-timeline__time" dateTime={event.occurredAt}>
            {formatDateTime(event.occurredAt)}
          </time>
          {event.failureCategory && (
            <span className="message-timeline__failure">{failureCategoryLabel(event.failureCategory)}</span>
          )}
        </li>
      ))}
    </ol>
  );
}
