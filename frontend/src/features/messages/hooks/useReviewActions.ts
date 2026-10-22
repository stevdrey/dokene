// ABOUTME: Runs approve, reject, cancel, send and Corregir for the review component, one confirm group at a time.
// ABOUTME: Binds each group to the version it opened on, keeps one idempotency key per intent, and resolves uncertain replies by reading.
import { useCallback, useEffect, useRef, useState } from 'react';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { MessageResponse } from '../types';
import {
  COPY,
  RATE_LIMIT_SECONDS,
  approvedSentence,
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
  /** The template preview shown when the group opened; an approval names it (issue 268). */
  openedOnTemplate: string | null;
  key: string;
  note: string;
  step: GroupStep;
}

function call(
  messageId: string,
  group: ConfirmState,
  signal: AbortSignal
): Promise<VersionedMessage> {
  const { intent, note, openedOnVersion: version, openedOnTemplate, key } = group;
  switch (intent) {
    case 'approve':
      return messageApi.decide(messageId, 'APPROVE', undefined, version, key, signal, openedOnTemplate);
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
      return approvedSentence(detail.status);
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
  /** Whether the current role may act; when it stops, any open or held group is dropped. */
  canAct: boolean;
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

export function useReviewActions({ messageId, detail, canAct, announce, onCorrect }: Options): ReviewActions {
  const [group, setGroup] = useState<ConfirmState | null>(null);
  const [alert, setAlert] = useState<string | null>(null);
  const [lockUntil, setLockUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  // An unsure attempt closed with Cerrar, held so reopening it retries on the same key.
  const [heldUnsure, setHeldUnsure] = useState<ConfirmState | null>(null);
  const groupRef = useRef(group);
  groupRef.current = group;
  // Counts the times the role lost the right to act. A request remembers the count it started under, so a result that
  // lands after a loss is dropped even when the role has already come back; a live flag would let it reopen the group.
  const roleLoss = useRef(0);

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

  // A role that can no longer act loses its confirmation at once, and it does not come back with the role.
  useEffect(() => {
    if (canAct) return;
    roleLoss.current += 1;
    setGroup(null);
    setHeldUnsure(null);
  }, [canAct]);

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
      const template = detail.state.kind === 'loaded' ? (detail.state.detail.template?.revision ?? null) : null;
      setGroup({
        intent,
        openedOnVersion: version,
        openedOnTemplate: template,
        key: crypto.randomUUID(),
        note: '',
        step: { kind: 'open' }
      });
    },
    [detail.state, heldUnsure, version]
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
        const named = code === 'APPROVAL_INVALIDATED' || code === 'CONTACT_NOT_OWNED';
        setAlert(named ? errorCodeSentence(code) : changedSentence(read.value.detail.status));
        return;
      }
      if (read.failure.kind === 'notFound') return detail.markNotFound();
      setAlert(COPY.refreshFailed);
    },
    [detail]
  );

  // The cancel checks only state and version, so it can succeed for a contact removed meanwhile; a copy of that
  // message would carry a contact the customer no longer has, so the operator is sent to a fresh draft instead.
  const startCorrection = useCallback(
    (cancelled: MessageResponse) => {
      if (cancelled.contactRemoved) {
        setAlert(COPY.correctionContactRemoved);
        return;
      }
      onCorrect(cancelled);
    },
    [onCorrect]
  );

  const resolveUncertain = useCallback(
    async (attempt: ConfirmState, generation: number) => {
      const read = await detail.readAgain();
      if (read.kind === 'dropped') return;
      const roleKept = roleLoss.current === generation;
      const stillUnsure = () => setGroup(roleKept ? { ...attempt, step: { kind: 'uncertain' } } : null);
      if (read.kind === 'failed') {
        if (read.failure.kind === 'notFound') {
          setGroup(null);
          return detail.markNotFound();
        }
        stillUnsure();
        return;
      }
      if (read.value.version === attempt.openedOnVersion) {
        stillUnsure();
        return;
      }
      setGroup(null);
      detail.apply(read.value);
      detail.loadEvents();
      // The lost reply was a cancel for Corregir and the server shows it cancelled, so the copy opens as on success,
      // unless the role lost the right to act meanwhile: a read only role is shown the cancellation, not an editable copy.
      if (attempt.intent === 'correct' && read.value.detail.status === 'CANCELLED') {
        announce(COPY.cancelled);
        if (roleKept) startCorrection(read.value.detail);
        return;
      }
      setAlert(COPY.mayHaveCompleted);
      if (attempt.intent === 'send' && isAwaitingProvider(read.value.detail)) detail.startPolling();
    },
    [announce, detail, startCorrection]
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
    const generation = roleLoss.current;

    const outcome = await detail.run((signal) => call(messageId, attempt, signal));
    const roleKept = roleLoss.current === generation;
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
      if (attempt.intent === 'correct' && roleKept) startCorrection(read.detail);
      return;
    }
    const keepOpen = (step: GroupStep) => {
      // The role lost the right to act while the request was in flight; its result does not reopen the group.
      if (!roleKept) {
        setGroup(null);
        return;
      }
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
        setLockUntil(Date.now() + (failure.retryAfterSeconds ?? RATE_LIMIT_SECONDS) * 1000);
        setNow(Date.now());
        keepOpen({ kind: 'rateLimited' });
        return;
      case 'uncertain':
        return resolveUncertain(attempt, generation);
      case 'refused':
        keepOpen({ kind: 'refused', message: errorCodeSentence(failure.code) });
        return;
      default:
        keepOpen({ kind: 'refused', message: COPY.actionFailed });
    }
  }, [announce, detail, lockUntil, messageId, reloadAfterConflict, resolveUncertain, startCorrection]);

  const refresh = useCallback(async () => {
    detail.stopPolling();
    setAlert(null);
    const held = detail.state.kind === 'loaded' ? detail.state.detail : null;
    const read = await detail.readAgain();
    if (read.kind === 'dropped') return;
    if (read.kind === 'ok') {
      detail.apply(read.value);
      detail.loadEvents();
      // Actualizar usually keeps its control and focus, so the result is heard rather than seen.
      const next = read.value.detail;
      const changed = held === null || held.status !== next.status || held.outcomeUnknown !== next.outcomeUnknown;
      announce(changed ? changedSentence(next.status) : COPY.unchanged);
      return;
    }
    if (read.failure.kind === 'notFound') return detail.markNotFound();
    setAlert(COPY.refreshFailed);
  }, [announce, detail]);

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
