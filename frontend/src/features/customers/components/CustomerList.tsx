import { useState, useEffect, useCallback, useRef } from 'react';
import { CustomerResponse, CustomerStatus } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { AddIcon, GroupIcon, EditIcon, WhatsAppIcon } from '@/shared/components/Icons';
import { CustomerFormModal, SUPPORTED_REGIONS } from '@/features/customers/components/CustomerFormModal';
import { useTenant, canWriteCustomer } from '@/features/tenants/TenantContext';

interface CustomerListProps {
  onSelectCustomer: (customerId: string) => void;
}

export function CustomerList({ onSelectCustomer }: CustomerListProps) {
  const { activeWorkspace } = useTenant();
  const canWrite = canWriteCustomer(activeWorkspace?.role);
  const [customers, setCustomers] = useState<CustomerResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<CustomerStatus | 'ALL'>('ACTIVE');
  const [nameSearch, setNameSearch] = useState('');
  const [phoneSearch, setPhoneSearch] = useState('');
  const [regionSearch, setRegionSearch] = useState('CL');
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [customerToEdit, setCustomerToEdit] = useState<CustomerResponse | null>(null);

  const queryGenerationRef = useRef(0);
  const activeSearchAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      activeSearchAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    activeSearchAbortRef.current?.abort();
    queryGenerationRef.current++;
    setCustomers([]);
    setNextCursor(null);
    setError(null);
  }, [activeWorkspace?.tenantId]);

  const loadCustomers = useCallback(async () => {
    if (activeSearchAbortRef.current) {
      activeSearchAbortRef.current.abort();
    }
    const abortController = new AbortController();
    activeSearchAbortRef.current = abortController;

    const generation = ++queryGenerationRef.current;
    setIsLoading(true);
    setIsLoadingMore(false);
    setError(null);

    try {
      const page = await customerApi.listCustomers({
        status: statusFilter,
        name: nameSearch.trim() || undefined,
        phone: phoneSearch.trim() || undefined,
        region: phoneSearch.trim() ? regionSearch : undefined
      }, abortController.signal);

      if (generation !== queryGenerationRef.current) {
        return;
      }

      setCustomers(page.customers);
      setNextCursor(page.nextCursor);
    } catch (err: unknown) {
      if (generation !== queryGenerationRef.current) {
        return;
      }
      if (err instanceof Error && (
        err.name === 'AbortError' ||
        err.name === 'AbortedTenantRequestError' ||
        err.name === 'StaleSessionError'
      )) {
        return;
      }
      if (err instanceof Error) setError(err.message);
      else setError('Error al cargar clientes.');
    } finally {
      if (generation === queryGenerationRef.current) {
        setIsLoading(false);
      }
    }
  }, [statusFilter, nameSearch, phoneSearch, regionSearch, activeWorkspace?.tenantId]);

  useEffect(() => {
    const timer = setTimeout(() => {
      loadCustomers();
    }, 300);
    return () => clearTimeout(timer);
  }, [loadCustomers]);

  const loadMore = async () => {
    if (!nextCursor || isLoading || isLoadingMore) return;
    const generation = queryGenerationRef.current;
    setIsLoadingMore(true);
    try {
      const page = await customerApi.listCustomers({
        status: statusFilter,
        name: nameSearch.trim() || undefined,
        phone: phoneSearch.trim() || undefined,
        region: phoneSearch.trim() ? regionSearch : undefined,
        cursor: nextCursor
      });

      if (generation !== queryGenerationRef.current) {
        return;
      }

      setCustomers((prev) => [...prev, ...page.customers]);
      setNextCursor(page.nextCursor);
    } catch (err: unknown) {
      if (generation !== queryGenerationRef.current) {
        return;
      }
      if (err instanceof Error && (
        err.name === 'AbortError' ||
        err.name === 'AbortedTenantRequestError' ||
        err.name === 'StaleSessionError'
      )) {
        return;
      }
      if (err instanceof Error) setError(err.message);
    } finally {
      if (generation === queryGenerationRef.current) {
        setIsLoadingMore(false);
      }
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
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-24)', paddingBottom: 'var(--space-32)' }}>
      {/* Header bar */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 'var(--space-16)'
        }}
      >
        <div>
          <h1
            style={{
              fontSize: 'var(--font-size-page-heading)',
              lineHeight: 'var(--line-height-page-heading)',
              fontWeight: 600,
              color: 'var(--color-text-main)'
            }}
          >
            Cartera de Clientes
          </h1>
          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginTop: '4px' }}>
            Gestiona la información de contacto, consentimientos y registro de compras de tus clientes.
          </p>
        </div>

        {canWrite && (
          <Button variant="primary" onClick={() => setIsCreateOpen(true)}>
            <AddIcon size={18} />
            Nuevo cliente
          </Button>
        )}
      </div>

      {/* Filter and Search Bar */}
      <section
        style={{
          backgroundColor: 'var(--color-surface)',
          borderRadius: 'var(--radius-lg)',
          padding: 'var(--space-16)',
          border: '1px solid var(--color-outline-subtle)',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 'var(--space-12)',
          alignItems: 'center'
        }}
      >
        <div style={{ flex: '1 1 240px' }}>
          <label htmlFor="search-name" className="sr-only">
            Buscar por nombre
          </label>
          <input
            id="search-name"
            type="search"
            placeholder="Buscar por nombre..."
            value={nameSearch}
            onChange={(e) => setNameSearch(e.target.value)}
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

        <div style={{ display: 'flex', gap: 'var(--space-8)', flex: '1 1 260px' }}>
          <label htmlFor="search-region" className="sr-only">
            Región telefónica
          </label>
          <select
            id="search-region"
            value={regionSearch}
            onChange={(e) => setRegionSearch(e.target.value)}
            style={{
              minHeight: '44px',
              padding: 'var(--space-8)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              backgroundColor: 'var(--color-surface)',
              fontSize: 'var(--font-size-dense)'
            }}
          >
            {SUPPORTED_REGIONS.map((r) => {
              const prefixMatch = r.label.match(/\(\+\d+\)/);
              const prefix = prefixMatch ? ` ${prefixMatch[0]}` : '';
              return (
                <option key={r.code} value={r.code}>
                  {r.code}{prefix}
                </option>
              );
            })}
          </select>

          <label htmlFor="search-phone" className="sr-only">
            Buscar por teléfono
          </label>
          <input
            id="search-phone"
            type="search"
            placeholder="Teléfono (ej. 984521190)..."
            value={phoneSearch}
            onChange={(e) => setPhoneSearch(e.target.value)}
            style={{
              flex: 1,
              minHeight: '44px',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              backgroundColor: 'var(--color-surface)',
              fontSize: 'var(--font-size-dense)'
            }}
          />
        </div>

        <div
          role="group"
          aria-label="Filtrar por estado del cliente"
          style={{ display: 'flex', gap: 'var(--space-4)', flexWrap: 'wrap' }}
        >
          {(['ACTIVE', 'ARCHIVED', 'ALL'] as const).map((st) => {
            const isSelected = statusFilter === st;
            const labels = { ACTIVE: 'Activos', ARCHIVED: 'Archivados', ALL: 'Todos' };
            return (
              <Button
                key={st}
                variant={isSelected ? 'primary' : 'ghost'}
                size="sm"
                aria-pressed={isSelected}
                onClick={() => setStatusFilter(st)}
              >
                {labels[st]}
              </Button>
            );
          })}
        </div>
      </section>

      {error && (
        <div
          role="alert"
          style={{
            backgroundColor: 'var(--color-error-bg)',
            color: 'var(--color-error-text)',
            border: '1px solid var(--color-error-border)',
            padding: 'var(--space-12)',
            borderRadius: 'var(--radius-md)',
            fontSize: 'var(--font-size-dense)'
          }}
        >
          {error}
        </div>
      )}

      {/* Customer List table/cards */}
      {isLoading ? (
        <div style={{ padding: 'var(--space-32)', textAlign: 'center', color: 'var(--color-text-supporting)' }}>
          Cargando clientes...
        </div>
      ) : error ? (
        <div
          style={{
            padding: 'var(--space-32)',
            textAlign: 'center',
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-outline-subtle)',
            color: 'var(--color-text-supporting)'
          }}
        >
          <p style={{ fontSize: 'var(--font-size-dense)', marginBottom: 'var(--space-16)' }}>
            No se pudo cargar la lista de clientes debido a un error.
          </p>
          <Button variant="secondary" onClick={() => loadCustomers()}>
            Reintentar
          </Button>
        </div>
      ) : customers.length === 0 ? (
        <div
          style={{
            padding: 'var(--space-32)',
            textAlign: 'center',
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-outline-subtle)',
            color: 'var(--color-text-supporting)'
          }}
        >
          <GroupIcon size={36} style={{ margin: '0 auto var(--space-12)', opacity: 0.4 }} />
          <h2 style={{ fontSize: 'var(--font-size-section-heading)', fontWeight: 600, color: 'var(--color-text-main)' }}>
            No se encontraron clientes
          </h2>
          <p style={{ fontSize: 'var(--font-size-dense)', marginTop: '4px' }}>
            Prueba ajustando los filtros de búsqueda o registra un nuevo cliente.
          </p>
        </div>
      ) : (
        <div
          style={{
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-outline-subtle)',
            overflow: 'hidden'
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {customers.map((c) => {
              const primary = c.phones.find((p) => p.primary) || c.phones[0];
              const isArchived = c.status === 'ARCHIVED';
              return (
                <div
                  key={c.id}
                  style={{
                    display: 'flex',
                    flexWrap: 'wrap',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: 'var(--space-16) var(--space-20)',
                    borderBottom: '1px solid var(--color-outline-subtle)',
                    gap: 'var(--space-12)'
                  }}
                >
                  <div style={{ minWidth: '240px', flex: '1 1 auto' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', flexWrap: 'wrap' }}>
                      <button
                        type="button"
                        onClick={() => onSelectCustomer(c.id)}
                        style={{
                          fontSize: 'var(--font-size-body)',
                          fontWeight: 600,
                          color: 'var(--color-text-main)',
                          textAlign: 'left',
                          cursor: 'pointer',
                          padding: 0
                        }}
                      >
                        {c.displayName}
                      </button>
                      {isArchived ? (
                        <Badge variant="archived">Archivado</Badge>
                      ) : (
                        <Badge variant="success">Activo</Badge>
                      )}
                    </div>

                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 'var(--space-12)',
                        fontSize: 'var(--font-size-meta)',
                        color: 'var(--color-text-supporting)',
                        marginTop: '4px',
                        flexWrap: 'wrap'
                      }}
                    >
                      {primary && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                          <WhatsAppIcon size={14} /> {primary.e164}
                        </span>
                      )}
                      <span>•</span>
                      <span>Registrado: {formatDate(c.createdAt)}</span>
                      {c.notes && (
                        <>
                          <span>•</span>
                          <span style={{ maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {c.notes}
                          </span>
                        </>
                      )}
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                    <Button variant="secondary" size="sm" onClick={() => onSelectCustomer(c.id)}>
                      Ver ficha
                    </Button>
                    {!isArchived && canWrite && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setCustomerToEdit(c)}
                        aria-label={`Editar ${c.displayName}`}
                      >
                        <EditIcon size={16} />
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {nextCursor && (
            <div style={{ padding: 'var(--space-16)', textAlign: 'center', borderTop: '1px solid var(--color-outline-subtle)' }}>
              <Button
                variant="secondary"
                onClick={loadMore}
                isLoading={isLoadingMore}
                disabled={isLoadingMore}
                style={{ minHeight: '44px' }}
              >
                Cargar más clientes
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Modals */}
      {isCreateOpen && (
        <CustomerFormModal
          isOpen={isCreateOpen}
          onClose={() => setIsCreateOpen(false)}
          onSaved={(newCustomer) => {
            loadCustomers();
            onSelectCustomer(newCustomer.id);
          }}
        />
      )}

      {customerToEdit && (
        <CustomerFormModal
          isOpen={Boolean(customerToEdit)}
          onClose={() => setCustomerToEdit(null)}
          customerToEdit={customerToEdit}
          onSaved={() => {
            loadCustomers();
          }}
        />
      )}
    </div>
  );
}
