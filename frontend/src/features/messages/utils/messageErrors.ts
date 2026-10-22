// ABOUTME: Sorts a failed message read or command into the few outcomes the review component treats differently.
// ABOUTME: Reads the problem code from ApiError.payload; an uncoded 5xx or a network failure is uncertain, never refused.
import { AbortedTenantRequestError, ApiError, StaleSessionError } from '@/shared/api/httpClient';
import { parseMessagingProblem, type MessagingErrorCode } from '../types';
import { InvalidMessageResponseError } from '../api/messageApi';

export type ReloadCode = 'STALE_VERSION' | 'INVALID_TRANSITION' | 'APPROVAL_INVALIDATED' | 'CONTACT_NOT_OWNED';

export type MessageFailure =
  /** The request belongs to a previous tenant, session or mount; render nothing. */
  | { kind: 'dropped' }
  | { kind: 'sessionEnded' }
  | { kind: 'forbidden' }
  | { kind: 'notFound' }
  /** The held version is no longer current; read the message again. */
  | { kind: 'reload'; code: ReloadCode }
  | { kind: 'rateLimited'; retryAfterSeconds?: number }
  /** The command may have landed; read the server state before saying anything. */
  | { kind: 'uncertain' }
  | { kind: 'refused'; code: MessagingErrorCode }
  | { kind: 'unexpected' };

// CONTACT_NOT_OWNED reloads too: contactRemoved changes without a version bump, so only a read shows the restricted controls.
const RELOAD_CODES: readonly string[] = ['STALE_VERSION', 'INVALID_TRANSITION', 'APPROVAL_INVALIDATED', 'CONTACT_NOT_OWNED'];

export function problemCode(error: unknown): MessagingErrorCode | null {
  return error instanceof ApiError ? parseMessagingProblem(error.payload)?.code ?? null : null;
}

function isAbort(error: unknown): boolean {
  // An aborted fetch rejects with a DOMException, which is not an Error instance in every runtime.
  const named = typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError';
  return (
    named ||
    error instanceof AbortedTenantRequestError ||
    error instanceof StaleSessionError
  );
}

export function classifyFailure(error: unknown): MessageFailure {
  if (isAbort(error)) return { kind: 'dropped' };
  // fetch rejects with a TypeError when the network fails, so the server may or may not have acted.
  if (error instanceof TypeError) return { kind: 'uncertain' };
  // A 2xx whose body or ETag cannot be trusted still means the command may have landed. The same holds for a 2xx
  // whose JSON the client could not parse: response.json() throws a SyntaxError after the server already answered.
  if (error instanceof InvalidMessageResponseError || error instanceof SyntaxError) return { kind: 'uncertain' };
  if (!(error instanceof ApiError)) return { kind: 'unexpected' };
  if (error.status === 401) return { kind: 'sessionEnded' };
  // The filter answers 403 with an empty body, so the status alone decides.
  if (error.status === 403) return { kind: 'forbidden' };
  if (error.status === 404) return { kind: 'notFound' };
  const code = problemCode(error);
  if (code === null) return error.status >= 500 ? { kind: 'uncertain' } : { kind: 'unexpected' };
  if (RELOAD_CODES.includes(code)) return { kind: 'reload', code: code as ReloadCode };
  if (code === 'RATE_LIMITED') return { kind: 'rateLimited', retryAfterSeconds: error.retryAfterSeconds };
  return { kind: 'refused', code };
}
