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
    minDigits: 8,
    maxDigits: 9,
    ruleDescription: 'Perú requiere 8 o 9 dígitos'
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
 * Extracts digits and handles international calling codes and domestic trunk/carrier prefixes.
 */
export function extractNationalDigits(
  rawNumber: string,
  callingCode: string,
  minNationalDigits: number,
  region = ''
): string {
  const trimmed = rawNumber.trim();
  let digitsOnly = trimmed.replace(/\D/g, '');
  const normRegion = region.toUpperCase();

  if (trimmed.startsWith('+')) {
    if (digitsOnly.startsWith(callingCode)) {
      digitsOnly = digitsOnly.slice(callingCode.length);
    }
  } else if (digitsOnly.startsWith('00' + callingCode) && digitsOnly.length >= minNationalDigits + callingCode.length + 2) {
    digitsOnly = digitsOnly.slice(callingCode.length + 2);
  } else if (digitsOnly.startsWith('011' + callingCode) && digitsOnly.length >= minNationalDigits + callingCode.length + 3) {
    digitsOnly = digitsOnly.slice(callingCode.length + 3);
  } else if (digitsOnly.startsWith(callingCode) && digitsOnly.length >= minNationalDigits + callingCode.length) {
    digitsOnly = digitsOnly.slice(callingCode.length);
  }

  // Handle region-specific domestic dialing prefixes (e.g. Argentine 011 15-..., Mexican 01/044/045)
  if (normRegion === 'AR') {
    // International mobile indicator (+54 9 ...)
    if (digitsOnly.startsWith('9') && digitsOnly.length === 11) {
      digitsOnly = digitsOnly.slice(1);
    }
    // Domestic trunk prefix '0'
    if (digitsOnly.startsWith('0')) {
      digitsOnly = digitsOnly.slice(1);
    }
    // Domestic mobile prefix '15' after 2, 3, or 4-digit area code (e.g. 011 15-2345-6789)
    if (digitsOnly.length === 12) {
      if (digitsOnly.slice(2, 4) === '15') {
        digitsOnly = digitsOnly.slice(0, 2) + digitsOnly.slice(4);
      } else if (digitsOnly.slice(3, 5) === '15') {
        digitsOnly = digitsOnly.slice(0, 3) + digitsOnly.slice(5);
      } else if (digitsOnly.slice(4, 6) === '15') {
        digitsOnly = digitsOnly.slice(0, 4) + digitsOnly.slice(6);
      }
    }
  } else if (normRegion === 'MX') {
    // Historical Mexican domestic trunk prefixes
    if (digitsOnly.startsWith('01') && digitsOnly.length === 12) {
      digitsOnly = digitsOnly.slice(2);
    } else if ((digitsOnly.startsWith('044') || digitsOnly.startsWith('045')) && digitsOnly.length === 13) {
      digitsOnly = digitsOnly.slice(3);
    }
  } else if (normRegion === 'BR') {
    // Domestic dialing with carrier selection code (0 + 2-digit CSP + 10 or 11 national digits = 13 or 14 digits)
    if (digitsOnly.startsWith('0') && (digitsOnly.length === 13 || digitsOnly.length === 14)) {
      digitsOnly = digitsOnly.slice(3);
    } else if (digitsOnly.startsWith('0') && (digitsOnly.length === 11 || digitsOnly.length === 12)) {
      // Domestic dialing with trunk 0 (0 + 10 or 11 national digits = 11 or 12 digits)
      digitsOnly = digitsOnly.slice(1);
    }
  } else if (normRegion === 'PE') {
    // Domestic trunk prefix '0' (e.g. 01 5173501 for Lima fixed-line or 09XXXXXXXX for mobile)
    if (digitsOnly.startsWith('0') && (digitsOnly.length === 9 || digitsOnly.length === 10)) {
      digitsOnly = digitsOnly.slice(1);
    }
  } else if (digitsOnly.startsWith('0') && digitsOnly.length === minNationalDigits + 1) {
    // Single leading trunk zero (e.g. 09XXXXXXXX in Chile)
    digitsOnly = digitsOnly.slice(1);
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
    const nationalDigits = extractNationalDigits(trimmed, rule.callingCode, rule.minDigits, region);
    if (nationalDigits.length < rule.minDigits || nationalDigits.length > rule.maxDigits) {
      return {
        isValid: false,
        message: `El número ingresado no es válido para la región seleccionada (${rule.ruleDescription}).`
      };
    }
    return { isValid: true };
  }

  // Generic fallback for other regions (E.164 total digits between 7 and 15)
  let allDigits = trimmed.replace(/\D/g, '');
  if (allDigits.startsWith('00') && allDigits.length >= 9) {
    allDigits = allDigits.slice(2);
  } else if (allDigits.startsWith('011') && allDigits.length >= 10) {
    allDigits = allDigits.slice(3);
  }
  if (allDigits.length < 7 || allDigits.length > 15) {
    return {
      isValid: false,
      message: 'El número ingresado no es válido para la región seleccionada (se requieren entre 7 y 15 dígitos).'
    };
  }

  return { isValid: true };
}
