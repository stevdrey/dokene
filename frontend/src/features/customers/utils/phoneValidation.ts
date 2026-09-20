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

export const PHONE_INPUT_MAX_LENGTH = 64;

export const UNICODE_DIGIT_ZEROS: number[] = [
  0x30, 0x660, 0x6f0, 0x7c0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6,
  0xc66, 0xce6, 0xd66, 0xde6, 0xe50, 0xed0, 0xf20, 0x1040, 0x1090, 0x17e0,
  0x1810, 0x1946, 0x19d0, 0x1a80, 0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620,
  0xa8d0, 0xa900, 0xa9d0, 0xa9f0, 0xaa50, 0xabf0, 0xff10, 0x104a0, 0x10d30, 0x10d40,
  0x11066, 0x110f0, 0x11136, 0x111d0, 0x112f0, 0x11450, 0x114d0, 0x11650, 0x116c0, 0x116d0,
  0x116da, 0x11730, 0x118e0, 0x11950, 0x11bf0, 0x11c50, 0x11d50, 0x11da0, 0x11de0, 0x11f50,
  0x16130, 0x16a60, 0x16ac0, 0x16b50, 0x16d70, 0x1ccf0, 0x1d7ce, 0x1d7d8, 0x1d7e2, 0x1d7ec,
  0x1d7f6, 0x1e140, 0x1e2f0, 0x1e4f0, 0x1e5f1, 0x1e950, 0x1fbf0
];

/**
 * Normalizes any Unicode decimal digits (Nd category) to standard ASCII 0-9 digits.
 */
export function normalizeUnicodeDigits(input: string): string {
  if (input.length > PHONE_INPUT_MAX_LENGTH || !/\p{Nd}/u.test(input)) {
    return input;
  }
  return input.replace(/\p{Nd}/gu, (ch) => {
    const cp = ch.codePointAt(0)!;
    if (cp >= 0x30 && cp <= 0x39) {
      return ch;
    }
    let low = 0;
    let high = UNICODE_DIGIT_ZEROS.length - 1;
    while (low <= high) {
      const mid = (low + high) >> 1;
      const base = UNICODE_DIGIT_ZEROS[mid];
      if (cp >= base && cp <= base + 9) {
        return String(cp - base);
      }
      if (cp < base) {
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }
    return ch;
  });
}

const KEYPAD_MAPPING: Record<string, string> = {
  A: '2', B: '2', C: '2',
  D: '3', E: '3', F: '3',
  G: '4', H: '4', I: '4',
  J: '5', K: '5', L: '5',
  M: '6', N: '6', O: '6',
  P: '7', Q: '7', R: '7', S: '7',
  T: '8', U: '8', V: '8',
  W: '9', X: '9', Y: '9', Z: '9'
};

export function convertVanityToDigits(rawNumber: string): string {
  return rawNumber.replace(/[a-zA-Z]/g, (ch) => KEYPAD_MAPPING[ch.toUpperCase()] ?? ch);
}

/**
 * Strips phone extensions recognized by libphonenumber (e.g. ext. 123, ext 123, extension 123, x123, #123, ,123, ;123).
 */
export function stripExtension(rawNumber: string): string {
  if (rawNumber.length > PHONE_INPUT_MAX_LENGTH) {
    return rawNumber;
  }
  const normalized = normalizeUnicodeDigits(rawNumber);
  return normalized
    .replace(/(?:[;,#]|\s+(?:ext\.?|extension|x|[;,#])|\s*[-–—]\s*(?:ext\.?|extension|x)|(?<=\d)[xX])\s*\d+\s*#?$/i, '')
    .replace(/[,;\s\-–—]+$/, '')
    .trim();
}

/**
 * Extracts digits and handles international calling codes and domestic trunk/carrier prefixes.
 */
export function extractNationalDigits(
  rawNumber: string,
  callingCode: string,
  minNationalDigits: number,
  region = ''
): string {
  if (rawNumber.length > PHONE_INPUT_MAX_LENGTH) {
    return rawNumber;
  }
  const normalized = normalizeUnicodeDigits(rawNumber);
  const withoutExt = stripExtension(normalized);
  const converted = convertVanityToDigits(withoutExt);
  const trimmed = converted.trim();
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

  if (trimmed.length > PHONE_INPUT_MAX_LENGTH) {
    return {
      isValid: false,
      message: 'El número telefónico no puede superar los 64 caracteres.'
    };
  }

  const normalized = normalizeUnicodeDigits(trimmed);

  // Check for disallowed characters (only +, digits, letters, spaces, hyphens, dots, parentheses, commas, semicolons, hash)
  if (!/^[+\da-zA-Z\s\-().,;#]+$/.test(normalized)) {
    return {
      isValid: false,
      message: 'El número telefónico contiene caracteres no válidos.'
    };
  }

  const rule = REGION_RULES[region.toUpperCase()];
  if (rule) {
    const nationalDigits = extractNationalDigits(normalized, rule.callingCode, rule.minDigits, region);
    if (nationalDigits.length < rule.minDigits || nationalDigits.length > rule.maxDigits) {
      return {
        isValid: false,
        message: `El número ingresado no es válido para la región seleccionada (${rule.ruleDescription}).`
      };
    }
    return { isValid: true };
  }

  // Generic fallback for other regions (E.164 total digits between 7 and 15)
  let allDigits = convertVanityToDigits(stripExtension(normalized)).replace(/\D/g, '');
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
