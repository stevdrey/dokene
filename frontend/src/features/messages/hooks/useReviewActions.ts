// ABOUTME: Runs approve, reject, cancel, send and Corregir for the review component, one confirm group at a time.
// ABOUTME: Binds each group to the version it opened on, keeps one idempotency key per intent, and resolves uncertain replies by reading.
import { useCallback, useEffect, useRef, useState } from 'react';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { MessageResponse } from '../types';
import {
  COPY,
  RATE_LIMIT_SECONDS,
  changedSentence,
  errorCodeSentence,
  sendResultSentence
} from '../utils/messageLabels';
import { isAwaitingProvider, type MessageDetailHandle } from './useMessageDetail';

export type ReviewIntent = 'approve' | 'reject' | 'cancel' | 'send' | 'correct';

export type GroupStep =
  | { kind: 'open' }
  | { kind: 'submitting' }
  | { kind: 'refused'; message: string }
  | { kind: 'uncertain' }
  | { kind: 'rateLimited' };

export interface ConfirmState {
  intent: ReviewIntent;
  openedOnVersion: number;
  key: string;
  note: string;
  step: GroupStep;
}

function call(
  messageId: string,
  group: ConfirmState,
  signal: AbortSignal
): Promise<VersionedMessage> {
  const { intent, note, openedOnVersion: version, key } = group;
  switch (intent) {
    case 'approve':
      return messageApi.decide(messageId, 'APPROVE', undefined, version, key, signal);
    case 'reject':
      return messageApi.decide(messageId, 'REJECT', note, version, key, signal);
    case 'cancel':
      return messageApi.cancel(messageId, note, version, key, signal);
    case 'correct':
      return messageApi.cancel(messageId, undefined, version, key, signal);
    case 'send':
      return messageApi.send(messageId, version, key, signal);
  }
}

function successSentence(intent: ReviewIntent, detail: MessageResponse): string {
  switch (intent) {
    case 'approve':
      return COPY.approved;
    case 'reject':
      return COPY.rejected;
    case 'cancel':
    case 'correct':
      return COPY.cancelled;
    case 'send':
      return sendResultSentence(detail.status);
  }
}

interface Options {
  messageId: string;
  detail: MessageDetailHandle;
  announce: (text: string) => void;
  onCorrect: (cancelled: MessageResponse) => void;
}

export interface ReviewActions {
  group: ConfirmState | null;
  /** An outcome that outlives its group: a reload sentence, a moved version, a failed refresh. */
  alert: string | null;
  sendLocked: boolean;
  lockSeconds: number;
  /** The intent of an attempt that may have landed; until the version moves, only its retry may open. */
  unsureIntent: ReviewIntent | null;
  open: (intent: ReviewIntent) => void;
  close: () => void;
  setNote: (note: string) => void;
  confirm: () => Promise<void>;
  refresh: () => Promise<void>;
}

export function useReviewActions({ messageId, detail, announce, onCorrect }: Options): ReviewActions {
  const [group, setGroup] = useState<ConfirmState | null>(null);
  const [alert, setAlert] = useState<string | null>(null);
  const [lockUntil, setLockUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  // An unsure attempt closed with Cerrar, held so reopening it retries on the same key.
  const [heldUnsure, setHeldUnsure] = useState<ConfirmState | null>(null);
  const groupRef = useRef(group);
  groupRef.current = group;

  const version = detail.state.kind === 'loaded' ? detail.state.version : null;
  const versionRef = useRef(version);
  versionRef.current = version;

  // A version that moves under an open group (a poll tick or a read) closes it, and ends any send lockout.
  useEffect(() => {
    setLockUntil(null);
    setHeldUnsure(null);
    const open = groupRef.current;
    if (open && version !== null && open.step.kind !== 'submitting' && open.openedOnVersion !== version) {
      setGroup(null);
      setAlert(COPY.versionMoved);
    }
  }, [version]);

  useEffect(() => {
    if (lockUntil === null) return;
    const timer = setTimeout(() => {
      setLockUntil(null);
      setGroup((current) =>
        current && current.step.kind === 'rateLimited' ? { ...current, step: { kind: 'open' } } : current
      );
    }, Math.max(0, lockUntil - Date.now()));
    return () => clearTimeout(timer);
  }, [lockUntil]);

  const counting = lockUntil !== null && group?.step.kind === 'rateLimited';
  useEffect(() => {
    if (!counting) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [counting]);

  const open = useCallback(
    (intent: ReviewIntent) => {
      const current = groupRef.current;
      if (version === null || current?.step.kind === 'submitting') return;
      const unsure = current?.step.kind === 'uncertain' ? current : heldUnsure;
      if (unsure) {
        if (unsure.intent !== intent) return;
        setHeldUnsure(null);
        setGroup(unsure);
        return;
      }
      setAlert(null);
      setGroup({ intent, openedOnVersion: version, key: crypto.randomUUID(), note: '', step: { kind: 'open' } });
    },
    [heldUnsure, version]
  );

  const close = useCallback(() => {
    const current = groupRef.current;
    if (current?.step.kind === 'submitting') return;
    if (current?.step.kind === 'uncertain') setHeldUnsure(current);
    setGroup(null);
  }, []);

  const setNote = useCallback((note: string) => {
    const current = groupRef.current;
    if (!current || current.step.kind === 'submitting' || current.step.kind === 'uncertain') return;
    // A changed note after a refusal is a new request body, so it gets a new key.
    const refused = current.step.kind === 'refused';
    const next: ConfirmState = {
      ...current,
      note,
      key: refused ? crypto.randomUUID() : current.key,
      step: refused ? { kind: 'open' } : current.step
    };
    groupRef.current = next;
    setGroup(next);
  }, []);

  const reloadAfterConflict = useCallback(
    async (code: string) => {
      const read = await detail.readAgain();
      if (read.kind === 'dropped') return;
      if (read.kind === 'ok') {
        detail.apply(read.value);
        detail.loadEvents();
        setAlert(code === 'APPROVAL_INVALIDATED' ? errorCodeSentence(code) : changedSentence(read.value.detail.status));
        return;
      }
      if (read.failure.kind === 'notFound') return detail.markNotFound();
      setAlert(COPY.refreshFailed);
    },
    [detail]
  );

  const resolveUncertain = useCallback(
    async (attempt: ConfirmState) => {
      const read = await detail.readAgain();
      if (read.kind === 'dropped') return;
      if (read.kind === 'failed') {
        if (read.failure.kind === 'notFound') {
          setGroup(null);
          return detail.markNotFound();
        }
        setGroup({ ...attempt, step: { kind: 'uncertain' } });
        return;
      }
      if (read.value.version === attempt.openedOnVersion) {
        setGroup({ ...attempt, step: { kind: 'uncertain' } });
        return;
      }
      setGroup(null);
      detail.apply(read.value);
      detail.loadEvents();
      setAlert(COPY.mayHaveCompleted);
      if (attempt.intent === 'send' && isAwaitingProvider(read.value.detail)) detail.startPolling();
    },
    [detail]
  );

  const confirm = useCallback(async () => {
    const attempt = groupRef.current;
    if (!attempt) return;
    const { kind } = attempt.step;
    if (kind === 'submitting' || kind === 'rateLimited') return;
    if (attempt.intent === 'send' && lockUntil !== null) return;
    detail.stopPolling();
    setAlert(null);
    const submitting = { ...attempt, step: { kind: 'submitting' } as const };
    groupRef.current = submitting;
    setGroup(submitting);

    const outcome = await detail.run((signal) => call(messageId, attempt, signal));
    if (outcome.kind === 'dropped') {
      setGroup(null);
      return;
    }
    if (outcome.kind === 'ok') {
      const read = outcome.value;
      setGroup(null);
      detail.apply(read);
      detail.loadEvents();
      announce(successSentence(attempt.intent, read.detail));
      if (attempt.intent === 'send' && isAwaitingProvider(read.detail)) detail.startPolling();
      if (attempt.intent === 'correct') onCorrect(read.detail);
      return;
    }
    const keepOpen = (step: GroupStep) => {
      // The held version moved while the request was in flight, so a retry would carry a stale If-Match.
      if (versionRef.current !== attempt.openedOnVersion) {
        setGroup(null);
        setAlert(COPY.versionMoved);
        return;
      }
      setGroup({ ...attempt, step });
    };
    const { failure } = outcome;
    switch (failure.kind) {
      case 'notFound':
        setGroup(null);
        detail.markNotFound();
        return;
      case 'forbidden':
        keepOpen({ kind: 'refused', message: COPY.forbidden });
        return;
      case 'reload':
        setGroup(null);
        return reloadAfterConflict(failure.code);
      case 'rateLimited':
        // The send limiter is the only one that answers 429 in v1; anything else is an ordinary refusal.
        if (attempt.intent !== 'send') {
          keepOpen({ kind: 'refused', message: errorCodeSentence('RATE_LIMITED') });
          return;
        }
        setLockUntil(Date.now() + RATE_LIMIT_SECONDS * 1000);
        setNow(Date.now());
        keepOpen({ kind: 'rateLimited' });
        return;
      case 'uncertain':
        return resolveUncertain(attempt);
      case 'refused':
        keepOpen({ kind: 'refused', message: errorCodeSentence(failure.code) });
        return;
      default:
        keepOpen({ kind: 'refused', message: COPY.actionFailed });
    }
  }, [announce, detail, lockUntil, messageId, onCorrect, reloadAfterConflict, resolveUncertain]);

  const refresh = useCallback(async () => {
    detail.stopPolling();
    setAlert(null);
    const read = await detail.readAgain();
    if (read.kind === 'dropped') return;
    if (read.kind === 'ok') {
      detail.apply(read.value);
      detail.loadEvents();
      return;
    }
    if (read.failure.kind === 'notFound') return detail.markNotFound();
    setAlert(COPY.refreshFailed);
  }, [detail]);

  const lockSeconds = lockUntil === null ? 0 : Math.max(0, Math.ceil((lockUntil - now) / 1000));

  return {
    group,
    alert,
    sendLocked: lockUntil !== null,
    lockSeconds,
    unsureIntent: group?.step.kind === 'uncertain' ? group.intent : heldUnsure?.intent ?? null,
    open,
    close,
    setNote,
    confirm,
    refresh
  };
}
