import React, { useState, useEffect } from 'react';
import { SessionProvider, useSession } from './features/auth/SessionContext';
import { TenantProvider, useTenant } from './features/tenants/TenantContext';
import { LoginView } from './features/auth/LoginView';
import { NoMembershipsView } from './features/tenants/NoMembershipsView';
import { AppShell, ActiveTab } from './components/shell/AppShell';
import { CustomerList } from './features/customers/components/CustomerList';
import { CustomerProfile } from './features/customers/components/CustomerProfile';
import { FollowUpWorkbench } from './features/followups/components/FollowUpWorkbench';

const MainAppContent: React.FC = () => {
  const { status: sessionStatus, error: sessionError, checkSession } = useSession();
  const { status: tenantStatus, error: tenantError, refreshWorkspaces, activeWorkspace } = useTenant();
  const [activeTab, setActiveTab] = useState<ActiveTab>('seguimientos');
  const [selectedCustomer, setSelectedCustomer] = useState<{ tenantId: string; id: string } | null>(null);
  const selectedCustomerId =
    selectedCustomer && activeWorkspace && selectedCustomer.tenantId === activeWorkspace.tenantId
      ? selectedCustomer.id
      : null;
  const setSelectedCustomerId = (id: string | null) => {
    setSelectedCustomer(id && activeWorkspace?.tenantId ? { tenantId: activeWorkspace.tenantId, id } : null);
  };

  if (sessionStatus === 'loading') {
    return (
      <div
        role="status"
        aria-live="polite"
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--color-bg-inset)',
          color: 'var(--color-brand)',
          fontSize: 'var(--font-size-component-heading)',
          gap: '12px',
        }}
      >
        <span className="material-symbols-outlined" aria-hidden="true" style={{ animation: 'spin 1s linear infinite' }}>
          progress_activity
        </span>
        <span>Cargando Dokene...</span>
        <style>{`
          @keyframes spin {
            from { transform: rotate(0deg); }
            to { transform: rotate(360deg); }
          }
        `}</style>
      </div>
    );
  }

  if (sessionStatus === 'error') {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--color-bg-inset)',
          padding: '16px',
        }}
      >
        <div
          role="alert"
          style={{
            maxWidth: '400px',
            width: '100%',
            backgroundColor: 'var(--color-surface)',
            padding: '24px',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-error-border)',
            textAlign: 'center',
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '32px', color: 'var(--color-error-text)', marginBottom: '8px' }}>
            wifi_off
          </span>
          <h2 style={{ fontSize: 'var(--font-size-section-heading)', margin: '0 0 8px' }}>Error de conexión</h2>
          <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', marginBottom: '16px' }}>
            {sessionError || 'No se pudo comprobar el estado de la sesión. Revisa tu conexión.'}
          </p>
          <button
            type="button"
            onClick={() => checkSession()}
            style={{
              padding: '8px 16px',
              backgroundColor: 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              border: 'none',
              borderRadius: 'var(--radius-md)',
              fontWeight: 500,
              cursor: 'pointer',
              minHeight: '44px',
            }}
          >
            Reintentar
          </button>
        </div>
      </div>
    );
  }

  if (sessionStatus === 'unauthenticated') {
    return <LoginView />;
  }

  if (tenantStatus === 'loading') {
    return (
      <div
        role="status"
        aria-live="polite"
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--color-bg-inset)',
          color: 'var(--color-brand)',
          fontSize: 'var(--font-size-component-heading)',
          gap: '12px',
        }}
      >
        <span className="material-symbols-outlined" aria-hidden="true" style={{ animation: 'spin 1s linear infinite' }}>
          progress_activity
        </span>
        <span>Cargando espacios de trabajo...</span>
        <style>{`
          @keyframes spin {
            from { transform: rotate(0deg); }
            to { transform: rotate(360deg); }
          }
        `}</style>
      </div>
    );
  }

  if (tenantStatus === 'no-memberships') {
    return <NoMembershipsView />;
  }

  if (tenantStatus === 'error') {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--color-bg-inset)',
          padding: '16px',
        }}
      >
        <div
          role="alert"
          style={{
            maxWidth: '400px',
            width: '100%',
            backgroundColor: 'var(--color-surface)',
            padding: '24px',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-error-border)',
            textAlign: 'center',
          }}
        >
          <span className="material-symbols-outlined" aria-hidden="true" style={{ fontSize: '32px', color: 'var(--color-error-text)', marginBottom: '8px' }}>
            error
          </span>
          <h2 style={{ fontSize: 'var(--font-size-section-heading)', margin: '0 0 8px' }}>Error de conexión</h2>
          <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)', marginBottom: '16px' }}>
            {tenantError || 'No se pudieron obtener los espacios de trabajo.'}
          </p>
          <button
            type="button"
            onClick={() => refreshWorkspaces()}
            style={{
              padding: '8px 16px',
              backgroundColor: 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              border: 'none',
              borderRadius: 'var(--radius-md)',
              fontWeight: 500,
              cursor: 'pointer',
              minHeight: '44px',
            }}
          >
            Reintentar
          </button>
        </div>
      </div>
    );
  }

  return (
    <AppShell
      activeTab={activeTab}
      onTabChange={(tab) => {
        setActiveTab(tab);
        if (tab !== 'clientes') {
          setSelectedCustomerId(null);
        }
      }}
    >
      {activeTab === 'clientes' && (
        selectedCustomerId ? (
          <CustomerProfile
            customerId={selectedCustomerId}
            onBack={() => setSelectedCustomerId(null)}
          />
        ) : (
          <CustomerList
            key={activeWorkspace?.tenantId}
            onSelectCustomer={(customerId) => setSelectedCustomerId(customerId)}
          />
        )
      )}

      {activeTab === 'seguimientos' && (
        <FollowUpWorkbench
          key={activeWorkspace?.tenantId}
          onNavigateToCustomer={(customerId) => {
            setActiveTab('clientes');
            setSelectedCustomerId(customerId);
          }}
        />
      )}

      {activeTab === 'configuracion' && (
        <section
          style={{
            backgroundColor: 'var(--color-surface)',
            borderRadius: 'var(--radius-lg)',
            padding: 'var(--space-32)',
            border: '1px solid var(--color-outline-subtle)',
            textAlign: 'center',
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
};

export function App() {
  return (
    <SessionProvider>
      <TenantProvider>
        <MainAppContent />
      </TenantProvider>
    </SessionProvider>
  );
}

export default App;
