import React, { useState, useEffect } from 'react';
import { QueueItemResponse } from '@/features/followups/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { PurchaseResponse } from '@/features/customers/types';
import { ManualFollowUpModal } from './ManualFollowUpModal';
import { SnoozeModal } from './SnoozeModal';
import { DismissModal } from './DismissModal';
import { Button } from '@/shared/components/Button';

interface FollowUpDetailProps {
  item: QueueItemResponse;
  isMobileView?: boolean;
  onBack?: () => void;
  onNavigateToCustomer: (customerId: string) => void;
  onRecordManualFollowUp: (notes: string | undefined, idempotencyKey: string) => Promise<void>;
  onSnooze: (until: string) => Promise<void>;
  onDismiss: (notes: string | undefined, idempotencyKey: string) => Promise<void>;
  canWrite: boolean;
  timeZone?: string;
}

export const FollowUpDetail: React.FC<FollowUpDetailProps> = ({
  item,
  isMobileView = false,
  onBack,
  onNavigateToCustomer,
  onRecordManualFollowUp,
  onSnooze,
  onDismiss,
  canWrite,
  timeZone
}) => {
  const [purchases, setPurchases] = useState<PurchaseResponse[]>([]);
  const [loadingPurchases, setLoadingPurchases] = useState(false);
  const [purchasesError, setPurchasesError] = useState<string | null>(null);
  const [purchasesRetryCount, setPurchasesRetryCount] = useState(0);
  const [copiedPhone, setCopiedPhone] = useState(false);

  const [isManualModalOpen, setIsManualModalOpen] = useState(false);
  const [isSnoozeModalOpen, setIsSnoozeModalOpen] = useState(false);
  const [isDismissModalOpen, setIsDismissModalOpen] = useState(false);

  useEffect(() => {
    let active = true;
    const loadPurchases = async () => {
      setLoadingPurchases(true);
      setPurchasesError(null);
      try {
        const data = await customerApi.listPurchases(item.customerId, undefined, undefined, 3);
        if (active) {
          setPurchases(data.purchases || []);
        }
      } catch (err) {
        if (active) {
          setPurchases([]);
          setPurchasesError(
            err instanceof Error ? err.message : 'Error al cargar el historial de compras.'
          );
        }
      } finally {
        if (active) {
          setLoadingPurchases(false);
        }
      }
    };

    loadPurchases();
    return () => {
      active = false;
    };
  }, [item.customerId, purchasesRetryCount]);

  const handleCopyPhone = async () => {
    if (!item.primaryPhone) return;
    try {
      await navigator.clipboard.writeText(item.primaryPhone);
      setCopiedPhone(true);
      setTimeout(() => setCopiedPhone(false), 2000);
    } catch {
      // clipboard write failed silently
    }
  };

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return null;
    try {
      const parts = dateStr.slice(0, 10).split('-');
      if (parts.length === 3) {
        const d = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
        return d.toLocaleDateString('es-CL', { day: 'numeric', month: 'short', year: 'numeric' });
      }
      return dateStr;
    } catch {
      return dateStr;
    }
  };

  const computeDaysOverdue = (dateStr: string) => {
    try {
      const parts = dateStr.split('-');
      const due = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
      const now = new Date();
      const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const diffTime = today.getTime() - due.getTime();
      const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24));
      return diffDays > 0 ? diffDays : null;
    } catch {
      return null;
    }
  };

  const getLatestInteractionText = () => {
    const manualDate = item.lastManualFollowUpDate;
    const dismissedDate = item.lastDismissedDate;

    if (manualDate && dismissedDate) {
      if (dismissedDate > manualDate) {
        return `Último ciclo descartado el ${formatDate(dismissedDate)}.`;
      }
      return `Último seguimiento completado el ${formatDate(manualDate)}.`;
    }
    if (manualDate) {
      return `Último seguimiento completado el ${formatDate(manualDate)}.`;
    }
    if (dismissedDate) {
      return `Último ciclo descartado el ${formatDate(dismissedDate)}.`;
    }
    return 'No se registran interacciones previas en este ciclo.';
  };

  const getReasonExplanation = () => {
    const isOverdue = item.status === 'OVERDUE' || item.reasons.includes('OVERDUE');
    const daysOverdue = isOverdue ? computeDaysOverdue(item.dueDate) : null;
    const overduePrefix = isOverdue
      ? `El seguimiento tiene ${daysOverdue || ''} ${daysOverdue === 1 ? 'día' : 'días'} de retraso. `
      : '';

    switch (item.timingSource) {
      case 'EXPLICIT_DATE':
        return `${overduePrefix}Se ha alcanzado la fecha específica de seguimiento programada manualmente (${formatDate(item.dueDate)}).`;
      case 'LAST_PURCHASE':
        return `${overduePrefix}Han transcurrido ${item.effectiveCadenceDays} días desde su última compra registrada. Buen momento para consultar cómo estuvo su último pedido y si requiere nuevo abastecimiento.`;
      case 'LAST_MANUAL_FOLLOW_UP':
        return `${overduePrefix}Se ha alcanzado la cadencia de ${item.effectiveCadenceDays} días desde el último contacto manual registrado.`;
      case 'LAST_DISMISSAL':
        return `${overduePrefix}Se ha cumplido el ciclo de ${item.effectiveCadenceDays} días tras el descarte del período anterior.`;
      case 'SNOOZE':
        return `${overduePrefix}Ha vencido el período de postergación acordado para este seguimiento.`;
      default:
        if (isOverdue) {
          return `${overduePrefix}Respecto a la cadencia habitual de atención (${item.effectiveCadenceDays} días), es momento oportuno para restablecer el contacto y verificar sus necesidades.`;
        }
        return `Se ha cumplido el plazo de ${item.effectiveCadenceDays} días previsto por la cadencia de atención. Se sugiere verificar satisfacción con la última compra o coordinar un nuevo pedido.`;
    }
  };

  return (
    <div
      style={{
        backgroundColor: 'var(--color-surface)',
        border: '1px solid var(--color-outline)',
        borderRadius: 'var(--radius-lg)',
        padding: 'var(--space-24)',
        boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--space-24)'
      }}
    >
      {/* Mobile Back Bar */}
      {isMobileView && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '-8px' }}>
          <button
            type="button"
            onClick={onBack}
            className="interactive-target"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              border: 'none',
              background: 'transparent',
              color: 'var(--color-primary)',
              fontWeight: 600,
              fontSize: 'var(--font-size-secondary)',
              cursor: 'pointer',
              padding: '8px 0',
              minHeight: '44px'
            }}
          >
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px' }}>
              arrow_back
            </span>
            <span>Volver a Seguimientos</span>
          </button>

          <button
            type="button"
            onClick={() => onNavigateToCustomer(item.customerId)}
            className="interactive-target"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              border: 'none',
              background: 'transparent',
              color: 'var(--color-primary)',
              fontWeight: 600,
              fontSize: 'var(--font-size-secondary)',
              cursor: 'pointer',
              minHeight: '44px'
            }}
          >
            <span>Ver perfil completo</span>
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '16px' }}>
              arrow_forward
            </span>
          </button>
        </div>
      )}

      {/* Customer Header Row */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--space-12)',
          paddingBottom: 'var(--space-16)',
          borderBottom: '1px solid var(--color-outline)'
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '8px' }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
              <h2
                style={{
                  fontSize: 'var(--font-size-page-heading)',
                  fontWeight: 600,
                  color: 'var(--color-text-main)',
                  margin: 0
                }}
              >
                {item.displayName}
              </h2>

              {!isMobileView && (
                <button
                  type="button"
                  onClick={() => onNavigateToCustomer(item.customerId)}
                  className="interactive-target"
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                    border: 'none',
                    background: 'transparent',
                    color: 'var(--color-primary)',
                    fontWeight: 600,
                    fontSize: 'var(--font-size-secondary)',
                    cursor: 'pointer',
                    padding: '4px 8px',
                    minHeight: '44px'
                  }}
                >
                  <span>Ver cliente</span>
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '16px' }}>
                    arrow_forward
                  </span>
                </button>
              )}
            </div>

            {/* Phone & Channel with Copy Button */}
            {item.primaryPhone && (
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 'var(--space-8)',
                  marginTop: 'var(--space-8)',
                  padding: '4px 10px',
                  backgroundColor: 'var(--color-bg-inset)',
                  borderRadius: 'var(--radius-md)',
                  border: '1px solid var(--color-outline-subtle)'
                }}
              >
                <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px', color: 'var(--color-primary)' }}>
                  chat
                </span>
                <span style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 600, color: 'var(--color-text-main)' }}>
                  {item.primaryPhone}
                </span>
                <span
                  style={{
                    padding: '2px 6px',
                    borderRadius: 'var(--radius-sm)',
                    backgroundColor: 'var(--color-surface-container-low)',
                    color: 'var(--color-brand)',
                    fontSize: 'var(--font-size-meta)',
                    fontWeight: 600
                  }}
                >
                  WhatsApp directo
                </span>

                <button
                  type="button"
                  onClick={handleCopyPhone}
                  aria-label="Copiar número de teléfono"
                  title="Copiar número"
                  className="interactive-target"
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    border: 'none',
                    background: 'transparent',
                    color: 'var(--color-primary)',
                    cursor: 'pointer',
                    padding: '4px',
                    borderRadius: 'var(--radius-sm)',
                    minHeight: '44px',
                    minWidth: '44px'
                  }}
                >
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                    {copiedPhone ? 'check' : 'content_copy'}
                  </span>
                </button>
                {copiedPhone && (
                  <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-brand)', fontWeight: 500 }}>
                    ¡Copiado!
                  </span>
                )}
              </div>
            )}
          </div>

          {/* Customer recurring state */}
          <div style={{ textAlign: isMobileView ? 'left' : 'right' }}>
            <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)', display: 'block' }}>
              Estado del contacto
            </span>
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                fontSize: 'var(--font-size-secondary)',
                fontWeight: 500,
                color: 'var(--color-brand)',
                marginTop: '2px'
              }}
            >
              <span
                style={{
                  width: '8px',
                  height: '8px',
                  borderRadius: 'var(--radius-full)',
                  backgroundColor: 'var(--color-brand)'
                }}
              />
              Activo en seguimiento
            </span>
          </div>
        </div>

        {/* Consent Strip */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-8)',
            backgroundColor: 'var(--color-surface-container)',
            padding: 'var(--space-8) var(--space-12)',
            borderRadius: 'var(--radius-md)',
            fontSize: 'var(--font-size-secondary)',
            color: 'var(--color-text-main)'
          }}
        >
          <span
            className="material-symbols-outlined"
            aria-hidden="true"
            style={{ fontSize: '18px', color: 'var(--color-primary)', fontVariationSettings: "'FILL' 1" }}
          >
            verified_user
          </span>
          <span>Consentimiento de contacto activo para recordatorios de abastecimiento y servicio.</span>
        </div>
      </div>

      {/* "¿Por qué contactar hoy?" block */}
      <div
        style={{
          backgroundColor: 'var(--color-bg-inset)',
          border: '1px solid var(--color-outline-subtle)',
          borderRadius: 'var(--radius-md)',
          padding: 'var(--space-16)',
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--space-8)'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px', color: 'var(--color-primary)' }}>
            lightbulb
          </span>
          <h3
            style={{
              fontSize: 'var(--font-size-component-heading)',
              fontWeight: 600,
              color: 'var(--color-text-main)',
              margin: 0
            }}
          >
            ¿Por qué contactar hoy?
          </h3>
        </div>
        <p
          style={{
            fontSize: 'var(--font-size-secondary)',
            lineHeight: 'var(--line-height-secondary)',
            color: 'var(--color-text-main)',
            margin: 0
          }}
        >
          {getReasonExplanation()}
        </p>
      </div>

      {/* Recent Purchases Section */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px', color: 'var(--color-text-muted)' }}>
              receipt_long
            </span>
            <h3
              style={{
                fontSize: 'var(--font-size-component-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                margin: 0
              }}
            >
              Historial reciente de compras
            </h3>
          </div>
          <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
            {purchases.length > 0 ? `${purchases.length} compras recientes` : ''}
          </span>
        </div>

        {loadingPurchases ? (
          <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: '4px 0' }}>
            Cargando historial de compras...
          </p>
        ) : purchasesError ? (
          <div
            role="alert"
            style={{
              padding: 'var(--space-12)',
              backgroundColor: 'var(--color-warning-bg)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-warning-border)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              gap: 'var(--space-8)'
            }}
          >
            <span style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-warning-text)' }}>
              No se pudo cargar el historial de compras.
            </span>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setPurchasesRetryCount((c) => c + 1)}
              style={{ minHeight: '32px', padding: '4px 10px', fontSize: 'var(--font-size-meta)' }}
            >
              Reintentar
            </Button>
          </div>
        ) : purchases.length > 0 ? (
          <div
            style={{
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              overflow: 'hidden',
              backgroundColor: 'var(--color-surface)'
            }}
          >
            {purchases.map((p, idx) => (
              <div
                key={p.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: 'var(--space-8) var(--space-12)',
                  borderBottom: idx < purchases.length - 1 ? '1px solid var(--color-outline-subtle)' : 'none',
                  fontSize: 'var(--font-size-secondary)'
                }}
              >
                <div>
                  <span style={{ fontWeight: 500, color: 'var(--color-text-main)', display: 'block' }}>
                    {p.description}
                  </span>
                  <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                    {formatDate(p.purchasedAt)}
                  </span>
                </div>
                <span
                  style={{
                    fontSize: 'var(--font-size-meta)',
                    padding: '2px 6px',
                    borderRadius: 'var(--radius-sm)',
                    backgroundColor: p.status === 'VOID' ? 'var(--color-warning-bg)' : 'var(--color-bg-inset)',
                    color: p.status === 'VOID' ? 'var(--color-warning-text)' : 'var(--color-text-muted)',
                    border: p.status === 'VOID' ? '1px solid var(--color-warning-border)' : 'none'
                  }}
                >
                  {p.status === 'VOID' ? 'Anulada' : 'Registrada'}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div
            style={{
              padding: 'var(--space-12)',
              backgroundColor: 'var(--color-bg-inset)',
              borderRadius: 'var(--radius-md)',
              fontSize: 'var(--font-size-secondary)',
              color: 'var(--color-text-muted)'
            }}
          >
            No hay compras registradas en este espacio.
          </div>
        )}
      </div>

      {/* Last Interaction Section */}
      <div
        style={{
          backgroundColor: 'var(--color-surface-container)',
          padding: 'var(--space-12) var(--space-16)',
          borderRadius: 'var(--radius-md)',
          display: 'flex',
          flexDirection: 'column',
          gap: '4px'
        }}
      >
        <span
          style={{
            fontSize: 'var(--font-size-meta)',
            fontWeight: 600,
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
            color: 'var(--color-text-muted)'
          }}
        >
          Última interacción registrada
        </span>
        <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-main)', margin: 0, fontStyle: 'italic' }}>
          {getLatestInteractionText()}
        </p>
      </div>

      {/* Human-in-the-loop Ethical notice */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--space-8)',
          fontSize: 'var(--font-size-meta)',
          color: 'var(--color-text-muted)',
          paddingTop: 'var(--space-8)',
          borderTop: '1px solid var(--color-outline)'
        }}
      >
        <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px', color: 'var(--color-primary)' }}>
          info
        </span>
        <span>
          Dokene te ayuda a recordar tus compromisos. Los contactos se realizan manualmente por tu canal habitual.
        </span>
      </div>

      {/* Role restriction hint if read-only */}
      {!canWrite && (
        <div
          role="status"
          style={{
            padding: 'var(--space-8) var(--space-12)',
            backgroundColor: 'var(--color-warning-bg)',
            color: 'var(--color-warning-text)',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--color-warning-border)',
            fontSize: 'var(--font-size-secondary)'
          }}
        >
          Tu rol en este espacio es de sólo lectura. No puedes registrar seguimientos, posponer ni descartar.
        </div>
      )}

      {/* Action Buttons Row */}
      <div
        style={{
          display: 'flex',
          flexDirection: isMobileView ? 'column' : 'row',
          alignItems: isMobileView ? 'stretch' : 'center',
          justifyContent: 'space-between',
          gap: 'var(--space-8)'
        }}
      >
        {/* Primary CTA */}
        <Button
          type="button"
          variant="primary"
          onClick={() => setIsManualModalOpen(true)}
          disabled={!canWrite}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 'var(--space-8)',
            fontWeight: 600
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
            edit_calendar
          </span>
          <span>Registrar seguimiento</span>
        </Button>

        {/* Secondary Actions */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-8)',
            width: isMobileView ? '100%' : 'auto'
          }}
        >
          <Button
            type="button"
            variant="secondary"
            onClick={() => setIsSnoozeModalOpen(true)}
            disabled={!canWrite}
            style={{
              flex: isMobileView ? 1 : 'initial',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '4px'
            }}
          >
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
              schedule
            </span>
            <span>Posponer</span>
          </Button>

          <Button
            type="button"
            variant="ghost"
            onClick={() => setIsDismissModalOpen(true)}
            disabled={!canWrite}
            style={{
              flex: isMobileView ? 1 : 'initial',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '4px',
              color: 'var(--color-error-text)'
            }}
          >
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
              close
            </span>
            <span>Descartar</span>
          </Button>
        </div>
      </div>

      {/* Modals */}
      <ManualFollowUpModal
        isOpen={isManualModalOpen}
        customerName={item.displayName}
        onClose={() => setIsManualModalOpen(false)}
        onSubmit={async (notes, key) => {
          await onRecordManualFollowUp(notes, key);
        }}
      />

      <SnoozeModal
        isOpen={isSnoozeModalOpen}
        customerName={item.displayName}
        timeZone={timeZone}
        onClose={() => setIsSnoozeModalOpen(false)}
        onSubmit={async (until) => {
          await onSnooze(until);
        }}
      />

      <DismissModal
        isOpen={isDismissModalOpen}
        customerName={item.displayName}
        onClose={() => setIsDismissModalOpen(false)}
        onSubmit={async (notes, key) => {
          await onDismiss(notes, key);
        }}
      />
    </div>
  );
};
