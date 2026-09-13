import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import { apiClient, UnauthorizedError } from '../../api/apiClient';

export interface SessionData {
  authenticated: boolean;
  identityId: string;
  csrfToken: string;
}

export type SessionStatus = 'loading' | 'unauthenticated' | 'authenticated';

export interface SessionContextValue {
  status: SessionStatus;
  identityId: string | null;
  csrfToken: string | null;
  wasExpired: boolean;
  loginUrl: string;
  checkSession: () => Promise<void>;
  logout: () => Promise<void>;
}

const SessionContext = createContext<SessionContextValue | undefined>(undefined);

export interface SessionProviderProps {
  children: React.ReactNode;
  initialCheck?: boolean;
}

export const SessionProvider: React.FC<SessionProviderProps> = ({
  children,
  initialCheck = true,
}) => {
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [identityId, setIdentityId] = useState<string | null>(null);
  const [csrfToken, setCsrfToken] = useState<string | null>(null);
  const [wasExpired, setWasExpired] = useState(false);

  const handleUnauthorized = useCallback(() => {
    apiClient.setCsrfToken(null);
    apiClient.setCurrentTenantId(null);
    apiClient.cancelAllRequests();
    setIdentityId(null);
    setCsrfToken(null);
    setStatus('unauthenticated');
    setWasExpired(true);
  }, []);

  const checkSession = useCallback(async () => {
    setStatus('loading');
    try {
      const data = await apiClient.get<SessionData>('/api/session');
      if (data && data.authenticated) {
        setIdentityId(data.identityId);
        setCsrfToken(data.csrfToken);
        apiClient.setCsrfToken(data.csrfToken);
        setStatus('authenticated');
        setWasExpired(false);
      } else {
        apiClient.setCsrfToken(null);
        apiClient.setCurrentTenantId(null);
        setIdentityId(null);
        setCsrfToken(null);
        setStatus('unauthenticated');
        setWasExpired(false);
      }
    } catch {
      apiClient.setCsrfToken(null);
      apiClient.setCurrentTenantId(null);
      setIdentityId(null);
      setCsrfToken(null);
      setStatus('unauthenticated');
      setWasExpired(false);
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiClient.post<void>('/logout');
    } catch {
      // Even if network fails, clear client session state
    } finally {
      apiClient.setCsrfToken(null);
      apiClient.setCurrentTenantId(null);
      apiClient.cancelAllRequests();
      setIdentityId(null);
      setCsrfToken(null);
      setWasExpired(false);
      setStatus('unauthenticated');
    }
  }, []);

  useEffect(() => {
    const unsubscribe = apiClient.onUnauthorized(handleUnauthorized);
    return () => unsubscribe();
  }, [handleUnauthorized]);

  useEffect(() => {
    if (initialCheck) {
      checkSession();
    }
  }, [initialCheck, checkSession]);

  const loginUrl = '/oauth2/authorization/dokene';

  return (
    <SessionContext.Provider
      value={{
        status,
        identityId,
        csrfToken,
        wasExpired,
        loginUrl,
        checkSession,
        logout,
      }}
    >
      {children}
    </SessionContext.Provider>
  );
};

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error('useSession must be used within a SessionProvider');
  }
  return context;
}
