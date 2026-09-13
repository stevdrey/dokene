export interface SessionInfo {
  authenticated: boolean;
  identityId: string;
  csrfToken: string;
}

export interface Workspace {
  tenantId: string;
  displayName: string;
  role: 'OWNER' | 'ADMIN' | 'OPERATOR' | 'VIEWER' | string;
}

export interface ApiErrorPayload {
  status: number;
  message: string;
  timestamp?: string;
  path?: string;
  error?: string;
}
