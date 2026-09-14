import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
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
});
