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
});
