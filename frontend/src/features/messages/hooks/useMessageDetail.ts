// ABOUTME: Holds one message's detail, version, events and post send polling for the review component.
// ABOUTME: Aborts everything on unmount, drops late replies by sequence, and turns a 401 into sessionEnded.
import { useCallback, useEffect, useRef, useState } from 'react';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { MessageEventItem, MessageResponse } from '../types';
import { classifyFailure, type MessageFailure } from '../utils/messageErrors';

export type DetailState =
  | { kind: 'loading' }
  | { kind: 'loaded'; detail: MessageResponse; version: number }
  | { kind: 'notFound' }
  | { kind: 'error' }
  | { kind: 'sessionEnded' };

export type EventsState =
  | { kind: 'loading' }
  | { kind: 'loaded'; events: readonly MessageEventItem[] }
  | { kind: 'error' };

export type PollState = 'idle' | 'active' | 'exhausted';

/** `dropped` means the reply belongs to an unmounted view or an ended session; callers ignore it. */
export type Outcome<T> = { kind: 'ok'; value: T } | { kind: 'failed'; failure: MessageFailure } | { kind: 'dropped' };

export const POLL_INTERVAL_MS = 2000;
export const POLL_WINDOW_MS = 20000;

const DROPPED = { kind: 'dropped' } as const;

/** Statuses a send can rest in while the provider still has to report; only these are polled. */
export function isAwaitingProvider(detail: MessageResponse): boolean {
  return (
    detail.status === 'QUEUED' || detail.status === 'SENT' || (detail.status === 'SENDING' && !detail.outcomeUnknown)
  );
}

export interface MessageDetailHandle {
  state: DetailState;
  events: EventsState;
  poll: PollState;
  retry: () => void;
  loadEvents: () => void;
  /** Reads the detail without touching the held state; callers decide what to apply. A reply overtaken by a later read is dropped. */
  readAgain: () => Promise<Outcome<VersionedMessage>>;
  apply: (read: VersionedMessage) => void;
  markNotFound: () => void;
  run: <T>(task: (signal: AbortSignal) => Promise<T>) => Promise<Outcome<T>>;
  startPolling: () => void;
  stopPolling: () => void;
}

export interface MessageDetailOptions {
  /** A poll tick applied a reply whose status or outcome differs from the held one; the caller announces it. */
  onPolledChange?: (read: VersionedMessage) => void;
}

export function useMessageDetail(
  messageId: string,
  initial?: VersionedMessage,
  { onPolledChange }: MessageDetailOptions = {}
): MessageDetailHandle {
  const [state, setState] = useState<DetailState>(() =>
    initial ? { kind: 'loaded', detail: initial.detail, version: initial.version } : { kind: 'loading' }
  );
  const [events, setEvents] = useState<EventsState>({ kind: 'loading' });
  const [poll, setPoll] = useState<PollState>('idle');
  const stateRef = useRef(state);
  const mountRef = useRef<AbortController | null>(null);
  const detailSeq = useRef(0);
  const eventsSeq = useRef(0);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollTick = useRef<AbortController | null>(null);
  const pollDeadline = useRef(0);

  const commit = useCallback((next: DetailState) => {
    stateRef.current = next;
    setState(next);
  }, []);

  const clearPoll = useCallback(() => {
    if (pollTimer.current !== null) clearTimeout(pollTimer.current);
    pollTimer.current = null;
    pollTick.current?.abort();
    pollTick.current = null;
  }, []);

  const stopPolling = useCallback(() => {
    clearPoll();
    setPoll('idle');
  }, [clearPoll]);

  const endSession = useCallback(() => {
    mountRef.current?.abort();
    clearPoll();
    setPoll('idle');
    commit({ kind: 'sessionEnded' });
  }, [clearPoll, commit]);

  const runWith = useCallback(
    async <T,>(task: (signal: AbortSignal) => Promise<T>, own?: AbortController): Promise<Outcome<T>> => {
      const mount = mountRef.current;
      if (!mount || mount.signal.aborted) return DROPPED;
      const controller = own ?? mount;
      const forward = () => controller.abort();
      if (own) mount.signal.addEventListener('abort', forward, { once: true });
      const live = () => !mount.signal.aborted && !controller.signal.aborted;
      try {
        const value = await task(controller.signal);
        return live() ? { kind: 'ok', value } : DROPPED;
      } catch (error) {
        if (!live()) return DROPPED;
        const failure = classifyFailure(error);
        if (failure.kind === 'dropped') return DROPPED;
        if (failure.kind === 'sessionEnded') {
          endSession();
          return DROPPED;
        }
        return { kind: 'failed', failure };
      } finally {
        if (own) mount.signal.removeEventListener('abort', forward);
      }
    },
    [endSession]
  );

  const run = useCallback(<T,>(task: (signal: AbortSignal) => Promise<T>) => runWith(task), [runWith]);

  const apply = useCallback(
    (read: VersionedMessage) => commit({ kind: 'loaded', detail: read.detail, version: read.version }),
    [commit]
  );

  const markNotFound = useCallback(() => {
    clearPoll();
    setPoll('idle');
    commit({ kind: 'notFound' });
  }, [clearPoll, commit]);

  const loadEvents = useCallback(() => {
    const seq = ++eventsSeq.current;
    setEvents({ kind: 'loading' });
    void runWith((signal) => messageApi.getEvents(messageId, signal)).then((outcome) => {
      if (seq !== eventsSeq.current || outcome.kind === 'dropped') return;
      setEvents(outcome.kind === 'ok' ? { kind: 'loaded', events: outcome.value } : { kind: 'error' });
    });
  }, [messageId, runWith]);

  const loadDetail = useCallback(() => {
    const seq = ++detailSeq.current;
    commit({ kind: 'loading' });
    void runWith((signal) => messageApi.getDetail(messageId, signal)).then((outcome) => {
      if (seq !== detailSeq.current || outcome.kind === 'dropped') return;
      if (outcome.kind === 'ok') return apply(outcome.value);
      commit(outcome.failure.kind === 'notFound' ? { kind: 'notFound' } : { kind: 'error' });
    });
  }, [apply, commit, messageId, runWith]);

  const readAgain = useCallback(async (): Promise<Outcome<VersionedMessage>> => {
    const seq = ++detailSeq.current;
    const outcome = await runWith((signal) => messageApi.getDetail(messageId, signal));
    // Two overlapping reads can resolve out of order; only the latest one may be applied.
    return seq === detailSeq.current ? outcome : DROPPED;
  }, [messageId, runWith]);

  const retry = useCallback(() => {
    loadDetail();
    loadEvents();
  }, [loadDetail, loadEvents]);

  const tick = useCallback(async () => {
    pollTimer.current = null;
    if (Date.now() >= pollDeadline.current) {
      setPoll('exhausted');
      return;
    }
    const controller = new AbortController();
    pollTick.current = controller;
    const held = stateRef.current;
    // A tick that never answers would otherwise hold the poll active past the window; the deadline aborts it.
    let deadlineHit = false;
    const deadline = setTimeout(() => {
      deadlineHit = true;
      controller.abort();
    }, Math.max(0, pollDeadline.current - Date.now()));
    const outcome = await runWith((signal) => messageApi.getDetail(messageId, signal), controller);
    clearTimeout(deadline);
    if (pollTick.current !== controller) return;
    if (deadlineHit) {
      pollTick.current = null;
      setPoll('exhausted');
      return;
    }
    if (outcome.kind === 'dropped') return;
    pollTick.current = null;
    if (outcome.kind === 'failed') {
      if (outcome.failure.kind === 'notFound') return markNotFound();
      setPoll('exhausted');
      return;
    }
    const read = outcome.value;
    if (held.kind !== 'loaded' || held.version !== read.version) {
      apply(read);
      loadEvents();
      const changed =
        held.kind !== 'loaded' ||
        held.detail.status !== read.detail.status ||
        held.detail.outcomeUnknown !== read.detail.outcomeUnknown;
      if (changed) onPolledChange?.(read);
    }
    if (!isAwaitingProvider(read.detail)) {
      setPoll('idle');
    } else if (Date.now() >= pollDeadline.current) {
      setPoll('exhausted');
    } else {
      pollTimer.current = setTimeout(() => void tick(), POLL_INTERVAL_MS);
    }
  }, [apply, loadEvents, markNotFound, messageId, onPolledChange, runWith]);

  const startPolling = useCallback(() => {
    clearPoll();
    pollDeadline.current = Date.now() + POLL_WINDOW_MS;
    setPoll('active');
    pollTimer.current = setTimeout(() => void tick(), POLL_INTERVAL_MS);
  }, [clearPoll, tick]);

  useEffect(() => {
    const mount = new AbortController();
    mountRef.current = mount;
    // A detail handed over by a submit is already current, so only the events need a read.
    if (stateRef.current.kind === 'loading') loadDetail();
    loadEvents();
    return () => {
      mount.abort();
      clearPoll();
    };
  }, [clearPoll, loadDetail, loadEvents]);

  return { state, events, poll, retry, loadEvents, readAgain, apply, markNotFound, run, startPolling, stopPolling };
}
