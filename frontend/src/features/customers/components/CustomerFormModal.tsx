import { useState, useEffect } from 'react';
import { CustomerResponse, CustomerWriteRequest, PhoneRequest } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Modal } from '@/shared/components/Modal';
import { Button } from '@/shared/components/Button';
import { AddIcon, CloseIcon } from '@/shared/components/Icons';

interface CustomerFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: (savedCustomer: CustomerResponse) => void;
  customerToEdit?: CustomerResponse | null;
}

const SUPPORTED_REGIONS = [
  { code: 'CL', label: 'Chile (+56)' },
  { code: 'AR', label: 'Argentina (+54)' },
  { code: 'CO', label: 'Colombia (+57)' },
  { code: 'PE', label: 'Perú (+51)' },
  { code: 'MX', label: 'México (+52)' },
  { code: 'ES', label: 'España (+34)' },
  { code: 'US', label: 'Estados Unidos (+1)' }
];

export function CustomerFormModal({
  isOpen,
  onClose,
  onSaved,
  customerToEdit
}: CustomerFormModalProps) {
  const [displayName, setDisplayName] = useState('');
  const [notes, setNotes] = useState('');
  const [phones, setPhones] = useState<PhoneRequest[]>([
    { number: '', region: 'CL', primary: true }
  ]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isEdit = Boolean(customerToEdit);

  useEffect(() => {
    if (customerToEdit) {
      setDisplayName(customerToEdit.displayName);
      setNotes(customerToEdit.notes || '');
      if (customerToEdit.phones && customerToEdit.phones.length > 0) {
        setPhones(
          customerToEdit.phones.map((p) => ({
            number: p.e164,
            region: 'CL',
            primary: p.primary
          }))
        );
      } else {
        setPhones([{ number: '', region: 'CL', primary: true }]);
      }
    } else {
      setDisplayName('');
      setNotes('');
      setPhones([{ number: '', region: 'CL', primary: true }]);
    }
    setError(null);
  }, [customerToEdit, isOpen]);

  const handlePhoneChange = (index: number, field: keyof PhoneRequest, value: unknown) => {
    setPhones((prev) => {
      const updated = [...prev];
      if (field === 'primary' && value === true) {
        // Only one phone can be primary
        return updated.map((item, idx) => ({
          ...item,
          primary: idx === index
        }));
      }
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  };

  const addPhone = () => {
    if (phones.length >= 10) return;
    setPhones((prev) => [...prev, { number: '', region: 'CL', primary: false }]);
  };

  const removePhone = (index: number) => {
    if (phones.length <= 1) return;
    setPhones((prev) => {
      const updated = prev.filter((_, idx) => idx !== index);
      // If we removed the primary phone, ensure the first remaining phone becomes primary
      if (!updated.some((p) => p.primary)) {
        updated[0] = { ...updated[0], primary: true };
      }
      return updated;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const trimmedName = displayName.trim();
    if (!trimmedName) {
      setError('El nombre del cliente es obligatorio.');
      return;
    }
    if (trimmedName.length > 160) {
      setError('El nombre no puede superar los 160 caracteres.');
      return;
    }

    if (notes && notes.length > 2000) {
      setError('Las notas no pueden superar los 2000 caracteres.');
      return;
    }

    const validPhones = phones.map((p) => ({
      ...p,
      number: p.number.trim()
    }));

    if (validPhones.some((p) => !p.number)) {
      setError('Todos los teléfonos deben tener un número asignado.');
      return;
    }

    if (!validPhones.some((p) => p.primary)) {
      setError('Debe seleccionar exactamente un teléfono como principal.');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload: CustomerWriteRequest = {
        displayName: trimmedName,
        notes: notes.trim() || null,
        phones: validPhones
      };

      let result: CustomerResponse;
      if (isEdit && customerToEdit) {
        result = await customerApi.updateCustomer(customerToEdit.id, customerToEdit.version, payload);
      } else {
        result = await customerApi.createCustomer(payload);
      }

      onSaved(result);
      onClose();
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Ocurrió un error al guardar la ficha del cliente.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={isEdit ? 'Editar cliente' : 'Nuevo cliente'}
      maxWidth="600px"
    >
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-16)' }}>
        {error && (
          <div
            role="alert"
            style={{
              backgroundColor: 'var(--color-error-bg)',
              color: 'var(--color-error-text)',
              border: '1px solid var(--color-error-border)',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              fontSize: 'var(--font-size-dense)'
            }}
          >
            {error}
          </div>
        )}

        <div>
          <label
            htmlFor="customer-display-name"
            style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
          >
            Nombre completo / Razón social *
          </label>
          <input
            id="customer-display-name"
            type="text"
            required
            maxLength={160}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Ej. Valentina Morales Gómez"
            style={{
              width: '100%',
              minHeight: '44px',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              backgroundColor: 'var(--color-surface)',
              fontSize: 'var(--font-size-body)'
            }}
          />
          <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', textAlign: 'right', marginTop: '2px' }}>
            {displayName.length}/160
          </div>
        </div>

        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-4)' }}>
            <label style={{ fontSize: 'var(--font-size-dense)', fontWeight: 500 }}>
              Teléfonos de contacto (1 a 10) *
            </label>
            {phones.length < 10 && (
              <Button type="button" variant="ghost" size="sm" onClick={addPhone}>
                <AddIcon size={16} />
                Agregar teléfono
              </Button>
            )}
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-8)' }}>
            {phones.map((phone, idx) => (
              <div
                key={idx}
                style={{
                  display: 'flex',
                  gap: 'var(--space-8)',
                  alignItems: 'center',
                  padding: 'var(--space-8)',
                  borderRadius: 'var(--radius-md)',
                  backgroundColor: phone.primary ? 'var(--color-surface-low)' : 'var(--color-surface-inset)',
                  border: '1px solid var(--color-outline-subtle)'
                }}
              >
                <select
                  value={phone.region}
                  onChange={(e) => handlePhoneChange(idx, 'region', e.target.value)}
                  aria-label={`Región para teléfono ${idx + 1}`}
                  style={{
                    minHeight: '44px',
                    padding: 'var(--space-8)',
                    borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--color-outline)',
                    backgroundColor: 'var(--color-surface)',
                    fontSize: 'var(--font-size-dense)'
                  }}
                >
                  {SUPPORTED_REGIONS.map((r) => (
                    <option key={r.code} value={r.code}>
                      {r.label}
                    </option>
                  ))}
                </select>

                <input
                  type="tel"
                  required
                  value={phone.number}
                  onChange={(e) => handlePhoneChange(idx, 'number', e.target.value)}
                  placeholder="Ej. +56 9 8452 1190 o 984521190"
                  aria-label={`Número de teléfono ${idx + 1}`}
                  style={{
                    flex: 1,
                    minHeight: '44px',
                    padding: 'var(--space-8) var(--space-12)',
                    borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--color-outline)',
                    backgroundColor: 'var(--color-surface)',
                    fontSize: 'var(--font-size-dense)'
                  }}
                />

                <label
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 'var(--space-4)',
                    fontSize: 'var(--font-size-meta)',
                    cursor: 'pointer',
                    whiteSpace: 'nowrap',
                    padding: '0 var(--space-4)'
                  }}
                >
                  <input
                    type="radio"
                    name="primaryPhone"
                    checked={phone.primary}
                    onChange={() => handlePhoneChange(idx, 'primary', true)}
                    style={{ width: '18px', height: '18px' }}
                  />
                  Principal
                </label>

                {phones.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removePhone(idx)}
                    aria-label={`Eliminar teléfono ${idx + 1}`}
                    className="interactive-target"
                    style={{
                      color: 'var(--color-error-text)',
                      borderRadius: 'var(--radius-md)'
                    }}
                  >
                    <CloseIcon size={18} />
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>

        <div>
          <label
            htmlFor="customer-notes"
            style={{ display: 'block', fontSize: 'var(--font-size-dense)', fontWeight: 500, marginBottom: 'var(--space-4)' }}
          >
            Notas operativas del cliente (opcional)
          </label>
          <textarea
            id="customer-notes"
            rows={3}
            maxLength={2000}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Detalles sobre despacho, horario preferido, especificaciones de taller..."
            style={{
              width: '100%',
              padding: 'var(--space-8) var(--space-12)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--color-outline)',
              backgroundColor: 'var(--color-surface)',
              fontSize: 'var(--font-size-dense)',
              resize: 'vertical'
            }}
          />
          <div style={{ fontSize: 'var(--font-size-meta)', color: 'var(--color-text-supporting)', textAlign: 'right', marginTop: '2px' }}>
            {notes.length}/2000
          </div>
        </div>

        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 'var(--space-12)',
            marginTop: 'var(--space-8)'
          }}
        >
          <Button type="button" variant="secondary" onClick={onClose} disabled={isSubmitting}>
            Cancelar
          </Button>
          <Button type="submit" variant="primary" isLoading={isSubmitting}>
            {isEdit ? 'Guardar cambios' : 'Crear cliente'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
