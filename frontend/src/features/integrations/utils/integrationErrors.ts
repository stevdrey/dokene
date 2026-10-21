// ABOUTME: Maps read and write failures of the integration panel to Spanish copy and panel decisions.
// ABOUTME: Never echoes a server or request value; unknown bodies fall back to the shared status copy.
import { ApiError, getFriendlyErrorMessage } from '@/shared/api/httpClient';
import { parseMessagingProblem } from '@/features/messages/types';
import { InvalidIntegrationResponseError } from '../api/integrationApi';
import { COPY } from './integrationCopy';

export type WriteOperation = 'save' | 'verify';

export type WriteFailure =
  | { kind: 'stale' }
  | { kind: 'forbidden'; message: string }
  | { kind: 'message'; message: string };

const WRITE_METHOD: Record<WriteOperation, 'PUT' | 'POST'> = { save: 'PUT', verify: 'POST' };

function problemCode(error: ApiError): string | null {
  return parseMessagingProblem(error.payload)?.code ?? null;
}

function fallbackMessage(error: unknown, method: 'GET' | 'PUT' | 'POST'): string {
  if (error instanceof ApiError) return getFriendlyErrorMessage(error.status, method);
  if (error instanceof InvalidIntegrationResponseError) return error.message;
  return getFriendlyErrorMessage(0);
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && problemCode(error) === 'NOT_FOUND';
}

export function readErrorMessage(error: unknown): string {
  return fallbackMessage(error, 'GET');
}

export function writeFailure(error: unknown, operation: WriteOperation): WriteFailure {
  if (error instanceof ApiError) {
    // The server is authoritative on permissions, so any 403 hides the controls, problem body or not.
    if (error.status === 403) return { kind: 'forbidden', message: COPY.accessDenied };
    const code = problemCode(error);
    if (code === 'STALE_VERSION') return { kind: 'stale' };
    if (code === 'INVALID_INPUT') {
      return { kind: 'message', message: operation === 'save' ? COPY.saveInvalid : COPY.verifyInvalid };
    }
    if (code === 'RATE_LIMITED') return { kind: 'message', message: COPY.rateLimited };
    if (code === 'PROVIDER_UNAVAILABLE' && operation === 'verify') {
      return { kind: 'message', message: COPY.providerUnavailable };
    }
  }
  return { kind: 'message', message: fallbackMessage(error, WRITE_METHOD[operation]) };
}
