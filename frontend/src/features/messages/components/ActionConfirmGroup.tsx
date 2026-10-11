// ABOUTME: The inline confirm group for approve, reject, cancel and send on a message detail.
// ABOUTME: Reject and cancel carry an optional note; send states the masked recipient.

import React, { useId } from 'react';
import { Button } from '@/shared/components/Button';
import type { ActionIntent, ActionState } from '../hooks/useMessageAction';
import { NOTE_MAX_LENGTH } from '../hooks/useMessageAction';
import { actionsRowStyle, errorAlertStyle, metaStyle, textareaStyle, warningAlertStyle } from './styles';

interface IntentCopy {
  readonly groupLabel: string;
  readonly question: (recipient: string) => string;
  readonly confirm: string;
  readonly withNote: boolean;
  readonly variant: 'primary' | 'danger';
}

const INTENT_COPY: Readonly<Record<ActionIntent, IntentCopy>> = {
  approve: {
    groupLabel: 'Confirmar aprobación',
    question: () => '¿Aprobar este mensaje? Quedará listo para enviarse.',
    confirm: 'Confirmar aprobación',
    withNote: false,
    variant: 'primary'
  },
  reject: {
    groupLabel: 'Confirmar rechazo',
    question: () => '¿Rechazar este mensaje? El cliente no lo recibirá.',
    confirm: 'Confirmar rechazo',
    withNote: true,
    variant: 'danger'
  },
  cancel: {
    groupLabel: 'Confirmar cancelación',
    question: () => '¿Cancelar este mensaje? Esta acción no se puede deshacer.',
    confirm: 'Confirmar cancelación',
    withNote: true,
    variant: 'danger'
  },
  send: {
    groupLabel: 'Confirmar envío',
    question: (recipient) => `¿Enviar este mensaje a ${recipient}? El envío es inmediato.`,
    confirm: 'Confirmar envío',
    withNote: false,
    variant: 'primary'
  }
};

interface ActionConfirmGroupProps {
  readonly state: Exclude<ActionState, { step: 'idle' } | { step: 'done' }>;
  readonly maskedRecipient: string;
  /** Seconds the confirm control stays disabled after a tenant wide rate limit; 0 when free. */
  readonly blockedSeconds: number;
  readonly onNoteChange: (note: string) => void;
  readonly onConfirm: () => void;
  readonly onDismiss: () => void;
}

export const ActionConfirmGroup: React.FC<ActionConfirmGroupProps> = ({
  state,
  maskedRecipient,
  blockedSeconds,
  onNoteChange,
  onConfirm,
  onDismiss
}) => {
  const noteId = useId();
  const counterId = useId();
  const copy = INTENT_COPY[state.intent];
  const busy = state.step === 'submitting';
  const blocked = state.intent === 'send' && blockedSeconds > 0;
  const refusal = state.step === 'refused' ? state : null;
  const refusalText =
    refusal && refusal.retryAfterSeconds !== undefined && blockedSeconds > 0
      ? `Se alcanzó el límite de envíos por minuto. Espera ${blockedSeconds} s.`
      : refusal?.message ?? null;

  return (
    <div role="group" aria-label={copy.groupLabel} style={warningAlertStyle}>
      <span>{copy.question(maskedRecipient)}</span>

      {copy.withNote && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          <label htmlFor={noteId} style={{ fontWeight: 500 }}>
            Nota (opcional)
          </label>
          <textarea
            id={noteId}
            value={state.note}
            maxLength={NOTE_MAX_LENGTH}
            disabled={busy}
            aria-describedby={counterId}
            onChange={(event) => onNoteChange(event.target.value)}
            style={textareaStyle}
          />
          <span id={counterId} style={{ ...metaStyle, color: 'var(--color-warning-text)' }}>
            {state.note.length}/{NOTE_MAX_LENGTH}
          </span>
        </div>
      )}

      {refusalText && (
        <div role="alert" style={{ ...errorAlertStyle, padding: 'var(--space-8) var(--space-12)' }}>
          {refusalText}
        </div>
      )}

      <div style={actionsRowStyle}>
        <Button variant={copy.variant} size="sm" onClick={onConfirm} disabled={busy || blocked} isLoading={busy}>
          {copy.confirm}
        </Button>
        <Button variant="ghost" size="sm" onClick={onDismiss} disabled={busy}>
          Cerrar
        </Button>
      </div>
    </div>
  );
};
