import React, { useState, useRef, useEffect } from 'react';
import { useTenant, Workspace } from '../../features/tenants/TenantContext';
import { ApiError } from '../../api/apiClient';

export interface WorkspaceSelectorProps {
  compact?: boolean;
}

export const WorkspaceSelector: React.FC<WorkspaceSelectorProps> = ({ compact = false }) => {
  const { workspaces, activeWorkspace, switchWorkspace, provisionWorkspace } = useTenant();
  const [isOpen, setIsOpen] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [newWorkspaceName, setNewWorkspaceName] = useState('');
  const [provisionError, setProvisionError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [operationKey, setOperationKey] = useState<string>(() => crypto.randomUUID());

  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleStartCreate = () => {
    setOperationKey(crypto.randomUUID());
    setProvisionError(null);
    setIsCreating(true);
  };

  const handleCancelCreate = () => {
    setIsCreating(false);
    setProvisionError(null);
    setNewWorkspaceName('');
    setOperationKey(crypto.randomUUID());
  };

  // Close on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setIsCreating(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Escape key handler
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        setIsOpen(false);
        setIsCreating(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  // Focus input on creating
  useEffect(() => {
    if (isCreating) {
      inputRef.current?.focus();
    }
  }, [isCreating]);

  const handleSelect = (workspace: Workspace) => {
    switchWorkspace(workspace.tenantId);
    setIsOpen(false);
    triggerRef.current?.focus();
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newWorkspaceName.trim()) return;
    setIsSubmitting(true);
    setProvisionError(null);
    try {
      await provisionWorkspace(newWorkspaceName.trim(), operationKey);
      setNewWorkspaceName('');
      setIsCreating(false);
      setIsOpen(false);
      setOperationKey(crypto.randomUUID());
      triggerRef.current?.focus();
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setProvisionError('Ya existe un espacio de trabajo con este nombre.');
        } else if (err.status === 400) {
          setProvisionError('El nombre del espacio de trabajo no es válido.');
        } else if (err.status === 403) {
          setProvisionError('No tienes permisos para crear espacios de trabajo.');
        } else {
          setProvisionError('No se pudo crear el espacio de trabajo. Inténtalo nuevamente.');
        }
      } else {
        setProvisionError('Error al crear el espacio de trabajo.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const formatRole = (role: string) => {
    switch (role) {
      case 'TENANT_ADMIN':
        return 'Administrador';
      case 'TENANT_OPERATOR':
        return 'Operador';
      default:
        return role;
    }
  };

  return (
    <div ref={containerRef} style={{ position: 'relative', width: '100%' }}>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-controls="workspace-listbox"
        aria-label="Seleccionar espacio de trabajo"
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '8px 12px',
          borderRadius: 'var(--radius-md)',
          border: '1px solid var(--color-outline)',
          backgroundColor: 'var(--color-surface)',
          cursor: 'pointer',
          textAlign: 'left',
          fontSize: 'var(--font-size-secondary)',
          color: 'var(--color-text-main)',
          gap: '8px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', overflow: 'hidden', flex: 1 }}>
          <span className="material-symbols-outlined" style={{ fontSize: '18px', color: 'var(--color-text-muted)' }}>
            store
          </span>
          <span
            style={{
              fontWeight: 500,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              maxWidth: compact ? '160px' : '150px',
            }}
          >
            {activeWorkspace ? activeWorkspace.displayName : 'Seleccionar espacio'}
          </span>
        </div>
        <span className="material-symbols-outlined" style={{ fontSize: '16px', color: 'var(--color-text-muted)' }}>
          unfold_more
        </span>
      </button>

      {isOpen && (
        <div
          id="workspace-dropdown-menu"
          style={{
            position: 'absolute',
            top: 'calc(100% + 4px)',
            left: 0,
            right: 0,
            backgroundColor: 'var(--color-surface)',
            border: '1px solid var(--color-outline)',
            borderRadius: 'var(--radius-md)',
            boxShadow: '0 4px 12px rgba(0, 0, 0, 0.08)',
            zIndex: 100,
            padding: '4px',
            maxHeight: '280px',
            overflowY: 'auto',
          }}
        >
          <div
            id="workspace-listbox"
            role="listbox"
            aria-label="Espacios de trabajo disponibles"
            style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}
          >
            {workspaces.map((ws) => {
              const isSelected = activeWorkspace?.tenantId === ws.tenantId;
              return (
                <div
                  key={ws.tenantId}
                  role="option"
                  aria-selected={isSelected}
                  tabIndex={0}
                  onClick={() => handleSelect(ws)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      handleSelect(ws);
                    }
                  }}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '8px 10px',
                    borderRadius: 'var(--radius-sm)',
                    cursor: 'pointer',
                    backgroundColor: isSelected ? 'var(--color-surface-selected)' : 'transparent',
                    color: isSelected ? 'var(--color-brand)' : 'var(--color-text-main)',
                    fontWeight: isSelected ? 600 : 400,
                    fontSize: 'var(--font-size-secondary)',
                    outline: 'none',
                    minHeight: '44px',
                  }}
                >
                  <div style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {ws.displayName}
                    </span>
                    <span style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-muted)' }}>
                      {formatRole(ws.role)}
                    </span>
                  </div>
                  {isSelected && (
                    <span className="material-symbols-outlined" style={{ fontSize: '18px', color: 'var(--color-primary)' }}>
                      check
                    </span>
                  )}
                </div>
              );
            })}
          </div>

          <div
            role="separator"
            aria-orientation="horizontal"
            style={{ borderTop: '1px solid var(--color-outline-subtle)', margin: '4px 0' }}
          />

          {isCreating ? (
            <form onSubmit={handleCreate} style={{ padding: '8px' }}>
              <input
                ref={inputRef}
                type="text"
                value={newWorkspaceName}
                onChange={(e) => setNewWorkspaceName(e.target.value)}
                placeholder="Nombre del negocio"
                disabled={isSubmitting}
                aria-label="Nombre del nuevo negocio"
                style={{
                  width: '100%',
                  padding: '8px 10px',
                  fontSize: 'var(--font-size-secondary)',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--color-outline)',
                  marginBottom: '8px',
                  minHeight: '44px',
                }}
              />
              {provisionError && (
                <div role="alert" style={{ color: 'var(--color-error-text)', fontSize: '12px', marginBottom: '8px' }}>
                  {provisionError}
                </div>
              )}
              <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
                <button
                  type="button"
                  onClick={handleCancelCreate}
                  disabled={isSubmitting}
                  style={{
                    padding: '8px 14px',
                    fontSize: '13px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-outline)',
                    background: 'transparent',
                    cursor: 'pointer',
                    minHeight: '44px',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  Cancelar
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting || !newWorkspaceName.trim()}
                  style={{
                    padding: '8px 16px',
                    fontSize: '13px',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-primary)',
                    color: 'var(--color-on-primary)',
                    fontWeight: 500,
                    cursor: 'pointer',
                    minHeight: '44px',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  {isSubmitting ? 'Creando...' : 'Crear'}
                </button>
              </div>
            </form>
          ) : (
            <button
              type="button"
              onClick={handleStartCreate}
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '8px 10px',
                borderRadius: 'var(--radius-sm)',
                border: 'none',
                backgroundColor: 'transparent',
                color: 'var(--color-primary)',
                fontSize: 'var(--font-size-secondary)',
                fontWeight: 500,
                cursor: 'pointer',
                textAlign: 'left',
                minHeight: '44px',
              }}
            >
              <span className="material-symbols-outlined" style={{ fontSize: '18px' }}>
                add_business
              </span>
              <span>Nuevo espacio de trabajo</span>
            </button>
          )}
        </div>
      )}
    </div>
  );
};
