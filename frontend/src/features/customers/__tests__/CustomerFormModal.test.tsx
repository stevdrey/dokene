import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { CustomerFormModal, detectRegionFromE164 } from '@/features/customers/components/CustomerFormModal';
import { customerApi } from '@/features/customers/api/customerApi';
import { ApiError } from '@/shared/api/httpClient';

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

  it('detects and preserves Brazilian (+55) and Canadian (+1) phone regions when editing', async () => {
    const onSaved = vi.fn();
    const updateSpy = vi.spyOn(customerApi, 'updateCustomer').mockResolvedValueOnce({
      id: 'cust-intl-1',
      displayName: 'Cliente Internacional',
      notes: 'Notas',
      phones: [
        { id: 'p-br', e164: '+5511999998888', primary: true },
        { id: 'p-ca', e164: '+14165551234', primary: false }
      ],
      status: 'ACTIVE',
      version: 2,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      archivedAt: null
    });

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={vi.fn()}
        onSaved={onSaved}
        customerToEdit={{
          id: 'cust-intl-1',
          displayName: 'Cliente Internacional',
          notes: 'Notas',
          phones: [
            { id: 'p-br', e164: '+5511999998888', primary: true },
            { id: 'p-ca', e164: '+14165551234', primary: false }
          ],
          status: 'ACTIVE',
          version: 2,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          archivedAt: null
        }}
      />
    );

    // Verify detected region options are present in selects
    expect(screen.getByText('BR (Detectado)')).toBeInTheDocument();
    expect(screen.getByText('CA (Detectado)')).toBeInTheDocument();

    // Edit only display name
    const nameInput = screen.getByLabelText(/Nombre completo/i);
    fireEvent.change(nameInput, { target: { value: 'Cliente Internacional Editado' } });

    fireEvent.click(screen.getByRole('button', { name: /Guardar cambios/i }));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        'cust-intl-1',
        2,
        expect.objectContaining({
          displayName: 'Cliente Internacional Editado',
          phones: [
            { number: '+5511999998888', region: 'BR', primary: true },
            { number: '+14165551234', region: 'CA', primary: false }
          ]
        })
      );
    });
  });

  it('accurately detects Ukraine (UA), Kazakhstan (KZ), and Russia (RU) phone regions', () => {
    expect(detectRegionFromE164('+380501234567')).toBe('UA');
    expect(detectRegionFromE164('+77011234567')).toBe('KZ');
    expect(detectRegionFromE164('+76011234567')).toBe('KZ');
    expect(detectRegionFromE164('+79011234567')).toBe('RU');
    expect(detectRegionFromE164('+74951234567')).toBe('RU');
  });

  it('displays session expired error rather than workspace permission error when mutation fails with 401', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    vi.spyOn(customerApi, 'createCustomer').mockRejectedValueOnce(
      new Error('Sesión no autorizada o expirada')
    );

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
      />
    );

    const nameInput = screen.getByLabelText(/Nombre completo \/ Razón social/i);
    fireEvent.change(nameInput, { target: { value: 'Test Customer' } });

    const phoneInput = screen.getByLabelText(/Número de teléfono 1/i);
    fireEvent.change(phoneInput, { target: { value: '984521190' } });

    const submitBtn = screen.getByRole('button', { name: /Crear cliente/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Sesión no autorizada o expirada');
      expect(screen.queryByText('Acceso denegado en este espacio de trabajo.')).not.toBeInTheDocument();
    });
  });

  it('validates Chilean phone length (rejecting 123 with actionable error and preserving input without API call) [Issue #72]', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();
    const createSpy = vi.spyOn(customerApi, 'createCustomer');

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
      />
    );

    // Step 1 & 2: Enter valid customer display name
    const nameInput = screen.getByLabelText(/Nombre completo \/ Razón social/i) as HTMLInputElement;
    fireEvent.change(nameInput, { target: { value: 'Valentina Morales' } });

    // Step 3: Select region Chile (+56) and enter 123
    const regionSelect = screen.getByLabelText(/Región para teléfono 1/i) as HTMLSelectElement;
    expect(regionSelect.value).toBe('CL');

    const phoneInput = screen.getByLabelText(/Número de teléfono 1/i) as HTMLInputElement;
    fireEvent.change(phoneInput, { target: { value: '123' } });

    // Step 4: Click Crear cliente
    const submitBtn = screen.getByRole('button', { name: /Crear cliente/i });
    fireEvent.click(submitBtn);

    // Assert actionable error message in banner and field-level error association
    const expectedMsg = 'El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos).';
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(expectedMsg);
    });

    // Assert field-level error association
    expect(phoneInput).toHaveAttribute('aria-invalid', 'true');
    expect(phoneInput).toHaveAttribute('aria-describedby', 'phone-error-0');
    expect(screen.getAllByText(expectedMsg)).toHaveLength(2);

    // Assert work preservation: valid user inputs remain intact
    expect(nameInput.value).toBe('Valentina Morales');
    expect(phoneInput.value).toBe('123');

    // Assert no network call was made
    expect(createSpy).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('associates backend validation error to specific phone input when API returns field-level error [Issue #72]', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    const apiError = new ApiError(400, 'El formato del teléfono es inválido para la región seleccionada.', {
      status: 400,
      message: 'El formato del teléfono es inválido para la región seleccionada.',
      field: 'phones[0].number'
    });

    vi.spyOn(customerApi, 'createCustomer').mockRejectedValueOnce(apiError);

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
      />
    );

    fireEvent.change(screen.getByLabelText(/Nombre completo/i), {
      target: { value: 'Valentina Morales' }
    });
    // Enter a 9-digit number that passes client validation but triggers backend rejection
    const phoneInput = screen.getByLabelText(/Número de teléfono 1/i);
    fireEvent.change(phoneInput, {
      target: { value: '984521190' }
    });

    fireEvent.click(screen.getByRole('button', { name: /Crear cliente/i }));

    const expectedApiMsg = 'El formato del teléfono es inválido para la región seleccionada.';
    await waitFor(() => {
      expect(phoneInput).toHaveAttribute('aria-invalid', 'true');
      expect(phoneInput).toHaveAttribute('aria-describedby', 'phone-error-0');
      expect(screen.getByRole('alert')).toHaveTextContent(expectedApiMsg);
      expect(screen.getAllByText(expectedApiMsg)).toHaveLength(2);
      // Verify generic "Error HTTP 400" is NOT displayed
      expect(screen.queryByText('Error HTTP 400')).not.toBeInTheDocument();
    });
  });

  it('preserves existing unchanged phone from uncommon/unlisted region during customer edit without validation error', async () => {
    const onSaved = vi.fn();
    const onClose = vi.fn();

    const customerWithUnusualPhone = {
      id: 'cust-za-1',
      displayName: 'South African Customer',
      notes: null,
      phones: [{ id: 'p-za-1', e164: '+27115551234', primary: true }],
      status: 'ACTIVE' as const,
      version: 1,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      archivedAt: null
    };

    const updateSpy = vi.spyOn(customerApi, 'updateCustomer').mockResolvedValueOnce({
      ...customerWithUnusualPhone,
      displayName: 'South African Customer Renamed',
      version: 2
    });

    render(
      <CustomerFormModal
        isOpen={true}
        onClose={onClose}
        onSaved={onSaved}
        customerToEdit={customerWithUnusualPhone}
      />
    );

    const nameInput = screen.getByLabelText(/Nombre completo/i);
    fireEvent.change(nameInput, { target: { value: 'South African Customer Renamed' } });

    fireEvent.click(screen.getByRole('button', { name: /Guardar cambios/i }));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        'cust-za-1',
        1,
        expect.objectContaining({
          displayName: 'South African Customer Renamed',
          phones: [{ number: '+27115551234', region: 'ZA', primary: true }]
        })
      );
      expect(onSaved).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });
});

