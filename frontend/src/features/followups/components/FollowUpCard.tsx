import React from 'react';
import { QueueItemResponse } from '@/features/followups/types';
import { getCalendarDateInTimeZone, computeDaysOverdueInTimeZone } from '../utils/dateUtils';

interface FollowUpCardProps {
  item: QueueItemResponse;
  isSelected: boolean;
  onSelect: () => void;
  timeZone?: string;
}

export const FollowUpCard: React.FC<FollowUpCardProps> = ({ item, isSelected, onSelect, timeZone }) => {
  const isOverdue = item.status === 'OVERDUE';
  const todayStr = getCalendarDateInTimeZone(0, timeZone);
  const isDueToday = item.status === 'DUE' || (!isOverdue && item.dueDate === todayStr);

  const formatDueDate = (dateStr: string) => {
    try {
      const parts = dateStr.split('-');
      if (parts.length === 3) {
        const year = parseInt(parts[0], 10);
        const month = parseInt(parts[1], 10) - 1;
        const day = parseInt(parts[2], 10);
        const d = new Date(year, month, day);
        return d.toLocaleDateString('es-CL', { day: 'numeric', month: 'short' });
      }
      return dateStr;
    } catch {
      return dateStr;
    }
  };

  const formatPurchaseDate = (dateIso: string | null) => {
    if (!dateIso) return null;
    try {
      const d = new Date(dateIso);
      return d.toLocaleDateString('es-CL', { day: 'numeric', month: 'short', year: 'numeric' });
    } catch {
      return null;
    }
  };

  const daysOverdue = isOverdue ? computeDaysOverdueInTimeZone(item.dueDate, timeZone) : null;

  const getReasonText = () => {
    const overduePrefix = isOverdue
      ? `El seguimiento tiene ${daysOverdue || ''} ${daysOverdue === 1 ? 'día' : 'días'} de retraso respecto a la cadencia de atención. `
      : '';

    switch (item.timingSource) {
      case 'EXPLICIT_DATE':
        return `${overduePrefix}Se ha alcanzado la fecha programada específicamente (${formatDueDate(item.dueDate)}).`;
      case 'LAST_PURCHASE':
        return `${overduePrefix}Ciclo de seguimiento tras la última compra registrada (cadencia de ${item.effectiveCadenceDays} días).`;
      case 'LAST_MANUAL_FOLLOW_UP':
        return `${overduePrefix}Nuevo ciclo tras el último seguimiento registrado (cadencia de ${item.effectiveCadenceDays} días).`;
      case 'LAST_DISMISSAL':
        return `${overduePrefix}Nuevo ciclo tras el descarte anterior (cadencia de ${item.effectiveCadenceDays} días).`;
      case 'SNOOZE':
        return `${overduePrefix}Fecha de postergación acordada cumplida.`;
      default:
        if (item.reasons.includes('DUE_TODAY') || isDueToday) {
          return `Corresponde contacto hoy según la cadencia establecida (${item.effectiveCadenceDays} días).`;
        }
        if (isOverdue) {
          if (daysOverdue && daysOverdue > 0) {
            return `El seguimiento tiene ${daysOverdue} ${daysOverdue === 1 ? 'día' : 'días'} de retraso respecto a la cadencia de atención.`;
          }
          return 'El seguimiento ha superado la fecha sugerida de contacto.';
        }
        return `Seguimiento programado según la cadencia del negocio (${item.effectiveCadenceDays} días).`;
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect();
    }
  };

  const lastPurchaseFormatted = formatPurchaseDate(item.lastPurchaseAt);

  return (
    <div
      role="button"
      tabIndex={0}
      aria-pressed={isSelected}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
      style={{
        position: 'relative',
        padding: 'var(--space-16)',
        borderRadius: 'var(--radius-lg)',
        backgroundColor: isSelected ? 'var(--color-surface-selected)' : 'var(--color-surface)',
        border: isSelected
          ? '2px solid var(--color-primary)'
          : isOverdue
          ? '1px solid var(--color-warning-border)'
          : '1px solid var(--color-outline)',
        cursor: 'pointer',
        transition: 'all 0.15s ease-in-out',
        boxShadow: isSelected ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
        outline: 'none',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--space-8)'
      }}
      className="follow-up-card interactive-target"
    >
      {/* Overdue warm stripe */}
      {isOverdue && !isSelected && (
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: '4px',
            backgroundColor: 'var(--color-warning-text)',
            borderTopLeftRadius: 'var(--radius-lg)',
            borderBottomLeftRadius: 'var(--radius-lg)'
          }}
        />
      )}

      {/* Header row: Customer name + status badge */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '8px' }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <h3
              style={{
                fontSize: 'var(--font-size-component-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                margin: 0,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
            >
              {item.displayName}
            </h3>
            {isSelected && (
              <span
                className="material-symbols-outlined"
                aria-hidden="true"
                style={{ fontSize: '18px', color: 'var(--color-primary)' }}
              >
                check_circle
              </span>
            )}
          </div>

          {/* Consent badge */}
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              marginTop: '4px',
              color: isSelected ? 'var(--color-brand)' : 'var(--color-text-muted)',
              fontSize: 'var(--font-size-meta)',
              fontWeight: 500
            }}
          >
            <span
              className="material-symbols-outlined"
              aria-hidden="true"
              style={{ fontSize: '15px', fontVariationSettings: "'FILL' 1" }}
            >
              verified
            </span>
            <span>
              {item.primaryPhone ? 'Contacto autorizado (WhatsApp)' : 'Contacto autorizado'}
            </span>
          </div>
        </div>

        {/* Status pill */}
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            padding: '2px 8px',
            borderRadius: 'var(--radius-full)',
            fontSize: 'var(--font-size-meta)',
            fontWeight: 600,
            whiteSpace: 'nowrap',
            backgroundColor: isOverdue
              ? 'var(--color-warning-bg)'
              : isDueToday
              ? 'var(--color-surface-container-low)'
              : 'var(--color-surface-container)',
            color: isOverdue
              ? 'var(--color-warning-text)'
              : isDueToday
              ? 'var(--color-brand)'
              : 'var(--color-text-muted)',
            border: isOverdue ? '1px solid var(--color-warning-border)' : 'none'
          }}
        >
          {isOverdue
            ? daysOverdue && daysOverdue > 0
              ? `Vencido hace ${daysOverdue} d`
              : 'Vencido'
            : isDueToday
            ? 'Pendiente para hoy'
            : 'Pendiente'}
        </span>
      </div>

      {/* Reason block */}
      <p
        style={{
          fontSize: 'var(--font-size-secondary)',
          lineHeight: 'var(--line-height-secondary)',
          color: isSelected ? 'var(--color-text-main)' : 'var(--color-text-muted)',
          margin: '2px 0 0',
          backgroundColor: isSelected ? 'rgba(255, 255, 255, 0.7)' : 'var(--color-bg-inset)',
          padding: '8px 10px',
          borderRadius: 'var(--radius-md)',
          border: isSelected ? '1px solid rgba(15, 118, 110, 0.2)' : '1px solid var(--color-outline-subtle)'
        }}
      >
        {getReasonText()}
      </p>

      {/* Metadata footer */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 'var(--space-8)',
          fontSize: 'var(--font-size-meta)',
          color: 'var(--color-text-muted)',
          paddingTop: 'var(--space-4)',
          borderTop: isSelected ? '1px solid rgba(15, 118, 110, 0.15)' : '1px solid var(--color-outline-subtle)'
        }}
      >
        <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '14px' }}>
            shopping_bag
          </span>
          <span>{lastPurchaseFormatted ? `Última: ${lastPurchaseFormatted}` : 'Sin compras'}</span>
        </span>

        <span
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '4px',
            color: isOverdue ? 'var(--color-warning-text)' : 'var(--color-brand)',
            fontWeight: 600
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '14px' }}>
            event
          </span>
          <span>
            {isDueToday ? `Sugerido: Hoy, ${formatDueDate(item.dueDate)}` : `Fecha: ${formatDueDate(item.dueDate)}`}
          </span>
        </span>
      </div>
    </div>
  );
};
