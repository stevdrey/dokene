import React from 'react';
import { SessionProvider, useSession } from './features/auth/SessionContext';
import { TenantProvider, useTenant } from './features/tenants/TenantContext';
import { LoginView } from './features/auth/LoginView';
import { NoMembershipsView } from './features/tenants/NoMembershipsView';
import { AppShell } from './components/shell/AppShell';

const MainAppContent: React.FC = () => {
  const { status: sessionStatus } = useSession();
  const { status: tenantStatus, error: tenantError, refreshWorkspaces } = useTenant();

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
        <span className="material-symbols-outlined" style={{ animation: 'spin 1s linear infinite' }}>
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
        <span className="material-symbols-outlined" style={{ animation: 'spin 1s linear infinite' }}>
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
          <span className="material-symbols-outlined" style={{ fontSize: '32px', color: 'var(--color-error-text)', marginBottom: '8px' }}>
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

  return <AppShell />;
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
