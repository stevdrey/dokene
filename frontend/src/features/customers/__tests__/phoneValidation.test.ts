import { describe, it, expect } from 'vitest';
import {
  validatePhoneNumber,
  extractNationalDigits,
  convertVanityToDigits,
  stripExtension,
  normalizeUnicodeDigits
} from '@/features/customers/utils/phoneValidation';

describe('phoneValidation utility', () => {
  describe('normalizeUnicodeDigits', () => {
    it('normalizes Arabic-Indic digits to ASCII digits', () => {
      expect(normalizeUnicodeDigits('٩٨٤٥٢١١٩٠')).toBe('984521190');
      expect(normalizeUnicodeDigits('+56 ٩٨٤٥٢١١٩٠')).toBe('+56 984521190');
    });

    it('normalizes Eastern Arabic-Indic, Devanagari, and Fullwidth digits', () => {
      expect(normalizeUnicodeDigits('۹۸۴۵۲۱۱۹۰')).toBe('984521190');
      expect(normalizeUnicodeDigits('९८४५२११९०')).toBe('984521190');
      expect(normalizeUnicodeDigits('９８４５２１１９０')).toBe('984521190');
    });

    it('normalizes mathematical bold and double-struck digits across adjacent Nd blocks', () => {
      // Mathematical double-struck (U+1D7D8..U+1D7E1) and bold (U+1D7CE..U+1D7D7)
      expect(normalizeUnicodeDigits('𝟡𝟠𝟜𝟝𝟚𝟙𝟙𝟡𝟘')).toBe('984521190');
      expect(normalizeUnicodeDigits('𝟵𝟴𝟰𝟱𝟮𝟭𝟭𝟵𝟬')).toBe('984521190');
    });

    it('normalizes full-width plus sign (U+FF0B) to ASCII plus', () => {
      expect(normalizeUnicodeDigits('＋56 984521190')).toBe('+56 984521190');
      expect(normalizeUnicodeDigits('＋５６ ９８４５２１１９０')).toBe('+56 984521190');
    });
  });

  describe('stripExtension', () => {
    it('safely bounds overlong inputs without regex backtracking', () => {
      const overlong = '1' + ' '.repeat(20000) + '1';
      const start = performance.now();
      const res = stripExtension(overlong);
      const elapsed = performance.now() - start;

      expect(res).toBe(overlong);
      expect(elapsed).toBeLessThan(100);
    });

    it('strips extension patterns recognized by libphonenumber', () => {
      expect(stripExtension('+1 202-555-0123 ext. 456')).toBe('+1 202-555-0123');
      expect(stripExtension('202-555-0123 ext 456')).toBe('202-555-0123');
      expect(stripExtension('202-555-0123 x456')).toBe('202-555-0123');
      expect(stripExtension('202-555-0123 extension 456')).toBe('202-555-0123');
      expect(stripExtension('202-555-0123 #456')).toBe('202-555-0123');
      expect(stripExtension('202-555-0123, 456')).toBe('202-555-0123');
      expect(stripExtension('202-555-0123; 456')).toBe('202-555-0123');
      expect(stripExtension('1-800-FLOWERS ext. 123')).toBe('1-800-FLOWERS');
      expect(stripExtension('+1 202-555-0123;ext=456')).toBe('+1 202-555-0123');
      expect(stripExtension('+1 202-555-0123;isub=456')).toBe('+1 202-555-0123');
    });

    it('does not strip letters that are part of vanity phonewords', () => {
      expect(stripExtension('1-800-FLOWERS')).toBe('1-800-FLOWERS');
      expect(stripExtension('1-800-FOX')).toBe('1-800-FOX');
    });
  });

  describe('convertVanityToDigits', () => {
    it('converts vanity phonewords to standard keypad digits', () => {
      expect(convertVanityToDigits('1-800-FLOWERS')).toBe('1-800-3569377');
      expect(convertVanityToDigits('FLOWERS')).toBe('3569377');
    });
  });

  describe('extractNationalDigits', () => {
    it('strips leading + and country calling code when present', () => {
      expect(extractNationalDigits('+56 9 8452 1190', '56', 9)).toBe('984521190');
      expect(extractNationalDigits('＋56 9 8452 1190', '56', 9)).toBe('984521190');
      expect(extractNationalDigits('＋５６ ９８４５２１１９０', '56', 9)).toBe('984521190');
      expect(extractNationalDigits('+54 9 11 1234 5678', '54', 10)).toBe('91112345678');
      expect(extractNationalDigits('+1 416 555 1234', '1', 10)).toBe('4165551234');
    });

    it('converts vanity numbers to digits and extracts national portion', () => {
      expect(extractNationalDigits('1-800-FLOWERS', '1', 10, 'US')).toBe('8003569377');
      expect(extractNationalDigits('+1-800-FLOWERS', '1', 10, 'US')).toBe('8003569377');
      expect(extractNationalDigits('800-FLOWERS', '1', 10, 'US')).toBe('8003569377');
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

    it('rejects characters other than digits, letters, and phone punctuation', () => {
      const res = validatePhoneNumber('98452!@#', 'CL');
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

      // Unicode decimal digits (Arabic-Indic, Fullwidth, Devanagari, Mathematical double-struck)
      expect(validatePhoneNumber('٩٨٤٥٢١١٩٠', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('+56 ٩٨٤٥٢١١٩٠', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('９８４５２１１９０', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('९८४५२११९०', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('𝟡𝟠𝟜𝟝𝟚𝟙𝟙𝟡𝟘', 'CL').isValid).toBe(true);

      // Full-width plus sign (U+FF0B)
      expect(validatePhoneNumber('＋56 9 8452 1190', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('＋５６ ９８４５２１１９０', 'CL').isValid).toBe(true);
      expect(validatePhoneNumber('＋56984521190', 'CL').isValid).toBe(true);

      // Overlong case
      const longRes = validatePhoneNumber('98452119012', 'CL');
      expect(longRes.isValid).toBe(false);
      expect(longRes.message).toBe('El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos).');
    });

    it('rejects input exceeding 64 characters without regex backtracking', () => {
      const overlong = '1' + ' '.repeat(20000) + '1';
      const start = performance.now();
      const res = validatePhoneNumber(overlong, 'US');
      const elapsed = performance.now() - start;

      expect(res.isValid).toBe(false);
      expect(res.message).toBe('El número telefónico no puede superar los 64 caracteres.');
      expect(elapsed).toBeLessThan(100);
    });

    it('validates Argentina (AR) numbers: requires 10 to 11 digits and accepts domestic 011 15 prefixes', () => {
      expect(validatePhoneNumber('123', 'AR').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'AR').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Argentina requiere 10 dígitos).'
      );
      expect(validatePhoneNumber('1112345678', 'AR').isValid).toBe(true);
      expect(validatePhoneNumber('+54 9 11 1234 5678', 'AR').isValid).toBe(true);
      // Domestic dialing with trunk 0 and mobile 15
      expect(validatePhoneNumber('011 15-2345-6789', 'AR').isValid).toBe(true);
      expect(validatePhoneNumber('011 2345 6789', 'AR').isValid).toBe(true);
      expect(validatePhoneNumber('11 15 2345 6789', 'AR').isValid).toBe(true);
    });

    it('validates Colombia (CO) numbers: requires 10 digits', () => {
      expect(validatePhoneNumber('3001234567', 'CO').isValid).toBe(true);
      expect(validatePhoneNumber('+57 300 123 4567', 'CO').isValid).toBe(true);
      expect(validatePhoneNumber('12345', 'CO').isValid).toBe(false);
      expect(validatePhoneNumber('12345', 'CO').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Colombia requiere 10 dígitos).'
      );
    });

    it('validates Perú (PE) numbers: requires 8 or 9 digits (permitting 8-digit fixed lines)', () => {
      // 9-digit mobile
      expect(validatePhoneNumber('912345678', 'PE').isValid).toBe(true);
      expect(validatePhoneNumber('+51 912 345 678', 'PE').isValid).toBe(true);
      // 8-digit fixed line (e.g. Lima +51 1 5173501)
      expect(validatePhoneNumber('15173501', 'PE').isValid).toBe(true);
      expect(validatePhoneNumber('+51 1 5173501', 'PE').isValid).toBe(true);
      // Domestic dialing with trunk 0 (e.g. 01 5173501, 0912345678)
      expect(validatePhoneNumber('01 5173501', 'PE').isValid).toBe(true);
      expect(validatePhoneNumber('0912345678', 'PE').isValid).toBe(true);

      expect(validatePhoneNumber('123', 'PE').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'PE').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Perú requiere 8 o 9 dígitos).'
      );
    });

    it('validates México (MX) numbers: requires 10 digits', () => {
      expect(validatePhoneNumber('5512345678', 'MX').isValid).toBe(true);
      expect(validatePhoneNumber('+52 55 1234 5678', 'MX').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'MX').isValid).toBe(false);
    });

    it('validates España (ES) numbers: requires 9 digits and accepts international 00 and 011 prefixes', () => {
      expect(validatePhoneNumber('612345678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('+34 612 345 678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('0034 612 345 678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('01134 612 345 678', 'ES').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'ES').isValid).toBe(false);
    });

    it('validates Estados Unidos (US) and Canadá (CA) numbers: requires 10 digits and supports vanity phonewords and extensions', () => {
      expect(validatePhoneNumber('2025550123', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1 202 555 0123', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('1-800-FLOWERS', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('800-FLOWERS', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1-800-FLOWERS', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1 202-555-0123 ext. 456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123 ext 456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123 x456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123 extension 456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123 #456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123, 456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123; 456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1 202-555-0123;ext=456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('202-555-0123;ext=456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('+1 202-555-0123;isub=456', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('1-800-FLOWERS ext. 123', 'US').isValid).toBe(true);
      expect(validatePhoneNumber('ext. 123', 'US').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'US').isValid).toBe(false);

      expect(validatePhoneNumber('4165551234', 'CA').isValid).toBe(true);
      expect(validatePhoneNumber('+1 416 555 1234', 'CA').isValid).toBe(true);
      expect(validatePhoneNumber('+1 416 555 1234 ext. 789', 'CA').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'CA').isValid).toBe(false);
    });

    it('validates Brasil (BR) numbers: requires 10 to 11 digits and accepts carrier selection codes and trunk 0', () => {
      // Standard mobile (11 digits) and landline (10 digits)
      expect(validatePhoneNumber('11999998888', 'BR').isValid).toBe(true);
      expect(validatePhoneNumber('+55 11 99999 8888', 'BR').isValid).toBe(true);
      expect(validatePhoneNumber('1123456789', 'BR').isValid).toBe(true);
      expect(validatePhoneNumber('+55 11 2345 6789', 'BR').isValid).toBe(true);

      // Carrier selection codes (0 + 2-digit CSP + DDD + number) e.g. 0 21 11 99999-8888
      expect(validatePhoneNumber('0 21 11 99999-8888', 'BR').isValid).toBe(true);
      expect(validatePhoneNumber('0 15 11 2345-6789', 'BR').isValid).toBe(true);

      // Trunk 0 (0 + DDD + number)
      expect(validatePhoneNumber('0 11 99999-8888', 'BR').isValid).toBe(true);
      expect(validatePhoneNumber('0 11 2345-6789', 'BR').isValid).toBe(true);

      // Invalid short number
      expect(validatePhoneNumber('123', 'BR').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'BR').message).toBe(
        'El número ingresado no es válido para la región seleccionada (Brasil requiere 10 u 11 dígitos).'
      );
    });

    it('validates generic/unlisted regions using E.164 7-15 digit boundaries', () => {
      expect(validatePhoneNumber('1234567', 'OTHER').isValid).toBe(true);
      expect(validatePhoneNumber('+380501234567', 'UA').isValid).toBe(true);
      expect(validatePhoneNumber('00380 50 123 4567', 'UA').isValid).toBe(true);
      expect(validatePhoneNumber('123', 'OTHER').isValid).toBe(false);
      expect(validatePhoneNumber('123', 'OTHER').message).toBe(
        'El número ingresado no es válido para la región seleccionada (se requieren entre 7 y 15 dígitos).'
      );
    });
  });
});
