import { useState, useEffect, useCallback } from 'react';
import { PurchaseResponse, PurchaseEventResponse } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { Badge } from '@/shared/components/Badge';
import { ShoppingBagIcon, AddIcon, EditIcon, HistoryIcon, CloseIcon } from '@/shared/components/Icons';

interface PurchaseSectionProps {
  customerId: string;
  isArchived: boolean;
}

export function PurchaseSection({ customerId, isArchived }: PurchaseSectionProps) {
  const [purchases, setPurchases] = useState<PurchaseResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modals state
  const [isRecordOpen, setIsRecordOpen] = useState(false);
  const [purchaseToEdit, setPurchaseToEdit] = useState<PurchaseResponse | null>(null);
  const [purchaseToVoid, setPurchaseToVoid] = useState<PurchaseResponse | null>(null);
  const [historyEvents, setHistoryEvents] = useState<PurchaseEventResponse[] | null>(null);
  const [historyPurchaseDesc, setHistoryPurchaseDesc] = useState('');

  // Form states
  const [purchaseDate, setPurchaseDate] = useState('');
  const [purchaseDesc, setPurchaseDesc] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const loadPurchases = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const page = await customerApi.listPurchases(customerId);
      setPurchases(page.purchases);
      setNextCursor(page.nextCursor);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
      else setError('Error al cargar historial de compras.');
    } finally {
      setIsLoading(false);
    }
  }, [customerId]);

  useEffect(() => {
    loadPurchases();
  }, [loadPurchases]);

  const loadMore = async () => {
    if (!nextCursor) return;
    try {
      const page = await customerApi.listPurchases(customerId, undefined, nextCursor);
      setPurchases((prev) => [...prev, ...page.purchases]);
      setNextCursor(page.nextCursor);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
    }
  };

  const openRecordModal = () => {
    const now = new Date();
    // format as YYYY-MM-DDTHH:mm
    const localIso = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
    setPurchaseDate(localIso);
    setPurchaseDesc('');
    setModalError(null);
    setIsRecordOpen(true);
  };

  const handleRecordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setModalError(null);

    if (!purchaseDesc.trim()) {
      setModalError('La descripción de la compra es obligatoria.');
      return;
    }
    if (purchaseDesc.length > 500) {
      setModalError('La descripción no puede superar los 500 caracteres.');
      return;
    }

    const selectedInstant = new Date(purchaseDate).toISOString();
    if (new Date(purchaseDate).getTime() > Date.now()) {
      setModalError('La fecha de la compra no puede estar en el futuro.');
      return;
    }

    setIsSubmitting(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      await customerApi.recordPurchase(customerId, idempotencyKey, {
        purchasedAt: selectedInstant,
        description: purchaseDesc.trim()
      });
      setIsRecordOpen(false);
      await loadPurchases();
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al registrar compra.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const openEditModal = (purchase: PurchaseResponse) => {
    const dateObj = new Date(purchase.purchasedAt);
    const localIso = new Date(dateObj.getTime() - dateObj.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
    setPurchaseDate(localIso);
    setPurchaseDesc(purchase.description);
    setPurchaseToEdit(purchase);
    setModalError(null);
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!purchaseToEdit) return;
    setModalError(null);

    if (!purchaseDesc.trim()) {
      setModalError('La descripción de la compra es obligatoria.');
      return;
    }

    const selectedInstant = new Date(purchaseDate).toISOString();
    if (new Date(purchaseDate).getTime() > Date.now()) {
      setModalError('La fecha de la compra no puede estar en el futuro.');
      return;
    }

    setIsSubmitting(true);
    try {
      await customerApi.correctPurchase(customerId, purchaseToEdit.id, purchaseToEdit.version, {
        purchasedAt: selectedInstant,
        description: purchaseDesc.trim()
      });
      setPurchaseToEdit(null);
      await loadPurchases();
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al corregir compra.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleVoidSubmit = async () => {
    if (!purchaseToVoid) return;
    setIsSubmitting(true);
    try {
      await customerApi.voidPurchase(customerId, purchaseToVoid.id, purchaseToVoid.version);
      setPurchaseToVoid(null);
      await loadPurchases();
    } catch (err: unknown) {
      if (err instanceof Error) setModalError(err.message);
      else setModalError('Error al anular compra.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const openHistoryModal = async (purchase: PurchaseResponse) => {
    try {
      setHistoryPurchaseDesc(purchase.description);
      const res = await customerApi.getPurchaseHistory(customerId, purchase.id);
      setHistoryEvents(res.events);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
    }
  };

  const formatDate = (isoString: string) => {
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

  return (
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
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 'var(--space-12)',
          paddingBottom: 'var(--space-16)',
          borderBottom: '1px solid var(--color-outline-subtle)',
          marginBottom: 'var(--space-16)'
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
            <span style={{ color: 'var(--color-primary)', display: 'inline-flex' }}>
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
              Historial de compras ({purchases.length})
            </h2>
          </div>
          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginTop: '4px' }}>
            Registro de actividad de compras y pedidos para seguimiento de clientes.
          </p>
        </div>

        {!isArchived && (
          <Button variant="primary" onClick={openRecordModal}>
            <AddIcon size={18} />
            Registrar compra
          </Button>
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
            fontSize: 'var(--font-size-dense)',
            marginBottom: 'var(--space-16)'
          }}
        >
          {error}
        </div>
      )}

      {isLoading ? (
        <div style={{ padding: 'var(--space-24)', textAlign: 'center', color: 'var(--color-text-supporting)' }}>
          Cargando compras...
        </div>
      ) : purchases.length === 0 ? (
        <div
          style={{
            padding: 'var(--space-32)',
            textAlign: 'center',
            backgroundColor: 'var(--color-surface-inset)',
            borderRadius: 'var(--radius-md)',
            color: 'var(--color-text-supporting)'
          }}
        >
          <ShoppingBagIcon size={32} style={{ margin: '0 auto var(--space-8)', opacity: 0.4 }} />
          <p style={{ fontWeight: 500 }}>No hay compras registradas para este cliente.</p>
          <p style={{ fontSize: 'var(--font-size-meta)', marginTop: '4px' }}>
            Registra una compra para iniciar el historial y la cadencia de seguimiento.
          </p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
          {purchases.map((purchase) => {
            const isVoid = purchase.status === 'VOID';
            return (
              <div
                key={purchase.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: 'var(--space-12) var(--space-16)',
                  borderRadius: 'var(--radius-md)',
                  backgroundColor: isVoid ? 'var(--color-surface-inset)' : 'var(--color-surface)',
                  border: '1px solid var(--color-outline-subtle)',
                  opacity: isVoid ? 0.75 : 1
                }}
              >
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 'var(--space-12)', minWidth: 0 }}>
                  <div
                    style={{
                      width: '36px',
                      height: '36px',
                      borderRadius: 'var(--radius-md)',
                      backgroundColor: isVoid ? 'var(--color-outline-subtle)' : 'var(--color-surface-container)',
                      color: isVoid ? 'var(--color-text-muted)' : 'var(--color-primary)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0
                    }}
                  >
                    <ShoppingBagIcon size={18} />
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', flexWrap: 'wrap' }}>
                      <span
                        style={{
                          fontWeight: 600,
                          fontSize: 'var(--font-size-dense)',
                          color: 'var(--color-text-main)',
                          textDecoration: isVoid ? 'line-through' : 'none'
                        }}
                      >
                        {purchase.description}
                      </span>
                      {isVoid ? (
                        <Badge variant="error">Anulada</Badge>
                      ) : (
                        <Badge variant="success">Válida</Badge>
                      )}
                    </div>
                    <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)', marginTop: '2px' }}>
                      Fecha de compra: {formatDate(purchase.purchasedAt)}
                    </div>
                  </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)', flexShrink: 0 }}>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => openHistoryModal(purchase)}
                    aria-label="Ver historial de auditoría de la compra"
                  >
                    <HistoryIcon size={16} />
                  </Button>
                  {!isArchived && !isVoid && (
                    <>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => openEditModal(purchase)}
                        aria-label="Corregir compra"
                      >
                        <EditIcon size={16} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPurchaseToVoid(purchase)}
                        aria-label="Anular compra"
                        style={{ color: 'var(--color-error-text)' }}
                      >
                        <CloseIcon size={16} />
                      </Button>
                    </>
                  )}
                </div>
              </div>
            );
          })}

          {nextCursor && (
            <div style={{ textAlign: 'center', marginTop: 'var(--space-12)' }}>
              <Button variant="secondary" onClick={loadMore}>
                Cargar compras anteriores
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Modal: Registrar Compra */}
      <Modal
        isOpen={isRecordOpen}
        onClose={() => setIsRecordOpen(false)}
        title="Registrar nueva compra"
        maxWidth="500px"
      >
        <form onSubmit={handleRecordSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
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
            <label
              htmlFor="record-purchase-date"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Fecha y hora de la compra *
            </label>
            <input
              id="record-purchase-date"
              type="datetime-local"
              required
              value={purchaseDate}
              onChange={(e) => setPurchaseDate(e.target.value)}
              style={{
                width: '100%',
                minHeight: '44px',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            />
          </div>

          <div>
            <label
              htmlFor="record-purchase-desc"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Descripción de la compra o pedido *
            </label>
            <textarea
              id="record-purchase-desc"
              rows={3}
              required
              maxLength={500}
              value={purchaseDesc}
              onChange={(e) => setPurchaseDesc(e.target.value)}
              placeholder="Ej. Kit Harinas Especiales + Esencias (Pedido telefónico para taller)"
              style={{
                width: '100%',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            />
            <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', textAlign: 'right', marginTop: '2px' }}>
              {purchaseDesc.length}/500
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-12)' }}>
            <Button type="button" variant="secondary" onClick={() => setIsRecordOpen(false)} disabled={isSubmitting}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSubmitting}>
              Guardar compra
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Corregir Compra */}
      <Modal
        isOpen={Boolean(purchaseToEdit)}
        onClose={() => setPurchaseToEdit(null)}
        title="Corregir compra"
        maxWidth="500px"
      >
        <form onSubmit={handleEditSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
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
            <label
              htmlFor="edit-purchase-date"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Fecha y hora corregida *
            </label>
            <input
              id="edit-purchase-date"
              type="datetime-local"
              required
              value={purchaseDate}
              onChange={(e) => setPurchaseDate(e.target.value)}
              style={{
                width: '100%',
                minHeight: '44px',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            />
          </div>

          <div>
            <label
              htmlFor="edit-purchase-desc"
              style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
            >
              Descripción corregida *
            </label>
            <textarea
              id="edit-purchase-desc"
              rows={3}
              required
              maxLength={500}
              value={purchaseDesc}
              onChange={(e) => setPurchaseDesc(e.target.value)}
              style={{
                width: '100%',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                fontSize: 'var(--font-size-dense)'
              }}
            />
            <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', textAlign: 'right', marginTop: '2px' }}>
              {purchaseDesc.length}/500
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-12)' }}>
            <Button type="button" variant="secondary" onClick={() => setPurchaseToEdit(null)} disabled={isSubmitting}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSubmitting}>
              Guardar corrección
            </Button>
          </div>
        </form>
      </Modal>

      {/* Modal: Anular Compra */}
      <Modal
        isOpen={Boolean(purchaseToVoid)}
        onClose={() => setPurchaseToVoid(null)}
        title="¿Anular esta compra?"
        maxWidth="480px"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', lineHeight: 1.5 }}>
            La compra "<strong>{purchaseToVoid?.description}</strong>" quedará marcada como anulada. Esta acción es irreversible y queda auditada.
          </p>

          <div
            style={{
              backgroundColor: 'var(--color-surface-low)',
              padding: 'var(--space-12)',
              borderRadius: 'var(--radius-md)',
              fontSize: 'var(--font-size-meta)',
              color: 'var(--color-text-supporting)'
            }}
          >
            La anulación recalculará automáticamente la última compra válida del cliente.
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-12)' }}>
            <Button variant="secondary" onClick={() => setPurchaseToVoid(null)} disabled={isSubmitting}>
              Cancelar
            </Button>
            <Button variant="danger" onClick={handleVoidSubmit} isLoading={isSubmitting}>
              Confirmar anulación
            </Button>
          </div>
        </div>
      </Modal>

      {/* Modal: Historial de Auditoría de Compra */}
      <Modal
        isOpen={Boolean(historyEvents)}
        onClose={() => setHistoryEvents(null)}
        title="Historial de revisiones de compra"
        maxWidth="550px"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
          <div style={{ fontSize: 'var(--font-size-dense)', fontWeight: 600, color: 'var(--color-text-main)' }}>
            {historyPurchaseDesc}
          </div>
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
                    <Badge variant={evt.type === 'VOID' ? 'error' : 'neutral'}>
                      {evt.type === 'RECORD' ? 'Registro inicial' : evt.type === 'CORRECT' ? 'Corrección' : 'Anulación'}
                    </Badge>
                    <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                      {formatDate(evt.occurredAt)}
                    </span>
                  </div>
                  <p style={{ marginTop: 'var(--space-8)', color: 'var(--color-text-main)' }}>
                    {evt.description}
                  </p>
                </div>
              ))}
            </div>
          ) : (
            <p style={{ color: 'var(--color-text-supporting)', fontSize: 'var(--font-size-dense)' }}>
              No hay eventos adicionales registrados.
            </p>
          )}
        </div>
      </Modal>
    </section>
  );
}
