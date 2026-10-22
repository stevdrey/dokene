// ABOUTME: Tests the failure classifier, the role allowlist and the label table of the message review component.
// ABOUTME: Every problem fixture lands in its documented outcome, and every v1 enum value has Spanish text.
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import { describe, expect, it } from 'vitest';
import { AbortedTenantRequestError, ApiError, StaleSessionError } from '@/shared/api/httpClient';
import { InvalidMessageResponseError } from '../api/messageApi';
import { MESSAGE_EVENT_TYPES, MESSAGE_STATUSES, MESSAGING_ERROR_CODES } from '../types';
import { formatDateTime, maskPhone } from '../utils/format';
import { classifyFailure } from '../utils/messageErrors';
import { errorCodeSentence, eventTypeLabel, statusLabel } from '../utils/messageLabels';
import { canActOnMessages } from '../utils/permissions';

const problems = JSON.parse(problemsJson);

// httpClient stores whatever JSON the server sent as the payload, so a problem body arrives untyped.
const apiError = (status: number, payload?: unknown) =>
  new ApiError(status, 'x', payload as ConstructorParameters<typeof ApiError>[2]);

// covers: AC-8, AC-9, AC-12
describe('classifyFailure', () => {
  it('drops aborts, tenant switches and stale sessions', () => {
    expect(classifyFailure(new DOMException('Aborted', 'AbortError'))).toEqual({ kind: 'dropped' });
    expect(classifyFailure(new AbortedTenantRequestError())).toEqual({ kind: 'dropped' });
    expect(classifyFailure(new StaleSessionError())).toEqual({ kind: 'dropped' });
  });

  it('reads status alone for 401, 403 and 404', () => {
    expect(classifyFailure(apiError(401))).toEqual({ kind: 'sessionEnded' });
    expect(classifyFailure(apiError(403))).toEqual({ kind: 'forbidden' });
    expect(classifyFailure(apiError(403, problems.valid.FORBIDDEN))).toEqual({ kind: 'forbidden' });
    expect(classifyFailure(apiError(404, problems.valid.NOT_FOUND))).toEqual({ kind: 'notFound' });
  });

  it('treats a network failure, an uncoded 5xx and an untrusted 2xx as uncertain', () => {
    expect(classifyFailure(new TypeError('Failed to fetch'))).toEqual({ kind: 'uncertain' });
    for (const status of [500, 502, 503, 504]) {
      expect(classifyFailure(apiError(status))).toEqual({ kind: 'uncertain' });
    }
    expect(classifyFailure(new InvalidMessageResponseError())).toEqual({ kind: 'uncertain' });
    // response.json() throws a SyntaxError on a 2xx whose body is not JSON; the command may still have landed.
    expect(classifyFailure(new SyntaxError('Unexpected end of JSON input'))).toEqual({ kind: 'uncertain' });
  });

  it('carries the Retry-After seconds of a RATE_LIMITED refusal', () => {
    expect(classifyFailure(new ApiError(429, 'x', problems.valid.RATE_LIMITED, 7))).toEqual({
      kind: 'rateLimited',
      retryAfterSeconds: 7
    });
  });

  it('treats a coded 503 PROVIDER_UNAVAILABLE as a definite refusal', () => {
    expect(classifyFailure(apiError(503, problems.valid.PROVIDER_UNAVAILABLE))).toEqual({
      kind: 'refused',
      code: 'PROVIDER_UNAVAILABLE'
    });
  });

  it('sends the reload codes and RATE_LIMITED to their own flows', () => {
    for (const code of ['STALE_VERSION', 'INVALID_TRANSITION', 'APPROVAL_INVALIDATED']) {
      expect(classifyFailure(apiError(409, problems.valid[code]))).toEqual({ kind: 'reload', code });
    }
    expect(classifyFailure(apiError(429, problems.valid.RATE_LIMITED))).toEqual({ kind: 'rateLimited' });
  });

  it('refuses with the code for every other problem fixture', () => {
    const special = ['FORBIDDEN', 'NOT_FOUND', 'STALE_VERSION', 'INVALID_TRANSITION', 'APPROVAL_INVALIDATED', 'RATE_LIMITED'];
    for (const [code, body] of Object.entries<{ status: number }>(problems.valid)) {
      if (special.includes(code)) continue;
      expect(classifyFailure(apiError(body.status, body))).toEqual({ kind: 'refused', code });
    }
  });

  it('treats an uncoded 4xx or an invalid problem body as unexpected', () => {
    expect(classifyFailure(apiError(409))).toEqual({ kind: 'unexpected' });
    expect(classifyFailure(apiError(400, { status: 400, message: 'bad' }))).toEqual({ kind: 'unexpected' });
    expect(classifyFailure(new Error('boom'))).toEqual({ kind: 'unexpected' });
  });
});

// covers: AC-6
describe('canActOnMessages', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR', 'TENANT_ADMIN', 'TENANT_OPERATOR'])('lets %s act', (role) => {
    expect(canActOnMessages(role)).toBe(true);
  });

  it.each([['VIEWER'], ['TENANT_VIEWER'], ['owner'], [''], [null], [undefined]])('keeps %s read only', (role) => {
    expect(canActOnMessages(role)).toBe(false);
  });
});

// covers: AC-5, AC-8, AC-14
describe('messageLabels', () => {
  it('has Spanish text for every status, event type and error code', () => {
    for (const status of MESSAGE_STATUSES) expect(statusLabel(status)).not.toBe(status);
    for (const type of MESSAGE_EVENT_TYPES) expect(eventTypeLabel(type)).not.toBe(type);
    for (const code of MESSAGING_ERROR_CODES) {
      if (['STALE_VERSION', 'INVALID_TRANSITION', 'RATE_LIMITED'].includes(code)) continue;
      expect(errorCodeSentence(code)).not.toContain(code);
    }
  });

  it('labels the two v1 values the 0004 table lacked', () => {
    expect(eventTypeLabel('SENT')).toBe('Enviado al proveedor');
    expect(errorCodeSentence('APPROVAL_INVALIDATED')).toBe('La aprobación ya no es válida. Revisa el mensaje de nuevo.');
  });

  it('falls back to the code in parentheses for an unknown code', () => {
    expect(errorCodeSentence('SOMETHING_NEW')).toBe('No se pudo completar la acción (SOMETHING_NEW).');
  });
});

// covers: AC-2, AC-5, AC-7
describe('format', () => {
  it('masks a phone to its last four digits, ignoring formatting characters', () => {
    expect(maskPhone('+56 9 1234-0001')).toBe('•••• 0001');
    expect(maskPhone('+56912340001')).toBe('•••• 0001');
  });

  it('shows whatever digits exist when the number is shorter than four', () => {
    expect(maskPhone('+12')).toBe('•••• 12');
  });

  it('formats a valid timestamp in es-CL with the year', () => {
    expect(formatDateTime('2026-10-20T15:04:00Z')).toContain('2026');
  });

  it('returns an unparseable timestamp unchanged instead of "Invalid Date"', () => {
    expect(formatDateTime('not a date')).toBe('not a date');
  });
});
