import React, { useState } from 'react';
import { Workspace } from '@/shared/types';
import {
  SpaIcon,
  StoreIcon,
  GroupIcon,
  AssignmentIcon,
  SettingsIcon
} from '@/shared/components/Icons';

interface AppShellProps {
  workspaces: Workspace[];
  activeWorkspace: Workspace | null;
  onSelectWorkspace: (workspace: Workspace) => void;
  activeNav: 'customers' | 'followups' | 'settings';
  onNavigate: (nav: 'customers' | 'followups' | 'settings') => void;
  children: React.ReactNode;
}

export function AppShell({
  workspaces,
  activeWorkspace,
  onSelectWorkspace,
  activeNav,
  onNavigate,
  children
}: AppShellProps) {
  const [isWorkspaceMenuOpen, setIsWorkspaceMenuOpen] = useState(false);

  return (
    <div style={{ display: 'flex', minHeight: '100vh', backgroundColor: 'var(--color-canvas-desktop)' }}>
      {/* Desktop Sidebar (232px) */}
      <aside
        style={{
          width: 'var(--sidebar-width)',
          borderRight: '1px solid var(--color-outline-subtle)',
          backgroundColor: 'var(--color-surface)',
          display: 'flex',
          flexDirection: 'column',
          position: 'fixed',
          top: 0,
          bottom: 0,
          left: 0,
          zIndex: 40
        }}
        className="desktop-sidebar"
      >
        {/* Brand header */}
        <div
          style={{
            height: 'var(--header-height)',
            padding: '0 var(--space-16)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid var(--color-outline-subtle)'
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
            <span style={{ color: 'var(--color-brand)' }}>
              <SpaIcon size={24} />
            </span>
            <span
              style={{
                fontSize: 'var(--font-size-section-heading)',
                fontWeight: 700,
                color: 'var(--color-brand)',
                letterSpacing: '-0.02em'
              }}
            >
              Dokene
            </span>
          </div>
        </div>

        {/* Workspace selector */}
        <div style={{ padding: 'var(--space-12) var(--space-16)', position: 'relative' }}>
          <button
            type="button"
            onClick={() => setIsWorkspaceMenuOpen(!isWorkspaceMenuOpen)}
            aria-expanded={isWorkspaceMenuOpen}
            aria-label="Seleccionar espacio de trabajo"
            style={{
              width: '100%',
              minHeight: '44px',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              backgroundColor: 'var(--color-surface-inset)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 'var(--space-8)',
              fontSize: 'var(--font-size-dense)',
              fontWeight: 500,
              color: 'var(--color-text-main)'
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', overflow: 'hidden' }}>
              <span style={{ color: 'var(--color-text-supporting)' }}>
                <StoreIcon size={18} />
              </span>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {activeWorkspace ? activeWorkspace.displayName : 'Espacio de trabajo'}
              </span>
            </div>
          </button>

          {isWorkspaceMenuOpen && (
            <div
              style={{
                position: 'absolute',
                top: 'calc(100% - 4px)',
                left: 'var(--space-16)',
                right: 'var(--space-16)',
                backgroundColor: 'var(--color-surface)',
                borderRadius: 'var(--radius-md)',
                border: '1px solid var(--color-outline-subtle)',
                boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.1)',
                zIndex: 50,
                padding: 'var(--space-4)',
                maxHeight: '200px',
                overflowY: 'auto'
              }}
            >
              {workspaces.map((w) => (
                <button
                  key={w.tenantId}
                  type="button"
                  onClick={() => {
                    onSelectWorkspace(w);
                    setIsWorkspaceMenuOpen(false);
                  }}
                  style={{
                    width: '100%',
                    padding: 'var(--space-8) var(--space-12)',
                    textAlign: 'left',
                    borderRadius: 'var(--radius-sm)',
                    backgroundColor:
                      activeWorkspace?.tenantId === w.tenantId ? 'var(--color-surface-selected)' : 'transparent',
                    color:
                      activeWorkspace?.tenantId === w.tenantId ? 'var(--color-brand)' : 'var(--color-text-main)',
                    fontSize: 'var(--font-size-dense)',
                    fontWeight: activeWorkspace?.tenantId === w.tenantId ? 600 : 400
                  }}
                >
                  {w.displayName}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Navigation links */}
        <div style={{ padding: 'var(--space-8) var(--space-16)', flex: 1, overflowY: 'auto' }}>
          <div
            style={{
              fontSize: 'var(--font-size-meta)',
              textTransform: 'uppercase',
              letterSpacing: '0.05em',
              color: 'var(--color-text-supporting)',
              marginBottom: 'var(--space-8)',
              fontWeight: 600
            }}
          >
            Relaciones
          </div>

          <nav style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
            <button
              type="button"
              onClick={() => onNavigate('followups')}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: 'var(--space-8) var(--space-12)',
                minHeight: '44px',
                borderRadius: 'var(--radius-md)',
                backgroundColor:
                  activeNav === 'followups' ? 'var(--color-surface-selected)' : 'transparent',
                color: activeNav === 'followups' ? 'var(--color-brand)' : 'var(--color-text-supporting)',
                fontWeight: activeNav === 'followups' ? 600 : 500,
                fontSize: 'var(--font-size-dense)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                <AssignmentIcon size={18} />
                <span>Seguimientos</span>
              </div>
            </button>

            <button
              type="button"
              onClick={() => onNavigate('customers')}
              aria-current={activeNav === 'customers' ? 'page' : undefined}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: 'var(--space-8) var(--space-12)',
                minHeight: '44px',
                borderRadius: 'var(--radius-md)',
                backgroundColor:
                  activeNav === 'customers' ? 'var(--color-surface-selected)' : 'transparent',
                color: activeNav === 'customers' ? 'var(--color-brand)' : 'var(--color-text-supporting)',
                fontWeight: activeNav === 'customers' ? 600 : 500,
                fontSize: 'var(--font-size-dense)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
                <GroupIcon size={18} />
                <span>Clientes</span>
              </div>
            </button>
          </nav>

          <div
            style={{
              fontSize: 'var(--font-size-meta)',
              textTransform: 'uppercase',
              letterSpacing: '0.05em',
              color: 'var(--color-text-supporting)',
              marginTop: 'var(--space-24)',
              marginBottom: 'var(--space-8)',
              fontWeight: 600
            }}
          >
            Ajustes
          </div>

          <nav style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
            <button
              type="button"
              onClick={() => onNavigate('settings')}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--space-8)',
                padding: 'var(--space-8) var(--space-12)',
                minHeight: '44px',
                borderRadius: 'var(--radius-md)',
                backgroundColor:
                  activeNav === 'settings' ? 'var(--color-surface-selected)' : 'transparent',
                color: activeNav === 'settings' ? 'var(--color-brand)' : 'var(--color-text-supporting)',
                fontWeight: activeNav === 'settings' ? 600 : 500,
                fontSize: 'var(--font-size-dense)'
              }}
            >
              <SettingsIcon size={18} />
              <span>Configuración</span>
            </button>
          </nav>
        </div>

        {/* User profile section */}
        <div
          style={{
            padding: 'var(--space-16)',
            borderTop: '1px solid var(--color-outline-subtle)',
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-12)'
          }}
        >
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: 'var(--radius-full)',
              backgroundColor: 'var(--color-brand)',
              color: 'var(--color-on-primary)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: 600,
              fontSize: 'var(--font-size-meta)'
            }}
          >
            OP
          </div>
          <div style={{ overflow: 'hidden' }}>
            <div style={{ fontSize: 'var(--font-size-dense)', fontWeight: 600, color: 'var(--color-text-main)', textOverflow: 'ellipsis', whiteSpace: 'nowrap', overflow: 'hidden' }}>
              Operador
            </div>
            <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)' }}>
              {activeWorkspace?.role || 'Miembro'}
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }} className="app-main-content">
        {/* Top Header */}
        <header
          style={{
            height: 'var(--header-height)',
            borderBottom: '1px solid var(--color-outline-subtle)',
            backgroundColor: 'var(--color-surface)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 var(--space-24)',
            position: 'sticky',
            top: 0,
            zIndex: 30
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)', fontSize: 'var(--font-size-dense)' }}>
            <span style={{ color: 'var(--color-text-supporting)' }}>Dokene</span>
            <span style={{ color: 'var(--color-outline)' }}>/</span>
            <span style={{ fontWeight: 600, color: 'var(--color-text-main)' }}>
              {activeNav === 'customers' ? 'Clientes' : activeNav === 'followups' ? 'Seguimientos' : 'Configuración'}
            </span>
          </div>

          <div style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-brand)', fontWeight: 500 }}>
            {activeWorkspace?.displayName}
          </div>
        </header>

        {/* Page Container */}
        <main
          style={{
            flex: 1,
            padding: 'var(--space-24)',
            maxWidth: 'var(--max-content-width)',
            width: '100%',
            margin: '0 auto'
          }}
        >
          {children}
        </main>
      </div>

      {/* Responsive layout styles */}
      <style>{`
        @media (min-width: 769px) {
          .app-main-content {
            margin-left: var(--sidebar-width);
          }
        }
        @media (max-width: 768px) {
          .desktop-sidebar {
            display: none !important;
          }
          .app-main-content {
            margin-left: 0;
            padding-bottom: 60px;
          }
        }
      `}</style>
    </div>
  );
}
