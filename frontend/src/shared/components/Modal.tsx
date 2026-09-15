import React, { useEffect, useRef } from 'react';
import { CloseIcon } from './Icons';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  maxWidth?: string;
  closeDisabled?: boolean;
}

export function Modal({
  isOpen,
  onClose,
  title,
  children,
  maxWidth = '560px',
  closeDisabled = false
}: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!isOpen) return;

    openerRef.current = document.activeElement as HTMLElement | null;

    const focusTimer = requestAnimationFrame(() => {
      if (!dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
      );
      if (focusable.length > 0) {
        focusable[0].focus();
      } else {
        dialogRef.current.focus();
      }
    });

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !closeDisabled) {
        onClose();
        return;
      }

      if (e.key === 'Tab' && dialogRef.current) {
        const focusable = Array.from(
          dialogRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
          )
        );

        if (focusable.length === 0) {
          e.preventDefault();
          return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (e.shiftKey) {
          if (document.activeElement === first || !dialogRef.current.contains(document.activeElement)) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last || !dialogRef.current.contains(document.activeElement)) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      cancelAnimationFrame(focusTimer);
      window.removeEventListener('keydown', handleKeyDown);
      openerRef.current?.focus();
    };
  }, [isOpen, onClose, closeDisabled]);

  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-title"
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(11, 31, 26, 0.4)',
        backdropFilter: 'blur(2px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 50,
        padding: 'var(--space-16)'
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && !closeDisabled) onClose();
      }}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        style={{
          backgroundColor: 'var(--color-surface)',
          borderRadius: 'var(--radius-lg)',
          border: '1px solid var(--color-outline-subtle)',
          boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1)',
          width: '100%',
          maxWidth,
          maxHeight: '90vh',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          outline: 'none'
        }}
      >
        <div
          style={{
            padding: 'var(--space-16) var(--space-24)',
            borderBottom: '1px solid var(--color-outline-subtle)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between'
          }}
        >
          <h2
            id="modal-title"
            style={{
              fontSize: 'var(--font-size-section-heading)',
              lineHeight: 'var(--line-height-section-heading)',
              fontWeight: 600,
              color: 'var(--color-text-main)'
            }}
          >
            {title}
          </h2>
          <button
            type="button"
            className="interactive-target"
            onClick={closeDisabled ? undefined : onClose}
            disabled={closeDisabled}
            aria-label="Cerrar modal"
            style={{
              color: 'var(--color-text-supporting)',
              borderRadius: 'var(--radius-md)',
              opacity: closeDisabled ? 0.4 : 1,
              cursor: closeDisabled ? 'not-allowed' : 'pointer'
            }}
          >
            <CloseIcon size={20} />
          </button>
        </div>
        <div style={{ padding: 'var(--space-24)', overflowY: 'auto' }}>{children}</div>
      </div>
    </div>
  );
}
