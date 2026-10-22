// ABOUTME: Review mode of the message component: shows a submitted message, its outcome and history, and its allowed actions.
// ABOUTME: Offers controls only where the status and role allow; approving never sends, and only a confirmed Enviar calls /sends.
import { useCallback, useEffect, useId, useRef, type RefObject } from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { actionLabel, intentLabel } from '@/features/followups/utils/aiLabels';
import type { VersionedMessage } from '../api/messageApi';
import { useMessageDetail, type PollState } from '../hooks/useMessageDetail';
import { useReviewActions, type ConfirmState, type ReviewActions, type ReviewIntent } from '../hooks/useReviewActions';
import type { MessageResponse } from '../types';
import { formatDateTime, maskPhone } from '../utils/format';
import {
  COPY,
  attemptOutcomeLabel,
  changedSentence,
  failureCategoryLabel,
  originLabel,
  revisionLabel,
  statusBadgeVariant,
  statusLabel,
  waitSentence
} from '../utils/messageLabels';
import { canActOnMessages } from '../utils/permissions';
import { ConfirmGroup, useReturnFocus } from './ConfirmGroup';
import { EventTimeline } from './EventTimeline';

interface MessageDetailProps {
  messageId: string;
  role: string | null;
  customerDisplayName?: string;
  initial?: VersionedMessage;
  announce: (text: string) => void;
  onCorrect: (cancelled: MessageResponse) => void;
}

interface IntentCopy {
  trigger: string;
  label: string;
  sentence: string;
  confirm: string;
  variant: 'primary' | 'danger' | 'secondary';
  withNote: boolean;
}

const INTENT_COPY: Record<ReviewIntent, IntentCopy> = {
  approve: {
    trigger: 'Aprobar',
    label: 'Confirmar aprobación',
    sentence: 'Se aprobará exactamente el texto mostrado. Aprobar no envía el mensaje.',
    confirm: 'Confirmar aprobación',
    variant: 'primary',
    withNote: false
  },
  reject: {
    trigger: 'Rechazar',
    label: 'Confirmar rechazo',
    sentence: 'El mensaje se rechazará y no se enviará al cliente.',
    confirm: 'Confirmar rechazo',
    variant: 'danger',
    withNote: true
  },
  cancel: {
    trigger: 'Cancelar',
    label: 'Confirmar cancelación',
    sentence: 'El mensaje se cancelará y no se enviará al cliente.',
    confirm: 'Confirmar cancelación',
    variant: 'danger',
    withNote: true
  },
  send: {
    trigger: 'Enviar',
    label: 'Confirmar envío',
    sentence: COPY.sendConfirm,
    confirm: 'Confirmar envío',
    variant: 'primary',
    withNote: false
  },
  correct: {
    trigger: 'Corregir',
    label: 'Confirmar corrección',
    sentence: COPY.correctConfirm,
    confirm: 'Confirmar corrección',
    variant: 'secondary',
    withNote: false
  }
};

type Controls = { kind: 'intents'; intents: ReviewIntent[] } | { kind: 'refresh' } | { kind: 'none' };

/**
 * The action state matrix of spec 0011; the server still decides every transition. A removed contact can only be
 * rejected or cancelled (spec 0001 contact lifecycle rule): approving or sending is refused with CONTACT_NOT_OWNED,
 * and Corregir would copy the removed contact into a draft that can never be submitted.
 */
export function controlsFor(detail: MessageResponse, poll: PollState): Controls {
  switch (detail.status) {
    case 'PENDING_APPROVAL':
      return {
        kind: 'intents',
        intents: detail.contactRemoved ? ['reject', 'cancel'] : ['approve', 'reject', 'cancel', 'correct']
      };
    case 'APPROVED':
      return { kind: 'intents', intents: detail.contactRemoved ? ['cancel'] : ['send', 'cancel', 'correct'] };
    case 'QUEUED':
    case 'SENT':
      return poll === 'active' ? { kind: 'none' } : { kind: 'refresh' };
    case 'SENDING':
      return detail.outcomeUnknown || poll !== 'active' ? { kind: 'refresh' } : { kind: 'none' };
    case 'DELIVERED':
      // Not terminal: a later provider report can still move it to READ, so a manual read stays available.
      return { kind: 'refresh' };
    default:
      return { kind: 'none' };
  }
}

export function MessageDetail({ messageId, role, customerDisplayName, initial, announce, onCorrect }: MessageDetailProps) {
  const headingId = useId();
  const timelineHeadingId = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  // A status the poll applied is otherwise only visible; the live region says it, as the direct action result did.
  const onPolledChange = useCallback(
    (read: VersionedMessage) => announce(changedSentence(read.detail.status)),
    [announce]
  );
  const detail = useMessageDetail(messageId, initial, { onPolledChange });
  const actions = useReviewActions({ messageId, detail, announce, onCorrect });
  const { state } = detail;

  if (state.kind === 'sessionEnded' || state.kind === 'notFound') {
    return (
      <section className="message-review" aria-label="Mensaje">
        <p className="message-review__placeholder">
          {state.kind === 'sessionEnded' ? COPY.sessionEnded : COPY.notFound}
        </p>
      </section>
    );
  }

  if (state.kind === 'loading') {
    return (
      <section className="message-review" aria-label="Mensaje" aria-busy="true">
        <p className="message-review__placeholder">{COPY.loading}</p>
      </section>
    );
  }

  if (state.kind === 'error') {
    return (
      <section className="message-review" aria-label="Mensaje">
        <div role="alert" className="message-review__inline-error">
          <span>{COPY.loadFailed}</span>
          <Button variant="secondary" size="sm" onClick={detail.retry}>
            Reintentar
          </Button>
        </div>
      </section>
    );
  }

  const message = state.detail;
  const lastAttempt = message.attempts.length > 0 ? message.attempts[message.attempts.length - 1] : null;
  const failure = message.failureCategory ?? lastAttempt?.failureCategory ?? null;
  const frozen = message.status === 'SENDING' && message.outcomeUnknown;
  // A contact removed after the send is history; the stranded message warning only fits a message that can still be cancelled.
  const stranded = message.contactRemoved && (message.status === 'PENDING_APPROVAL' || message.status === 'APPROVED');

  return (
    <section className="message-review" aria-labelledby={headingId}>
      <header className="message-review__header">
        <div className="message-review__title">
          <h2 id={headingId} ref={headingRef} tabIndex={-1} className="message-review__heading">
            {customerDisplayName ?? maskPhone(message.recipientPhone)}
          </h2>
          <p className="message-review__muted">{revisionLabel(state.version)}</p>
        </div>
        <Badge variant={statusBadgeVariant(message.status)}>{statusLabel(message.status)}</Badge>
      </header>

      {frozen && <p className="message-review__notice message-review__notice--warning">{COPY.frozen}</p>}
      {stranded && (
        <p className="message-review__notice message-review__notice--warning">{COPY.contactRemoved}</p>
      )}

      <dl className="message-review__facts">
        <div>
          <dt>Teléfono</dt>
          <dd>{message.recipientPhone}</dd>
        </div>
        <div>
          <dt>Acción</dt>
          <dd>{actionLabel(message.action)}</dd>
        </div>
        <div>
          <dt>Plantilla</dt>
          <dd>{intentLabel(message.templateIntent)}</dd>
        </div>
        <div>
          <dt>Origen</dt>
          <dd>{originLabel(message.origin)}</dd>
        </div>
      </dl>

      <section className="message-review__section" aria-label="Texto del mensaje">
        <h3 className="message-review__section-heading">Texto del mensaje</h3>
        <p className="message-review__body">{message.body}</p>
      </section>

      {lastAttempt && (
        <section className="message-review__card" aria-label="Resultado del envío">
          <h3 className="message-review__section-heading">Resultado del envío</h3>
          <p>
            Intento {lastAttempt.attemptNumber} · {attemptOutcomeLabel(lastAttempt.outcome)}
          </p>
          {failure && <p className="message-review__failure">{failureCategoryLabel(failure)}</p>}
          <OutcomeTimes message={message} />
        </section>
      )}

      <ActionArea
        message={message}
        role={role}
        poll={detail.poll}
        actions={actions}
        customerDisplayName={customerDisplayName}
        focusAfterResult={headingRef}
      />

      <EventTimeline headingId={timelineHeadingId} events={detail.events} onRetry={detail.loadEvents} />
    </section>
  );
}

function OutcomeTimes({ message }: { message: MessageResponse }) {
  const rows = [
    ['Hora de envío', message.sentAt],
    ['Hora de entrega', message.deliveredAt],
    ['Hora de lectura', message.readAt]
  ].filter((row): row is [string, string] => row[1] !== null);
  if (rows.length === 0) return null;
  return (
    <dl className="message-review__facts">
      {rows.map(([label, at]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>
            <time dateTime={at}>{formatDateTime(at)}</time>
          </dd>
        </div>
      ))}
    </dl>
  );
}

interface ActionAreaProps {
  message: MessageResponse;
  role: string | null;
  poll: PollState;
  actions: ReviewActions;
  customerDisplayName?: string;
  focusAfterResult: RefObject<HTMLHeadingElement | null>;
}

function ActionArea({ message, role, poll, actions, customerDisplayName, focusAfterResult }: ActionAreaProps) {
  const { group } = actions;
  const focus = useReturnFocus(group !== null, focusAfterResult);
  // Actualizar can remove itself with its own result (DELIVERED to READ); focus then goes to the heading, not the body.
  const refreshFocused = useRef(false);
  useEffect(() => {
    if (!refreshFocused.current) return;
    refreshFocused.current = false;
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) focusAfterResult.current?.focus();
  }, [message, focusAfterResult]);

  if (!canActOnMessages(role)) {
    return <p className="message-review__muted">{COPY.readOnly}</p>;
  }

  const controls = controlsFor(message, poll);
  if (controls.kind === 'none' && !actions.alert && !group) return null;
  const busy = group?.step.kind === 'submitting';
  const dismiss = () => {
    focus.restoreOnClose();
    actions.close();
  };

  return (
    <section className="message-review__section" aria-label="Acciones">
      {controls.kind !== 'none' && (
        <div className="message-actions">
          {controls.kind === 'refresh' ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={(event) => {
                refreshFocused.current = document.activeElement === event.currentTarget;
                void actions.refresh();
              }}
            >
              Actualizar
            </Button>
          ) : (
            controls.intents.map((intent) => (
              <Button
                key={intent}
                variant={intent === 'approve' || intent === 'send' ? 'primary' : 'secondary'}
                size="sm"
                disabled={
                  busy ||
                  (intent === 'send' && actions.sendLocked) ||
                  (actions.unsureIntent !== null && actions.unsureIntent !== intent)
                }
                onClick={(event) => {
                  focus.remember(event.currentTarget);
                  actions.open(intent);
                }}
              >
                {INTENT_COPY[intent].trigger}
              </Button>
            ))
          )}
        </div>
      )}
      {actions.alert && (
        <p role="alert" className="message-review__notice message-review__notice--error">
          {actions.alert}
        </p>
      )}
      {group && (
        <ActionConfirm
          key={group.intent}
          group={group}
          actions={actions}
          recipient={maskPhone(message.recipientPhone)}
          customerName={customerDisplayName}
          onDismiss={dismiss}
        />
      )}
    </section>
  );
}

interface ActionConfirmProps {
  group: ConfirmState;
  actions: ReviewActions;
  recipient: string;
  customerName?: string;
  onDismiss: () => void;
}

function ActionConfirm({ group, actions, recipient, customerName, onDismiss }: ActionConfirmProps) {
  const copy = INTENT_COPY[group.intent];
  const { step } = group;
  const alertText = step.kind === 'refused' ? step.message : step.kind === 'uncertain' ? COPY.unconfirmed : null;
  // The lockout is announced once; the count that changes every second stays out of the live region.
  const alert =
    step.kind === 'rateLimited' ? (
      <p className="message-review__notice message-review__notice--error">
        <span role="alert">{COPY.sendLimitReached}</span> <span>{waitSentence(actions.lockSeconds)}</span>
      </p>
    ) : (
      alertText && (
        <p role="alert" className="message-review__notice message-review__notice--error">
          {alertText}
        </p>
      )
    );

  return (
    <ConfirmGroup
      label={copy.label}
      recipient={recipient}
      customerName={customerName}
      note={
        copy.withNote
          ? { value: group.note, onChange: actions.setNote, readOnly: step.kind === 'uncertain' }
          : undefined
      }
      alert={alert}
      confirmLabel={step.kind === 'uncertain' ? 'Reintentar' : copy.confirm}
      confirmVariant={copy.variant === 'danger' ? 'danger' : 'primary'}
      busy={step.kind === 'submitting'}
      confirmDisabled={step.kind === 'rateLimited'}
      onConfirm={() => void actions.confirm()}
      onDismiss={onDismiss}
    >
      <p>{copy.sentence}</p>
    </ConfirmGroup>
  );
}
