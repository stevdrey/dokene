import React, { useCallback, useEffect, useState } from 'react';
import { apiClient, ApiError, StaleSessionError } from '../../api/apiClient';
import { formatTenantRole, useTenant } from './TenantContext';

/** A pending invitation of the signed-in identity (`GET /api/tenants/invitations`). */
export interface PendingInvitation {
  tenantId: string;
  displayName: string;
  role: string;
  invitedAt: string;
}

type LoadState = 'loading' | 'ready' | 'error';

function formatInvitedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleDateString('es', { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * Lists the identity's pending workspace invitations and lets it accept one. An invitation grants no access until it
 * is accepted (issue 317), so the workspace name is shown with its id: two workspaces can share a name. Renders
 * nothing when there is no invitation.
 */
export const PendingInvitations: React.FC = () => {
  const { refreshWorkspaces } = useTenant();
  const [invitations, setInvitations] = useState<PendingInvitation[]>([]);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [acceptingId, setAcceptingId] = useState<string | null>(null);
  const [acceptError, setAcceptError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadState('loading');
    try {
      const list = await apiClient.get<PendingInvitation[]>('/api/tenants/invitations');
      setInvitations(Array.isArray(list) ? list : []);
      setLoadState('ready');
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      setLoadState('error');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const accept = async (tenantId: string) => {
    setAcceptingId(tenantId);
    setAcceptError(null);
    try {
      await apiClient.post(`/api/tenants/${encodeURIComponent(tenantId)}/invitation/acceptance`);
      setInvitations((current) => current.filter((invitation) => invitation.tenantId !== tenantId));
      await refreshWorkspaces();
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.status === 404) {
        setAcceptError('Esta invitación ya no está disponible.');
        setInvitations((current) => current.filter((invitation) => invitation.tenantId !== tenantId));
      } else {
        setAcceptError('No se pudo aceptar la invitación. Inténtalo nuevamente.');
      }
    } finally {
      setAcceptingId(null);
    }
  };

  if (loadState === 'loading') return null;

  if (loadState === 'error') {
    return (
      <div
        role="status"
        style={{
          marginBottom: '16px',
          fontSize: 'var(--font-size-secondary)',
          color: 'var(--color-text-muted)',
          textAlign: 'left',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '8px',
        }}
      >
        <span>No se pudieron cargar tus invitaciones.</span>
        <button
          type="button"
          onClick={() => void load()}
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--color-brand)',
            cursor: 'pointer',
            textDecoration: 'underline',
            minHeight: '44px',
            fontSize: 'var(--font-size-secondary)',
          }}
        >
          Reintentar
        </button>
      </div>
    );
  }

  if (invitations.length === 0 && !acceptError) return null;

  return (
    <section
      aria-labelledby="pending-invitations-heading"
      style={{
        marginBottom: '24px',
        padding: '16px',
        borderRadius: 'var(--radius-md)',
        border: '1px solid var(--color-outline)',
        backgroundColor: 'var(--color-surface-container)',
        textAlign: 'left',
      }}
    >
      <h2
        id="pending-invitations-heading"
        style={{
          margin: '0 0 4px',
          fontSize: 'var(--font-size-component-heading)',
          color: 'var(--color-text-main)',
          fontWeight: 600,
        }}
      >
        Invitaciones pendientes
      </h2>
      <p
        style={{
          margin: '0 0 12px',
          fontSize: 'var(--font-size-secondary)',
          lineHeight: 'var(--line-height-secondary)',
          color: 'var(--color-text-muted)',
        }}
      >
        No tendrás acceso a un espacio hasta aceptar su invitación. Acepta solo invitaciones de negocios que reconoces.
      </p>

      {acceptError && (
        <div
          role="alert"
          style={{
            marginBottom: '12px',
            padding: '10px 12px',
            borderRadius: 'var(--radius-md)',
            backgroundColor: 'var(--color-error-bg)',
            color: 'var(--color-error-text)',
            border: '1px solid var(--color-error-border)',
            fontSize: 'var(--font-size-secondary)',
          }}
        >
          {acceptError}
        </div>
      )}

      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {invitations.map((invitation) => {
          const invitedAt = formatInvitedAt(invitation.invitedAt);
          const isAccepting = acceptingId === invitation.tenantId;
          return (
            <li
              key={invitation.tenantId}
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: '8px',
                padding: '12px',
                borderRadius: 'var(--radius-md)',
                backgroundColor: 'var(--color-surface)',
                border: '1px solid var(--color-outline)',
              }}
            >
              <div style={{ minWidth: 0, flex: '1 1 200px' }}>
                <div
                  style={{
                    fontWeight: 600,
                    color: 'var(--color-text-main)',
                    fontSize: 'var(--font-size-body)',
                    overflowWrap: 'anywhere',
                  }}
                >
                  {invitation.displayName}
                </div>
                <div style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)' }}>
                  {formatTenantRole(invitation.role)}
                  {invitedAt ? ` · invitado el ${invitedAt}` : ''}
                </div>
                <div
                  style={{
                    fontSize: 'var(--font-size-secondary)',
                    color: 'var(--color-text-muted)',
                    fontFamily: 'monospace',
                    overflowWrap: 'anywhere',
                  }}
                >
                  ID: {invitation.tenantId}
                </div>
              </div>
              <button
                type="button"
                onClick={() => void accept(invitation.tenantId)}
                disabled={acceptingId !== null}
                aria-label={`Aceptar invitación a ${invitation.displayName}`}
                style={{
                  minHeight: '44px',
                  padding: '10px 16px',
                  borderRadius: 'var(--radius-md)',
                  border: 'none',
                  backgroundColor: 'var(--color-primary)',
                  color: 'var(--color-on-primary)',
                  fontWeight: 600,
                  cursor: acceptingId !== null ? 'not-allowed' : 'pointer',
                }}
              >
                {isAccepting ? 'Aceptando...' : 'Aceptar'}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
};
