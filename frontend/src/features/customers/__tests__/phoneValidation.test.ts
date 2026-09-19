import { describe, it, expect } from 'vitest';
import { validatePhoneNumber, extractNationalDigits } from '@/features/customers/utils/phoneValidation';

describe('phoneValidation utility', () => {
  describe('extractNationalDigits', () => {
    it('strips leading + and country calling code when present', () => {
      expect(extractNationalDigits('+56 9 8452 1190', '56', 9)).toBe('984521190');
      expect(extractNationalDigits('+54 9 11 1234 5678', '54', 10)).toBe('91112345678');
      expect(extractNationalDigits('+1 416 555 1234', '1', 10)).toBe('4165551234');
    });

    it('strips un-prefixed country code only when total length clearly includes calling code', () => {
      expect(extractNationalDigits('56984521190', '56', 9)).toBe('984521190');
      // If it starts with 56 but total digits is 9, it is not stripped
      expect(extractNationalDigits('561234567', '56', 9)).toBe('561234567');
    });

    it('preserves national digits when no calling code prefix is provided', () => {
      expect(extractNationalDigits('984521190', '56', 9)).toBe('984521190');
      expect(extractNationalDigits('123', '56', 9)).toBe('123');
    });
  });

  describe('validatePhoneNumber', () => {
    it('rejects empty or whitespace-only phone numbers', () => {
      const res = validatePhoneNumber('', 'CL');
      expect(res.isValid).toBe(false);
      expect(res.message).toBe('Todos los teléfonos deben tener un número asignado.');
    });

    it('rejects characters other than digits and phone punctuation', () => {
      const res = validatePhoneNumber('98452abc', 'CL');
      expect(res.isValid).toBe(false);
      expect(res.message).toBe('El número telefónico contiene caracteres no válidos.');
    });

    it('validates Chile (CL) numbers: accepts 9 digits and rejects 123 with actionable error', () => {
      // The exact issue reproduction case: 123 with CL
      const shortRes = validatePhoneNumber('123', 'CL');
      expect(shortRes.isValid).toBe(false);
      expect(shortRes.message).toBe('El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos).');

      // Valid cases
      expect(validatePhoneNumber('984521190', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('+56 9 8452 1190', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('+56984521190', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('9-8452-1190', 'CL').isValid).toBe(true);

      // Overlong case
      const longRes = validatePhoneNumber('98452119012', 'CL');
      expect(longRes.isValid).toBe(false);
      expect(longRes.message).toBe('El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos).');
    });

    it('validates Argentina (AR) numbers: requires 10 to 11 digits', () => {
      expect(validatePhoneNumber('123', 'AR').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'AR').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Argentina requiere 10 dígitos).'
      );
      expect(validatePhoneNumber('1112345678', 'AR').isValid).toBe(true);
      expect(validatePhoneNumber('+54 9 11 1234 5678', 'AR').isValid).toBe(true);
    });

    it('validates Colombia (CO) numbers: requires 10 digits', () => {
      expect(validatePhoneNumber('3001234567', 'CO').isValid).toBe(true);
      expect(validatePhoneNumber('+57 300 123 4567', 'CO').isValid).toBe(true);
      expect(validatePhoneNumber('12345', 'CO').isValid).toBe(false);
      expect(validatePhoneNumber('12345', 'CO').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Colombia requiere 10 dígitos).'
      );
    });

    it('validates Perú (PE) numbers: requires 9 digits', () => {
      expect(validatePhoneNumber('912345678', 'PE').isValid).toBe(true);
      expect(validatePhoneNumber('+51 912 345 678', 'PE').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'PE').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'PE').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Perú requiere 9 dígitos).'
      );
    });

    it('validates México (MX) numbers: requires 10 digits', () => {
      expect(validatePhoneNumber('5512345678', 'MX').isValid).toBe(true);
      expect(validatePhoneNumber('+52 55 1234 5678', 'MX').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'MX').isValid).toBe(false);
    });

    it('validates España (ES) numbers: requires 9 digits', () => {
      expect(validatePhoneNumber('612345678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('+34 612 345 678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'ES').isValid).toBe(false);
    });

    it('validates Estados Unidos (US) and Canadá (CA) numbers: requires 10 digits', () => {
      expect(validatePhoneNumber('2025550123', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1 202 555 0123', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'US').isValid).toBe(false);

      expect(validatePhoneNumber('4165551234', 'CA').isValid).toBe(true);
      expect(validatePhoneNumber('+1 416 555 1234', 'CA').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'CA').isValid).toBe(false);
    });

    it('validates generic/unlisted regions using E.164 7-15 digit boundaries', () => {
      expect(validatePhoneNumber('1234567', 'OTHER').isValid).toBe(true);
      expect(validatePhoneNumber('+380501234567', 'UA').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'OTHER').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'OTHER').message).toBe(
        'El número ingresado no es válido para la región seleccionada (se requieren entre 7 y 15 dígitos).'
      );
    });
  });
});
