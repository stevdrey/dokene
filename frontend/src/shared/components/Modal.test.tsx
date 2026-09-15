import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Modal } from '@/shared/components/Modal';

describe('Modal', () => {
  it('renders title and children when open', () => {
    render(
      <Modal isOpen={true} onClose={vi.fn()} title="Test Modal">
        <p>Modal content</p>
      </Modal>
    );

    expect(screen.getByRole('dialog', { name: /Test Modal/i })).toBeInTheDocument();
    expect(screen.getByText('Modal content')).toBeInTheDocument();
  });

  it('allows dismissing via Escape, backdrop, and close button when closeDisabled is false', () => {
    const onClose = vi.fn();
    const { rerender } = render(
      <Modal isOpen={true} onClose={onClose} title="Dismissible Modal" closeDisabled={false}>
        <p>Content</p>
      </Modal>
    );

    // 1. Close button
    const closeBtn = screen.getByRole('button', { name: /Cerrar modal/i });
    expect(closeBtn).not.toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledTimes(1);

    // 2. Escape key
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);

    // 3. Backdrop click
    const dialog = screen.getByRole('dialog');
    fireEvent.click(dialog);
    expect(onClose).toHaveBeenCalledTimes(3);
  });

  it('prevents dismissing via Escape, backdrop, and close button when closeDisabled is true during mutations', () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen={true} onClose={onClose} title="Pending Mutation Modal" closeDisabled={true}>
        <p>Processing mutation...</p>
      </Modal>
    );

    // 1. Close button is disabled
    const closeBtn = screen.getByRole('button', { name: /Cerrar modal/i });
    expect(closeBtn).toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).not.toHaveBeenCalled();

    // 2. Escape key ignored
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();

    // 3. Backdrop click ignored
    const dialog = screen.getByRole('dialog');
    fireEvent.click(dialog);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('traps focus inside the modal and wraps around on Tab navigation', () => {
    render(
      <div>
        <button data-testid="outside-btn">Outside</button>
        <Modal isOpen={true} onClose={vi.fn()} title="Focus Trap Modal">
          <input data-testid="modal-input" placeholder="Input" />
          <button data-testid="modal-submit">Submit</button>
        </Modal>
      </div>
    );

    const closeBtn = screen.getByRole('button', { name: /Cerrar modal/i });
    const submitBtn = screen.getByTestId('modal-submit');

    // Tab from last element (submitBtn) wraps to first (closeBtn)
    submitBtn.focus();
    fireEvent.keyDown(window, { key: 'Tab' });
    expect(document.activeElement).toBe(closeBtn);

    // Shift+Tab from first element (closeBtn) wraps to last (submitBtn)
    closeBtn.focus();
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(submitBtn);
  });

  it('restores focus to opener element upon close', () => {
    const onClose = vi.fn();
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    expect(document.activeElement).toBe(opener);

    const { unmount } = render(
      <Modal isOpen={true} onClose={onClose} title="Restore Focus Modal">
        <button>Inside</button>
      </Modal>
    );

    unmount();
    expect(document.activeElement).toBe(opener);
    document.body.removeChild(opener);
  });
});
