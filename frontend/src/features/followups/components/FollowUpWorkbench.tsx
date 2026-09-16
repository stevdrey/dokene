import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTenant } from '@/features/tenants/TenantContext';
import { QueueItemResponse, FollowUpStatus } from '@/features/followups/types';
import { followUpApi } from '@/features/followups/api/followUpApi';
import { FollowUpCard } from './FollowUpCard';
import { FollowUpDetail } from './FollowUpDetail';
import { Button } from '@/shared/components/Button';
import { ApiError } from '@/shared/api/httpClient';

interface FollowUpWorkbenchProps {
  onNavigateToCustomer: (customerId: string) => void;
}

export const FollowUpWorkbench: React.FC<FollowUpWorkbenchProps> = ({ onNavigateToCustomer }) => {
  const { activeWorkspace } = useTenant();
  const canWrite = activeWorkspace?.role ? activeWorkspace.role !== 'VIEWER' : true;
  const [items, setItems] = useState<QueueItemResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflictNotice, setConflictNotice] = useState<string | null>(null);
  const [tenantTimeZone, setTenantTimeZone] = useState<string | undefined>(undefined);

  const [selectedCustomerId, setSelectedCustomerId] = useState<string | null>(null);
  const [activeFilter, setActiveFilter] = useState<'ALL' | 'OVERDUE'>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const requestGenerationRef = useRef(0);
  const loadMoreAbortControllerRef = useRef<AbortController | null>(null);

  // Detect mobile width (< 1024px) for responsive split vs stacked view
  const [isMobile, setIsMobile] = useState(() =>
    typeof window !== 'undefined' ? window.innerWidth < 1024 : false
  );

  useEffect(() => {
    const handleResize = () => {
      setIsMobile(window.innerWidth < 1024);
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Fetch tenant policy time zone
  useEffect(() => {
    let active = true;
    const fetchPolicy = async () => {
      try {
        const { policy } = await followUpApi.getTenantFollowUpPolicy();
        if (active && policy?.timeZone) {
          setTenantTimeZone(policy.timeZone);
        }
      } catch {
        // Fallback gracefully to browser/default
      }
    };
    fetchPolicy();
    return () => {
      active = false;
    };
  }, [activeWorkspace?.tenantId]);

  const fetchQueue = useCallback(
    async (statusFilter?: FollowUpStatus, signal?: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        const page = await followUpApi.getFollowUpQueue(
          { status: statusFilter, limit: 50 },
          signal
        );
        setItems(page.items || []);
        setNextCursor(page.nextCursor || null);

        // Auto-select first item on desktop if nothing selected
        if (!isMobile && page.items && page.items.length > 0) {
          setSelectedCustomerId(page.items[0].customerId);
        } else {
          setSelectedCustomerId(null);
        }
      } catch (err) {
        if (err instanceof Error && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Error al cargar la lista de seguimientos.');
      } finally {
        setLoading(false);
      }
    },
    [isMobile]
  );

  // Reload queue when workspace or filter changes
  useEffect(() => {
    requestGenerationRef.current += 1;
    if (loadMoreAbortControllerRef.current) {
      loadMoreAbortControllerRef.current.abort();
      loadMoreAbortControllerRef.current = null;
    }
    const controller = new AbortController();
    fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined, controller.signal);
    return () => {
      controller.abort();
      if (loadMoreAbortControllerRef.current) {
        loadMoreAbortControllerRef.current.abort();
        loadMoreAbortControllerRef.current = null;
      }
    };
  }, [activeWorkspace?.tenantId, activeFilter, fetchQueue]);

  const handleLoadMore = async () => {
    if (!nextCursor || loadingMore) return;
    const currentGeneration = requestGenerationRef.current;
    const currentFilter = activeFilter;
    const controller = new AbortController();
    loadMoreAbortControllerRef.current = controller;
    setLoadingMore(true);
    try {
      const page = await followUpApi.getFollowUpQueue(
        {
          status: currentFilter === 'OVERDUE' ? 'OVERDUE' : undefined,
          cursor: nextCursor,
          limit: 50
        },
        controller.signal
      );
      if (requestGenerationRef.current === currentGeneration && activeFilter === currentFilter) {
        setItems((prev) => [...prev, ...(page.items || [])]);
        setNextCursor(page.nextCursor || null);
      }
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') return;
      if (requestGenerationRef.current === currentGeneration) {
        setError(err instanceof Error ? err.message : 'Error al cargar más seguimientos.');
      }
    } finally {
      if (requestGenerationRef.current === currentGeneration) {
        setLoadingMore(false);
      }
    }
  };

  // Filter items by search query across currently loaded pages
  const isSearchActive = searchQuery.trim().length > 0;
  const filteredItems = useMemo(() => {
    if (!isSearchActive) return items;
    const q = searchQuery.toLowerCase().trim();
    return items.filter(
      (item) =>
        item.displayName.toLowerCase().includes(q) ||
        (item.primaryPhone && item.primaryPhone.includes(q))
    );
  }, [items, searchQuery, isSearchActive]);

  // Selected item reference scoped to filteredItems
  const selectedItem = useMemo(() => {
    if (filteredItems.length === 0) return null;
    if (selectedCustomerId) {
      const match = filteredItems.find((i) => i.customerId === selectedCustomerId);
      if (match) return match;
    }
    return isMobile ? null : filteredItems[0];
  }, [filteredItems, selectedCustomerId, isMobile]);

  // Stats calculation
  const dueTodayCount = items.filter((i) => i.status === 'DUE').length;
  const overdueCount = items.filter((i) => i.status === 'OVERDUE').length;

  // Disposition handlers
  const handleRecordManualFollowUp = async (notes: string | undefined, idempotencyKey: string) => {
    if (!selectedItem) return;
    setConflictNotice(null);
    try {
      // Fetch latest policy to ensure fresh numeric ETag
      const { version } = await followUpApi.getCustomerFollowUpPolicy(selectedItem.customerId);
      await followUpApi.recordManualFollowUp(selectedItem.customerId, version, idempotencyKey, notes);

      // Refresh queue after success
      await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
        setConflictNotice(
          'El estado del cliente cambió o ya no es elegible para seguimiento. La lista se ha actualizado.'
        );
      }
      throw err;
    }
  };

  const handleSnooze = async (until: string) => {
    if (!selectedItem) return;
    setConflictNotice(null);
    try {
      const { version } = await followUpApi.getCustomerFollowUpPolicy(selectedItem.customerId);
      await followUpApi.snoozeFollowUp(selectedItem.customerId, until, version);

      // Refresh queue after success
      await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
        setConflictNotice(
          'El estado del cliente cambió o no puede posponerse en este momento. La lista se ha actualizado.'
        );
      }
      throw err;
    }
  };

  const handleDismiss = async (notes: string | undefined, idempotencyKey: string) => {
    if (!selectedItem) return;
    setConflictNotice(null);
    try {
      const { version } = await followUpApi.getCustomerFollowUpPolicy(selectedItem.customerId);
      await followUpApi.dismissFollowUp(selectedItem.customerId, version, idempotencyKey, notes);

      // Refresh queue after success
      await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        await fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined);
        setConflictNotice(
          'El estado del cliente cambió o ya no puede descartarse. La lista se ha actualizado.'
        );
      }
      throw err;
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--space-24)',
        backgroundColor: isMobile ? 'var(--color-canvas-mobile)' : 'var(--color-canvas-desktop)',
        minHeight: '100%'
      }}
    >
      {/* Page Header */}
      {(!isMobile || !selectedItem) && (
        <div
          style={{
            display: 'flex',
            flexDirection: isMobile ? 'column' : 'row',
            justifyContent: 'space-between',
            alignItems: isMobile ? 'flex-start' : 'flex-end',
            gap: 'var(--space-16)'
          }}
        >
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px' }}>
              <span
                style={{
                  fontSize: 'var(--font-size-meta)',
                  fontWeight: 600,
                  color: 'var(--color-brand)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em'
                }}
              >
                Gestión de relaciones
              </span>
              <span style={{ width: '4px', height: '4px', borderRadius: '50%', backgroundColor: 'var(--color-outline)' }} />
              <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                Ciclo de atención
              </span>
            </div>
            <h1
              style={{
                fontSize: 'var(--font-size-display)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                margin: 0,
                letterSpacing: '-0.02em'
              }}
            >
              Seguimientos
            </h1>
            <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: '4px 0 0' }}>
              Ten presente a quién contactar y por qué.
            </p>
          </div>

          {/* Quick Stats Strip */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-12)', flexWrap: 'wrap' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--space-8)',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-lg)',
                backgroundColor: 'var(--color-surface-container)',
                border: '1px solid var(--color-outline-subtle)'
              }}
            >
              <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px', color: 'var(--color-primary)' }}>
                calendar_today
              </span>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                  Contactos para hoy
                </span>
                <span style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 600, color: 'var(--color-text-main)' }}>
                  {dueTodayCount} {dueTodayCount === 1 ? 'programado' : 'programados'}
                </span>
              </div>
            </div>

            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--space-8)',
                padding: 'var(--space-8) var(--space-12)',
                borderRadius: 'var(--radius-lg)',
                backgroundColor: overdueCount > 0 ? 'var(--color-warning-bg)' : 'var(--color-bg-inset)',
                border: overdueCount > 0 ? '1px solid var(--color-warning-border)' : '1px solid var(--color-outline-subtle)'
              }}
            >
              <span
                className="material-symbols-outlined"
                aria-hidden="true"
                style={{
                  fontSize: '20px',
                  color: overdueCount > 0 ? 'var(--color-warning-text)' : 'var(--color-text-muted)'
                }}
              >
                priority_high
              </span>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <span
                  style={{
                    fontSize: 'var(--font-size-meta)',
                    color: overdueCount > 0 ? 'var(--color-warning-text)' : 'var(--color-text-muted)'
                  }}
                >
                  Prioridad alta
                </span>
                <span
                  style={{
                    fontSize: 'var(--font-size-secondary)',
                    fontWeight: 600,
                    color: overdueCount > 0 ? 'var(--color-warning-text)' : 'var(--color-text-main)'
                  }}
                >
                  {overdueCount} con retraso
                </span>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Conflict / Stale Candidate Notification */}
      {conflictNotice && (
        <div
          role="alert"
          style={{
            padding: 'var(--space-12) var(--space-16)',
            backgroundColor: 'var(--color-warning-bg)',
            color: 'var(--color-warning-text)',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--color-warning-border)',
            fontSize: 'var(--font-size-secondary)',
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-8)'
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px' }}>
            warning
          </span>
          <span>{conflictNotice}</span>
        </div>
      )}

      {/* Toolbar: Status Filter Pills + Search */}
      {(!isMobile || !selectedItem) && (
        <div
          style={{
            display: 'flex',
            flexDirection: isMobile ? 'column' : 'row',
            justifyContent: 'space-between',
            alignItems: isMobile ? 'stretch' : 'center',
            gap: 'var(--space-12)'
          }}
        >
          {/* Segmented Filter Pills */}
          <div
            role="group"
            aria-label="Filtros de estado de seguimiento"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 'var(--space-4)',
              backgroundColor: 'var(--color-surface-container)',
              padding: '4px',
              borderRadius: 'var(--radius-lg)'
            }}
          >
            <button
              type="button"
              aria-pressed={activeFilter === 'ALL'}
              onClick={() => setActiveFilter('ALL')}
              className="interactive-target"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '6px 14px',
                borderRadius: 'var(--radius-md)',
                border: activeFilter === 'ALL' ? '1px solid var(--color-outline)' : 'none',
                backgroundColor: activeFilter === 'ALL' ? 'var(--color-surface)' : 'transparent',
                color: activeFilter === 'ALL' ? 'var(--color-primary)' : 'var(--color-text-muted)',
                fontWeight: activeFilter === 'ALL' ? 600 : 500,
                fontSize: 'var(--font-size-secondary)',
                cursor: 'pointer',
                minHeight: '44px'
              }}
            >
              <span>Pendientes</span>
              <span
                style={{
                  padding: '1px 6px',
                  borderRadius: 'var(--radius-full)',
                  backgroundColor: 'var(--color-surface-container-low)',
                  color: 'var(--color-brand)',
                  fontSize: 'var(--font-size-meta)',
                  fontWeight: 600
                }}
              >
                {items.length}
              </span>
            </button>

            <button
              type="button"
              aria-pressed={activeFilter === 'OVERDUE'}
              onClick={() => setActiveFilter('OVERDUE')}
              className="interactive-target"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '6px 14px',
                borderRadius: 'var(--radius-md)',
                border: activeFilter === 'OVERDUE' ? '1px solid var(--color-warning-border)' : 'none',
                backgroundColor: activeFilter === 'OVERDUE' ? 'var(--color-surface)' : 'transparent',
                color: activeFilter === 'OVERDUE' ? 'var(--color-warning-text)' : 'var(--color-text-muted)',
                fontWeight: activeFilter === 'OVERDUE' ? 600 : 500,
                fontSize: 'var(--font-size-secondary)',
                cursor: 'pointer',
                minHeight: '44px'
              }}
            >
              <span>Vencidos</span>
              <span
                style={{
                  padding: '1px 6px',
                  borderRadius: 'var(--radius-full)',
                  backgroundColor: 'var(--color-warning-bg)',
                  color: 'var(--color-warning-text)',
                  border: '1px solid var(--color-warning-border)',
                  fontSize: 'var(--font-size-meta)',
                  fontWeight: 600
                }}
              >
                {overdueCount}
              </span>
            </button>
          </div>

          {/* Search Input Bar */}
          <div style={{ position: 'relative', width: isMobile ? '100%' : '360px' }}>
            <span
              className="material-symbols-outlined"
              aria-hidden="true"
              style={{
                position: 'absolute',
                left: '12px',
                top: '50%',
                transform: 'translateY(-50%)',
                fontSize: '20px',
                color: 'var(--color-text-muted)',
                pointerEvents: 'none'
              }}
            >
              search
            </span>
            <input
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Buscar por nombre o teléfono..."
              style={{
                width: '100%',
                height: '44px',
                paddingLeft: '40px',
                paddingRight: '12px',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-text-main)',
                fontSize: 'var(--font-size-secondary)',
                outline: 'none',
                fontFamily: 'inherit'
              }}
            />
            {isSearchActive && nextCursor && (
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  fontSize: 'var(--font-size-meta)',
                  color: 'var(--color-text-muted)',
                  marginTop: '4px'
                }}
              >
                <span>Buscando en {items.length} cargados</span>
                <button
                  type="button"
                  onClick={handleLoadMore}
                  disabled={loadingMore}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--color-brand)',
                    cursor: 'pointer',
                    padding: 0,
                    textDecoration: 'underline',
                    fontSize: 'var(--font-size-meta)'
                  }}
                >
                  {loadingMore ? 'Cargando...' : 'Cargar más páginas'}
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Main Content Area */}
      {loading ? (
        <div
          role="status"
          aria-live="polite"
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 'var(--space-32)',
            gap: 'var(--space-12)',
            color: 'var(--color-brand)'
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '32px', animation: 'spin 1s linear infinite' }}>
            progress_activity
          </span>
          <span style={{ fontSize: 'var(--font-size-secondary)', fontWeight: 500 }}>
            Cargando seguimientos...
          </span>
        </div>
      ) : error ? (
        <div
          role="alert"
          style={{
            padding: 'var(--space-24)',
            backgroundColor: 'var(--color-surface)',
            border: '1px solid var(--color-error-border)',
            borderRadius: 'var(--radius-lg)',
            textAlign: 'center',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 'var(--space-12)'
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '32px', color: 'var(--color-error-text)' }}>
            error
          </span>
          <div>
            <h2 style={{ fontSize: 'var(--font-size-component-heading)', margin: '0 0 4px', color: 'var(--color-text-main)' }}>
              No se pudieron cargar los seguimientos
            </h2>
            <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: 0 }}>
              {error}
            </p>
          </div>
          <Button
            type="button"
            variant="primary"
            onClick={() => fetchQueue(activeFilter === 'OVERDUE' ? 'OVERDUE' : undefined)}
          >
            Reintentar
          </Button>
        </div>
      ) : filteredItems.length === 0 ? (
        /* Empty State */
        <div
          style={{
            padding: 'var(--space-32)',
            backgroundColor: 'var(--color-surface)',
            border: '1px solid var(--color-outline)',
            borderRadius: 'var(--radius-lg)',
            textAlign: 'center',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 'var(--space-12)'
          }}
        >
          <span
            className="material-symbols-outlined"
            aria-hidden="true"
            style={{ fontSize: '40px', color: 'var(--color-primary)' }}
          >
            check_circle
          </span>
          <div>
            <h2 style={{ fontSize: 'var(--font-size-section-heading)', margin: '0 0 4px', color: 'var(--color-text-main)' }}>
              {searchQuery.trim() ? 'Sin resultados en los seguimientos cargados' : 'No hay seguimientos pendientes'}
            </h2>
            <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', margin: 0 }}>
              {searchQuery.trim()
                ? nextCursor
                  ? `No se encontraron coincidencias para "${searchQuery}" en los ${items.length} seguimientos cargados actualmente.`
                  : `No se encontraron resultados para "${searchQuery}".`
                : '¡Todo al día! No hay clientes que requieran atención en este momento.'}
            </p>
            {searchQuery.trim() && nextCursor && (
              <div style={{ marginTop: 'var(--space-16)', display: 'flex', gap: 'var(--space-12)', justifyContent: 'center' }}>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={handleLoadMore}
                  isLoading={loadingMore}
                >
                  Cargar más seguimientos
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => setSearchQuery('')}
                >
                  Limpiar búsqueda
                </Button>
              </div>
            )}
          </div>
        </div>
      ) : isMobile && selectedItem ? (
        /* Mobile Stacked View: Detail Screen */
        <FollowUpDetail
          item={selectedItem}
          isMobileView={true}
          timeZone={tenantTimeZone}
          onBack={() => setSelectedCustomerId(null)}
          onNavigateToCustomer={onNavigateToCustomer}
          onRecordManualFollowUp={handleRecordManualFollowUp}
          onSnooze={handleSnooze}
          onDismiss={handleDismiss}
          canWrite={canWrite}
        />
      ) : (
        /* Desktop Split View or Mobile List Screen */
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: isMobile ? '1fr' : '5fr 7fr',
            gap: 'var(--space-24)',
            alignItems: 'start'
          }}
        >
          {/* Left Column: Follow-up Queue Cards */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 4px' }}>
              <span
                style={{
                  fontSize: 'var(--font-size-meta)',
                  fontWeight: 600,
                  color: 'var(--color-text-muted)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em'
                }}
              >
                Seguimientos priorizados
              </span>
              <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                Orden por fecha sugerida
              </span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' }}>
              {filteredItems.map((item) => (
                <FollowUpCard
                  key={item.customerId}
                  item={item}
                  isSelected={selectedCustomerId === item.customerId}
                  onSelect={() => setSelectedCustomerId(item.customerId)}
                />
              ))}
            </div>

            {/* Pagination Load More */}
            {nextCursor && (
              <div style={{ textAlign: 'center', marginTop: 'var(--space-8)' }}>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={handleLoadMore}
                  isLoading={loadingMore}
                >
                  {isSearchActive ? 'Cargar más páginas y seguir buscando' : 'Cargar más seguimientos'}
                </Button>
              </div>
            )}
          </div>

          {/* Right Column: Contextual Detail Panel (Desktop only) */}
          {!isMobile && (
            <div>
              {selectedItem ? (
                <FollowUpDetail
                  item={selectedItem}
                  isMobileView={false}
                  timeZone={tenantTimeZone}
                  onNavigateToCustomer={onNavigateToCustomer}
                  onRecordManualFollowUp={handleRecordManualFollowUp}
                  onSnooze={handleSnooze}
                  onDismiss={handleDismiss}
                  canWrite={canWrite}
                />
              ) : (
                <div
                  style={{
                    backgroundColor: 'var(--color-surface)',
                    border: '1px solid var(--color-outline)',
                    borderRadius: 'var(--radius-lg)',
                    padding: 'var(--space-32)',
                    textAlign: 'center',
                    color: 'var(--color-text-muted)'
                  }}
                >
                  Selecciona un cliente de la lista para ver su detalle de seguimiento.
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
