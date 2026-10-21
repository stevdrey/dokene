// ABOUTME: The inline confirm group every message action goes through: names the recipient, then confirm or close.
// ABOUTME: Moves focus into itself on open, closes on Escape, and offers an optional bounded note with a counter.
import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react';
import { Button } from '@/shared/components/Button';
import { MESSAGE_NOTE_MAX_LENGTH } from '../types';

interface NoteField {
  value: string;
  onChange: (value: string) => void;
  readOnly: boolean;
}

interface ConfirmGroupProps {
  label: string;
  recipient: string;
  customerName?: string;
  children: ReactNode;
  note?: NoteField;
  alert?: ReactNode;
  confirmLabel: string;
  confirmVariant: 'primary' | 'danger';
  busy: boolean;
  confirmDisabled?: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}

export function ConfirmGroup({
  label,
  recipient,
  customerName,
  children,
  note,
  alert,
  confirmLabel,
  confirmVariant,
  busy,
  confirmDisabled = false,
  onConfirm,
  onDismiss
}: ConfirmGroupProps) {
  const groupRef = useRef<HTMLDivElement>(null);
  const noteId = useId();
  const counterId = useId();

  useEffect(() => {
    groupRef.current?.querySelector<HTMLElement>('textarea, button:not([disabled])')?.focus();
  }, []);

  return (
    <div
      ref={groupRef}
      role="group"
      aria-label={label}
      className="message-confirm"
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !busy) {
          event.stopPropagation();
          onDismiss();
        }
      }}
    >
      <p className="message-confirm__recipient">
        {customerName ? `Para ${customerName} · ${recipient}` : `Para ${recipient}`}
      </p>
      {children}
      {note && (
        <div className="message-field">
          <label htmlFor={noteId} className="message-field__label">
            Nota (opcional)
          </label>
          <textarea
            id={noteId}
            className="message-field__input message-field__input--note"
            value={note.value}
            maxLength={MESSAGE_NOTE_MAX_LENGTH}
            readOnly={note.readOnly || busy}
            aria-describedby={counterId}
            onChange={(event) => note.onChange(event.target.value)}
          />
          <span id={counterId} className="message-field__counter">
            {note.value.length}/{MESSAGE_NOTE_MAX_LENGTH}
          </span>
        </div>
      )}
      {alert}
      <div className="message-actions">
        <Button
          variant={confirmVariant}
          size="sm"
          onClick={onConfirm}
          disabled={busy || confirmDisabled}
          isLoading={busy}
        >
          {confirmLabel}
        </Button>
        <Button variant="ghost" size="sm" onClick={onDismiss} disabled={busy}>
          Cerrar
        </Button>
      </div>
    </div>
  );
}

/**
 * Remembers the control that opened a group and focuses it again once the group closes by dismissal.
 * When the group closes for another reason (an action result), focus moves to `fallback` instead, but only
 * if it would otherwise be lost because the focused control left the page.
 */
export function useReturnFocus(isOpen: boolean, fallback?: RefObject<HTMLElement | null>) {
  const opener = useRef<HTMLElement | null>(null);
  const restore = useRef(false);
  const wasOpen = useRef(isOpen);

  useEffect(() => {
    const closed = wasOpen.current && !isOpen;
    wasOpen.current = isOpen;
    if (!closed) return;
    if (restore.current) {
      restore.current = false;
      opener.current?.focus();
      return;
    }
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) fallback?.current?.focus();
  }, [isOpen, fallback]);

  return {
    remember(element: HTMLElement | null) {
      opener.current = element;
    },
    restoreOnClose() {
      restore.current = true;
    }
  };
}
