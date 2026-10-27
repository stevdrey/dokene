import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Button } from '@/shared/components/Button';
import { isFocusLost } from '@/shared/utils/focus';
import {
  useFollowUpAssistant,
  type AssistantErrorKind,
  type PendingDiscard,
  type QueueRefreshReason
} from '@/features/followups/hooks/useFollowUpAssistant';
import {
  AI_MANUAL_FALLBACK,
  actionLabel,
  confidenceLabel,
  ineligibleReasonLabel,
  intentLabel,
  noDraftReasonLabel,
  noRecommendationReasonLabel,
  unavailableMessage
} from '@/features/followups/utils/aiLabels';
import { DRAFT_BODY_MAX_CODE_POINTS } from '@/features/followups/types';
import type {
  DraftResponse,
  RecommendationResponse,
  SemanticAction,
  SemanticTemplateIntent
} from '@/features/followups/types';
import { countCodePoints } from '@/features/followups/utils/textLength';
import { formatEvidenceEntry, formatWarningText } from '@/features/followups/utils/draftEvidence';

interface AiAssistantPanelProps {
  customerId: string;
  /** Policy version of the queue item currently shown; anything produced for another version is stale. */
  policyVersion: number;
  /** UI affordance only: the backend remains the authority for AI permissions. */
  canUseAi: boolean;
  /** Asks the workbench to reload the queue after the follow-up changed under the assistant. */
  onRequestRefresh: (reason?: QueueRefreshReason) => Promise<boolean>;
  /**
   * Hands the current draft, with the operator's edits, to message review. Passed only for a role that may submit
   * messages; it never submits anything itself, so sending still needs the explicit review, approval and send.
   */
  onReviewDraft?: (draft: ReviewableDraft) => void;
}

/**
 * What message review needs from an AI draft: its semantic action and template intent, the edited text, and the
 * policy version the draft was written for. The submit stays bound to that version, so a draft never rides on a
 * newer evaluation it was not written for.
 */
export interface ReviewableDraft {
  action: SemanticAction;
  templateIntent: SemanticTemplateIntent;
  body: string;
  policyVersion: number;
}

const FORBIDDEN_MESSAGE = 'Tu rol no permite usar el asistente IA en este espacio de trabajo.';

const ERROR_MESSAGES: Record<AssistantErrorKind, string> = {
  forbidden: FORBIDDEN_MESSAGE,
  'rate-limited': 'Hay demasiadas solicitudes al asistente IA. Espera un momento e inténtalo de nuevo.',
  failed: `No pudimos consultar al asistente IA. ${AI_MANUAL_FALLBACK}`
};

const STALE_CHANGED_MESSAGE =
  'El seguimiento cambió mientras consultabas al asistente. Actualizamos la lista; actualiza la recomendación.';
const STALE_REFRESHING_MESSAGE = 'El seguimiento cambió mientras consultabas al asistente.';
const STALE_REFRESH_FAILED_MESSAGE = 'No pudimos actualizar la lista de seguimientos.';

type DiscardKind = NonNullable<PendingDiscard>;

const DISCARD_COPY = {
  'regenerate-draft': {
    message: 'Regenerar reemplazará tus cambios en el borrador.',
    confirm: 'Reemplazar mis cambios'
  },
  requery: {
    message: 'Consultar de nuevo descartará el borrador y tus cambios.',
    confirm: 'Descartar y consultar'
  }
} as const;

const STALE_RESULT_MESSAGE = 'Esta recomendación ya no está actualizada porque el seguimiento cambió.';

const alertStyle: React.CSSProperties = {
  backgroundColor: 'var(--color-warning-bg)',
  color: 'var(--color-warning-text)',
  border: '1px solid var(--color-warning-border)',
  borderRadius: 'var(--radius-md)',
  padding: 'var(--space-12)',
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--space-8)',
  alignItems: 'flex-start'
};

const noteStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  color: 'var(--color-text-muted)'
};

const labelStyle: React.CSSProperties = {
  fontSize: 'var(--font-size-meta)',
  fontWeight: 600,
  color: 'var(--color-text-muted)',
  textTransform: 'uppercase',
  letterSpacing: '0.04em'
};

const bodyTextStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  color: 'var(--color-text-main)',
  overflowWrap: 'anywhere'
};

const draftTextareaStyle: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  minHeight: '120px',
  padding: 'var(--space-12)',
  border: '1px solid var(--color-outline)',
  borderRadius: 'var(--radius-md)',
  font: 'inherit',
  fontSize: 'var(--font-size-secondary)',
  color: 'var(--color-text-main)',
  backgroundColor: 'var(--color-surface)',
  resize: 'vertical'
};

const actionsRowStyle: React.CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 'var(--space-8)' };

export const AiAssistantPanel: React.FC<AiAssistantPanelProps> = ({
  customerId,
  policyVersion,
  canUseAi,
  onRequestRefresh,
  onReviewDraft
}) => {
  const headingId = useId();
  const recHeadingId = useId();
  const draftLabelId = useId();
  const rawValuesId = useId();
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const panelHeadingRef = useRef<HTMLHeadingElement | null>(null);
  const recHeadingRef = useRef<HTMLHeadingElement | null>(null);
  // Set when the operator activates a control in the panel: most actions swap the activated
  // button for another branch's button, so focus must be recovered once the outcome renders.
  const focusRecoveryRef = useRef(false);
  // Whether the panel is still waiting for an outcome (request or queue refresh in flight).
  const awaitingOutcomeRef = useRef(false);
  // Discard confirmation: focus moves to its confirm button when it opens and, on cancel, back
  // to the control that opened it (or the equivalent button rendered in its place).
  const discardConfirmRef = useRef<HTMLButtonElement | null>(null);
  const discardOpenerRef = useRef<HTMLElement | null>(null);
  const cancelledDiscardRef = useRef<DiscardKind | null>(null);
  const regenerateButtonRef = useRef<HTMLButtonElement | null>(null);
  const requeryButtonRef = useRef<HTMLButtonElement | null>(null);

  const assistant = useFollowUpAssistant({ customerId, policyVersion, onRequestRefresh });
  const { recommendation, draft, draftText, draftDirty, pendingDiscard, copyStatus } = assistant;

  const recLoading = recommendation.kind === 'loading';
  const draftLoading = draft.kind === 'loading';
  const busy = recLoading || draftLoading;

  const recData: RecommendationResponse | null = recommendation.kind === 'result' ? recommendation.data : null;
  const versionDrifted = recommendation.kind === 'result' && recommendation.version !== policyVersion;
  // Explicit `stale` (409 / STALE_STATE / version mismatch) or a queue refresh that moved the item on.
  const isStale = recommendation.kind === 'stale' || versionDrifted;
  // A derived stale result already means the queue moved on, so no refresh is pending.
  const staleRefresh = recommendation.kind === 'stale' ? recommendation.refresh : 'done';
  let staleMessage = STALE_RESULT_MESSAGE;
  if (recommendation.kind === 'stale') {
    if (staleRefresh === 'pending') staleMessage = STALE_REFRESHING_MESSAGE;
    else if (staleRefresh === 'failed') staleMessage = STALE_REFRESH_FAILED_MESSAGE;
    else staleMessage = STALE_CHANGED_MESSAGE;
  }
  const draftData: DraftResponse | null = draft.kind === 'result' ? draft.data : null;
  const draftTimeZone = draftData?.evaluation?.tenantTimeZone ?? null;
  const shownDraft = draftData?.draft ?? null;
  // Formatted once per draft, not on every keystroke in the editor.
  const draftFacts = useMemo(() => {
    if (shownDraft === null) {
      return null;
    }
    const evidence = shownDraft.evidence.map((raw) => ({ raw, text: formatEvidenceEntry(raw, draftTimeZone) }));
    const warnings = shownDraft.warnings.map((raw) => ({ raw, text: formatWarningText(raw, draftTimeZone) }));
    const changedRaw = [...evidence, ...warnings].filter((entry) => entry.text !== entry.raw).map((entry) => entry.raw);
    return { evidence, warnings, changedRaw };
  }, [shownDraft, draftTimeZone]);
  // Tied to the draft it was opened for, so a new draft starts collapsed without an effect.
  const [rawValuesShownFor, setRawValuesShownFor] = useState<typeof shownDraft>(null);
  const rawValuesShown = shownDraft !== null && rawValuesShownFor === shownDraft;
  const keptDraft = isStale && draftDirty && draftText !== '';

  const showRecommendation = recData !== null && !isStale;
  const showDraftEditor = showRecommendation && draftData?.status === 'AVAILABLE' && !!draftData.draft;

  useEffect(() => {
    if (showDraftEditor) {
      textareaRef.current?.focus();
    }
  }, [draft.kind === 'result' ? draft.data : null, showDraftEditor]);

  // Runs after the editor effect above: when the activated control was removed (or disabled while
  // loading) focus fell to the body, so move it to the recommendation heading, or the panel
  // heading, instead of losing the operator's place. Focus moved elsewhere meanwhile is kept.
  // A queue refresh still in flight may remove this detail, so recovery waits for it to settle
  // (the workbench recovers focus if the detail is gone).
  const refreshPending = staleRefresh === 'pending' || assistant.ineligibleRefresh === 'pending';
  useEffect(() => {
    awaitingOutcomeRef.current = busy || refreshPending;
    if (busy || refreshPending || !focusRecoveryRef.current) return;
    focusRecoveryRef.current = false;
    const cancelled = cancelledDiscardRef.current;
    cancelledDiscardRef.current = null;
    if (!isFocusLost()) return;
    let target: HTMLElement | null = null;
    if (cancelled) {
      const opener = discardOpenerRef.current;
      target = opener?.isConnected
        ? opener
        : cancelled === 'regenerate-draft'
          ? regenerateButtonRef.current
          : requeryButtonRef.current;
    }
    (target ?? recHeadingRef.current ?? panelHeadingRef.current)?.focus();
  });

  let liveMessage = '';
  if (isStale && staleRefresh === 'pending') liveMessage = 'Actualizando la lista…';
  else if (assistant.ineligibleRefresh === 'pending') liveMessage = 'Actualizando la lista…';
  else if (recLoading) liveMessage = 'Consultando al asistente…';
  else if (draftLoading) liveMessage = 'Redactando borrador…';
  else if (copyStatus === 'copied') liveMessage = 'Borrador copiado';
  else if (copyStatus === 'failed') liveMessage = 'No se pudo copiar el borrador';
  else if (showDraftEditor) liveMessage = 'Borrador listo para revisar';
  else if (showRecommendation && draftData?.status === 'NO_DRAFT') liveMessage = 'Borrador no disponible';
  else if (showRecommendation && draftData?.status === 'INELIGIBLE') {
    liveMessage = 'Borrador no disponible para este cliente';
  } else if (showRecommendation && recData?.status === 'AVAILABLE') liveMessage = 'Recomendación lista';
  else if (showRecommendation && recData?.status === 'NO_RECOMMENDATION') liveMessage = 'Sin recomendación de la IA';
  else if (showRecommendation && recData?.status === 'INELIGIBLE') {
    liveMessage = 'Recomendación no disponible para este cliente';
  }

  const recErrorKind: AssistantErrorKind | null = recommendation.kind === 'error' ? recommendation.error : null;
  const draftErrorKind: AssistantErrorKind | null = draft.kind === 'error' ? draft.error : null;

  const rememberDiscardOpener = () => {
    discardOpenerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  };

  const askRequery = () => {
    if (draftDirty) {
      rememberDiscardOpener();
      assistant.askDiscard('requery');
    } else {
      void assistant.requestRecommendation();
    }
  };

  const askRegenerate = () => {
    if (draftDirty) {
      rememberDiscardOpener();
      assistant.askDiscard('regenerate-draft');
    } else if (recData) {
      void assistant.requestDraft(recData);
    }
  };

  // A regenerate confirmation belongs to the draft of a *current* recommendation: if the item
  // moved on (derived staleness) it is dropped, so confirming can never request a draft for an
  // obsolete recommendation. Requerying stays available.
  const activeDiscard = pendingDiscard === 'regenerate-draft' && isStale ? null : pendingDiscard;

  // Opening the confirmation always moves focus to it, even when the opener stays (e.g.
  // "Consultar de nuevo" above the editor), so keyboard users land on the decision.
  useEffect(() => {
    if (activeDiscard) {
      discardConfirmRef.current?.focus();
    }
  }, [activeDiscard]);

  const cancelDiscard = () => {
    cancelledDiscardRef.current = activeDiscard;
    assistant.cancelDiscard();
  };

  const confirmDiscard = () => {
    if (activeDiscard === 'requery') {
      void assistant.requestRecommendation();
    } else if (activeDiscard === 'regenerate-draft' && recData) {
      void assistant.requestDraft(recData);
    }
  };

  const renderDiscardConfirm = (): React.ReactNode => {
    if (!activeDiscard) return null;
    const copy = DISCARD_COPY[activeDiscard];
    return (
      <div role="group" aria-label="Confirmar reemplazo del borrador" style={alertStyle}>
        <span>{copy.message}</span>
        <div style={actionsRowStyle}>
          <Button ref={discardConfirmRef} variant="danger" size="sm" onClick={confirmDiscard}>
            {copy.confirm}
          </Button>
          <Button variant="ghost" size="sm" onClick={cancelDiscard}>
            Cancelar
          </Button>
        </div>
      </div>
    );
  };

  const renderUnavailable = (
    data: RecommendationResponse | DraftResponse,
    onRetry: () => void
  ): React.ReactNode => (
    <div role="alert" style={alertStyle}>
      <span>{unavailableMessage(data.unavailableReason, data.retryable)}</span>
      {data.retryable && (
        <Button variant="secondary" size="sm" onClick={onRetry} disabled={busy || !canUseAi}>
          Reintentar
        </Button>
      )}
    </div>
  );

  const renderError = (kind: AssistantErrorKind): React.ReactNode => (
    <div role="alert" style={alertStyle}>
      <span>{ERROR_MESSAGES[kind]}</span>
    </div>
  );

  const rec = showRecommendation && recData ? recData : null;

  return (
    <section
      aria-labelledby={headingId}
      onClickCapture={(event) => {
        if (event.target instanceof Element && event.target.closest('button')) {
          focusRecoveryRef.current = true;
          // A click that starts no request and changes nothing must not leave the marker armed
          // for a later, unrelated render.
          requestAnimationFrame(() => {
            if (!awaitingOutcomeRef.current) focusRecoveryRef.current = false;
          });
        }
      }}
      style={{
        border: '1px solid var(--color-outline-subtle)',
        borderLeft: '4px solid var(--color-brand)',
        borderRadius: 'var(--radius-md)',
        backgroundColor: 'var(--color-surface)',
        padding: 'var(--space-16)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--space-12)',
        minWidth: 0
      }}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 'var(--space-8)' }}>
        <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px', color: 'var(--color-brand)' }}>
          auto_awesome
        </span>
        <h3
          id={headingId}
          ref={panelHeadingRef}
          tabIndex={-1}
          style={{ fontSize: 'var(--font-size-component-heading)', fontWeight: 600, color: 'var(--color-text-main)', margin: 0 }}
        >
          Asistente IA
        </h3>
      </div>
      <p style={noteStyle}>
        Sugerencia generada por IA · Dokene no ha enviado nada. El envío es manual por tu canal habitual y la decisión es tuya.
      </p>

      {!canUseAi && <p style={noteStyle}>{FORBIDDEN_MESSAGE}</p>}

      <div role="status" aria-live="polite" style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)', minHeight: '1em' }}>
        {liveMessage}
      </div>

      {recommendation.kind === 'idle' && (
        <div style={actionsRowStyle}>
          <Button variant="primary" onClick={() => void assistant.requestRecommendation()} disabled={!canUseAi}>
            Obtener recomendación
          </Button>
        </div>
      )}

      {recLoading && (
        <div style={actionsRowStyle}>
          <Button variant="primary" disabled>
            Obtener recomendación
          </Button>
        </div>
      )}

      {recErrorKind && (
        <>
          {renderError(recErrorKind)}
          <div style={actionsRowStyle}>
            <Button variant="primary" onClick={() => void assistant.requestRecommendation()} disabled={!canUseAi}>
              Obtener recomendación
            </Button>
          </div>
        </>
      )}

      {isStale && (
        <>
          <div role="alert" style={alertStyle}>
            <span>{staleMessage}</span>
          </div>
          {keptDraft && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
              <label htmlFor={`${draftLabelId}-kept`} style={{ ...bodyTextStyle, fontWeight: 600 }}>
                Tu borrador editado (la recomendación ya no está actualizada)
              </label>
              <textarea
                id={`${draftLabelId}-kept`}
                value={draftText}
                onChange={(event) => assistant.editDraft(event.target.value)}
                rows={6}
                style={draftTextareaStyle}
              />
              <div style={{ ...noteStyle, display: 'flex', justifyContent: 'space-between', gap: 'var(--space-8)' }}>
                <span>Edición local: no se guarda ni se envía.</span>
                <span>{countCodePoints(draftText)} / {DRAFT_BODY_MAX_CODE_POINTS}</span>
              </div>
              {!activeDiscard && (
                <div style={actionsRowStyle}>
                  <Button variant="primary" onClick={() => void assistant.copyDraft()} disabled={draftText.trim() === ''}>
                    Copiar borrador
                  </Button>
                </div>
              )}
            </div>
          )}
          {activeDiscard ? (
            renderDiscardConfirm()
          ) : (
            <div style={actionsRowStyle}>
              {staleRefresh === 'failed' ? (
                <Button variant="primary" onClick={() => void assistant.retryRefresh()} disabled={busy}>
                  Reintentar actualizar lista
                </Button>
              ) : (
                <Button
                  ref={requeryButtonRef}
                  variant="primary"
                  onClick={askRequery}
                  disabled={!canUseAi || busy || staleRefresh === 'pending'}
                >
                  Actualizar recomendación
                </Button>
              )}
            </div>
          )}
        </>
      )}

      {assistant.ineligibleRefresh === 'failed' && (
        <div role="alert" style={alertStyle}>
          <span>{STALE_REFRESH_FAILED_MESSAGE}</span>
          <Button variant="secondary" size="sm" onClick={() => void assistant.retryIneligibleRefresh()}>
            Reintentar actualizar lista
          </Button>
        </div>
      )}

      {rec && rec.status === 'INELIGIBLE' && (
        <p style={noteStyle}>
          Ahora no se puede sugerir un contacto: {ineligibleReasonLabel(rec.rejectionReason)}.
        </p>
      )}

      {rec && rec.status === 'AI_UNAVAILABLE' && renderUnavailable(rec, () => void assistant.requestRecommendation())}

      {rec && (rec.status === 'AVAILABLE' || rec.status === 'NO_RECOMMENDATION') && (
        <section
          aria-labelledby={recHeadingId}
          style={{
            backgroundColor: 'var(--color-surface-selected)',
            border: '1px solid var(--color-outline-subtle)',
            borderRadius: 'var(--radius-md)',
            padding: 'var(--space-12)',
            display: 'flex',
            flexDirection: 'column',
            gap: 'var(--space-8)'
          }}
        >
          <h4 id={recHeadingId} ref={recHeadingRef} tabIndex={-1} style={{ margin: 0, fontSize: 'var(--font-size-secondary)', fontWeight: 600, color: 'var(--color-brand)' }}>
            Recomendación de la IA
          </h4>
          {rec.status === 'AVAILABLE' && rec.recommendation && (
            <>
              <div>
                <div style={labelStyle}>Acción sugerida</div>
                <p style={{ ...bodyTextStyle, fontWeight: 600 }}>
                  {actionLabel(rec.recommendation.action)} · {intentLabel(rec.recommendation.templateIntent)}
                </p>
              </div>
              <div>
                <div style={labelStyle}>Por qué lo sugiere la IA</div>
                <p style={bodyTextStyle}>{rec.recommendation.rationale}</p>
              </div>
              <p style={noteStyle}>Confianza del modelo: {confidenceLabel(rec.recommendation.confidence)}</p>
            </>
          )}
          {rec.status === 'NO_RECOMMENDATION' && (
            <>
              <p style={{ ...bodyTextStyle, fontWeight: 600 }}>
                La IA no recomienda una acción por ahora: {noRecommendationReasonLabel(rec.refusal?.reason ?? rec.refusalReason)}.
              </p>
              {rec.refusal && <p style={bodyTextStyle}>{rec.refusal.rationale}</p>}
            </>
          )}
          <div style={actionsRowStyle}>
            <Button variant="ghost" size="sm" onClick={askRequery} disabled={busy || !canUseAi}>
              Consultar de nuevo
            </Button>
          </div>
        </section>
      )}

      {rec && rec.status === 'AVAILABLE' && rec.recommendation && draft.kind === 'idle' && (
        <div style={actionsRowStyle}>
          <Button variant="secondary" onClick={() => void assistant.requestDraft(rec)} disabled={!canUseAi || busy}>
            Generar borrador
          </Button>
        </div>
      )}

      {rec && draftLoading && (
        <div style={actionsRowStyle}>
          <Button variant="secondary" disabled>
            Generar borrador
          </Button>
        </div>
      )}

      {rec && draftErrorKind && (
        <>
          {renderError(draftErrorKind)}
          <div style={actionsRowStyle}>
            <Button variant="secondary" onClick={() => void assistant.requestDraft(rec)} disabled={!canUseAi || busy}>
              Generar borrador
            </Button>
          </div>
        </>
      )}

      {rec && draftData && draftData.status === 'INELIGIBLE' && (
        <p style={noteStyle}>
          Ahora no se puede redactar un borrador: {ineligibleReasonLabel(draftData.rejectionReason)}.
        </p>
      )}

      {rec && draftData && draftData.status === 'AI_UNAVAILABLE' &&
        renderUnavailable(draftData, () => void assistant.requestDraft(rec))}

      {rec && draftData && draftData.status === 'NO_DRAFT' && (
        <div style={{ ...noteStyle, display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          <strong style={{ color: 'var(--color-text-main)' }}>
            La IA no pudo redactar un borrador: {noDraftReasonLabel(draftData.refusal?.reason ?? draftData.refusalReason)}.
          </strong>
          {draftData.refusal && <span>{draftData.refusal.rationale}</span>}
          <div style={actionsRowStyle}>
            <Button variant="secondary" size="sm" onClick={askRegenerate} disabled={busy || !canUseAi}>
              Regenerar borrador
            </Button>
          </div>
        </div>
      )}

      {showDraftEditor && draftData?.draft && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          <label id={draftLabelId} htmlFor={`${draftLabelId}-input`} style={{ ...bodyTextStyle, fontWeight: 600 }}>
            Borrador del mensaje (editable)
          </label>
          <textarea
            id={`${draftLabelId}-input`}
            ref={textareaRef}
            value={draftText}
            onChange={(event) => assistant.editDraft(event.target.value)}
            rows={6}
            style={draftTextareaStyle}
          />
          <div style={{ ...noteStyle, display: 'flex', justifyContent: 'space-between', gap: 'var(--space-8)' }}>
            <span>Edición local: no se guarda ni se envía.</span>
            <span>{countCodePoints(draftText)} / {DRAFT_BODY_MAX_CODE_POINTS}</span>
          </div>

          {draftFacts !== null && draftFacts.evidence.length > 0 && (
            <div>
              <div style={labelStyle}>Datos en los que se basa</div>
              <ul style={{ ...bodyTextStyle, paddingLeft: 'var(--space-16)' }}>
                {draftFacts.evidence.map((entry, index) => (
                  <li key={`${index}-${entry.raw}`}>{entry.text}</li>
                ))}
              </ul>
            </div>
          )}
          {draftFacts !== null && draftFacts.warnings.length > 0 && (
            <div>
              <div style={labelStyle}>Advertencias para revisar</div>
              <ul style={{ ...bodyTextStyle, paddingLeft: 'var(--space-16)' }}>
                {draftFacts.warnings.map((entry, index) => (
                  <li key={`${index}-${entry.raw}`}>{entry.text}</li>
                ))}
              </ul>
            </div>
          )}
          {draftFacts !== null && draftFacts.changedRaw.length > 0 && (
            <div>
              <Button
                variant="ghost"
                size="sm"
                aria-expanded={rawValuesShown}
                aria-controls={rawValuesId}
                onClick={() => setRawValuesShownFor(rawValuesShown ? null : shownDraft)}
              >
                {rawValuesShown ? 'Ocultar valores originales' : 'Ver valores originales'}
              </Button>
              {/* Raw machine values stay out of the DOM until asked for, so screen readers never announce them unasked. */}
              <div id={rawValuesId}>
                {rawValuesShown && (
                  <ul style={{ ...noteStyle, paddingLeft: 'var(--space-16)' }}>
                    {draftFacts.changedRaw.map((raw, index) => (
                      <li key={`${index}-${raw}`}>{raw}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}

          {activeDiscard ? (
            renderDiscardConfirm()
          ) : (
            <div style={actionsRowStyle}>
              <Button variant="primary" onClick={() => void assistant.copyDraft()} disabled={draftText.trim() === ''}>
                Copiar borrador
              </Button>
              {onReviewDraft && shownDraft && draft.kind === 'result' && (
                <Button
                  variant="secondary"
                  onClick={() =>
                    onReviewDraft({
                      action: shownDraft.action,
                      templateIntent: shownDraft.templateIntent,
                      body: draftText,
                      policyVersion: draft.version
                    })
                  }
                  disabled={draftText.trim() === '' || countCodePoints(draftText) > DRAFT_BODY_MAX_CODE_POINTS}
                >
                  Revisar y enviar
                </Button>
              )}
              <Button ref={regenerateButtonRef} variant="secondary" onClick={askRegenerate} disabled={busy || !canUseAi}>
                Regenerar borrador
              </Button>
            </div>
          )}
        </div>
      )}

    </section>
  );
};
