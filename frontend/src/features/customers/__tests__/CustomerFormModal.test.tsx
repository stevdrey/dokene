import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { CustomerFormModal } from '@/features/customers/components/CustomerFormModal';
import { customerApi } from '@/features/customers/api/customerApi';

describe('CustomerFormModal', () => {
  it('renders correctly and validates required name', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
      />
    );

    expect(screen.getByRole('dialog', { name: /Nuevo cliente/i })).toBeInTheDocument();

    const nameInput = screen.getByLabelText(/Nombre completo \/ Razón social/i);
    expect(nameInput).toBeInTheDocument();

    const submitBtn = screen.getByRole('button', { name: /Crear cliente/i });
    fireEvent.click(submitBtn);

    // HTML5 validation or component error check
    expect(nameInput).toBeRequired();
  });

  it('submits valid customer data and calls onSaved', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    vi.spyOn(customerApi, 'createCustomer').mockResolvedValueOnce({
      id: 'cust-123',
      displayName: 'Valentina Morales',
      notes: 'Taller Las Lilas',
      phones: [{ id: 'p-1', e164: '+56984521190', primary: true }],
      status: 'ACTIVE',
      version: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      archivedAt: null
    });

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
      />
    );

    const nameInput = screen.getByLabelText(/Nombre completo \/ Razón social/i);
    fireEvent.change(nameInput, { target: { value: 'Valentina Morales' } });

    const phoneInput = screen.getByLabelText(/Número de teléfono 1/i);
    fireEvent.change(phoneInput, { target: { value: '984521190' } });

    const notesInput = screen.getByLabelText(/Notas operativas/i);
    fireEvent.change(notesInput, { target: { value: 'Taller Las Lilas' } });

    const submitBtn = screen.getByRole('button', { name: /Crear cliente/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(customerApi.createCustomer).toHaveBeenCalledWith({
        displayName: 'Valentina Morales',
        notes: 'Taller Las Lilas',
        phones: [{ number: '984521190', region: 'CL', primary: true }]
      });
      expect(onSaved).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });

  it('displays conflict error message when telephone is duplicated', async () => {
    vi.spyOn(customerApi, 'createCustomer').mockRejectedValueOnce(
      new Error('Conflicto: el registro o número de contacto ya existe o está en conflicto.')
    );

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    fireEvent.change(screen.getByLabelText(/Nombre completo/i), {
      target: { value: 'Cliente Duplicado' }
    });
    fireEvent.change(screen.getByLabelText(/Número de teléfono 1/i), {
      target: { value: '984521190' }
    });

    fireEvent.click(screen.getByRole('button', { name: /Crear cliente/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Conflicto/i);
    });
  });

  it('detects region correctly and preserves international country code when editing', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    const customerToEdit = {
      id: 'cust-arg-1',
      displayName: 'Martín Palermo',
      notes: 'Contacto Buenos Aires',
      phones: [{ id: 'p-arg', e164: '+5491112345678', primary: true }],
      status: 'ACTIVE' as const,
      version: 1,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      archivedAt: null
    };

    vi.spyOn(customerApi, 'updateCustomer').mockResolvedValueOnce({
      ...customerToEdit,
      version: 2
    });

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
        customerToEdit={customerToEdit}
      />
    );

    expect(screen.getByRole('dialog', { name: /Editar cliente/i })).toBeInTheDocument();

    const regionSelect = screen.getByLabelText(/Región para teléfono 1/i) as HTMLSelectElement;
    expect(regionSelect.value).toBe('AR');

    const submitBtn = screen.getByRole('button', { name: /Guardar cambios/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(customerApi.updateCustomer).toHaveBeenCalledWith(
        'cust-arg-1',
        1,
        expect.objectContaining({
          phones: [{ number: '+5491112345678', region: 'AR', primary: true }]
        })
      );
      expect(onSaved).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });

  it('renders phone controls with responsive flex wrapping and permitted shrinkage', async () => {
    render(
      <CustomerFormModal
        isOpen={true}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    const phoneInput = screen.getByLabelText(/Número de teléfono 1/i);
    expect(phoneInput).toHaveStyle({ minWidth: '0px' });
  });

  it('preserves notes verbatim with leading/trailing whitespace during profile edits', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    const customerWithSpacedNotes = {
      id: 'cust-spaced-1',
      displayName: 'Empresa Test',
      notes: '  Notas con espacios al inicio y final  \n',
      phones: [{ id: 'p-1', e164: '+56911112222', primary: true }],
      status: 'ACTIVE' as const,
      version: 3,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      archivedAt: null
    };

    const updateSpy = vi.spyOn(customerApi, 'updateCustomer').mockResolvedValueOnce({
      ...customerWithSpacedNotes,
      displayName: 'Empresa Test Actualizada',
      version: 4
    });

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
        customerToEdit={customerWithSpacedNotes}
      />
    );

    // Edit only the display name, leaving notes untouched
    const nameInput = screen.getByLabelText(/Nombre completo/i);
    fireEvent.change(nameInput, { target: { value: 'Empresa Test Actualizada' } });

    fireEvent.click(screen.getByRole('button', { name: /Guardar cambios/i }));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        'cust-spaced-1',
        3,
        expect.objectContaining({
          displayName: 'Empresa Test Actualizada',
          notes: '  Notas con espacios al inicio y final  \n'
        })
      );
    });
  });

  it('counts display name in Unicode code points and allows supplementary characters', async () => {
    render(
      <CustomerFormModal
        isOpen={true}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    const nameInput = screen.getByLabelText(/Nombre completo/i);
    // Emojis consist of 2 UTF-16 code units each (surrogate pairs)
    const emojiName = 'Taller 🚗🔧';
    // 'Taller ' (7) + 🚗 (1 code point, 2 code units) + 🔧 (1 code point, 2 code units) = 9 code points, 11 code units
    fireEvent.change(nameInput, { target: { value: emojiName } });

    expect(screen.getByText('9/160')).toBeInTheDocument();
    expect(nameInput).not.toHaveAttribute('maxLength');
  });
});
