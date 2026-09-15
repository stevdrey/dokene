import { useState, useEffect } from 'react';
import { CustomerResponse, CustomerWriteRequest, PhoneRequest } from '@/features/customers/types';
import { customerApi } from '@/features/customers/api/customerApi';
import { Modal } from '@/shared/components/Modal';
import { Button } from '@/shared/components/Button';
import { AddIcon, CloseIcon } from '@/shared/components/Icons';
import { countCodePoints } from '@/features/tenants/TenantContext';

interface CustomerFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: (savedCustomer: CustomerResponse) => void;
  customerToEdit?: CustomerResponse | null;
}

export const SUPPORTED_REGIONS = [
  { code: 'CL', label: 'Chile (+56)' },
  { code: 'AR', label: 'Argentina (+54)' },
  { code: 'CO', label: 'Colombia (+57)' },
  { code: 'PE', label: 'Perú (+51)' },
  { code: 'MX', label: 'México (+52)' },
  { code: 'ES', label: 'España (+34)' },
  { code: 'US', label: 'Estados Unidos (+1)' }
];

const CANADIAN_AREA_CODES = new Set([
  '204', '226', '236', '249', '250', '289', '306', '343', '365', '367', '368',
  '403', '416', '418', '428', '431', '437', '438', '450', '506', '514', '519',
  '548', '579', '581', '584', '587', '604', '613', '639', '647', '672', '683',
  '705', '709', '742', '753', '778', '780', '782', '807', '819', '825', '867',
  '873', '879', '902', '905'
]);

const NANPA_TERRITORIES: Record<string, string> = {
  '242': 'BS',
  '246': 'BB',
  '264': 'AI',
  '268': 'AG',
  '284': 'VG',
  '345': 'KY',
  '441': 'BM',
  '473': 'GD',
  '649': 'TC',
  '664': 'MS',
  '721': 'SX',
  '758': 'LC',
  '767': 'DM',
  '784': 'VC',
  '787': 'PR',
  '939': 'PR',
  '809': 'DO',
  '829': 'DO',
  '849': 'DO',
  '868': 'TT',
  '869': 'KN',
  '876': 'JM',
  '658': 'JM'
};

const CALLING_CODE_MAP: [string, string][] = [
  ['+591', 'BO'],
  ['+592', 'GY'],
  ['+593', 'EC'],
  ['+594', 'GF'],
  ['+595', 'PY'],
  ['+596', 'MQ'],
  ['+597', 'SR'],
  ['+598', 'UY'],
  ['+501', 'BZ'],
  ['+502', 'GT'],
  ['+503', 'SV'],
  ['+504', 'HN'],
  ['+505', 'NI'],
  ['+506', 'CR'],
  ['+507', 'PA'],
  ['+351', 'PT'],
  ['+352', 'LU'],
  ['+353', 'IE'],
  ['+354', 'IS'],
  ['+358', 'FI'],
  ['+420', 'CZ'],
  ['+421', 'SK'],
  ['+56', 'CL'],
  ['+54', 'AR'],
  ['+55', 'BR'],
  ['+57', 'CO'],
  ['+51', 'PE'],
  ['+52', 'MX'],
  ['+58', 'VE'],
  ['+34', 'ES'],
  ['+44', 'GB'],
  ['+49', 'DE'],
  ['+33', 'FR'],
  ['+39', 'IT'],
  ['+31', 'NL'],
  ['+32', 'BE'],
  ['+41', 'CH'],
  ['+43', 'AT'],
  ['+46', 'SE'],
  ['+47', 'NO'],
  ['+45', 'DK'],
  ['+48', 'PL'],
  ['+30', 'GR'],
  ['+90', 'TR'],
  ['+81', 'JP'],
  ['+82', 'KR'],
  ['+86', 'CN'],
  ['+91', 'IN'],
  ['+61', 'AU'],
  ['+64', 'NZ'],
  ['+27', 'ZA'],
  ['+7', 'RU']
];

export function detectRegionFromE164(e164?: string | null): string {
  if (!e164) return 'CL';
  const clean = e164.trim();
  if (!clean.startsWith('+')) return 'CL';

  if (clean.startsWith('+1')) {
    if (clean.length >= 5) {
      const areaCode = clean.slice(2, 5);
      if (CANADIAN_AREA_CODES.has(areaCode)) return 'CA';
      if (NANPA_TERRITORIES[areaCode]) return NANPA_TERRITORIES[areaCode];
    }
    return 'US';
  }

  for (const [prefix, region] of CALLING_CODE_MAP) {
    if (clean.startsWith(prefix)) {
      return region;
    }
  }

  return 'CL';
}

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
            region: detectRegionFromE164(p.e164),
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
    if (countCodePoints(trimmedName) > 160) {
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
      let resolvedNotes: string | null;
      if (isEdit && customerToEdit && (customerToEdit.notes ?? '') === notes) {
        resolvedNotes = customerToEdit.notes;
      } else {
        resolvedNotes = notes === '' ? null : notes;
      }

      const payload: CustomerWriteRequest = {
        displayName: trimmedName,
        notes: resolvedNotes,
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
      closeDisabled={isSubmitting}
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
            {countCodePoints(displayName)}/160
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
                  flexWrap: 'wrap',
                  gap: 'var(--space-8)',
                  alignItems: 'center',
                  padding: 'var(--space-8)',
                  borderRadius: 'var(--radius-md)',
                  backgroundColor: phone.primary ? 'var(--color-surface-low)' : 'var(--color-surface-inset)',
                  border: '1px solid var(--color-outline-subtle)'
                }}
              >
                <div style={{ display: 'flex', gap: 'var(--space-8)', flex: '1 1 240px', minWidth: '0px' }}>
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
                      fontSize: 'var(--font-size-dense)',
                      flexShrink: 0
                    }}
                  >
                    {!SUPPORTED_REGIONS.some((r) => r.code === phone.region) && (
                      <option value={phone.region}>
                        {phone.region} (Detectado)
                      </option>
                    )}
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
                      minWidth: '0px',
                      minHeight: '44px',
                      padding: 'var(--space-8) var(--space-12)',
                      borderRadius: 'var(--radius-md)',
                      border: '1px solid var(--color-outline)',
                      backgroundColor: 'var(--color-surface)',
                      fontSize: 'var(--font-size-dense)'
                    }}
                  />
                </div>

                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 'var(--space-8)',
                    flexShrink: 0,
                    marginLeft: 'auto'
                  }}
                >
                  <label
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 'var(--space-4)',
                      fontSize: 'var(--font-size-meta)',
                      cursor: 'pointer',
                      whiteSpace: 'nowrap',
                      minHeight: '44px',
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
                        borderRadius: 'var(--radius-md)',
                        minWidth: '44px',
                        minHeight: '44px',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center'
                      }}
                    >
                      <CloseIcon size={18} />
                    </button>
                  )}
                </div>
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
