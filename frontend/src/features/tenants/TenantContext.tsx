import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import { apiClient } from '../../api/apiClient';
import { useSession } from '../auth/SessionContext';

export interface Workspace {
  tenantId: string;
  displayName: string;
  role: string;
}

export type TenantStatus = 'loading' | 'no-memberships' | 'ready' | 'error';

export interface TenantContextValue {
  status: TenantStatus;
  workspaces: Workspace[];
  activeWorkspace: Workspace | null;
  error: string | null;
  switchWorkspace: (tenantId: string) => void;
  refreshWorkspaces: () => Promise<void>;
  provisionWorkspace: (displayName: string, idempotencyKey?: string) => Promise<Workspace>;
}

const TenantContext = createContext<TenantContextValue | undefined>(undefined);

const STORAGE_KEY = 'dokene_active_tenant_id';

export const TenantProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { status: sessionStatus } = useSession();
  const [status, setStatus] = useState<TenantStatus>('loading');
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [activeWorkspace, setActiveWorkspace] = useState<Workspace | null>(null);
  const [error, setError] = useState<string | null>(null);

  const switchWorkspace = useCallback((tenantId: string) => {
    setWorkspaces((currentWorkspaces) => {
      const found = currentWorkspaces.find((w) => w.tenantId === tenantId);
      if (found) {
        apiClient.setCurrentTenantId(found.tenantId);
        setActiveWorkspace(found);
        try {
          sessionStorage.setItem(STORAGE_KEY, found.tenantId);
        } catch {
          // ignore storage error
        }
      }
      return currentWorkspaces;
    });
  }, []);

  const refreshWorkspaces = useCallback(async () => {
    if (sessionStatus !== 'authenticated') {
      setStatus('loading');
      setWorkspaces([]);
      setActiveWorkspace(null);
      apiClient.setCurrentTenantId(null);
      return;
    }

    setStatus('loading');
    setError(null);

    try {
      const list = await apiClient.get<Workspace[]>('/api/tenants');
      setWorkspaces(list);

      if (!list || list.length === 0) {
        setStatus('no-memberships');
        setActiveWorkspace(null);
        apiClient.setCurrentTenantId(null);
        try {
          sessionStorage.removeItem(STORAGE_KEY);
        } catch {
          // ignore
        }
        return;
      }

      // Check persisted workspace or choose first
      let selected: Workspace | undefined;
      try {
        const savedId = sessionStorage.getItem(STORAGE_KEY);
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
        sessionStorage.setItem(STORAGE_KEY, selected.tenantId);
      } catch {
        // ignore
      }
      setStatus('ready');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Error al cargar espacios de trabajo');
      setStatus('error');
    }
  }, [sessionStatus]);

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
        sessionStorage.setItem(STORAGE_KEY, newWs.tenantId);
      } catch {
        // ignore
      }
      setStatus('ready');
      return newWs;
    },
    []
  );

  useEffect(() => {
    if (sessionStatus === 'authenticated') {
      refreshWorkspaces();
    } else {
      setStatus('loading');
      setWorkspaces([]);
      setActiveWorkspace(null);
      apiClient.setCurrentTenantId(null);
      try {
        sessionStorage.removeItem(STORAGE_KEY);
      } catch {
        // ignore
      }
    }
  }, [sessionStatus, refreshWorkspaces]);

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
