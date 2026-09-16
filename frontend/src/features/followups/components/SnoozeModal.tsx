import React, { useState, useEffect } from 'react';
import { Modal } from '@/shared/components/Modal';
import { Button } from '@/shared/components/Button';

import { ApiError } from '@/shared/api/httpClient';

interface SnoozeModalProps {
  isOpen: boolean;
  customerName: string;
  onClose: () => void;
  onSubmit: (until: string) => Promise<void>;
  timeZone?: string;
}

import { getCalendarDateInTimeZone } from '../utils/dateUtils';
export { getCalendarDateInTimeZone };

export const SnoozeModal: React.FC<SnoozeModalProps> = ({
  isOpen,
  customerName,
  onClose,
  onSubmit,
  timeZone
}) => {
  const [selectedDate, setSelectedDate] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const todayStr = getCalendarDateInTimeZone(0, timeZone);
  const tomorrowStr = getCalendarDateInTimeZone(1, timeZone);

  useEffect(() => {
    if (isOpen) {
      setSelectedDate(tomorrowStr);
      setError(null);
      setSubmitting(false);
    }
  }, [isOpen, tomorrowStr]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!timeZone) {
      setError('No se ha podido determinar la zona horaria del espacio comercial.');
      return;
    }
    if (!selectedDate) {
      setError('Selecciona una fecha válida.');
      return;
    }
    if (selectedDate < todayStr) {
      setError('La fecha no puede ser anterior a hoy.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await onSubmit(selectedDate);
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        onClose();
        return;
      }
      const msg = err instanceof Error ? err.message : 'Error al posponer el seguimiento.';
      setError(msg);
      setSubmitting(false);
    }
  };

  const presets = [
    { label: 'Mañana', days: 1 },
    { label: 'En 3 días', days: 3 },
    { label: 'En 1 semana', days: 7 },
    { label: 'En 2 semanas', days: 14 }
  ];

  return (
    <Modal isOpen={isOpen} onClose={submitting ? () => {} : onClose} title="Posponer seguimiento">
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
        <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: 0 }}>
          Pospón el seguimiento de <strong>{customerName}</strong>. El cliente dejará de aparecer en la lista de pendientes
          hasta la fecha elegida.
        </p>

        {error && (
          <div
            role="alert"
            style={{
              padding: 'var(--space-12)',
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-error-border)',
              fontSize: 'var(--font-size-secondary)'
            }}
          >
            {error}
          </div>
        )}

        {/* Quick Presets */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          <span style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 500, color: 'var(--color-text-main)' }}>
            Opciones rápidas
          </span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-8)' }}>
            {presets.map((p) => {
              const pDate = getCalendarDateInTimeZone(p.days, timeZone);
              const isSelected = selectedDate === pDate;
              return (
                <button
                  key={p.days}
                  type="button"
                  onClick={() => setSelectedDate(pDate)}
                  disabled={submitting}
                  style={{
                    padding: 'var(--space-8) var(--space-12)',
                    borderRadius: 'var(--radius-md)',
                    border: isSelected ? '2px solid var(--color-primary)' : '1px solid var(--color-outline)',
                    backgroundColor: isSelected ? 'var(--color-surface-selected)' : 'var(--color-surface)',
                    color: isSelected ? 'var(--color-brand)' : 'var(--color-text-main)',
                    fontWeight: isSelected ? 600 : 400,
                    fontSize: 'var(--font-size-secondary)',
                    cursor: 'pointer',
                    minHeight: '44px'
                  }}
                >
                  {p.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* Custom date picker */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          <label
            htmlFor="snooze-date"
            style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 500, color: 'var(--color-text-main)' }}
          >
            O elige una fecha específica
          </label>
          <input
            id="snooze-date"
            type="date"
            min={todayStr}
            value={selectedDate}
            onChange={(e) => setSelectedDate(e.target.value)}
            disabled={submitting}
            style={{
              padding: 'var(--space-8) var(--space-12)',
              fontSize: 'var(--font-size-secondary)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              outline: 'none',
              minHeight: '44px'
            }}
          />
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-8)', marginTop: 'var(--space-8)' }}>
          <Button type="button" variant="secondary" onClick={onClose} disabled={submitting}>
            Cancelar
          </Button>
          <Button type="submit" variant="primary" isLoading={submitting}>
            Confirmar fecha
          </Button>
        </div>
      </form>
    </Modal>
  );
};
