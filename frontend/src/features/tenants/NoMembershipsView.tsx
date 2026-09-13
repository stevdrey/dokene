import React, { useState } from 'react';
import { useTenant } from './TenantContext';
import { useSession } from '../auth/SessionContext';

export const NoMembershipsView: React.FC = () => {
  const { provisionWorkspace } = useTenant();
  const { logout } = useSession();
  const [workspaceName, setWorkspaceName] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!workspaceName.trim()) return;
    setIsSubmitting(true);
    setError(null);
    try {
      await provisionWorkspace(workspaceName.trim());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Error al crear el espacio');
    } finally {
      setIsSubmitting(false);
    }
  };

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
        style={{
          width: '100%',
          maxWidth: '460px',
          backgroundColor: 'var(--color-surface)',
          borderRadius: 'var(--radius-xl)',
          padding: '32px 24px',
          border: '1px solid var(--color-outline)',
          boxShadow: '0 4px 20px rgba(0, 0, 0, 0.05)',
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
            borderRadius: 'var(--radius-full)',
            backgroundColor: 'var(--color-surface-container)',
            color: 'var(--color-brand)',
            marginBottom: '16px',
          }}
        >
          <span className="material-symbols-outlined" style={{ fontSize: '28px' }}>
            domain_disabled
          </span>
        </div>

        <h1
          style={{
            margin: '0 0 8px',
            fontSize: 'var(--font-size-page-heading)',
            color: 'var(--color-text-main)',
            fontWeight: 600,
          }}
        >
          Sin espacios de trabajo
        </h1>

        <p
          style={{
            margin: '0 0 24px',
            fontSize: 'var(--font-size-secondary)',
            lineHeight: 'var(--line-height-secondary)',
            color: 'var(--color-text-muted)',
          }}
        >
          Tu cuenta no tiene membresías activas. Puedes crear un nuevo espacio de trabajo para tu negocio o solicitar acceso a un administrador.
        </p>

        {error && (
          <div
            role="alert"
            style={{
              marginBottom: '16px',
              padding: '10px 12px',
              borderRadius: 'var(--radius-md)',
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              border: '1px solid var(--color-error-border)',
              fontSize: 'var(--font-size-secondary)',
              textAlign: 'left',
            }}
          >
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ textAlign: 'left', marginBottom: '24px' }}>
          <label
            htmlFor="new-workspace-name"
            style={{
              display: 'block',
              fontSize: 'var(--font-size-secondary)',
              fontWeight: 500,
              color: 'var(--color-text-main)',
              marginBottom: '6px',
            }}
          >
            Nombre del negocio
          </label>
          <input
            id="new-workspace-name"
            type="text"
            value={workspaceName}
            onChange={(e) => setWorkspaceName(e.target.value)}
            placeholder="Ej. Café & Taller Artesano"
            disabled={isSubmitting}
            required
            style={{
              width: '100%',
              minHeight: '44px',
              padding: '10px 12px',
              fontSize: 'var(--font-size-body)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              marginBottom: '16px',
            }}
          />

          <button
            type="submit"
            disabled={isSubmitting || !workspaceName.trim()}
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
              cursor: isSubmitting ? 'not-allowed' : 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <span className="material-symbols-outlined" style={{ fontSize: '20px' }}>
              add_business
            </span>
            <span>{isSubmitting ? 'Creando espacio...' : 'Crear espacio de trabajo'}</span>
          </button>
        </form>

        <button
          type="button"
          onClick={() => logout()}
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--color-text-muted)',
            fontSize: 'var(--font-size-secondary)',
            cursor: 'pointer',
            textDecoration: 'underline',
            minHeight: '44px',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          Cerrar sesión
        </button>
      </div>
    </div>
  );
};
