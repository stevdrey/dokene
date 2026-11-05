import React, { createContext, useContext, useEffect, useState, useCallback, useRef } from 'react';
import { apiClient, StaleSessionError, AbortedTenantRequestError } from '../../api/apiClient';
import { useSession } from '../auth/SessionContext';

export type TenantRoleType = 'OWNER' | 'ADMIN' | 'OPERATOR' | 'VIEWER';

export function formatTenantRole(role?: string | null): string {
  switch (role) {
    case 'OWNER':
      return 'Propietario(a)';
    case 'ADMIN':
    case 'TENANT_ADMIN':
      return 'Administrador(a)';
    case 'OPERATOR':
    case 'TENANT_OPERATOR':
      return 'Operador(a)';
    case 'VIEWER':
      return 'Lector(a)';
    default:
      return role || 'Miembro';
  }
}

export function canWriteCustomer(role?: string | null): boolean {
  if (!role) return false;
  return role === 'OWNER' || role === 'ADMIN' || role === 'TENANT_ADMIN' || role === 'OPERATOR' || role === 'TENANT_OPERATOR';
}

export function canArchiveCustomer(role?: string | null): boolean {
  if (!role) return false;
  return role === 'OWNER' || role === 'ADMIN' || role === 'TENANT_ADMIN';
}

const MESSAGE_ACTING_ROLES: readonly string[] = ['OWNER', 'ADMIN', 'OPERATOR', 'TENANT_ADMIN', 'TENANT_OPERATOR'];

/**
 * Whether the role may see the message action controls (submit, approve, cancel, send). A usability aid only; the
 * server stays the gate and a missing or unknown role is read only.
 */
export function canActOnMessages(role?: string | null): boolean {
  return typeof role === 'string' && MESSAGE_ACTING_ROLES.includes(role);
}

/** Every present role holds MESSAGE_READ (ADR 0023 §5); only a missing role hides the read only history. */
export function canReadMessages(role?: string | null): boolean {
  return typeof role === 'string' && role !== '';
}

export const MAX_WORKSPACE_NAME_CODE_POINTS = 160;

/**
 * Counts Unicode code points in a string (handling surrogate pairs / supplementary characters).
 */
export function countCodePoints(str: string): number {
  return Array.from(str).length;
}

const BACKEND_DISPLAY_NAME_WHITESPACE_REGEX = /^[\t\n\v\f\r\u0085\u001c-\u001f\p{Z}]+|[\t\n\v\f\r\u0085\u001c-\u001f\p{Z}]+$/gu;

/**
 * Normalizes a workspace display name matching backend Tenant.normalizeDisplayName whitespace stripping.
 */
export function normalizeWorkspaceName(name: string): string {
  if (!name) return '';
  return name.replace(BACKEND_DISPLAY_NAME_WHITESPACE_REGEX, '');
}

export interface Workspace {
  tenantId: string;
  displayName: string;
  role: TenantRoleType | string;
}

export type TenantStatus = 'loading' | 'no-memberships' | 'ready' | 'error';

export interface TenantContextValue {
  status: TenantStatus;
  workspaces: Workspace[];
  activeWorkspace: Workspace | null;
  error: string | null;
  switchWorkspace: (tenantId: string) => void;
  /**
   * Reloads the workspace list. With `background`, the app keeps its current state while loading, so a change such
   * as accepting an invitation does not blank the shell: a failure leaves the state unchanged and rejects the
   * returned promise for the caller to report, and a refresh already in flight still completes.
   */
  refreshWorkspaces: (options?: { background?: boolean }) => Promise<void>;
  provisionWorkspace: (displayName: string, idempotencyKey?: string) => Promise<Workspace>;
}

const TenantContext = createContext<TenantContextValue | undefined>(undefined);

const LEGACY_STORAGE_KEY = 'dokene_active_tenant_id';

export function getTenantStorageKey(identityId: string | null | undefined): string {
  return identityId ? `dokene_active_tenant_id_${identityId}` : LEGACY_STORAGE_KEY;
}

export const TenantProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { status: sessionStatus, identityId } = useSession();
  const [status, setStatus] = useState<TenantStatus>('loading');
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [activeWorkspace, setActiveWorkspace] = useState<Workspace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refreshGenerationRef = useRef(0);
  const lastAuthenticatedStorageKeyRef = useRef<string | null>(null);

  if (identityId) {
    lastAuthenticatedStorageKeyRef.current = getTenantStorageKey(identityId);
  }

  const storageKey = getTenantStorageKey(identityId);

  const switchWorkspace = useCallback(
    (tenantId: string) => {
      const found = workspaces.find((w) => w.tenantId === tenantId);
      if (found) {
        apiClient.setCurrentTenantId(found.tenantId);
        setActiveWorkspace(found);
        try {
          sessionStorage.setItem(storageKey, found.tenantId);
        } catch {
          // ignore storage error
        }
      }
    },
    [workspaces, storageKey]
  );

  const refreshWorkspaces = useCallback(async (options?: { background?: boolean }) => {
    const background = options?.background === true;
    // A background refresh never supersedes another refresh: it is applied only while no newer foreground refresh
    // started, so it can never discard the result of a foreground refresh that is still loading.
    const currentRefreshGen = background ? refreshGenerationRef.current : ++refreshGenerationRef.current;

    if (background && sessionStatus !== 'authenticated') {
      return;
    }

    if (sessionStatus !== 'authenticated') {
      setStatus('loading');
      setWorkspaces([]);
      setActiveWorkspace(null);
      apiClient.setCurrentTenantId(null);
      return;
    }

    if (!background) {
      setStatus('loading');
      setError(null);
    }

    try {
      const list = await apiClient.get<Workspace[]>('/api/tenants');

      if (currentRefreshGen !== refreshGenerationRef.current) {
        return;
      }

      setWorkspaces(list);

      if (!list || list.length === 0) {
        setStatus('no-memberships');
        setActiveWorkspace(null);
        apiClient.setCurrentTenantId(null);
        try {
          sessionStorage.removeItem(storageKey);
          sessionStorage.removeItem(LEGACY_STORAGE_KEY);
        } catch {
          // ignore
        }
        return;
      }

      // Check persisted workspace or choose first
      let selected: Workspace | undefined;
      try {
        const savedId = sessionStorage.getItem(storageKey) || sessionStorage.getItem(LEGACY_STORAGE_KEY);
        if (savedId) {
          selected = list.find((w) => w.tenantId === savedId);
        }
      } catch {
        // ignore
      }

      if (!selected) {
        selected = list[0];
      }

      setActiveWorkspace(selected);
      apiClient.setCurrentTenantId(selected.tenantId);
      try {
        sessionStorage.setItem(storageKey, selected.tenantId);
      } catch {
        // ignore
      }
      setStatus('ready');
    } catch (err) {
      if (currentRefreshGen !== refreshGenerationRef.current) {
        return;
      }
      if (err instanceof StaleSessionError || err instanceof AbortedTenantRequestError) {
        // Discard late/cancelled response silently
        return;
      }
      if (background) {
        throw err;
      }
      setError(err instanceof Error ? err.message : 'Error al cargar espacios de trabajo');
      setStatus('error');
    }
  }, [sessionStatus, storageKey]);

  const provisionWorkspace = useCallback(
    async (displayName: string, idempotencyKey?: string): Promise<Workspace> => {
      const key = idempotencyKey || crypto.randomUUID();
      const newWs = await apiClient.post<Workspace>(
        '/api/tenants',
        { displayName, idempotencyKey: key },
        { headers: { 'Idempotency-Key': key } }
      );

      setWorkspaces((prev) => {
        const updated = [...prev.filter((w) => w.tenantId !== newWs.tenantId), newWs];
        return updated;
      });

      apiClient.setCurrentTenantId(newWs.tenantId);
      setActiveWorkspace(newWs);
      try {
        sessionStorage.setItem(storageKey, newWs.tenantId);
      } catch {
        // ignore
      }
      setStatus('ready');
      return newWs;
    },
    [storageKey]
  );

  useEffect(() => {
    refreshGenerationRef.current++;
    if (sessionStatus === 'authenticated') {
      refreshWorkspaces();
    } else if (sessionStatus === 'unauthenticated') {
      // Only wipe persisted storage when definitively unauthenticated
      setStatus('loading');
      setWorkspaces([]);
      setActiveWorkspace(null);
      apiClient.setCurrentTenantId(null);
      try {
        if (lastAuthenticatedStorageKeyRef.current) {
          sessionStorage.removeItem(lastAuthenticatedStorageKeyRef.current);
          lastAuthenticatedStorageKeyRef.current = null;
        }
        sessionStorage.removeItem(storageKey);
        sessionStorage.removeItem(LEGACY_STORAGE_KEY);
      } catch {
        // ignore
      }
    } else {
      // 'loading' or 'error': keep sessionStorage untouched so selection is retained once session is resolved
      setStatus('loading');
      setWorkspaces([]);
      setActiveWorkspace(null);
      apiClient.setCurrentTenantId(null);
    }
  }, [sessionStatus, identityId, storageKey, refreshWorkspaces]);

  return (
    <TenantContext.Provider
      value={{
        status,
        workspaces,
        activeWorkspace,
        error,
        switchWorkspace,
        refreshWorkspaces,
        provisionWorkspace,
      }}
    >
      {children}
    </TenantContext.Provider>
  );
};

export function useTenant(): TenantContextValue {
  const context = useContext(TenantContext);
  if (!context) {
    throw new Error('useTenant must be used within a TenantProvider');
  }
  return context;
}
