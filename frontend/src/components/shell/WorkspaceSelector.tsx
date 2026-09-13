import React, { useState, useRef, useEffect } from 'react';
import { useTenant, Workspace } from '../../features/tenants/TenantContext';

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

  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

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
      await provisionWorkspace(newWorkspaceName.trim());
      setNewWorkspaceName('');
      setIsCreating(false);
      setIsOpen(false);
      triggerRef.current?.focus();
    } catch (err) {
      setProvisionError(err instanceof Error ? err.message : 'Error al crear el espacio');
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
          role="listbox"
          aria-label="Espacios de trabajo disponibles"
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

          <div style={{ borderTop: '1px solid var(--color-outline-subtle)', margin: '4px 0' }} />

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
                  padding: '6px 8px',
                  fontSize: 'var(--font-size-secondary)',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--color-outline)',
                  marginBottom: '6px',
                }}
              />
              {provisionError && (
                <div style={{ color: 'var(--color-error-text)', fontSize: '12px', marginBottom: '6px' }}>
                  {provisionError}
                </div>
              )}
              <div style={{ display: 'flex', gap: '6px', justifyContent: 'flex-end' }}>
                <button
                  type="button"
                  onClick={() => setIsCreating(false)}
                  disabled={isSubmitting}
                  style={{
                    padding: '4px 8px',
                    fontSize: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-outline)',
                    background: 'transparent',
                    cursor: 'pointer',
                    minHeight: '32px',
                  }}
                >
                  Cancelar
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting || !newWorkspaceName.trim()}
                  style={{
                    padding: '4px 10px',
                    fontSize: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-primary)',
                    color: 'var(--color-on-primary)',
                    fontWeight: 500,
                    cursor: 'pointer',
                    minHeight: '32px',
                  }}
                >
                  {isSubmitting ? 'Creando...' : 'Crear'}
                </button>
              </div>
            </form>
          ) : (
            <button
              type="button"
              onClick={() => setIsCreating(true)}
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
