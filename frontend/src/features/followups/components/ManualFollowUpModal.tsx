import React, { useState, useEffect } from 'react';
import { Modal } from '@/shared/components/Modal';
import { Button } from '@/shared/components/Button';

import { ApiError } from '@/shared/api/httpClient';

interface ManualFollowUpModalProps {
  isOpen: boolean;
  customerName: string;
  onClose: () => void;
  onSubmit: (notes: string | undefined, idempotencyKey: string) => Promise<void>;
}

export const ManualFollowUpModal: React.FC<ManualFollowUpModalProps> = ({
  isOpen,
  customerName,
  onClose,
  onSubmit
}) => {
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState('');

  useEffect(() => {
    if (isOpen) {
      setNotes('');
      setError(null);
      setSubmitting(false);
      setIdempotencyKey(crypto.randomUUID ? crypto.randomUUID() : `mfu-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`);
    }
  }, [isOpen]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setError(null);

    try {
      await onSubmit(notes.trim() || undefined, idempotencyKey);
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        onClose();
        return;
      }
      const msg = err instanceof Error ? err.message : 'Error al registrar el seguimiento.';
      setError(msg);
      setSubmitting(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={submitting ? () => {} : onClose} title="Registrar seguimiento manual">
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
        <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: 0 }}>
          Registra el resultado de la interacción con <strong>{customerName}</strong> realizada por tu canal habitual.
          Esta acción avanza la fecha de contacto según la cadencia configurada.
        </p>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-8)',
            padding: 'var(--space-8) var(--space-12)',
            backgroundColor: 'var(--color-surface-container-low)',
            borderRadius: 'var(--radius-md)',
            fontSize: 'var(--font-size-meta)',
            color: 'var(--color-brand)'
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
            info
          </span>
          <span>Dokene no envía mensajes automáticamente ni contacta al cliente de forma directa.</span>
        </div>

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

        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label
              htmlFor="manual-followup-notes"
              style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 500, color: 'var(--color-text-main)' }}
            >
              Notas de la conversación (opcional)
            </label>
            <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
              {notes.length}/500
            </span>
          </div>

          <textarea
            id="manual-followup-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value.slice(0, 500))}
            placeholder="Ej: Conversamos por WhatsApp. Confirmó recepción de pedido y requiere abastecimiento la próxima semana."
            disabled={submitting}
            rows={4}
            style={{
              width: '100%',
              padding: 'var(--space-8) var(--space-12)',
              fontSize: 'var(--font-size-secondary)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              fontFamily: 'inherit',
              resize: 'vertical',
              outline: 'none'
            }}
          />
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-8)', marginTop: 'var(--space-8)' }}>
          <Button type="button" variant="secondary" onClick={onClose} disabled={submitting}>
            Cancelar
          </Button>
          <Button type="submit" variant="primary" isLoading={submitting}>
            Guardar seguimiento
          </Button>
        </div>
      </form>
    </Modal>
  );
};
