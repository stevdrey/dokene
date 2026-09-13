import { useState, useEffect } from 'react';
import '@/styles/base.css';
import { Workspace } from '@/shared/types';
import { httpClient } from '@/shared/api/httpClient';
import { AppShell } from '@/features/shell/AppShell';
import { CustomerList } from '@/features/customers/components/CustomerList';
import { CustomerProfile } from '@/features/customers/components/CustomerProfile';

export function App() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [activeWorkspace, setActiveWorkspace] = useState<Workspace | null>(null);
  const [activeNav, setActiveNav] = useState<'customers' | 'followups' | 'settings'>('customers');
  const [selectedCustomerId, setSelectedCustomerId] = useState<string | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);

  // Initialize session and workspaces
  useEffect(() => {
    async function init() {
      try {
        // Fetch session
        const sessionRes = await fetch('/api/session', { credentials: 'same-origin' });
        if (sessionRes.ok) {
          const sessionData = await sessionRes.json();
          if (sessionData.csrfToken) {
            httpClient.setCsrfToken(sessionData.csrfToken);
          }
        }

        // Fetch workspaces
        const tenantsRes = await fetch('/api/tenants', { credentials: 'same-origin' });
        if (tenantsRes.ok) {
          const list: Workspace[] = await tenantsRes.json();
          if (list && list.length > 0) {
            setWorkspaces(list);
            setActiveWorkspace(list[0]);
            httpClient.setTenantId(list[0].tenantId);
            setIsInitializing(false);
            return;
          }
        }
      } catch {
        // Standalone or mock fallback
      }

      // Default fallback workspace for standalone dev / test
      const defaultWorkspace: Workspace = {
        tenantId: '00000000-0000-0000-0000-000000000001',
        displayName: 'Café & Taller Artesano',
        role: 'OPERATOR'
      };
      setWorkspaces([defaultWorkspace]);
      setActiveWorkspace(defaultWorkspace);
      httpClient.setTenantId(defaultWorkspace.tenantId);
      setIsInitializing(false);
    }

    init();
  }, []);

  const handleSelectWorkspace = (workspace: Workspace) => {
    // Clear tenant-scoped state on workspace change to prevent cross-tenant leakage
    setSelectedCustomerId(null);
    setActiveWorkspace(workspace);
    httpClient.setTenantId(workspace.tenantId);
  };

  if (isInitializing) {
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh',
          backgroundColor: 'var(--color-canvas-desktop)',
          color: 'var(--color-text-supporting)',
          fontSize: 'var(--font-size-dense)'
        }}
      >
        Iniciando Dokene...
      </div>
    );
  }

  return (
    <AppShell
      workspaces={workspaces}
      activeWorkspace={activeWorkspace}
      onSelectWorkspace={handleSelectWorkspace}
      activeNav={activeNav}
      onNavigate={(nav) => {
        setActiveNav(nav);
        if (nav !== 'customers') {
          setSelectedCustomerId(null);
        }
      }}
    >
      {activeNav === 'customers' && (
        selectedCustomerId ? (
          <CustomerProfile
            customerId={selectedCustomerId}
            onBack={() => setSelectedCustomerId(null)}
          />
        ) : (
          <CustomerList
            onSelectCustomer={(customerId) => setSelectedCustomerId(customerId)}
          />
        )
      )}

      {activeNav === 'followups' && (
        <section
          style={{
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            padding: 'var(--space-32)',
            border: '1px solid var(--color-outline-subtle)',
            textAlign: 'center'
          }}
        >
          <h2 style={{ fontSize: 'var(--font-size-page-heading)', fontWeight: 600, color: 'var(--color-text-main)' }}>
            Cola de Seguimientos
          </h2>
          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginTop: 'var(--space-8)' }}>
            Este módulo se integrará en el Issue #39. Selecciona la pestaña "Clientes" para gestionar la cartera de clientes.
          </p>
        </section>
      )}

      {activeNav === 'settings' && (
        <section
          style={{
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            padding: 'var(--space-32)',
            border: '1px solid var(--color-outline-subtle)',
            textAlign: 'center'
          }}
        >
          <h2 style={{ fontSize: 'var(--font-size-page-heading)', fontWeight: 600, color: 'var(--color-text-main)' }}>
            Configuración del Espacio de Trabajo
          </h2>
          <p style={{ fontSize: 'var(--font-size-dense)', color: 'var(--color-text-supporting)', marginTop: 'var(--space-8)' }}>
            Ajustes generales del negocio y cadencias de contacto.
          </p>
        </section>
      )}
    </AppShell>
  );
}
export default App;
