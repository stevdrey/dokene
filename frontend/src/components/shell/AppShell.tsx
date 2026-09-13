import React, { useState } from 'react';
import { useSession } from '../../features/auth/SessionContext';
import { useTenant } from '../../features/tenants/TenantContext';
import { WorkspaceSelector } from './WorkspaceSelector';

export type ActiveTab = 'seguimientos' | 'clientes' | 'configuracion' | 'mas';

export interface AppShellProps {
  children?: React.ReactNode;
}

export const AppShell: React.FC<AppShellProps> = ({ children }) => {
  const { logout, identityId } = useSession();
  const { activeWorkspace } = useTenant();
  const [activeTab, setActiveTab] = useState<ActiveTab>('seguimientos');

  const initials = identityId ? identityId.substring(0, 2).toUpperCase() : 'OP';
  const roleLabel = activeWorkspace?.role === 'TENANT_ADMIN' ? 'Administradora' : 'Operador';

  return (
    <div className="dokene-app-layout">
      <style>{`
        .dokene-app-layout {
          min-height: 100vh;
          display: flex;
          background-color: var(--color-canvas-desktop);
        }

        .desktop-sidebar {
          display: flex;
          flex-direction: column;
          justify-content: space-between;
          width: var(--sidebar-width);
          position: fixed;
          top: 0;
          bottom: 0;
          left: 0;
          background-color: var(--color-surface);
          border-right: 1px solid var(--color-outline);
          z-index: 40;
        }

        .desktop-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          position: fixed;
          top: 0;
          left: var(--sidebar-width);
          right: 0;
          height: var(--header-height);
          background-color: var(--color-surface);
          border-bottom: 1px solid var(--color-outline);
          padding: 0 var(--space-24);
          z-index: 30;
        }

        .desktop-main {
          flex: 1;
          margin-left: var(--sidebar-width);
          margin-top: var(--header-height);
          padding: var(--space-24);
          max-width: 1440px;
          min-height: calc(100vh - var(--header-height));
        }

        .mobile-header {
          display: none;
        }
        .mobile-bottom-nav {
          display: none;
        }

        .nav-item {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 8px 12px;
          border-radius: var(--radius-md);
          color: var(--color-text-muted);
          text-decoration: none;
          font-size: var(--font-size-secondary);
          font-weight: 500;
          cursor: pointer;
          border: none;
          background: transparent;
          width: 100%;
          text-align: left;
          transition: background-color 0.15s ease, color 0.15s ease;
          min-height: 44px;
        }
        .nav-item:hover {
          background-color: var(--color-surface-container-low);
          color: var(--color-text-main);
        }
        .nav-item.active {
          background-color: var(--color-surface-selected);
          color: var(--color-brand);
          font-weight: 600;
        }

        @media (max-width: 767px) {
          .dokene-app-layout {
            flex-direction: column;
            background-color: var(--color-canvas-mobile);
          }
          .desktop-sidebar {
            display: none;
          }
          .desktop-header {
            display: none;
          }
          .desktop-main {
            margin-left: 0;
            margin-top: var(--header-height);
            margin-bottom: var(--bottom-nav-height);
            padding: var(--space-16);
            width: 100%;
          }

          .mobile-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            height: var(--header-height);
            background-color: rgba(231, 255, 246, 0.9);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--color-outline);
            padding: 0 var(--space-16);
            z-index: 50;
          }

          .mobile-bottom-nav {
            display: flex;
            align-items: center;
            justify-content: space-around;
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            height: var(--bottom-nav-height);
            background-color: rgba(231, 255, 246, 0.95);
            backdrop-filter: blur(12px);
            border-top: 1px solid var(--color-outline);
            padding-bottom: env(safe-area-inset-bottom, 0px);
            z-index: 50;
          }

          .mobile-nav-btn {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            background: transparent;
            border: none;
            color: var(--color-text-muted);
            font-size: var(--font-size-meta);
            cursor: pointer;
            min-width: 64px;
            min-height: 44px;
            text-decoration: none;
          }
          .mobile-nav-btn.active {
            color: var(--color-brand);
            font-weight: 600;
          }
        }
      `}</style>

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
              <span className="material-symbols-outlined" style={{ color: 'var(--color-brand)', fontSize: '24px' }}>
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
                  <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
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
                  <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
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
                  <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
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
            onClick={() => logout()}
            className="nav-item"
            style={{
              color: 'var(--color-error-text)',
              backgroundColor: 'transparent',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
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
          <span className="material-symbols-outlined" style={{ fontSize: '14px', color: 'var(--color-text-muted)' }}>
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
            <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
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
          onClick={() => logout()}
          aria-label="Cerrar sesión"
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--color-text-muted)',
            cursor: 'pointer',
            padding: '8px',
            display: 'flex',
            alignItems: 'center',
          }}
        >
          <span className="material-symbols-outlined" style={{ fontSize: '20px' }}>
            logout
          </span>
        </button>
      </header>

      {/* MAIN CONTENT AREA */}
      <main className="desktop-main">
        {children ? (
          children
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
          <span className="material-symbols-outlined" style={{ fontSize: '22px' }}>
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
          <span className="material-symbols-outlined" style={{ fontSize: '22px' }}>
            group
          </span>
          <span style={{ marginTop: '2px' }}>Clientes</span>
        </button>

        <button
          type="button"
          className={`mobile-nav-btn ${activeTab === 'mas' ? 'active' : ''}`}
          aria-current={activeTab === 'mas' ? 'page' : undefined}
          onClick={() => setActiveTab('mas')}
        >
          <span className="material-symbols-outlined" style={{ fontSize: '22px' }}>
            more_horiz
          </span>
          <span style={{ marginTop: '2px' }}>Más</span>
        </button>
      </nav>
    </div>
  );
};
