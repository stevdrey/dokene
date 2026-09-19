export interface PhoneValidationResult {
  isValid: boolean;
  message?: string;
}

interface RegionRule {
  countryName: string;
  callingCode: string;
  minDigits: number;
  maxDigits: number;
  ruleDescription: string;
}

const REGION_RULES: Record<string, RegionRule> = {
  CL: {
    countryName: 'Chile',
    callingCode: '56',
    minDigits: 9,
    maxDigits: 9,
    ruleDescription: 'Chile requiere 9 dígitos'
  },
  AR: {
    countryName: 'Argentina',
    callingCode: '54',
    minDigits: 10,
    maxDigits: 11,
    ruleDescription: 'Argentina requiere 10 dígitos'
  },
  CO: {
    countryName: 'Colombia',
    callingCode: '57',
    minDigits: 10,
    maxDigits: 10,
    ruleDescription: 'Colombia requiere 10 dígitos'
  },
  PE: {
    countryName: 'Perú',
    callingCode: '51',
    minDigits: 9,
    maxDigits: 9,
    ruleDescription: 'Perú requiere 9 dígitos'
  },
  MX: {
    countryName: 'México',
    callingCode: '52',
    minDigits: 10,
    maxDigits: 10,
    ruleDescription: 'México requiere 10 dígitos'
  },
  ES: {
    countryName: 'España',
    callingCode: '34',
    minDigits: 9,
    maxDigits: 9,
    ruleDescription: 'España requiere 9 dígitos'
  },
  US: {
    countryName: 'Estados Unidos',
    callingCode: '1',
    minDigits: 10,
    maxDigits: 10,
    ruleDescription: 'Estados Unidos requiere 10 dígitos'
  },
  CA: {
    countryName: 'Canadá',
    callingCode: '1',
    minDigits: 10,
    maxDigits: 10,
    ruleDescription: 'Canadá requiere 10 dígitos'
  },
  BR: {
    countryName: 'Brasil',
    callingCode: '55',
    minDigits: 10,
    maxDigits: 11,
    ruleDescription: 'Brasil requiere 10 u 11 dígitos'
  }
};

/**
 * Extracts digits and strips international calling code if present.
 */
export function extractNationalDigits(rawNumber: string, callingCode: string, minNationalDigits: number): string {
  const trimmed = rawNumber.trim();
  const digitsOnly = trimmed.replace(/\D/g, '');

  if (trimmed.startsWith('+')) {
    if (digitsOnly.startsWith(callingCode)) {
      return digitsOnly.slice(callingCode.length);
    }
  } else if (digitsOnly.startsWith(callingCode) && digitsOnly.length >= minNationalDigits + callingCode.length) {
    return digitsOnly.slice(callingCode.length);
  }

  return digitsOnly;
}

/**
 * Validates whether a phone number conforms to the requirements of the given region.
 */
export function validatePhoneNumber(phoneNumber: string, region: string): PhoneValidationResult {
  const trimmed = phoneNumber.trim();
  if (!trimmed) {
    return {
      isValid: false,
      message: 'Todos los teléfonos deben tener un número asignado.'
    };
  }

  // Check for disallowed characters (only +, digits, spaces, hyphens, dots, parentheses)
  if (!/^[+\d\s\-().]+$/.test(trimmed)) {
    return {
      isValid: false,
      message: 'El número telefónico contiene caracteres no válidos.'
    };
  }

  const rule = REGION_RULES[region.toUpperCase()];
  if (rule) {
    const nationalDigits = extractNationalDigits(trimmed, rule.callingCode, rule.minDigits);
    if (nationalDigits.length < rule.minDigits || nationalDigits.length > rule.maxDigits) {
      return {
        isValid: false,
        message: `El número ingresado no es válido para la región seleccionada (${rule.ruleDescription}).`
      };
    }
    return { isValid: true };
  }

  // Generic fallback for other regions (E.164 total digits between 7 and 15)
  const allDigits = trimmed.replace(/\D/g, '');
  if (allDigits.length < 7 || allDigits.length > 15) {
    return {
      isValid: false,
      message: 'El número ingresado no es válido para la región seleccionada (se requieren entre 7 y 15 dígitos).'
    };
  }

  return { isValid: true };
}
