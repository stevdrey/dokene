import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { LoginView } from './LoginView';
import * as SessionContextModule from './SessionContext';

describe('LoginView', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.history.pushState({}, '', '/');
  });

  it('renders default branding, description, and OIDC login button', () => {
    vi.spyOn(SessionContextModule, 'useSession').mockReturnValue({
      status: 'unauthenticated',
      identityId: null,
      csrfToken: null,
      wasExpired: false,
      error: null,
      loginUrl: '/oauth2/authorization/dokene',
      checkSession: vi.fn(),
      logout: vi.fn(),
    });

    render(<LoginView />);

    expect(screen.getByRole('heading', { level: 1, name: 'Dokene' })).toBeInTheDocument();
    expect(
      screen.getByText('Gestión de relaciones y seguimiento a clientes para pequeños negocios.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Iniciar sesión con OIDC/i })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('navigates to loginUrl when clicking the login button', () => {
    vi.spyOn(SessionContextModule, 'useSession').mockReturnValue({
      status: 'unauthenticated',
      identityId: null,
      csrfToken: null,
      wasExpired: false,
      error: null,
      loginUrl: '/oauth2/authorization/dokene',
      checkSession: vi.fn(),
      logout: vi.fn(),
    });

    // Mock window.location.href assignment
    const hrefSetter = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      configurable: true,
      value: {
        ...originalLocation,
        get href() {
          return '';
        },
        set href(val: string) {
          hrefSetter(val);
        },
      },
    });

    render(<LoginView />);

    fireEvent.click(screen.getByRole('button', { name: /Iniciar sesión con OIDC/i }));
    expect(hrefSetter).toHaveBeenCalledWith('/oauth2/authorization/dokene');

    Object.defineProperty(window, 'location', {
      writable: true,
      configurable: true,
      value: originalLocation,
    });
  });

  it('displays session expiration alert when wasExpired is true', () => {
    vi.spyOn(SessionContextModule, 'useSession').mockReturnValue({
      status: 'unauthenticated',
      identityId: null,
      csrfToken: null,
      wasExpired: true,
      error: null,
      loginUrl: '/oauth2/authorization/dokene',
      checkSession: vi.fn(),
      logout: vi.fn(),
    });

    render(<LoginView />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(
      'Tu sesión ha expirado por inactividad. Por favor inicia sesión nuevamente.'
    );
  });

  it('displays recoverable authentication error alert when ?error=login_failed is in URL', () => {
    window.history.pushState({}, '', '/?error=login_failed');

    vi.spyOn(SessionContextModule, 'useSession').mockReturnValue({
      status: 'unauthenticated',
      identityId: null,
      csrfToken: null,
      wasExpired: false,
      error: null,
      loginUrl: '/oauth2/authorization/dokene',
      checkSession: vi.fn(),
      logout: vi.fn(),
    });

    render(<LoginView />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(
      'No se pudo completar el inicio de sesión o el enlace de autenticación ha expirado. Por favor intenta iniciar sesión nuevamente.'
    );
    expect(screen.getByRole('button', { name: /Iniciar sesión con OIDC/i })).toBeInTheDocument();
  });
});
