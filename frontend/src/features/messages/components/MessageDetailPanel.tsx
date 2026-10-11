// ABOUTME: The message detail pane: header, body card, outcome card, actions row and history.
// ABOUTME: Owns the detail and action hooks for the selected message and patches the list row.

import React, { useCallback, useEffect } from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { actionLabel, intentLabel } from '@/features/followups/utils/aiLabels';
import { useMessageDetail } from '../hooks/useMessageDetail';
import { useMessageAction, type ActionIntent } from '../hooks/useMessageAction';
import { isTerminalStatus, type MessageDetail, type MessageStatus, type VersionedDetail } from '../types';
import {
  attemptOutcomeLabel,
  failureCategoryLabel,
  FROZEN_SENTENCE,
  READ_ONLY_SENTENCE,
  statusBadgeVariant,
  statusLabel
} from '../utils/messageLabels';
import { formatDateTime, maskPhone } from '../utils/format';
import { ActionConfirmGroup } from './ActionConfirmGroup';
import { EventTimeline } from './EventTimeline';
import {
  actionsRowStyle,
  cardStyle,
  definitionListStyle,
  definitionTermStyle,
  definitionValueStyle,
  errorAlertStyle,
  metaStyle,
  sectionHeadingStyle,
  secondaryTextStyle,
  statusLineStyle,
  warningAlertStyle
} from './styles';

interface MessageDetailPanelProps {
  readonly messageId: string;
  readonly canAct: boolean;
  readonly isMobile: boolean;
  readonly headingRef: React.RefObject<HTMLHeadingElement | null>;
  readonly onBack: () => void;
  readonly onNotFound: () => void;
  readonly onRowPatch: (messageId: string, patch: { status: MessageStatus; version: number }) => void;
  readonly sendBlockedSeconds: number;
  readonly onRateLimited: (seconds: number) => void;
}

function offeredIntents(detail: MessageDetail, canAct: boolean): readonly ActionIntent[] {
  if (!canAct) return [];
  if (detail.status === 'PENDING_APPROVAL') return ['approve', 'reject', 'cancel'];
  if (detail.status === 'APPROVED') return ['send', 'cancel'];
  return [];
}

function isFrozen(detail: MessageDetail): boolean {
  return detail.status === 'SENDING' && detail.outcomeUnknown;
}

function offersRefresh(detail: MessageDetail): boolean {
  return !isTerminalStatus(detail.status) && detail.status !== 'PENDING_APPROVAL' && detail.status !== 'APPROVED';
}

const INTENT_BUTTON: Readonly<Record<ActionIntent, { label: string; variant: 'primary' | 'secondary' | 'danger' }>> = {
  approve: { label: 'Aprobar', variant: 'primary' },
  reject: { label: 'Rechazar', variant: 'danger' },
  cancel: { label: 'Cancelar', variant: 'secondary' },
  send: { label: 'Enviar', variant: 'primary' }
};

export const MessageDetailPanel: React.FC<MessageDetailPanelProps> = ({
  messageId,
  canAct,
  isMobile,
  headingRef,
  onBack,
  onNotFound,
  onRowPatch,
  sendBlockedSeconds,
  onRateLimited
}) => {
  const detailState = useMessageDetail({ messageId, onNotFound });
  const { detail, version, events, eventsError, polling, step, error } = detailState;

  const handleReplaced = useCallback(
    (result: VersionedDetail, intent: ActionIntent) => {
      detailState.replace(result);
      if (intent === 'send') {
        detailState.startPolling(result.detail);
      }
    },
    [detailState]
  );

  const action = useMessageAction({
    messageId,
    version,
    onReplaced: handleReplaced,
    onConflict: detailState.reloadForConflict,
    onNotFound,
    onRateLimited,
    onActionStart: detailState.stopPolling
  });

  useEffect(() => {
    if (detail && version !== null) {
      onRowPatch(detail.messageId, { status: detail.status, version });
    }
  }, [detail, version, onRowPatch]);

  if (step === 'loading' || step === 'idle') {
    return (
      <section aria-label="Detalle del mensaje" style={cardStyle}>
        {isMobile && <BackButton onBack={onBack} />}
        <div role="status" aria-live="polite" style={statusLineStyle}>
          Cargando mensaje...
        </div>
      </section>
    );
  }

  if (step === 'error' || detail === null) {
    return (
      <section aria-label="Detalle del mensaje" style={cardStyle}>
        {isMobile && <BackButton onBack={onBack} />}
        <div role="alert" style={errorAlertStyle}>
          <span>{error ?? 'No se pudo cargar el mensaje.'}</span>
          <div>
            <Button variant="secondary" size="sm" onClick={detailState.refetch}>
              Reintentar
            </Button>
          </div>
        </div>
      </section>
    );
  }

  const intents = offeredIntents(detail, canAct);
  const frozen = isFrozen(detail);
  const actionState = action.state;
  const pendingAction =
    actionState.step === 'confirming' || actionState.step === 'submitting' || actionState.step === 'refused'
      ? actionState
      : null;
  const busy = actionState.step === 'submitting';
  const lastAttempt = detail.lastAttempt ?? (detail.attempts.length > 0 ? detail.attempts[detail.attempts.length - 1] : null);

  return (
    <article aria-labelledby="message-detail-heading" style={{ ...cardStyle, gap: 'var(--space-16)' }}>
      {isMobile && <BackButton onBack={onBack} />}

      <header style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 'var(--space-8)', flexWrap: 'wrap' }}>
          <h2
            id="message-detail-heading"
            ref={headingRef}
            tabIndex={-1}
            style={{
              fontSize: 'var(--font-size-section-heading)',
              lineHeight: 'var(--line-height-section-heading)',
              fontWeight: 600,
              margin: 0,
              color: 'var(--color-text-main)',
              overflowWrap: 'anywhere'
            }}
          >
            {detail.customerDisplayName}
          </h2>
          <Badge variant={statusBadgeVariant(detail.status)}>{statusLabel(detail.status)}</Badge>
        </div>
        <p style={{ ...secondaryTextStyle, margin: 0 }}>
          Destinatario: <span style={{ color: 'var(--color-text-main)', fontWeight: 500 }}>{detail.recipientPhone}</span>
        </p>
        {frozen && (
          <div role="alert" style={warningAlertStyle}>
            {FROZEN_SENTENCE}
          </div>
        )}
        {detail.contactRemoved && (
          <div role="status" style={warningAlertStyle}>
            El teléfono de este mensaje ya no está entre los contactos del cliente.
          </div>
        )}
      </header>

      <section aria-labelledby="message-body-heading" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
        <h3 id="message-body-heading" style={sectionHeadingStyle}>
          Mensaje
        </h3>
        <p
          style={{
            margin: 0,
            padding: 'var(--space-12) var(--space-16)',
            backgroundColor: 'var(--color-bg-inset)',
            border: '1px solid var(--color-outline-subtle)',
            borderRadius: 'var(--radius-md)',
            fontSize: 'var(--font-size-body)',
            lineHeight: 'var(--line-height-body)',
            color: 'var(--color-text-main)',
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere'
          }}
        >
          {detail.body}
        </p>
        <dl style={definitionListStyle}>
          <dt style={definitionTermStyle}>Acción</dt>
          <dd style={definitionValueStyle}>{actionLabel(detail.action)}</dd>
          <dt style={definitionTermStyle}>Plantilla</dt>
          <dd style={definitionValueStyle}>{intentLabel(detail.templateIntent)}</dd>
          <dt style={definitionTermStyle}>Idioma</dt>
          <dd style={definitionValueStyle}>{detail.locale}</dd>
        </dl>
      </section>

      {detail.attempts.length > 0 && lastAttempt && (
        <section aria-labelledby="message-outcome-heading" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          <h3 id="message-outcome-heading" style={sectionHeadingStyle}>
            Resultado del envío
          </h3>
          <dl style={definitionListStyle}>
            <dt style={definitionTermStyle}>Último intento</dt>
            <dd style={definitionValueStyle}>{attemptOutcomeLabel(lastAttempt.outcome)}</dd>
            {lastAttempt.failureCategory && (
              <>
                <dt style={definitionTermStyle}>Motivo</dt>
                <dd style={{ ...definitionValueStyle, color: 'var(--color-error-text)' }}>
                  {failureCategoryLabel(lastAttempt.failureCategory)}
                </dd>
              </>
            )}
            {detail.sentAt && (
              <>
                <dt style={definitionTermStyle}>Enviado</dt>
                <dd style={definitionValueStyle}>
                  <time dateTime={detail.sentAt}>{formatDateTime(detail.sentAt)}</time>
                </dd>
              </>
            )}
            {detail.deliveredAt && (
              <>
                <dt style={definitionTermStyle}>Entregado</dt>
                <dd style={definitionValueStyle}>
                  <time dateTime={detail.deliveredAt}>{formatDateTime(detail.deliveredAt)}</time>
                </dd>
              </>
            )}
            {detail.readAt && (
              <>
                <dt style={definitionTermStyle}>Leído</dt>
                <dd style={definitionValueStyle}>
                  <time dateTime={detail.readAt}>{formatDateTime(detail.readAt)}</time>
                </dd>
              </>
            )}
          </dl>
        </section>
      )}

      <section aria-label="Acciones" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
        {action.noticeText && (
          <div role="alert" style={warningAlertStyle}>
            <span>{action.noticeText}</span>
            <div>
              <Button variant="ghost" size="sm" onClick={action.dismissNotice}>
                Entendido
              </Button>
            </div>
          </div>
        )}

        {!canAct && (
          <p role="status" style={{ ...statusLineStyle, margin: 0 }}>
            {READ_ONLY_SENTENCE}
          </p>
        )}

        {pendingAction ? (
          <ActionConfirmGroup
            state={pendingAction}
            maskedRecipient={maskPhone(detail.recipientPhone)}
            blockedSeconds={sendBlockedSeconds}
            onNoteChange={action.setNote}
            onConfirm={() => void action.confirm()}
            onDismiss={action.dismiss}
          />
        ) : (
          <div style={actionsRowStyle}>
            {intents.map((intent) => (
              <Button
                key={intent}
                variant={INTENT_BUTTON[intent].variant}
                onClick={() => action.begin(intent)}
                disabled={busy || (intent === 'send' && sendBlockedSeconds > 0)}
              >
                {INTENT_BUTTON[intent].label}
              </Button>
            ))}
            {intents.includes('send') && sendBlockedSeconds > 0 && (
              <span role="status" style={metaStyle}>
                Envíos en pausa por {sendBlockedSeconds} s.
              </span>
            )}
            {offersRefresh(detail) && (
              <Button variant="secondary" onClick={detailState.refetch} disabled={polling === 'active'}>
                Actualizar
              </Button>
            )}
            {polling === 'active' && (
              <span role="status" aria-live="polite" style={metaStyle}>
                Siguiendo el envío...
              </span>
            )}
          </div>
        )}
      </section>

      <EventTimeline detail={detail} events={events} eventsError={eventsError} onRetry={detailState.retryEvents} />
    </article>
  );
};

const BackButton: React.FC<{ onBack: () => void }> = ({ onBack }) => (
  <div>
    <Button variant="ghost" size="sm" onClick={onBack}>
      <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
        arrow_back
      </span>
      Volver
    </Button>
  </div>
);
