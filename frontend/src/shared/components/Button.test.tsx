import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Button } from '@/shared/components/Button';
import { CloseIcon } from '@/shared/components/Icons';

describe('Button', () => {
  it('enforces a minimum 44px height and 44px width for icon-only buttons', () => {
    render(
      <Button size="sm" aria-label="Eliminar elemento">
        <CloseIcon size={16} />
      </Button>
    );

    const button = screen.getByRole('button', { name: /Eliminar elemento/i });
    expect(button).toHaveStyle({
      minHeight: '44px',
      minWidth: '44px'
    });
  });

  it('renders with children and applies primary variant', () => {
    render(<Button variant="primary">Guardar</Button>);
    const button = screen.getByRole('button', { name: /Guardar/i });
    expect(button).toBeInTheDocument();
    expect(button).toHaveStyle({
      minHeight: '44px',
      minWidth: '44px'
    });
  });

  it('stays focusable but inert while loading, so keyboard focus is not dropped', () => {
    const onClick = vi.fn();
    const onSubmit = vi.fn((event: React.FormEvent) => event.preventDefault());
    const { rerender } = render(
      <form onSubmit={onSubmit}>
        <Button type="submit" onClick={onClick}>
          Guardar
        </Button>
      </form>
    );
    const button = screen.getByRole('button', { name: 'Guardar' });
    button.focus();

    rerender(
      <form onSubmit={onSubmit}>
        <Button type="submit" onClick={onClick} isLoading>
          Guardar
        </Button>
      </form>
    );

    const loading = screen.getByRole('button', { name: 'Cargando...' });
    expect(loading).toBe(button);
    expect(loading).not.toBeDisabled();
    expect(loading).toHaveAttribute('aria-disabled', 'true');
    expect(loading).toHaveAttribute('aria-busy', 'true');
    expect(loading).toHaveFocus();

    fireEvent.click(loading);
    expect(onClick).not.toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('remains natively disabled when disabled, even while loading', () => {
    render(
      <Button disabled isLoading>
        Guardar
      </Button>
    );
    const button = screen.getByRole('button', { name: 'Cargando...' });
    expect(button).toBeDisabled();
    expect(button).not.toHaveAttribute('aria-disabled');
  });
});
