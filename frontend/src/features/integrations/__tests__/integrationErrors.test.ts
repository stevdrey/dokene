// ABOUTME: Tests how read and write failures become Spanish copy and panel decisions.
// ABOUTME: Problem codes come from the frozen problems fixture; anything else falls back to status copy.
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import { describe, expect, it } from 'vitest';
import { ApiError, StaleSessionError } from '@/shared/api/httpClient';
import { InvalidIntegrationResponseError } from '../api/integrationApi';
import { isNotFound, readErrorMessage, writeFailure } from '../utils/integrationErrors';

const problems = JSON.parse(problemsJson);
const problem = (code: string) => new ApiError(problems.valid[code].status, 'texto del servidor', problems.valid[code]);

describe('isNotFound', () => {
  it('is true only for a 404 carrying the NOT_FOUND problem', () => {
    expect(isNotFound(problem('NOT_FOUND'))).toBe(true);
    expect(isNotFound(new ApiError(404, 'x'))).toBe(false);
    expect(isNotFound(problem('FORBIDDEN'))).toBe(false);
    expect(isNotFound(new Error('x'))).toBe(false);
  });
});

describe('readErrorMessage', () => {
  it('uses the status copy, never the server text', () => {
    expect(readErrorMessage(new ApiError(500, 'stack trace here'))).toBe(
      'Ocurrió un problema en el servidor al procesar la solicitud. Por favor intenta nuevamente.'
    );
  });

  it('keeps the narrowing failure copy', () => {
    expect(readErrorMessage(new InvalidIntegrationResponseError())).toBe(
      'La respuesta del servidor sobre la integración no tiene el formato esperado.'
    );
  });

  it('gives a generic sentence for a network failure', () => {
    expect(readErrorMessage(new TypeError('Failed to fetch'))).toBe(
      'Ocurrió un error inesperado al procesar la solicitud. Por favor intenta nuevamente.'
    );
  });
});

describe('writeFailure', () => {
  it('recognises a stale version', () => {
    expect(writeFailure(problem('STALE_VERSION'), 'save')).toEqual({ kind: 'stale' });
    expect(writeFailure(problem('STALE_VERSION'), 'verify')).toEqual({ kind: 'stale' });
  });

  it('treats any 403 as forbidden', () => {
    const expected = { kind: 'forbidden', message: 'Acceso denegado en este espacio de trabajo.' };
    expect(writeFailure(problem('FORBIDDEN'), 'save')).toEqual(expected);
    expect(writeFailure(new ApiError(403, '<html>'), 'verify')).toEqual(expected);
  });

  it.each([
    ['INVALID_INPUT', 'save', 'Revisa los identificadores, el número visible y la referencia.'],
    [
      'INVALID_INPUT',
      'verify',
      'No se pudo comprobar la propiedad del número con la referencia configurada. Revisa que la integración esté habilitada y que los identificadores sean correctos.'
    ],
    ['RATE_LIMITED', 'save', 'Demasiadas solicitudes. Espera un momento antes de reintentar.'],
    ['RATE_LIMITED', 'verify', 'Demasiadas solicitudes. Espera un momento antes de reintentar.'],
    ['PROVIDER_UNAVAILABLE', 'verify', 'El proveedor no está disponible en este momento. Intenta más tarde.']
  ] as const)('maps %s on %s', (code, operation, message) => {
    expect(writeFailure(problem(code), operation)).toEqual({ kind: 'message', message });
  });

  it('falls back to the status copy for a body that is not a valid problem', () => {
    const malformed = new ApiError(400, 'phoneNumberId 123 is invalid', { status: 400, message: 'phoneNumberId 123 is invalid' });

    expect(writeFailure(malformed, 'save')).toEqual({
      kind: 'message',
      message: 'La solicitud no pudo ser procesada. Revisa los datos ingresados.'
    });
  });

  it('falls back to the status copy for a code the panel does not map', () => {
    expect(writeFailure(problem('PROVIDER_UNAVAILABLE'), 'save')).toEqual({
      kind: 'message',
      message: 'El servicio no está disponible temporalmente. Por favor intenta de nuevo en unos momentos.'
    });
  });

  it('gives a generic sentence for an unexpected error', () => {
    expect(writeFailure(new StaleSessionError(), 'save').kind).toBe('message');
    expect(writeFailure(new InvalidIntegrationResponseError(), 'verify')).toEqual({
      kind: 'message',
      message: 'La respuesta del servidor sobre la integración no tiene el formato esperado.'
    });
  });
});
