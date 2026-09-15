import { useState, useEffect, useCallback, useRef } from 'react';
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
  canWrite?: boolean;
  onPolicyUpdated?: () => void;
}

export function ConsentSection({ customerId, phones, isArchived, canWrite = true, onPolicyUpdated }: ConsentSectionProps) {
  const [policy, setPolicy] = useState<ContactPolicyResponse | null>(null);
  const [policyVersion, setPolicyVersion] = useState<number>(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modals state
  const [targetPhone, setTargetPhone] = useState<PhoneResponse | null>(null);
  const [isConsentModalOpen, setIsConsentModalOpen] = useState(false);
  const [isDoNotContactModalOpen, setIsDoNotContactModalOpen] = useState(false);
  const [historyEvents, setHistoryEvents] = useState<PolicyEventResponse[] | null>(null);
  const [historyNextCursor, setHistoryNextCursor] = useState<string | null>(null);
  const [isHistoryLoadingMore, setIsHistoryLoadingMore] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  // Form states
  const [targetStatus, setTargetStatus] = useState<'GRANTED' | 'REVOKED' | null>(null);
  const [currentConsentStatus, setCurrentConsentStatus] = useState<ConsentStatus | null>(null);
  const [targetSource, setTargetSource] = useState<ContactIntentSource>('CUSTOMER_VERBAL');
  const [doNotContactSource, setDoNotContactSource] = useState<ContactIntentSource>('CUSTOMER_VERBAL');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const queryGenerationRef = useRef(0);
  const activeLoadAbortRef = useRef<AbortController | null>(null);
  const activeHistoryAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      activeLoadAbortRef.current?.abort();
      activeHistoryAbortRef.current?.abort();
    };
  }, []);

  const loadPolicy = useCallback(async () => {
    if (activeLoadAbortRef.current) {
      activeLoadAbortRef.current.abort();
    }
    const abortController = new AbortController();
    activeLoadAbortRef.current = abortController;
    const generation = ++queryGenerationRef.current;

    setIsLoading(true);
    setError(null);
    try {
      const res = await customerApi.getContactPolicy(customerId, abortController.signal);
      if (generation !== queryGenerationRef.current) {
        return;
      }
      setPolicy(res.policy);
      setPolicyVersion(res.version);
    } catch (err: unknown) {
      if (generation !== queryGenerationRef.current) {
        return;
      }
      if (err instanceof Error && (err.name === 'AbortError' || err.name === 'AbortedTenantRequestError' || err.name === 'StaleSessionError')) {
        return;
      }
      if (err instanceof Error) setError(err.message);
      else setError('Error al cargar la política de contacto.');
    } finally {
      if (generation === queryGenerationRef.current) {
        setIsLoading(false);
      }
    }
  }, [customerId]);

  const phonesKey = phones.map((p) => `${p.id}:${p.e164}`).join('|');

  useEffect(() => {
    loadPolicy();
  }, [loadPolicy, phonesKey]);

  const openConsentModal = (phone: PhoneResponse) => {
    setTargetPhone(phone);
    const existingConsent = policy?.consents.find(
      (c) => c.contactId === phone.id && c.channel === 'WHATSAPP'
    );
    const current = existingConsent?.status ?? 'UNKNOWN';
    setCurrentConsentStatus(current);
    // Explicitly require operator to make a deliberate choice rather than auto-flipping
    setTargetStatus(null);
    setTargetSource('CUSTOMER_VERBAL');
    setModalError(null);
    setIsConsentModalOpen(true);
  };

  const handleConsentSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!targetPhone) return;
    setModalError(null);

    if (!targetStatus) {
      setModalError('Debes seleccionar explícitamente el nuevo estado de autorización.');
      return;
    }

    if (targetStatus === currentConsentStatus) {
      setModalError('El estado seleccionado es igual al estado actual del contacto. Selecciona un cambio de estado.');
      return;
    }

    setIsSubmitting(true);
    try {
      const updated = await customerApi.changeConsent(
        customerId,
        targetPhone.id,
        'WHATSAPP',
        policyVersion,
        targetStatus,
        targetSource
      );
      setPolicy(updated);
      setPolicyVersion(updated.version);
      setIsConsentModalOpen(false);
      onPolicyUpdated?.();
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
      onPolicyUpdated?.();
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al actualizar protocolo No contactar.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const openHistoryModal = async () => {
    activeHistoryAbortRef.current?.abort();
    const abortController = new AbortController();
    activeHistoryAbortRef.current = abortController;

    setHistoryError(null);
    setHistoryNextCursor(null);
    try {
      const res = await customerApi.getContactPolicyHistory(customerId, undefined, 50, abortController.signal);
      setHistoryEvents(res.events);
      setHistoryNextCursor(res.nextCursor);
    } catch (err: unknown) {
      if (err instanceof Error && (err.name === 'AbortError' || err.name === 'AbortedTenantRequestError' || err.name === 'StaleSessionError')) {
        return;
      }
      if (err instanceof Error) setError(err.message);
    }
  };

  const loadMoreHistory = async () => {
    if (!historyNextCursor || isHistoryLoadingMore) return;
    setIsHistoryLoadingMore(true);
    setHistoryError(null);
    try {
      const res = await customerApi.getContactPolicyHistory(
        customerId,
        historyNextCursor,
        50,
        activeHistoryAbortRef.current?.signal
      );
      setHistoryEvents((prev) => (prev ? [...prev, ...res.events] : res.events));
      setHistoryNextCursor(res.nextCursor);
    } catch (err: unknown) {
      if (err instanceof Error && (err.name === 'AbortError' || err.name === 'AbortedTenantRequestError' || err.name === 'StaleSessionError')) {
        return;
      }
      if (err instanceof Error) setHistoryError(err.message);
      else setHistoryError('Error al cargar historial anterior.');
    } finally {
      setIsHistoryLoadingMore(false);
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

  const formatDateTime = (isoString?: string | null) => {
    if (!isoString) return 'Sin fecha';
    try {
      const d = new Date(isoString);
      return d.toLocaleString('es-CL', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });
    } catch {
      return isoString;
    }
  };

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
      {/* Section Header */}
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
            Consentimiento y políticas de contacto
          </h2>
        </div>

        {policy?.doNotContact ? (
          <Badge variant="error">No contactar activo</Badge>
        ) : (
          <Badge variant="neutral">Gestión por teléfono</Badge>
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
          {/* Customer-wide Do Not Contact Restriction */}
          {policy?.doNotContact ? (
            <div
              role="alert"
              style={{
                backgroundColor: 'var(--color-error-bg)',
                color: 'var(--color-error-text)',
                border: '1px solid var(--color-error-border)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-16)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-8)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', fontWeight: 600 }}>
                  <DoNotDisturbIcon size={20} />
                  <span>Protocolo "No contactar" ACTIVADO</span>
                </div>
                {!isArchived && canWrite && (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      setDoNotContactSource('CUSTOMER_VERBAL');
                      setModalError(null);
                      setIsDoNotContactModalOpen(true);
                    }}
                  >
                    Desactivar No contactar
                  </Button>
                )}
              </div>
              <p style={{ marginTop: '8px', fontSize: 'var(--font-size-dense)', lineHeight: 1.5 }}>
                Este cliente tiene restringido cualquier contacto directo por todos los canales. Esta restricción anula todos los consentimientos de WhatsApp de sus teléfonos individuales. El cambio fue registrado como <strong>{formatSource(policy.doNotContactSource)}</strong> el {formatDate(policy.doNotContactChangedAt)}.
              </p>
            </div>
          ) : (
            <div
              style={{
                backgroundColor: 'var(--color-surface-container-low)',
                border: '1px solid var(--color-outline-subtle)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-12) var(--space-16)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: 'var(--space-8)'
              }}
            >
              <div>
                <span style={{ fontWeight: 600, fontSize: 'var(--font-size-dense)' }}>Restricción global: </span>
                <span style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)' }}>
                  Protocolo "No contactar" inactivo
                </span>
              </div>
              {!isArchived && canWrite && (
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => {
                    setDoNotContactSource('CUSTOMER_VERBAL');
                    setModalError(null);
                    setIsDoNotContactModalOpen(true);
                  }}
                >
                  <DoNotDisturbIcon size={16} />
                  Marcar No contactar
                </Button>
              )}
            </div>
          )}

          {/* Per-Contact WhatsApp Consent Cards */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
            <h3 style={{ fontSize: 'var(--font-size-component-heading)', fontWeight: 600, margin: 0, color: 'var(--color-text-main)' }}>
              Consentimiento de WhatsApp por teléfono registrado
            </h3>

            {phones.length === 0 ? (
              <p style={{ color: 'var(--color-text-supporting)', fontSize: 'var(--font-size-dense)' }}>
                Este cliente no tiene teléfonos registrados. Agrega un teléfono para gestionar su consentimiento.
              </p>
            ) : (
              phones.map((phone) => {
                const phoneConsent = policy?.consents.find(
                  (c) => c.contactId === phone.id && c.channel === 'WHATSAPP'
                );
                const consentStatus = phoneConsent?.status || 'UNKNOWN';

                return (
                  <div
                    key={phone.id}
                    data-testid={`contact-card-${phone.id}`}
                    style={{
                      backgroundColor: 'var(--color-surface-inset)',
                      borderRadius: 'var(--radius-md)',
                      border: '1px solid var(--color-outline-subtle)',
                      padding: 'var(--space-16)',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 'var(--space-8)'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-8)' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                        <WhatsAppIcon size={18} />
                        <span style={{ fontWeight: 600, fontSize: 'var(--font-size-body)' }}>{phone.e164}</span>
                        <Badge variant="neutral">
                          {phone.primary ? 'Principal' : 'Secundario'}
                        </Badge>
                      </div>

                      <Badge
                        variant={
                          consentStatus === 'GRANTED'
                            ? 'success'
                            : consentStatus === 'REVOKED'
                            ? 'error'
                            : 'warning'
                        }
                      >
                        {formatStatus(consentStatus)}
                      </Badge>
                    </div>

                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-16)', fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)' }}>
                      <span>
                        Origen: <strong>{formatSource(phoneConsent?.source || null)}</strong>
                      </span>
                      <span>•</span>
                      <span>
                        Fecha de cambio: <strong>{formatDate(phoneConsent?.changedAt)}</strong>
                      </span>
                    </div>

                    {!isArchived && canWrite && (
                      <div style={{ marginTop: 'var(--space-4)', display: 'flex', justifyContent: 'flex-end' }}>
                        <Button
                          variant="secondary"
                          size="sm"
                          aria-label={`Gestionar consentimiento para ${phone.e164}`}
                          onClick={() => openConsentModal(phone)}
                        >
                          <SettingsIcon size={16} />
                          Gestionar consentimiento
                        </Button>
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              paddingTop: 'var(--space-12)',
              borderTop: '1px solid var(--color-outline-subtle)'
            }}
          >
            <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)' }}>
              Consentimiento válido únicamente para avisos de reorden y acuerdos directos.
            </span>
            <Button variant="ghost" size="sm" onClick={openHistoryModal}>
              <HistoryIcon size={16} />
              Historial de políticas
            </Button>
          </div>
        </>
      )}

      {/* Modal: Gestionar Consentimiento de Contacto Específico */}
      <Modal
        isOpen={isConsentModalOpen}
        onClose={() => setIsConsentModalOpen(false)}
        title="Gestionar consentimiento de WhatsApp"
        maxWidth="480px"
        closeDisabled={isSubmitting}
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

          {targetPhone && (
            <div
              style={{
                padding: 'var(--space-12)',
                backgroundColor: 'var(--color-surface-container-low)',
                borderRadius: 'var(--radius-md)',
                fontSize: 'var(--font-size-dense)',
                display: 'flex',
                flexDirection: 'column',
                gap: 'var(--space-4)'
              }}
            >
              <div style={{ fontWeight: 600, color: 'var(--color-text-main)' }}>
                Teléfono: {targetPhone.e164} ({targetPhone.primary ? 'Principal' : 'Secundario'})
              </div>
              <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                ID de contacto: {targetPhone.id}
              </div>
              <div style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-main)', marginTop: '2px' }}>
                <strong>Estado actual:</strong> {formatStatus(currentConsentStatus ?? undefined)}
              </div>
            </div>
          )}

          <div>
            <label style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-8)' }}>
              Nuevo estado de autorización para este teléfono *
            </label>
            <div style={{ display: 'flex', gap: 'var(--space-16)', flexWrap: 'wrap' }}>
              <label
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 'var(--space-8)',
                  cursor: 'pointer',
                  minHeight: '44px',
                  padding: '0 var(--space-8)',
                  borderRadius: 'var(--radius-md)'
                }}
              >
                <input
                  type="radio"
                  name="consentStatus"
                  value="GRANTED"
                  checked={targetStatus === 'GRANTED'}
                  onChange={() => setTargetStatus('GRANTED')}
                  style={{ width: '18px', height: '18px', margin: 0 }}
                />
                Concedido / Activo
              </label>
              <label
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 'var(--space-8)',
                  cursor: 'pointer',
                  minHeight: '44px',
                  padding: '0 var(--space-8)',
                  borderRadius: 'var(--radius-md)'
                }}
              >
                <input
                  type="radio"
                  name="consentStatus"
                  value="REVOKED"
                  checked={targetStatus === 'REVOKED'}
                  onChange={() => setTargetStatus('REVOKED')}
                  style={{ width: '18px', height: '18px', margin: 0 }}
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
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              disabled={isSubmitting || !targetStatus || targetStatus === currentConsentStatus}
            >
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
        closeDisabled={isSubmitting}
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
              ? 'Al desactivar el protocolo, la ficha volverá a respetar los consentimientos individuales previamente concedidos a cada teléfono.'
              : 'Al activar "No contactar", se cancelarán todos los avisos futuros y no se permitirá ningún contacto directo por ningún canal en ninguno de los teléfonos.'}
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
        onClose={() => {
          setHistoryEvents(null);
          setHistoryNextCursor(null);
          setHistoryError(null);
          activeHistoryAbortRef.current?.abort();
        }}
        title="Historial de consentimientos y políticas"
        maxWidth="550px"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
          {historyError && (
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
              {historyError}
            </div>
          )}

          {historyEvents && historyEvents.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
              {historyEvents.map((evt) => {
                const isDncEvent = evt.type === 'DO_NOT_CONTACT_CHANGED';
                const eventTitle = isDncEvent ? 'Protocolo No contactar' : 'Consentimiento WhatsApp';
                const eventStatusText = isDncEvent
                  ? (evt.doNotContact ? 'Restricción activada (No contactar)' : 'Restricción desactivada')
                  : formatStatus(evt.consentStatus);
                const badgeVariant = isDncEvent
                  ? (evt.doNotContact ? 'error' : 'success')
                  : (evt.consentStatus === 'GRANTED' ? 'success' : evt.consentStatus === 'REVOKED' ? 'error' : 'warning');
                const matchedPhone = evt.contactId ? phones.find((p) => p.id === evt.contactId) : undefined;

                return (
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
                      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                        <span style={{ fontWeight: 600 }}>{eventTitle}</span>
                        <Badge variant={badgeVariant}>
                          {isDncEvent
                            ? (evt.doNotContact ? 'No contactar' : 'Permitido')
                            : (evt.consentStatus === 'GRANTED' ? 'Concedido' : evt.consentStatus === 'REVOKED' ? 'Revocado' : 'Desconocido')}
                        </Badge>
                      </div>
                      <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                        {formatDateTime(evt.occurredAt)}
                      </span>
                    </div>
                    <div style={{ marginTop: '4px', color: 'var(--color-text-supporting)' }}>
                      Estado: {eventStatusText} • Fuente: {formatSource(evt.source)}
                    </div>
                    {evt.contactId && (
                      <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)', marginTop: '2px' }}>
                        {matchedPhone
                          ? `Teléfono: ${matchedPhone.e164} (${matchedPhone.primary ? 'Principal' : 'Secundario'})`
                          : `Contacto: ID ${evt.contactId}`}
                      </div>
                    )}
                  </div>
                );
              })}

              {historyNextCursor && (
                <div style={{ textAlign: 'center', marginTop: 'var(--space-8)' }}>
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={loadMoreHistory}
                    isLoading={isHistoryLoadingMore}
                  >
                    Cargar más historial
                  </Button>
                </div>
              )}
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

