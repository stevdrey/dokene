// ABOUTME: Drives one approve, reject, cancel or send intent: confirm group, idempotency key, refusals.
// ABOUTME: One intent holds one key; a retry of the same confirm reuses it, a new intent gets a new one.

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/shared/api/httpClient';
import { messageApi } from '../api/messageApi';
import type { MessageStatus, VersionedDetail } from '../types';
import {
  changedSentence,
  errorCodeSentence,
  FORBIDDEN_SENTENCE,
  NETWORK_SENTENCE,
  rateLimitedSentence
} from '../utils/messageLabels';
import { isAbortLike } from './useMessageList';

export type ActionIntent = 'approve' | 'reject' | 'cancel' | 'send';

export const NOTE_MAX_LENGTH = 500;
export const DEFAULT_RETRY_AFTER_SECONDS = 60;

export type ActionState =
  | { readonly step: 'idle' }
  | { readonly step: 'confirming'; readonly intent: ActionIntent; readonly idempotencyKey: string; readonly note: string }
  | { readonly step: 'submitting'; readonly intent: ActionIntent; readonly idempotencyKey: string; readonly note: string }
  | {
      readonly step: 'refused';
      readonly intent: ActionIntent;
      readonly idempotencyKey: string;
      readonly note: string;
      readonly code?: string;
      readonly message: string;
      readonly retryAfterSeconds?: number;
    }
  | { readonly step: 'done'; readonly intent: ActionIntent };

/** A notice that outlives the intent: the 409 reload alert shown after the detail was refetched. */
export type ActionNotice = { readonly kind: 'changed'; readonly status: MessageStatus } | null;

export interface UseMessageActionOptions {
  readonly messageId: string | null;
  readonly version: number | null;
  readonly onReplaced: (result: VersionedDetail, intent: ActionIntent) => void;
  readonly onConflict: () => Promise<MessageStatus | null>;
  readonly onNotFound: () => void;
  readonly onRateLimited: (seconds: number) => void;
  readonly onActionStart: () => void;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function useMessageAction({
  messageId,
  version,
  onReplaced,
  onConflict,
  onNotFound,
  onRateLimited,
  onActionStart
}: UseMessageActionOptions) {
  const [state, setState] = useState<ActionState>({ step: 'idle' });
  const [notice, setNotice] = useState<ActionNotice>(null);
  const inFlightRef = useRef(false);
  const callbacks = useRef({ onReplaced, onConflict, onNotFound, onRateLimited, onActionStart });
  callbacks.current = { onReplaced, onConflict, onNotFound, onRateLimited, onActionStart };

  useEffect(() => {
    setState({ step: 'idle' });
    setNotice(null);
    inFlightRef.current = false;
  }, [messageId]);

  const begin = useCallback((intent: ActionIntent) => {
    setNotice(null);
    setState({ step: 'confirming', intent, idempotencyKey: newIdempotencyKey(), note: '' });
  }, []);

  const setNote = useCallback((note: string) => {
    setState((current) =>
      current.step === 'confirming' || current.step === 'refused'
        ? { ...current, note: note.slice(0, NOTE_MAX_LENGTH) }
        : current
    );
  }, []);

  const dismiss = useCallback(() => {
    setState({ step: 'idle' });
  }, []);

  const dismissNotice = useCallback(() => setNotice(null), []);

  const confirm = useCallback(async () => {
    if (inFlightRef.current || messageId === null || version === null) return;
    if (state.step !== 'confirming' && state.step !== 'refused') return;
    const { intent, idempotencyKey, note } = state;
    inFlightRef.current = true;
    callbacks.current.onActionStart();
    setNotice(null);
    setState({ step: 'submitting', intent, idempotencyKey, note });

    const trimmedNote = note.trim();
    try {
      let result: VersionedDetail;
      if (intent === 'approve') {
        result = await messageApi.decide(messageId, { decision: 'APPROVE', version, idempotencyKey });
      } else if (intent === 'reject') {
        result = await messageApi.decide(messageId, {
          decision: 'REJECT',
          note: trimmedNote || undefined,
          version,
          idempotencyKey
        });
      } else if (intent === 'cancel') {
        result = await messageApi.cancel(messageId, { note: trimmedNote || undefined, version, idempotencyKey });
      } else {
        result = await messageApi.send(messageId, { version, idempotencyKey });
      }
      inFlightRef.current = false;
      setState({ step: 'done', intent });
      callbacks.current.onReplaced(result, intent);
      setState({ step: 'idle' });
    } catch (err: unknown) {
      inFlightRef.current = false;
      if (isAbortLike(err)) {
        setState({ step: 'confirming', intent, idempotencyKey, note });
        return;
      }
      if (err instanceof ApiError) {
        if (err.status === 409 && (err.code === 'STALE_VERSION' || err.code === 'INVALID_TRANSITION')) {
          setState({ step: 'idle' });
          const freshStatus = await callbacks.current.onConflict();
          if (freshStatus !== null) {
            setNotice({ kind: 'changed', status: freshStatus });
          }
          return;
        }
        if (err.status === 404) {
          setState({ step: 'idle' });
          callbacks.current.onNotFound();
          return;
        }
        if (err.status === 403) {
          setState({ step: 'refused', intent, idempotencyKey, note, code: err.code, message: FORBIDDEN_SENTENCE });
          return;
        }
        if (err.status === 429) {
          const seconds = err.retryAfterSeconds ?? DEFAULT_RETRY_AFTER_SECONDS;
          callbacks.current.onRateLimited(seconds);
          setState({
            step: 'refused',
            intent,
            idempotencyKey,
            note,
            code: err.code ?? 'RATE_LIMITED',
            message: rateLimitedSentence(seconds),
            retryAfterSeconds: seconds
          });
          return;
        }
        const code = err.code ?? (err.status === 400 ? 'INVALID_INPUT' : undefined);
        setState({
          step: 'refused',
          intent,
          idempotencyKey,
          note,
          code,
          message: code ? errorCodeSentence(code) : err.message
        });
        return;
      }
      if (err instanceof TypeError) {
        setState({ step: 'refused', intent, idempotencyKey, note, message: NETWORK_SENTENCE });
        return;
      }
      setState({
        step: 'refused',
        intent,
        idempotencyKey,
        note,
        message: err instanceof Error && err.message ? err.message : NETWORK_SENTENCE
      });
    }
  }, [messageId, version, state]);

  return {
    state,
    notice,
    noticeText: notice ? changedSentence(notice.status) : null,
    begin,
    setNote,
    dismiss,
    dismissNotice,
    confirm
  };
}
