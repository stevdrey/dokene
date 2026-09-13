import React, { createContext, useContext, useEffect, useState, useCallback, useRef } from 'react';
import { apiClient, UnauthorizedError, ApiError } from '../../api/apiClient';

export interface SessionData {
  authenticated: boolean;
  identityId: string;
  csrfToken: string;
}

export type SessionStatus = 'loading' | 'unauthenticated' | 'authenticated' | 'error';

export interface SessionContextValue {
  status: SessionStatus;
  identityId: string | null;
  csrfToken: string | null;
  wasExpired: boolean;
  error: string | null;
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
  const [error, setError] = useState<string | null>(null);
  const wasAuthenticatedRef = useRef(false);

  const handleUnauthorized = useCallback(() => {
    const hadSession = wasAuthenticatedRef.current;
    wasAuthenticatedRef.current = false;
    apiClient.setCsrfToken(null);
    apiClient.setCurrentTenantId(null);
    apiClient.invalidateSession();
    setIdentityId(null);
    setCsrfToken(null);
    setError(null);
    setStatus('unauthenticated');
    setWasExpired(hadSession);
  }, []);

  const checkSession = useCallback(async () => {
    setStatus('loading');
    setError(null);
    try {
      const data = await apiClient.get<SessionData>('/api/session');
      if (data && data.authenticated) {
        setIdentityId(data.identityId);
        setCsrfToken(data.csrfToken);
        apiClient.setCsrfToken(data.csrfToken);
        setStatus('authenticated');
        setWasExpired(false);
        setError(null);
        wasAuthenticatedRef.current = true;
      } else {
        const hadSession = wasAuthenticatedRef.current;
        wasAuthenticatedRef.current = false;
        apiClient.setCsrfToken(null);
        apiClient.setCurrentTenantId(null);
        apiClient.invalidateSession();
        setIdentityId(null);
        setCsrfToken(null);
        setStatus('unauthenticated');
        setWasExpired(hadSession);
        setError(null);
      }
    } catch (err: unknown) {
      if (err instanceof UnauthorizedError || (err instanceof ApiError && err.status === 401)) {
        const hadSession = wasAuthenticatedRef.current;
        wasAuthenticatedRef.current = false;
        apiClient.setCsrfToken(null);
        apiClient.setCurrentTenantId(null);
        apiClient.invalidateSession();
        setIdentityId(null);
        setCsrfToken(null);
        setStatus('unauthenticated');
        setWasExpired(hadSession);
        setError(null);
      } else {
        // Recoverable network / 5xx / timeout failure
        setStatus('error');
        setError(err instanceof Error ? err.message : 'Error al verificar la sesión');
      }
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiClient.post<void>('/logout');
    } catch {
      // Even if network fails, clear client session state
    } finally {
      wasAuthenticatedRef.current = false;
      apiClient.setCsrfToken(null);
      apiClient.setCurrentTenantId(null);
      apiClient.invalidateSession();
      setIdentityId(null);
      setCsrfToken(null);
      setWasExpired(false);
      setError(null);
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
        error,
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
