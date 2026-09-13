import React from 'react';
import { useSession } from './SessionContext';

export const LoginView: React.FC = () => {
  const { wasExpired, loginUrl } = useSession();

  const handleLogin = () => {
    window.location.href = loginUrl;
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'var(--color-canvas-mobile)',
        padding: '16px',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: '420px',
          backgroundColor: 'var(--color-surface)',
          borderRadius: 'var(--radius-xl)',
          padding: '32px 24px',
          boxShadow: '0 4px 20px rgba(0, 0, 0, 0.06)',
          border: '1px solid var(--color-outline)',
          textAlign: 'center',
        }}
      >
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: '48px',
            height: '48px',
            borderRadius: 'var(--radius-lg)',
            backgroundColor: 'var(--color-surface-container-low)',
            color: 'var(--color-brand)',
            marginBottom: '16px',
          }}
        >
          <span className="material-symbols-outlined" style={{ fontSize: '30px' }}>
            spa
          </span>
        </div>

        <h1
          style={{
            margin: '0 0 8px',
            fontSize: 'var(--font-size-page-heading)',
            lineHeight: 'var(--line-height-page-heading)',
            fontWeight: 600,
            color: 'var(--color-brand)',
            letterSpacing: '-0.015em',
          }}
        >
          Dokene
        </h1>

        <p
          style={{
            margin: '0 0 24px',
            fontSize: 'var(--font-size-secondary)',
            lineHeight: 'var(--line-height-secondary)',
            color: 'var(--color-text-muted)',
          }}
        >
          Gestión de relaciones y seguimiento a clientes para pequeños negocios.
        </p>

        {wasExpired && (
          <div
            role="alert"
            style={{
              marginBottom: '20px',
              padding: '12px',
              borderRadius: 'var(--radius-md)',
              backgroundColor: 'var(--color-warning-bg)',
              color: 'var(--color-warning-text)',
              border: '1px solid var(--color-warning-border)',
              fontSize: 'var(--font-size-secondary)',
              textAlign: 'left',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
            }}
          >
            <span className="material-symbols-outlined" style={{ fontSize: '20px' }}>
              warning
            </span>
            <span>Tu sesión ha expirado por inactividad. Por favor inicia sesión nuevamente.</span>
          </div>
        )}

        <button
          type="button"
          onClick={handleLogin}
          style={{
            width: '100%',
            minHeight: '44px',
            padding: '12px 16px',
            borderRadius: 'var(--radius-md)',
            border: 'none',
            backgroundColor: 'var(--color-primary)',
            color: 'var(--color-on-primary)',
            fontSize: 'var(--font-size-component-heading)',
            fontWeight: 600,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            transition: 'background-color 0.15s ease',
          }}
          onMouseOver={(e) => (e.currentTarget.style.backgroundColor = 'var(--color-primary-hover)')}
          onMouseOut={(e) => (e.currentTarget.style.backgroundColor = 'var(--color-primary)')}
        >
          <span className="material-symbols-outlined" style={{ fontSize: '20px' }}>
            login
          </span>
          <span>Iniciar sesión con OIDC</span>
        </button>
      </div>
    </div>
  );
};
