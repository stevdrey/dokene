import { useState } from 'react';
import { CustomerResponse } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Modal } from '@/shared/components/Modal';
import { Button } from '@/shared/components/Button';
import { ArchiveIcon } from '@/shared/components/Icons';

interface ArchiveModalProps {
  customer: CustomerResponse;
  isOpen: boolean;
  onClose: () => void;
  onArchived: () => void;
}

export function ArchiveModal({ customer, isOpen, onClose, onArchived }: ArchiveModalProps) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleArchive = async () => {
    setIsSubmitting(true);
    setError(null);
    try {
      await customerApi.archiveCustomer(customer.id, customer.version);
      onArchived();
      onClose();
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('No se pudo archivar la ficha del cliente.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={`¿Archivar ficha de ${customer.displayName}?`}
      maxWidth="480px"
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
        <div style={{ display: 'flex', gap: 'var(--space-16)', alignItems: 'flex-start' }}>
          <div
            style={{
              width: '40px',
              height: '40px',
              borderRadius: 'var(--radius-full)',
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0
            }}
          >
            <ArchiveIcon size={20} />
          </div>
          <div>
            <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', lineHeight: 1.5 }}>
              El cliente ya no aparecerá en las listas operativas activas ni en los recordatorios diarios de reorden.
            </p>
          </div>
        </div>

        <div
          style={{
            backgroundColor: 'var(--color-surface-low)',
            padding: 'var(--space-12)',
            borderRadius: 'var(--radius-md)',
            fontSize: 'var(--font-size-meta)',
            color: 'var(--color-text-supporting)'
          }}
        >
          <strong>Nota de resguardo:</strong> Sus compras y el registro histórico de consentimientos seguirán resguardados e intactos en la base de datos auditable de Dokene.
        </div>

        {error && (
          <div
            role="alert"
            style={{
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              border: '1px solid var(--color-error-border)',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              fontSize: 'var(--font-size-dense)'
            }}
          >
            {error}
          </div>
        )}

        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 'var(--space-12)',
            marginTop: 'var(--space-8)'
          }}
        >
          <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
            Cancelar
          </Button>
          <Button variant="danger" onClick={handleArchive} isLoading={isSubmitting}>
            Confirmar y archivar
          </Button>
        </div>
      </div>
    </Modal>
  );
}
