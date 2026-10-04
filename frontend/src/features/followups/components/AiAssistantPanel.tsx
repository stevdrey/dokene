import React, { useEffect, useId, useRef } from 'react';
import { Button } from '@/shared/components/Button';
import {
  DRAFT_MAX_LENGTH,
  useFollowUpAssistant,
  type AssistantErrorKind
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
import type { DraftResponse, RecommendationResponse } from '@/features/followups/types';

interface AiAssistantPanelProps {
  customerId: string;
  /** Policy version of the queue item currently shown; anything produced for another version is stale. */
  policyVersion: number;
  /** UI affordance only: the backend remains the authority for AI permissions. */
  canUseAi: boolean;
  /** Asks the workbench to reload the queue after the follow-up changed under the assistant. */
  onRequestRefresh: () => void;
}

const FORBIDDEN_MESSAGE = 'Tu rol no permite usar el asistente IA en este espacio de trabajo.';

const ERROR_MESSAGES: Record<AssistantErrorKind, string> = {
  forbidden: FORBIDDEN_MESSAGE,
  'rate-limited': 'Hay demasiadas solicitudes al asistente IA. Espera un momento e inténtalo de nuevo.',
  failed: `No pudimos consultar al asistente IA. ${AI_MANUAL_FALLBACK}`
};

const STALE_CHANGED_MESSAGE =
  'El seguimiento cambió mientras consultabas al asistente. Actualizamos la lista; actualiza la recomendación.';

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
  onRequestRefresh
}) => {
  const headingId = useId();
  const recHeadingId = useId();
  const draftLabelId = useId();
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  const assistant = useFollowUpAssistant({ customerId, policyVersion, onRequestRefresh });
  const { recommendation, draft, draftText, draftDirty, pendingDiscard, copyStatus } = assistant;

  const recLoading = recommendation.kind === 'loading';
  const draftLoading = draft.kind === 'loading';
  const busy = recLoading || draftLoading;

  const recData: RecommendationResponse | null = recommendation.kind === 'result' ? recommendation.data : null;
  const versionDrifted = recommendation.kind === 'result' && recommendation.version !== policyVersion;
  // Explicit `stale` (409 / STALE_STATE / version mismatch) or a queue refresh that moved the item on.
  const isStale = recommendation.kind === 'stale' || versionDrifted;
  const staleMessage = recommendation.kind === 'stale' ? STALE_CHANGED_MESSAGE : STALE_RESULT_MESSAGE;
  const draftData: DraftResponse | null = draft.kind === 'result' ? draft.data : null;
  const keptDraft = isStale && draftDirty && draftText !== '';

  const showRecommendation = recData !== null && !isStale;
  const showDraftEditor = showRecommendation && draftData?.status === 'AVAILABLE' && !!draftData.draft;

  useEffect(() => {
    if (showDraftEditor) {
      textareaRef.current?.focus();
    }
  }, [draft.kind === 'result' ? draft.data : null, showDraftEditor]);

  let liveMessage = '';
  if (recLoading) liveMessage = 'Consultando al asistente…';
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

  const askRefresh = () => {
    if (draftDirty) {
      assistant.askDiscard('requery');
    } else {
      void assistant.requestRecommendation();
    }
  };

  const askRequery = () => {
    if (draftDirty) {
      assistant.askDiscard('requery');
    } else {
      void assistant.requestRecommendation();
    }
  };

  const askRegenerate = () => {
    if (draftDirty) {
      assistant.askDiscard('regenerate-draft');
    } else if (recData) {
      void assistant.requestDraft(recData);
    }
  };

  const confirmDiscard = () => {
    if (pendingDiscard === 'requery') {
      void assistant.requestRecommendation();
    } else if (pendingDiscard === 'regenerate-draft' && recData) {
      void assistant.requestDraft(recData);
    }
  };

  const renderDiscardConfirm = (): React.ReactNode => {
    if (!pendingDiscard) return null;
    const copy = DISCARD_COPY[pendingDiscard];
    return (
      <div role="group" aria-label="Confirmar reemplazo del borrador" style={alertStyle}>
        <span>{copy.message}</span>
        <div style={actionsRowStyle}>
          <Button variant="danger" size="sm" onClick={confirmDiscard}>
            {copy.confirm}
          </Button>
          <Button variant="ghost" size="sm" onClick={assistant.cancelDiscard}>
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
                maxLength={DRAFT_MAX_LENGTH}
                onChange={(event) => assistant.editDraft(event.target.value)}
                rows={6}
                style={draftTextareaStyle}
              />
              <div style={{ ...noteStyle, display: 'flex', justifyContent: 'space-between', gap: 'var(--space-8)' }}>
                <span>Edición local: no se guarda ni se envía.</span>
                <span>{draftText.length} / {DRAFT_MAX_LENGTH}</span>
              </div>
              {!pendingDiscard && (
                <div style={actionsRowStyle}>
                  <Button variant="primary" onClick={() => void assistant.copyDraft()} disabled={draftText.trim() === ''}>
                    Copiar borrador
                  </Button>
                </div>
              )}
            </div>
          )}
          {pendingDiscard ? (
            renderDiscardConfirm()
          ) : (
            <div style={actionsRowStyle}>
              <Button variant="primary" onClick={askRefresh} disabled={!canUseAi || busy}>
                Actualizar recomendación
              </Button>
            </div>
          )}
        </>
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
          <h4 id={recHeadingId} style={{ margin: 0, fontSize: 'var(--font-size-secondary)', fontWeight: 600, color: 'var(--color-brand)' }}>
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
            maxLength={DRAFT_MAX_LENGTH}
            onChange={(event) => assistant.editDraft(event.target.value)}
            rows={6}
            style={draftTextareaStyle}
          />
          <div style={{ ...noteStyle, display: 'flex', justifyContent: 'space-between', gap: 'var(--space-8)' }}>
            <span>Edición local: no se guarda ni se envía.</span>
            <span>{draftText.length} / {DRAFT_MAX_LENGTH}</span>
          </div>

          {draftData.draft.evidence.length > 0 && (
            <div>
              <div style={labelStyle}>Datos en los que se basa</div>
              <ul style={{ ...bodyTextStyle, paddingLeft: 'var(--space-16)' }}>
                {draftData.draft.evidence.map((entry, index) => (
                  <li key={`${index}-${entry}`}>{entry}</li>
                ))}
              </ul>
            </div>
          )}
          {draftData.draft.warnings.length > 0 && (
            <div>
              <div style={labelStyle}>Advertencias para revisar</div>
              <ul style={{ ...bodyTextStyle, paddingLeft: 'var(--space-16)' }}>
                {draftData.draft.warnings.map((entry, index) => (
                  <li key={`${index}-${entry}`}>{entry}</li>
                ))}
              </ul>
            </div>
          )}

          {pendingDiscard ? (
            renderDiscardConfirm()
          ) : (
            <div style={actionsRowStyle}>
              <Button variant="primary" onClick={() => void assistant.copyDraft()} disabled={draftText.trim() === ''}>
                Copiar borrador
              </Button>
              <Button variant="secondary" onClick={askRegenerate} disabled={busy || !canUseAi}>
                Regenerar borrador
              </Button>
            </div>
          )}
        </div>
      )}

    </section>
  );
};
