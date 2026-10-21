// ABOUTME: Compose mode of the message component: the operator edits the final text and hands exactly that text to approval.
// ABOUTME: Keeps the text on every refusal, reuses the key while the text is unchanged, and resends the same request when unsure.
import { useEffect, useId, useRef, useState } from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { actionLabel, intentLabel } from '@/features/followups/utils/aiLabels';
import { countCodePoints } from '@/features/followups/utils/textLength';
import { DRAFT_BODY_MAX_CODE_POINTS, type SemanticAction, type SemanticTemplateIntent } from '@/features/followups/types';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { MessageOrigin, SubmitMessageRequest } from '../types';
import { maskPhone } from '../utils/format';
import { classifyFailure } from '../utils/messageErrors';
import { COPY, RATE_LIMIT_SECONDS, errorCodeSentence, originLabel, rateLimitedSentence } from '../utils/messageLabels';
import { canActOnMessages } from '../utils/permissions';
import { ConfirmGroup, useReturnFocus } from './ConfirmGroup';

export interface ComposeInput {
  customerId: string;
  customerDisplayName: string;
  contactId: string;
  recipientPhone: string;
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  origin: MessageOrigin;
  policyVersion: number;
  initialBody: string;
}

interface MessageComposeProps {
  input: ComposeInput;
  role: string | null;
  /** Opened by Corregir: no parent may be there to refresh a stale policy version. */
  fromCorrection: boolean;
  onSubmitted: (read: VersionedMessage) => void;
  onPolicyStale?: () => void;
}

type Phase =
  | { kind: 'editing' }
  | { kind: 'confirming' }
  | { kind: 'submitting' }
  | { kind: 'refused'; message: string }
  | { kind: 'uncertain' }
  | { kind: 'sessionEnded' };

/** One submit intent: the exact request a retry must resend. */
interface Attempt {
  key: string;
  body: string;
  policyVersion: number;
}

const LOCALE = 'es-419';

export function MessageCompose({ input, role, fromCorrection, onSubmitted, onPolicyStale }: MessageComposeProps) {
  const headingId = useId();
  const textId = useId();
  const counterId = useId();
  const [text, setText] = useState(input.initialBody);
  const [phase, setPhase] = useState<Phase>({ kind: 'editing' });
  const [stale, setStale] = useState(false);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [seenPolicyVersion, setSeenPolicyVersion] = useState(input.policyVersion);
  const mountRef = useRef<AbortController | null>(null);
  const inFlight = useRef(false);
  const focus = useReturnFocus(phase.kind === 'confirming' || phase.kind === 'submitting');

  // A fresh policy version from the parent clears the stale state and starts a new intent, except while unsure.
  if (input.policyVersion !== seenPolicyVersion) {
    setSeenPolicyVersion(input.policyVersion);
    if (phase.kind !== 'uncertain' && phase.kind !== 'submitting') {
      setStale(false);
      setAttempt(null);
      if (phase.kind === 'confirming') setPhase({ kind: 'editing' });
    }
  }

  useEffect(() => {
    const mount = new AbortController();
    mountRef.current = mount;
    return () => mount.abort();
  }, []);

  if (phase.kind === 'sessionEnded') {
    return (
      <section className="message-review" aria-label="Mensaje">
        <p className="message-review__placeholder">{COPY.sessionEnded}</p>
      </section>
    );
  }

  const length = countCodePoints(text);
  const overLimit = length > DRAFT_BODY_MAX_CODE_POINTS;
  const busy = phase.kind === 'submitting';
  const canAct = canActOnMessages(role);
  const canSubmit =
    canAct && text.trim() !== '' && !overLimit && !stale && !busy && phase.kind !== 'uncertain';
  const recipient = maskPhone(input.recipientPhone);

  const edit = (value: string) => {
    setText(value);
    if (phase.kind === 'confirming' || phase.kind === 'refused') setPhase({ kind: 'editing' });
  };

  const openConfirm = (opener: HTMLElement) => {
    if (!canSubmit) return;
    focus.remember(opener);
    // The key follows the text and the policy version: unchanged after a refusal reuses it, any edit rotates it.
    if (!attempt || attempt.body !== text || attempt.policyVersion !== input.policyVersion) {
      setAttempt({ key: crypto.randomUUID(), body: text, policyVersion: input.policyVersion });
    }
    setPhase({ kind: 'confirming' });
  };

  const dismiss = () => {
    focus.restoreOnClose();
    setPhase({ kind: 'editing' });
  };

  const submit = async (current: Attempt) => {
    const mount = mountRef.current;
    if (!mount || mount.signal.aborted || inFlight.current) return;
    inFlight.current = true;
    setPhase({ kind: 'submitting' });
    const request: SubmitMessageRequest = {
      contactId: input.contactId,
      action: input.action,
      templateIntent: input.templateIntent,
      body: current.body,
      locale: LOCALE,
      origin: input.origin
    };
    try {
      const read = await messageApi.submit(input.customerId, request, current.policyVersion, current.key, mount.signal);
      if (!mount.signal.aborted) onSubmitted(read);
    } catch (error) {
      if (mount.signal.aborted) return;
      const failure = classifyFailure(error);
      switch (failure.kind) {
        case 'dropped':
          return;
        case 'sessionEnded':
          setPhase({ kind: 'sessionEnded' });
          return;
        case 'uncertain':
          setPhase({ kind: 'uncertain' });
          return;
        case 'reload':
          if (failure.code === 'STALE_VERSION') {
            setStale(true);
            setPhase({ kind: 'editing' });
            onPolicyStale?.();
            return;
          }
          setPhase({ kind: 'refused', message: errorCodeSentence(failure.code) });
          return;
        case 'forbidden':
          setPhase({ kind: 'refused', message: COPY.forbidden });
          return;
        case 'notFound':
          setPhase({ kind: 'refused', message: errorCodeSentence('NOT_FOUND') });
          return;
        case 'rateLimited':
          setPhase({ kind: 'refused', message: rateLimitedSentence(RATE_LIMIT_SECONDS) });
          return;
        case 'refused':
          setPhase({ kind: 'refused', message: errorCodeSentence(failure.code) });
          return;
        default:
          setPhase({ kind: 'refused', message: COPY.actionFailed });
      }
    } finally {
      inFlight.current = false;
    }
  };

  return (
    <section className="message-review" aria-labelledby={headingId}>
      <header className="message-review__header">
        <div className="message-review__title">
          <h2 id={headingId} className="message-review__heading">
            {input.customerDisplayName || recipient}
          </h2>
          <p className="message-review__muted">Texto final antes de la aprobación</p>
        </div>
        <Badge variant="neutral">Borrador</Badge>
      </header>

      <dl className="message-review__facts">
        <div>
          <dt>Teléfono</dt>
          <dd>{recipient}</dd>
        </div>
        <div>
          <dt>Acción</dt>
          <dd>{actionLabel(input.action)}</dd>
        </div>
        <div>
          <dt>Plantilla</dt>
          <dd>{intentLabel(input.templateIntent)}</dd>
        </div>
        <div>
          <dt>Origen</dt>
          <dd>{originLabel(input.origin)}</dd>
        </div>
      </dl>

      <div className="message-field">
        <label htmlFor={textId} className="message-field__label">
          Texto final del mensaje
        </label>
        <textarea
          id={textId}
          className="message-field__input"
          value={text}
          rows={6}
          readOnly={!canAct || busy || phase.kind === 'uncertain'}
          aria-invalid={overLimit}
          aria-describedby={counterId}
          onChange={(event) => edit(event.target.value)}
        />
        <span
          id={counterId}
          className={`message-field__counter${overLimit ? ' message-field__counter--over' : ''}`}
        >
          {length}/{DRAFT_BODY_MAX_CODE_POINTS}
        </span>
      </div>

      {stale && (
        <p role="alert" className="message-review__notice message-review__notice--warning">
          {fromCorrection ? COPY.correctionStale : COPY.composeStale}
        </p>
      )}
      {phase.kind === 'refused' && (
        <p role="alert" className="message-review__notice message-review__notice--error">
          {phase.message}
        </p>
      )}
      {phase.kind === 'uncertain' && attempt && (
        <div role="alert" className="message-review__inline-error">
          <span>{COPY.unconfirmed}</span>
          <Button variant="secondary" size="sm" onClick={() => void submit(attempt)}>
            Reintentar
          </Button>
        </div>
      )}

      {canAct ? (
        <div className="message-actions">
          <Button
            variant="primary"
            size="sm"
            disabled={!canSubmit}
            aria-expanded={phase.kind === 'confirming' || busy}
            onClick={(event) => openConfirm(event.currentTarget)}
          >
            Enviar a aprobación
          </Button>
        </div>
      ) : (
        <p className="message-review__muted">{COPY.readOnly}</p>
      )}

      {(phase.kind === 'confirming' || busy) && attempt && (
        <ConfirmGroup
          label="Confirmar envío a aprobación"
          recipient={recipient}
          customerName={input.customerDisplayName}
          confirmLabel="Confirmar envío a aprobación"
          confirmVariant="primary"
          busy={busy}
          onConfirm={() => void submit(attempt)}
          onDismiss={dismiss}
        >
          <p>{COPY.submitConfirm}</p>
        </ConfirmGroup>
      )}
    </section>
  );
}
