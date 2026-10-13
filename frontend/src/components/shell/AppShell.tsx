import React, { useState } from 'react';
import { useSession } from '../../features/auth/SessionContext';
import { useTenant, formatTenantRole } from '../../features/tenants/TenantContext';
import { WorkspaceSelector } from './WorkspaceSelector';
import './AppShell.css';

export type ActiveTab = 'seguimientos' | 'clientes' | 'configuracion' | 'mas';

export interface AppShellProps {
  children?: React.ReactNode | ((activeTab: ActiveTab) => React.ReactNode);
  activeTab?: ActiveTab;
  onTabChange?: (tab: ActiveTab) => void;
}

export const AppShell: React.FC<AppShellProps> = ({
  children,
  activeTab: controlledActiveTab,
  onTabChange,
}) => {
  const { logout, identityId } = useSession();
  const { activeWorkspace } = useTenant();
  const [internalActiveTab, setInternalActiveTab] = useState<ActiveTab>('seguimientos');
  const activeTab = controlledActiveTab ?? internalActiveTab;
  const setActiveTab = (tab: ActiveTab) => {
    setInternalActiveTab(tab);
    onTabChange?.(tab);
  };
  const [logoutError, setLogoutError] = useState<string | null>(null);

  const handleLogout = async () => {
    setLogoutError(null);
    try {
      await logout();
    } catch (err) {
      setLogoutError(err instanceof Error ? err.message : 'Error al cerrar sesión');
    }
  };

  const initials = identityId ? identityId.substring(0, 2).toUpperCase() : 'OP';
  const roleLabel = formatTenantRole(activeWorkspace?.role);
  const resolvedChildren = typeof children === 'function' ? children(activeTab) : children;
  const hasRenderableChildren = React.Children.toArray(resolvedChildren).length > 0;

  return (
    <div className="dokene-app-layout">

      {/* DESKTOP SIDEBAR */}
      <aside className="desktop-sidebar" aria-label="Barra lateral de navegación">
        <div>
          <div
            style={{
              height: 'var(--header-height)',
              padding: '0 16px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              borderBottom: '1px solid var(--color-outline)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="material-symbols-outlined" aria-hidden="true" style={{ color: 'var(--color-brand)', fontSize: '24px' }}>
                spa
              </span>
              <span
                style={{
                  fontSize: 'var(--font-size-section-heading)',
                  fontWeight: 600,
                  color: 'var(--color-brand)',
                  letterSpacing: '-0.02em',
                }}
              >
                Dokene
              </span>
            </div>
          </div>

          <div style={{ padding: '12px 16px' }}>
            <WorkspaceSelector />
          </div>

          <div style={{ padding: '8px 16px' }}>
            <span
              style={{
                display: 'block',
                fontSize: '11px',
                fontWeight: 600,
                color: 'var(--color-text-muted)',
                letterSpacing: '0.05em',
                marginBottom: '8px',
                paddingLeft: '8px',
              }}
            >
              RELACIONES
            </span>
            <nav aria-label="Navegación principal" style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <button
                type="button"
                className={`nav-item ${activeTab === 'seguimientos' ? 'active' : ''}`}
                aria-current={activeTab === 'seguimientos' ? 'page' : undefined}
                onClick={() => setActiveTab('seguimientos')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                    assignment_turned_in
                  </span>
                  <span>Seguimientos</span>
                </div>
              </button>

              <button
                type="button"
                className={`nav-item ${activeTab === 'clientes' ? 'active' : ''}`}
                aria-current={activeTab === 'clientes' ? 'page' : undefined}
                onClick={() => setActiveTab('clientes')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                    group
                  </span>
                  <span>Clientes</span>
                </div>
              </button>
            </nav>
          </div>

          <div style={{ padding: '8px 16px' }}>
            <div style={{ borderTop: '1px solid var(--color-outline-subtle)', margin: '8px 0 12px' }} />
            <span
              style={{
                display: 'block',
                fontSize: '11px',
                fontWeight: 600,
                color: 'var(--color-text-muted)',
                letterSpacing: '0.05em',
                marginBottom: '8px',
                paddingLeft: '8px',
              }}
            >
              AJUSTES
            </span>
            <nav aria-label="Ajustes" style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <button
                type="button"
                className={`nav-item ${activeTab === 'configuracion' ? 'active' : ''}`}
                aria-current={activeTab === 'configuracion' ? 'page' : undefined}
                onClick={() => setActiveTab('configuracion')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                    settings
                  </span>
                  <span>Configuración</span>
                </div>
              </button>
            </nav>
          </div>
        </div>

        <div style={{ padding: '16px', borderTop: '1px solid var(--color-outline)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '12px' }}>
            <div
              style={{
                width: '32px',
                height: '32px',
                borderRadius: 'var(--radius-full)',
                backgroundColor: 'var(--color-brand)',
                color: 'var(--color-on-primary)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '12px',
                fontWeight: 600,
              }}
            >
              {initials}
            </div>
            <div style={{ overflow: 'hidden' }}>
              <div
                style={{
                  fontSize: 'var(--font-size-secondary)',
                  fontWeight: 500,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  maxWidth: '140px',
                }}
              >
                {activeWorkspace ? activeWorkspace.displayName : 'Operador'}
              </div>
              <div style={{ fontSize: '11px', color: 'var(--color-text-muted)' }}>{roleLabel}</div>
            </div>
          </div>

          <button
            type="button"
            onClick={handleLogout}
            className="nav-item"
            style={{
              color: 'var(--color-error-text)',
              backgroundColor: 'transparent',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                logout
              </span>
              <span>Cerrar sesión</span>
            </div>
          </button>
        </div>
      </aside>

      {/* DESKTOP HEADER */}
      <header className="desktop-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: 'var(--font-size-secondary)' }}>
          <span style={{ color: 'var(--color-text-muted)' }}>Dokene</span>
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '14px', color: 'var(--color-text-muted)' }}>
            chevron_right
          </span>
          <span style={{ fontWeight: 600, color: 'var(--color-text-main)' }}>
            {activeTab === 'seguimientos' && 'Seguimientos'}
            {activeTab === 'clientes' && 'Clientes'}
            {activeTab === 'configuracion' && 'Configuración'}
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div
            style={{
              width: '32px',
              height: '32px',
              borderRadius: 'var(--radius-full)',
              backgroundColor: 'var(--color-brand)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--color-on-primary)',
            }}
          >
            <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
              person
            </span>
          </div>
        </div>
      </header>

      {/* MOBILE HEADER */}
      <header className="mobile-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <div
            style={{
              width: '32px',
              height: '32px',
              borderRadius: 'var(--radius-md)',
              backgroundColor: 'var(--color-brand)',
              color: 'var(--color-on-primary)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: 700,
              fontSize: '18px',
            }}
          >
            D
          </div>
          <div style={{ width: '180px' }}>
            <WorkspaceSelector compact />
          </div>
        </div>

        <button
          type="button"
          onClick={handleLogout}
          aria-label="Cerrar sesión"
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--color-text-muted)',
            cursor: 'pointer',
            padding: '8px',
            minWidth: '44px',
            minHeight: '44px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px' }}>
            logout
          </span>
        </button>
      </header>

      {/* MAIN CONTENT AREA */}
      <main className="desktop-main">
        {logoutError && (
          <div
            role="alert"
            style={{
              marginBottom: '16px',
              padding: '12px 16px',
              borderRadius: 'var(--radius-md)',
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              border: '1px solid var(--color-error-border)',
              fontSize: 'var(--font-size-secondary)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '8px',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px' }}>
                error
              </span>
              <span>{logoutError}</span>
            </div>
            <button
              type="button"
              onClick={() => setLogoutError(null)}
              aria-label="Cerrar aviso de error"
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                color: 'inherit',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                minWidth: '44px',
                minHeight: '44px',
                padding: '10px',
                borderRadius: 'var(--radius-sm)',
              }}
            >
              <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '18px' }}>
                close
              </span>
            </button>
          </div>
        )}
        {hasRenderableChildren ? (
          resolvedChildren
        ) : (
          <div>
            <h1
              style={{
                fontSize: 'var(--font-size-page-heading)',
                fontWeight: 600,
                color: 'var(--color-text-main)',
                margin: '0 0 8px',
              }}
            >
              {activeTab === 'seguimientos' && 'Seguimientos'}
              {activeTab === 'clientes' && 'Clientes'}
              {activeTab === 'configuracion' && 'Configuración'}
              {activeTab === 'mas' && 'Más opciones'}
            </h1>
            <p style={{ color: 'var(--color-text-muted)', fontSize: 'var(--font-size-secondary)' }}>
              Espacio activo: <strong>{activeWorkspace?.displayName}</strong>
            </p>
            {activeTab === 'mas' && (
              <div style={{ marginTop: '24px' }}>
                <button
                  type="button"
                  aria-label="Abrir Configuración"
                  onClick={() => setActiveTab('configuracion')}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '12px',
                    padding: '12px 16px',
                    minHeight: '44px',
                    backgroundColor: 'var(--color-surface)',
                    border: '1px solid var(--color-outline)',
                    borderRadius: 'var(--radius-md)',
                    color: 'var(--color-text-main)',
                    fontSize: 'var(--font-size-body)',
                    fontWeight: 500,
                    cursor: 'pointer',
                    width: '100%',
                    maxWidth: '400px',
                    textAlign: 'left',
                  }}
                >
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '20px', color: 'var(--color-brand)' }}>
                    settings
                  </span>
                  <span>Configuración</span>
                  <span className="material-symbols-outlined" aria-hidden="true" style={{ marginLeft: 'auto', fontSize: '18px', color: 'var(--color-text-muted)' }}>
                    chevron_right
                  </span>
                </button>
              </div>
            )}
          </div>
        )}
      </main>

      {/* MOBILE BOTTOM NAVIGATION */}
      <nav className="mobile-bottom-nav" aria-label="Navegación móvil inferior">
        <button
          type="button"
          className={`mobile-nav-btn ${activeTab === 'seguimientos' ? 'active' : ''}`}
          aria-current={activeTab === 'seguimientos' ? 'page' : undefined}
          onClick={() => setActiveTab('seguimientos')}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '22px' }}>
            assignment_turned_in
          </span>
          <span style={{ marginTop: '2px' }}>Seguimientos</span>
        </button>

        <button
          type="button"
          className={`mobile-nav-btn ${activeTab === 'clientes' ? 'active' : ''}`}
          aria-current={activeTab === 'clientes' ? 'page' : undefined}
          onClick={() => setActiveTab('clientes')}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '22px' }}>
            group
          </span>
          <span style={{ marginTop: '2px' }}>Clientes</span>
        </button>

        <button
          type="button"
          aria-label="Más opciones"
          className={`mobile-nav-btn ${activeTab === 'mas' ? 'active' : ''}`}
          aria-current={activeTab === 'mas' ? 'page' : undefined}
          onClick={() => setActiveTab('mas')}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '22px' }}>
            more_horiz
          </span>
          <span style={{ marginTop: '2px' }}>Más</span>
        </button>
      </nav>
    </div>
  );
};
