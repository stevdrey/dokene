// ABOUTME: Tests the app's settings tab (issue 140): it shows the active workspace's WhatsApp integration panel, and a
// ABOUTME: short note instead of a blank page when no workspace is selected. The panel and the workbench are stubbed.
import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { useSession } from './features/auth/SessionContext';
import { useTenant } from './features/tenants/TenantContext';

vi.mock('./features/auth/SessionContext', () => ({
  SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useSession: vi.fn()
}));

vi.mock('./features/tenants/TenantContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./features/tenants/TenantContext')>();
  return {
    ...actual,
    TenantProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    useTenant: vi.fn()
  };
});

vi.mock('./features/followups/components/FollowUpWorkbench', () => ({
  FollowUpWorkbench: () => <p>Seguimientos</p>
}));

vi.mock('./features/integrations/components/IntegrationStatusPanel', () => ({
  IntegrationStatusPanel: ({ workspace }: { workspace: { tenantId: string; role: string | null } }) => (
    <p>
      Integración {workspace.tenantId} {workspace.role}
    </p>
  )
}));

const workspace = { tenantId: 't-1', displayName: 'Café & Taller Artesano', role: 'ADMIN' };

function setWorkspace(activeWorkspace: typeof workspace | null) {
  vi.mocked(useTenant).mockReturnValue({
    status: 'ready',
    workspaces: activeWorkspace ? [activeWorkspace] : [],
    activeWorkspace,
    error: null,
    switchWorkspace: vi.fn(),
    refreshWorkspaces: vi.fn(),
    provisionWorkspace: vi.fn()
  });
}

function openSettings() {
  render(<App />);
  fireEvent.click(screen.getAllByRole('button', { name: /Configuración/ })[0]);
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.mocked(useSession).mockReturnValue({
    status: 'authenticated',
    identityId: 'd45b7f64-9917-4a62-b3fc-2c963f66afa6',
    csrfToken: 'csrf-123',
    wasSessionEnded: false,
    error: null,
    loginUrl: '/login',
    checkSession: vi.fn(),
    logout: vi.fn()
  });
});

describe('App settings tab', () => {
  it('shows the integration panel for the active workspace', () => {
    setWorkspace(workspace);
    openSettings();
    expect(screen.getByText('Integración t-1 ADMIN')).toBeInTheDocument();
  });

  it('explains that a workspace is needed instead of rendering a blank page', () => {
    setWorkspace(null);
    openSettings();
    expect(screen.getByText('Selecciona un espacio de trabajo para ver su configuración.')).toBeInTheDocument();
    expect(screen.queryByText(/^Integración/)).toBeNull();
  });
});
