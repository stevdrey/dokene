import { useState, useEffect, useCallback } from 'react';
import {
  ContactPolicyResponse,
  PolicyEventResponse,
  ConsentStatus,
  ContactIntentSource,
  PhoneResponse
} from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { Badge } from '@/shared/components/Badge';
import {
  CheckCircleIcon,
  DoNotDisturbIcon,
  WhatsAppIcon,
  HistoryIcon,
  SettingsIcon
} from '@/shared/components/Icons';

interface ConsentSectionProps {
  customerId: string;
  phones: PhoneResponse[];
  isArchived: boolean;
}

export function ConsentSection({ customerId, phones, isArchived }: ConsentSectionProps) {
  const [policy, setPolicy] = useState<ContactPolicyResponse | null>(null);
  const [policyVersion, setPolicyVersion] = useState<number>(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modals state
  const [isConsentModalOpen, setIsConsentModalOpen] = useState(false);
  const [isDoNotContactModalOpen, setIsDoNotContactModalOpen] = useState(false);
  const [historyEvents, setHistoryEvents] = useState<PolicyEventResponse[] | null>(null);

  // Form states
  const [targetStatus, setTargetStatus] = useState<'GRANTED' | 'REVOKED'>('GRANTED');
  const [targetSource, setTargetSource] = useState<ContactIntentSource>('CUSTOMER_VERBAL');
  const [doNotContactSource, setDoNotContactSource] = useState<ContactIntentSource>('CUSTOMER_VERBAL');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const primaryPhone = phones.find((p) => p.primary) || phones[0];

  const loadPolicy = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const res = await customerApi.getContactPolicy(customerId);
      setPolicy(res.policy);
      setPolicyVersion(res.version);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
      else setError('Error al cargar la política de contacto.');
    } finally {
      setIsLoading(false);
    }
  }, [customerId]);

  useEffect(() => {
    loadPolicy();
  }, [loadPolicy]);

  const handleConsentSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!primaryPhone) return;
    setModalError(null);
    setIsSubmitting(true);
    try {
      const updated = await customerApi.changeConsent(
        customerId,
        primaryPhone.id,
        'WHATSAPP',
        policyVersion,
        targetStatus,
        targetSource
      );
      setPolicy(updated);
      setPolicyVersion(updated.version);
      setIsConsentModalOpen(false);
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al actualizar consentimiento.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDoNotContactSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!policy) return;
    setModalError(null);
    setIsSubmitting(true);
    const newEnabled = !policy.doNotContact;
    try {
      const updated = await customerApi.changeDoNotContact(
        customerId,
        policyVersion,
        newEnabled,
        doNotContactSource
      );
      setPolicy(updated);
      setPolicyVersion(updated.version);
      setIsDoNotContactModalOpen(false);
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al actualizar protocolo No contactar.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const openHistoryModal = async () => {
    try {
      const res = await customerApi.getContactPolicyHistory(customerId);
      setHistoryEvents(res.events);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
    }
  };

  const formatSource = (src: ContactIntentSource | null) => {
    switch (src) {
      case 'CUSTOMER_VERBAL':
        return 'Verbal por el cliente';
      case 'CUSTOMER_WRITTEN':
        return 'Escrito por el cliente (Firma / Mensaje)';
      case 'OPERATOR_CORRECTION':
        return 'Corrección del operador';
      default:
        return 'No registrado';
    }
  };

  const formatStatus = (st?: ConsentStatus) => {
    switch (st) {
      case 'GRANTED':
        return 'Activo / Concedido';
      case 'REVOKED':
        return 'Revocado';
      case 'UNKNOWN':
      default:
        return 'Desconocido (No solicitado)';
    }
  };

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

  const currentConsent = policy?.consents.find(
    (c) => c.channel === 'WHATSAPP' && (!primaryPhone || c.contactId === primaryPhone.id)
  );

  return (
    <section
      style={{
        backgroundColor: 'var(--color-surface)',
        borderRadius: 'var(--radius-lg)',
        padding: 'var(--space-24)',
        border: '1px solid var(--color-outline-subtle)',
        boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--space-16)'
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          paddingBottom: 'var(--space-12)',
          borderBottom: '1px solid var(--color-outline-subtle)'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
          <span style={{ color: 'var(--color-primary)', display: 'inline-flex' }}>
            <CheckCircleIcon size={20} />
          </span>
          <h2
            style={{
              fontSize: 'var(--font-size-section-heading)',
              lineHeight: 'var(--line-height-section-heading)',
              fontWeight: 600,
              color: 'var(--color-text-main)'
            }}
          >
            Consentimiento
          </h2>
        </div>

        {policy?.doNotContact ? (
          <Badge variant="error">No contactar activo</Badge>
        ) : currentConsent?.status === 'GRANTED' ? (
          <Badge variant="success">Activo</Badge>
        ) : currentConsent?.status === 'REVOKED' ? (
          <Badge variant="error">Revocado</Badge>
        ) : (
          <Badge variant="warning">Desconocido</Badge>
        )}
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

      {isLoading ? (
        <div style={{ padding: 'var(--space-16)', textAlign: 'center', color: 'var(--color-text-supporting)' }}>
          Cargando política de contacto...
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)', fontSize: 'var(--font-size-dense)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 'var(--space-4) 0' }}>
              <span style={{ color: 'var(--color-text-supporting)' }}>Canal principal:</span>
              <span style={{ fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 'var(--space-4)' }}>
                <WhatsAppIcon size={16} /> WhatsApp ({primaryPhone?.e164 || 'Sin número'})
              </span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 'var(--space-4) 0', borderTop: '1px solid var(--color-outline-subtle)' }}>
              <span style={{ color: 'var(--color-text-supporting)' }}>Estado de autorización:</span>
              <span style={{ fontWeight: 500 }}>
                {formatStatus(currentConsent?.status)}
              </span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 'var(--space-4) 0', borderTop: '1px solid var(--color-outline-subtle)' }}>
              <span style={{ color: 'var(--color-text-supporting)' }}>Origen de registro:</span>
              <span style={{ fontWeight: 500 }}>
                {formatSource(currentConsent?.source || null)}
              </span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 'var(--space-4) 0', borderTop: '1px solid var(--color-outline-subtle)' }}>
              <span style={{ color: 'var(--color-text-supporting)' }}>Fecha de cambio:</span>
              <span style={{ color: 'var(--color-text-supporting)' }}>
                {formatDate(currentConsent?.changedAt)}
              </span>
            </div>
          </div>

          {policy?.doNotContact ? (
            <div
              style={{
                backgroundColor: 'var(--color-error-bg)',
                color: 'var(--color-error-text)',
                border: '1px solid var(--color-error-border)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-12)',
                fontSize: 'var(--font-size-meta)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)', fontWeight: 600 }}>
                <DoNotDisturbIcon size={16} /> Protocolo "No contactar" ACTIVADO
              </div>
              <p style={{ marginTop: '4px' }}>
                Este cliente tiene restringido cualquier contacto directo por todos los canales. El cambio fue registrado como {formatSource(policy.doNotContactSource)} el {formatDate(policy.doNotContactChangedAt)}.
              </p>
            </div>
          ) : (
            <div
              style={{
                backgroundColor: 'var(--color-surface-low)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-12)',
                fontSize: 'var(--font-size-meta)',
                color: 'var(--color-text-supporting)'
              }}
            >
              Consentimiento válido únicamente para avisos de reorden, seguimiento y acuerdos directos solicitados por el cliente. Dokene no realiza envíos masivos.
            </div>
          )}

          {!isArchived && (
            <div
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 'var(--space-8)',
                paddingTop: 'var(--space-12)',
                borderTop: '1px solid var(--color-outline-subtle)'
              }}
            >
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setTargetStatus(currentConsent?.status === 'GRANTED' ? 'REVOKED' : 'GRANTED');
                  setTargetSource('CUSTOMER_VERBAL');
                  setModalError(null);
                  setIsConsentModalOpen(true);
                }}
              >
                <SettingsIcon size={16} />
                Gestionar consentimiento
              </Button>

              <Button
                variant={policy?.doNotContact ? 'secondary' : 'danger'}
                size="sm"
                onClick={() => {
                  setDoNotContactSource('CUSTOMER_VERBAL');
                  setModalError(null);
                  setIsDoNotContactModalOpen(true);
                }}
              >
                <DoNotDisturbIcon size={16} />
                {policy?.doNotContact ? 'Desactivar No contactar' : 'Marcar No contactar'}
              </Button>

              <Button variant="ghost" size="sm" onClick={openHistoryModal}>
                <HistoryIcon size={16} />
                Historial
              </Button>
            </div>
          )}
        </>
      )}

      {/* Modal: Gestionar Consentimiento */}
      <Modal
        isOpen={isConsentModalOpen}
        onClose={() => setIsConsentModalOpen(false)}
        title="Gestionar consentimiento de WhatsApp"
        maxWidth="480px"
      >
        <form onSubmit={handleConsentSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
          {modalError && (
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
              {modalError}
            </div>
          )}

          <div>
            <label style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-8)' }}>
              Nuevo estado de autorización *
            </label>
            <div style={{ display: 'flex', gap: 'var(--space-16)' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="consentStatus"
                  value="GRANTED"
                  checked={targetStatus === 'GRANTED'}
                  onChange={() => setTargetStatus('GRANTED')}
                />
                Concedido / Activo
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="consentStatus"
                  value="REVOKED"
                  checked={targetStatus === 'REVOKED'}
                  onChange={() => setTargetStatus('REVOKED')}
                />
                Revocado
              </label>
            </div>
          </div>

          <div>
            <label
              htmlFor="consent-source-select"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Origen de la solicitud (auditable) *
            </label>
            <select
              id="consent-source-select"
              value={targetSource}
              onChange={(e) => setTargetSource(e.target.value as ContactIntentSource)}
              style={{
                width: '100%',
                minHeight: '44px',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            >
              <option value="CUSTOMER_VERBAL">Verbal por el cliente (Llamada / Taller)</option>
              <option value="CUSTOMER_WRITTEN">Escrito por el cliente (Firma en boleta / Mensaje)</option>
              <option value="OPERATOR_CORRECTION">Corrección administrativa del operador</option>
            </select>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-12)' }}>
            <Button type="button" variant="secondary" onClick={() => setIsConsentModalOpen(false)} disabled={isSubmitting}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSubmitting}>
              Guardar consentimiento
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Marcar / Desactivar No Contactar */}
      <Modal
        isOpen={isDoNotContactModalOpen}
        onClose={() => setIsDoNotContactModalOpen(false)}
        title={policy?.doNotContact ? '¿Desactivar protocolo No contactar?' : '¿Marcar como No contactar?'}
        maxWidth="480px"
      >
        <form onSubmit={handleDoNotContactSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
          {modalError && (
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
              {modalError}
            </div>
          )}

          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', lineHeight: 1.5 }}>
            {policy?.doNotContact
              ? 'Al desactivar el protocolo, la ficha volverá a respetar los consentimientos individuales previamente concedidos.'
              : 'Al activar "No contactar", se cancelarán todos los avisos futuros y no se permitirá ningún contacto directo por ningún canal.'}
          </p>

          <div>
            <label
              htmlFor="dnc-source-select"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Origen de la solicitud *
            </label>
            <select
              id="dnc-source-select"
              value={doNotContactSource}
              onChange={(e) => setDoNotContactSource(e.target.value as ContactIntentSource)}
              style={{
                width: '100%',
                minHeight: '44px',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            >
              <option value="CUSTOMER_VERBAL">Solicitud verbal del cliente</option>
              <option value="CUSTOMER_WRITTEN">Solicitud escrita del cliente</option>
              <option value="OPERATOR_CORRECTION">Corrección del operador</option>
            </select>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-12)' }}>
            <Button type="button" variant="secondary" onClick={() => setIsDoNotContactModalOpen(false)} disabled={isSubmitting}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant={policy?.doNotContact ? 'primary' : 'danger'}
              isLoading={isSubmitting}
            >
              {policy?.doNotContact ? 'Confirmar y reactivar' : 'Confirmar restricción'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Historial de Consentimiento */}
      <Modal
        isOpen={Boolean(historyEvents)}
        onClose={() => setHistoryEvents(null)}
        title="Historial de consentimientos y políticas"
        maxWidth="550px"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
          {historyEvents && historyEvents.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
              {historyEvents.map((evt) => (
                <div
                  key={evt.id}
                  style={{
                    padding: 'var(--space-12)',
                    backgroundColor: 'var(--color-surface-inset)',
                    borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--color-outline-subtle)',
                    fontSize: 'var(--font-size-dense)'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: 600 }}>{evt.type}</span>
                    <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                      {formatDate(evt.occurredAt)}
                    </span>
                  </div>
                  <div style={{ marginTop: '4px', color: 'var(--color-text-supporting)' }}>
                    Estado: {formatStatus(evt.consentStatus)} • Fuente: {formatSource(evt.source)}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p style={{ color: 'var(--color-text-supporting)', fontSize: 'var(--font-size-dense)' }}>
              No hay eventos de consentimiento registrados aún.
            </p>
          )}
        </div>
      </Modal>
    </section>
  );
}
