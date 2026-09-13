import { useState, useEffect, useCallback } from 'react';
import { CustomerResponse, PurchaseResponse, EligibilityResponse } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import {
  ArrowBackIcon,
  EditIcon,
  ArchiveIcon,
  WhatsAppIcon,
  ShoppingBagIcon,
  CheckCircleIcon,
  DoNotDisturbIcon
} from '@/shared/components/Icons';
import { CustomerFormModal } from '@/features/customers/components/CustomerFormModal';
import { ArchiveModal } from '@/features/customers/components/ArchiveModal';
import { PurchaseSection } from '@/features/customers/components/PurchaseSection';
import { ConsentSection } from '@/features/customers/components/ConsentSection';

interface CustomerProfileProps {
  customerId: string;
  onBack: () => void;
}

export function CustomerProfile({ customerId, onBack }: CustomerProfileProps) {
  const [customer, setCustomer] = useState<CustomerResponse | null>(null);
  const [lastPurchase, setLastPurchase] = useState<PurchaseResponse | null>(null);
  const [eligibility, setEligibility] = useState<EligibilityResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [isArchiveModalOpen, setIsArchiveModalOpen] = useState(false);

  const loadData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const res = await customerApi.getCustomer(customerId);
      setCustomer(res.customer);

      const last = await customerApi.getLastPurchase(customerId);
      setLastPurchase(last);

      const primaryPhone = res.customer.phones.find((p) => p.primary) || res.customer.phones[0];
      if (primaryPhone) {
        try {
          const elig = await customerApi.getContactEligibility(customerId, 'WHATSAPP', primaryPhone.id);
          setEligibility(elig);
        } catch {
          // eligibility failure shouldn't block customer profile
        }
      }
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
      else setError('No se pudo cargar la información del cliente.');
    } finally {
      setIsLoading(false);
    }
  }, [customerId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const formatDate = (isoString?: string | null) => {
    if (!isoString) return 'Sin fecha';
    try {
      const d = new Date(isoString);
      return d.toLocaleDateString('es-CL', {
        day: '2-digit',
        month: 'short',
        year: 'numeric'
      });
    } catch {
      return isoString;
    }
  };

  if (isLoading) {
    return (
      <div style={{ padding: 'var(--space-32)', textAlign: 'center', color: 'var(--color-text-supporting)' }}>
        Cargando perfil del cliente...
      </div>
    );
  }

  if (error || !customer) {
    return (
      <div style={{ padding: 'var(--space-24)' }}>
        <Button variant="secondary" onClick={onBack} style={{ marginBottom: 'var(--space-16)' }}>
          <ArrowBackIcon size={18} />
          Volver a Clientes
        </Button>
        <div
          role="alert"
          style={{
            backgroundColor: 'var(--color-error-bg)',
            color: 'var(--color-error-text)',
            border: '1px solid var(--color-error-border)',
            padding: 'var(--space-16)',
            borderRadius: 'var(--radius-md)'
          }}
        >
          {error || 'Cliente no encontrado.'}
        </div>
      </div>
    );
  }

  const isArchived = customer.status === 'ARCHIVED';
  const primaryPhone = customer.phones.find((p) => p.primary) || customer.phones[0];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-24)', paddingBottom: 'var(--space-32)' }}>
      {/* Top Navigation Bridge */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Button variant="ghost" onClick={onBack} style={{ paddingLeft: 0 }}>
          <ArrowBackIcon size={18} />
          <span>Volver a Clientes</span>
        </Button>

        <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)' }}>
          ID: #{customer.id.slice(0, 8)}
        </span>
      </div>

      {/* Customer Header & Main Actions */}
      <section
        style={{
          backgroundColor: 'var(--color-surface)',
          borderRadius: 'var(--radius-lg)',
          padding: 'var(--space-24)',
          border: '1px solid var(--color-outline-subtle)',
          boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
          display: 'flex',
          flexDirection: 'row',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 'var(--space-16)'
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-12)', flexWrap: 'wrap' }}>
            <h1
              style={{
                fontSize: 'var(--font-size-page-heading)',
                lineHeight: 'var(--line-height-page-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)'
              }}
            >
              {customer.displayName}
            </h1>
            {isArchived ? (
              <Badge variant="archived">Archivado</Badge>
            ) : (
              <Badge variant="success">Cliente Activo</Badge>
            )}
            {primaryPhone && (
              <Badge variant="neutral">
                <WhatsAppIcon size={14} /> WhatsApp ({primaryPhone.e164})
              </Badge>
            )}
          </div>

          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: 'var(--space-16)',
              fontSize: 'var(--font-size-dense)',
              color: 'var(--color-text-supporting)'
            }}
          >
            <span>Cliente desde {formatDate(customer.createdAt)}</span>
            {customer.notes && (
              <>
                <span>•</span>
                <span>{customer.notes}</span>
              </>
            )}
          </div>
        </div>

        <div style={{ display: 'flex', gap: 'var(--space-8)' }}>
          {!isArchived && (
            <>
              <Button variant="secondary" onClick={() => setIsEditModalOpen(true)}>
                <EditIcon size={18} />
                Editar cliente
              </Button>
              <Button
                variant="ghost"
                onClick={() => setIsArchiveModalOpen(true)}
                aria-label="Archivar cliente"
                style={{ color: 'var(--color-error-text)' }}
              >
                <ArchiveIcon size={18} />
                Archivar
              </Button>
            </>
          )}
        </div>
      </section>

      {/* Two-Column Responsive Grid */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: 'var(--space-24)',
          alignItems: 'start'
        }}
      >
        {/* Left Column (Activity & Records) */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-24)' }}>
          {/* Summary / Last Activity */}
          <section
            style={{
              backgroundColor: 'var(--color-surface)',
              borderRadius: 'var(--radius-lg)',
              padding: 'var(--space-24)',
              border: '1px solid var(--color-outline-subtle)',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 'var(--space-16)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                <span style={{ color: 'var(--color-primary)' }}>
                  <ShoppingBagIcon size={20} />
                </span>
                <h2
                  style={{
                    fontSize: 'var(--font-size-section-heading)',
                    lineHeight: 'var(--line-height-section-heading)',
                    fontWeight: 600,
                    color: 'var(--color-text-main)'
                  }}
                >
                  Resumen y última actividad
                </h2>
              </div>
              <Badge variant={isArchived ? 'archived' : 'success'}>
                {isArchived ? 'Inactivo' : 'Perfil activo'}
              </Badge>
            </div>

            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
                gap: 'var(--space-16)'
              }}
            >
              <div
                style={{
                  backgroundColor: 'var(--color-surface-low)',
                  padding: 'var(--space-16)',
                  borderRadius: 'var(--radius-md)'
                }}
              >
                <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', marginBottom: '4px' }}>
                  Última compra registrada
                </div>
                {lastPurchase ? (
                  <>
                    <div style={{ fontSize: 'var(--font-size-section-heading)', fontWeight: 600, color: 'var(--color-text-main)' }}>
                      {formatDate(lastPurchase.purchasedAt)}
                    </div>
                    <div style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginTop: '4px' }}>
                      {lastPurchase.description}
                    </div>
                  </>
                ) : (
                  <div style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-muted)' }}>
                    Sin compras registradas
                  </div>
                )}
              </div>

              <div
                style={{
                  backgroundColor: 'var(--color-surface-low)',
                  padding: 'var(--space-16)',
                  borderRadius: 'var(--radius-md)'
                }}
              >
                <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', marginBottom: '4px' }}>
                  Estado de contacto
                </div>
                <div style={{ fontSize: 'var(--font-size-dense)', fontWeight: 600, color: 'var(--color-text-main)' }}>
                  {eligibility?.eligible ? (
                    <span style={{ color: 'var(--color-brand)', display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                      <CheckCircleIcon size={16} /> Contacto habilitado
                    </span>
                  ) : (
                    <span style={{ color: 'var(--color-warning-text)', display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                      <DoNotDisturbIcon size={16} /> No elegible ({eligibility?.reasons?.join(', ') || 'Restringido'})
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', marginTop: '4px' }}>
                  Evaluado contra políticas de consentimiento de Dokene
                </div>
              </div>
            </div>
          </section>

          {/* Purchases List Component */}
          <PurchaseSection customerId={customer.id} isArchived={isArchived} />

          {/* Follow-up Queue / History hook (Integrated via Issue #39) */}
          <section
            style={{
              backgroundColor: 'var(--color-surface)',
              borderRadius: 'var(--radius-lg)',
              padding: 'var(--space-24)',
              border: '1px solid var(--color-outline-subtle)',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
            }}
          >
            <h2
              style={{
                fontSize: 'var(--font-size-section-heading)',
                lineHeight: 'var(--line-height-section-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                marginBottom: 'var(--space-8)'
              }}
            >
              Seguimientos manuales
            </h2>
            <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)' }}>
              Registro de contactos y compromisos acordados manualmente por el equipo artesano. La gestión de acuerdos y resolución de colas se integra con el módulo de seguimientos.
            </p>
          </section>
        </div>

        {/* Right Column (Consent, Cadence, Status) */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-24)' }}>
          {/* Consent Section Component */}
          <ConsentSection customerId={customer.id} phones={customer.phones} isArchived={isArchived} />

          {/* Business Status & Archive Zone */}
          <section
            style={{
              backgroundColor: 'var(--color-surface)',
              borderRadius: 'var(--radius-lg)',
              padding: 'var(--space-24)',
              border: '1px solid var(--color-outline-subtle)',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
            }}
          >
            <h2
              style={{
                fontSize: 'var(--font-size-section-heading)',
                lineHeight: 'var(--line-height-section-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                marginBottom: 'var(--space-8)'
              }}
            >
              Estado en el negocio
            </h2>
            <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginBottom: 'var(--space-16)' }}>
              {isArchived
                ? 'Este cliente se encuentra archivado. Sus registros históricos se mantienen resguardados con fines de auditoría y consultas contables.'
                : `${customer.displayName} forma parte de la cartera activa de clientes.`}
            </p>

            {!isArchived && (
              <Button
                variant="danger"
                onClick={() => setIsArchiveModalOpen(true)}
                style={{ width: '100%' }}
              >
                <ArchiveIcon size={18} />
                Archivar ficha de cliente
              </Button>
            )}
          </section>
        </div>
      </div>

      {/* Modals */}
      {isEditModalOpen && (
        <CustomerFormModal
          isOpen={isEditModalOpen}
          onClose={() => setIsEditModalOpen(false)}
          customerToEdit={customer}
          onSaved={(updated) => {
            setCustomer(updated);
            loadData();
          }}
        />
      )}

      {isArchiveModalOpen && (
        <ArchiveModal
          customer={customer}
          isOpen={isArchiveModalOpen}
          onClose={() => setIsArchiveModalOpen(false)}
          onArchived={() => {
            loadData();
          }}
        />
      )}
    </div>
  );
}
