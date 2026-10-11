// ABOUTME: Loads one message's detail and events, holds its strong ETag version, and polls after a send.
// ABOUTME: A generation counter drops late responses; polling is a timeout chain with a 20 s budget.

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/shared/api/httpClient';
import { messageApi } from '../api/messageApi';
import { isTerminalStatus, type MessageDetail, type MessageEvent, type MessageStatus, type VersionedDetail } from '../types';
import { describeError, isAbortLike } from './useMessageList';

export type MessageDetailStep = 'idle' | 'loading' | 'ready' | 'notFound' | 'error';
export type PollingState = 'off' | 'active' | 'exhausted';

export const POLL_INTERVAL_MS = 2000;
export const POLL_BUDGET_MS = 20000;

export interface MessageDetailState {
  readonly step: MessageDetailStep;
  readonly detail: MessageDetail | null;
  readonly version: number | null;
  readonly events: readonly MessageEvent[];
  readonly eventsError: boolean;
  readonly error: string | null;
  readonly polling: PollingState;
}

const INITIAL: MessageDetailState = {
  step: 'idle',
  detail: null,
  version: null,
  events: [],
  eventsError: false,
  error: null,
  polling: 'off'
};

/** Statuses the local provider moves through on its own after a send, so polling has something to see. */
export function shouldPoll(detail: MessageDetail): boolean {
  if (detail.status === 'QUEUED' || detail.status === 'SENT') return true;
  return detail.status === 'SENDING' && !detail.outcomeUnknown;
}

export interface UseMessageDetailOptions {
  readonly messageId: string | null;
  /** Called when the backend answers 404 for the selected message. */
  readonly onNotFound: () => void;
}

export function useMessageDetail({ messageId, onNotFound }: UseMessageDetailOptions) {
  const [state, setState] = useState<MessageDetailState>(INITIAL);
  const generationRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollControllerRef = useRef<AbortController | null>(null);
  const pollStartedAtRef = useRef<number | null>(null);
  const onNotFoundRef = useRef(onNotFound);
  onNotFoundRef.current = onNotFound;
  // Mirrors state.version so a poll tick can compare versions without relying on an updater running.
  const versionRef = useRef<number | null>(null);
  useEffect(() => {
    versionRef.current = state.version;
  }, [state.version]);

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current !== null) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    pollControllerRef.current?.abort();
    pollControllerRef.current = null;
    pollStartedAtRef.current = null;
    setState((current) => (current.polling === 'active' ? { ...current, polling: 'off' } : current));
  }, []);

  const fetchEvents = useCallback((id: string, generation: number, signal: AbortSignal) => {
    return messageApi
      .getEvents(id, signal)
      .then((events) => {
        if (generation !== generationRef.current) return;
        setState((current) => ({ ...current, events, eventsError: false }));
      })
      .catch((err: unknown) => {
        if (generation !== generationRef.current || isAbortLike(err)) return;
        if (err instanceof ApiError && err.status === 404) {
          setState({ ...INITIAL, step: 'notFound' });
          onNotFoundRef.current();
          return;
        }
        setState((current) => ({ ...current, eventsError: true }));
      });
  }, []);

  const load = useCallback(
    (id: string) => {
      generationRef.current += 1;
      const generation = generationRef.current;
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      setState((current) => ({
        ...current,
        step: current.detail?.messageId === id ? current.step : 'loading',
        error: null
      }));

      const detailRequest = messageApi
        .getDetail(id, controller.signal)
        .then(({ detail, version }) => {
          if (generation !== generationRef.current) return;
          setState((current) => ({ ...current, step: 'ready', detail, version, error: null }));
        })
        .catch((err: unknown) => {
          if (generation !== generationRef.current || isAbortLike(err)) return;
          if (err instanceof ApiError && err.status === 404) {
            setState({ ...INITIAL, step: 'notFound' });
            onNotFoundRef.current();
            return;
          }
          setState({ ...INITIAL, step: 'error', error: describeError(err) });
        });

      void Promise.all([detailRequest, fetchEvents(id, generation, controller.signal)]);
    },
    [fetchEvents]
  );

  useEffect(() => {
    stopPolling();
    if (messageId === null) {
      generationRef.current += 1;
      controllerRef.current?.abort();
      setState(INITIAL);
      return;
    }
    setState({ ...INITIAL, step: 'loading' });
    load(messageId);
    return () => {
      stopPolling();
      controllerRef.current?.abort();
    };
  }, [messageId, load, stopPolling]);

  const refetch = useCallback(() => {
    if (messageId === null) return;
    stopPolling();
    load(messageId);
  }, [messageId, load, stopPolling]);

  /** Refetches detail and events after a 409 and resolves to the fresh status for the alert. */
  const reloadForConflict = useCallback(async (): Promise<MessageStatus | null> => {
    if (messageId === null) return null;
    stopPolling();
    generationRef.current += 1;
    const generation = generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    try {
      const [{ detail, version }] = await Promise.all([
        messageApi.getDetail(messageId, controller.signal),
        fetchEvents(messageId, generation, controller.signal)
      ]);
      if (generation !== generationRef.current) return null;
      setState((current) => ({ ...current, step: 'ready', detail, version, error: null }));
      return detail.status;
    } catch (err: unknown) {
      if (generation !== generationRef.current || isAbortLike(err)) return null;
      if (err instanceof ApiError && err.status === 404) {
        setState({ ...INITIAL, step: 'notFound' });
        onNotFoundRef.current();
        return null;
      }
      setState({ ...INITIAL, step: 'error', error: describeError(err) });
      return null;
    }
  }, [messageId, fetchEvents, stopPolling]);

  const retryEvents = useCallback(() => {
    if (messageId === null) return;
    const controller = controllerRef.current ?? new AbortController();
    void fetchEvents(messageId, generationRef.current, controller.signal);
  }, [messageId, fetchEvents]);

  /** Replaces the held detail from an action response; events are refetched and polling stops. */
  const replace = useCallback(
    ({ detail, version }: VersionedDetail) => {
      stopPolling();
      if (messageId === null || detail.messageId !== messageId) return;
      setState((current) => ({ ...current, step: 'ready', detail, version, error: null }));
      const controller = new AbortController();
      controllerRef.current?.abort();
      controllerRef.current = controller;
      void fetchEvents(messageId, generationRef.current, controller.signal);
    },
    [messageId, fetchEvents, stopPolling]
  );

  const scheduleTick = useCallback(
    (id: string, generation: number) => {
      pollTimerRef.current = setTimeout(() => {
        pollTimerRef.current = null;
        if (generation !== generationRef.current || pollStartedAtRef.current === null) return;
        const controller = new AbortController();
        pollControllerRef.current = controller;
        messageApi
          .getDetail(id, controller.signal)
          .then(({ detail, version }) => {
            if (generation !== generationRef.current || pollStartedAtRef.current === null) return;
            const versionChanged = versionRef.current !== version;
            versionRef.current = version;
            setState((current) => ({ ...current, detail, version }));
            if (versionChanged) {
              void fetchEvents(id, generation, controller.signal);
            }
            const keepGoing = shouldPoll(detail) && !isTerminalStatus(detail.status);
            const withinBudget = Date.now() - pollStartedAtRef.current < POLL_BUDGET_MS;
            if (keepGoing && withinBudget) {
              scheduleTick(id, generation);
            } else {
              pollStartedAtRef.current = null;
              setState((current) => ({ ...current, polling: keepGoing ? 'exhausted' : 'off' }));
            }
          })
          .catch((err: unknown) => {
            if (generation !== generationRef.current || isAbortLike(err)) return;
            if (err instanceof ApiError && err.status === 404) {
              setState({ ...INITIAL, step: 'notFound' });
              onNotFoundRef.current();
              return;
            }
            pollStartedAtRef.current = null;
            setState((current) => ({ ...current, polling: 'exhausted' }));
          });
      }, POLL_INTERVAL_MS);
    },
    [fetchEvents]
  );

  /** Starts the post send poll from the given detail; a no op when its status needs no polling. */
  const startPolling = useCallback(
    (detail: MessageDetail) => {
      if (messageId === null || detail.messageId !== messageId || !shouldPoll(detail)) return;
      stopPolling();
      pollStartedAtRef.current = Date.now();
      setState((current) => ({ ...current, polling: 'active' }));
      scheduleTick(messageId, generationRef.current);
    },
    [messageId, scheduleTick, stopPolling]
  );

  return { ...state, refetch, reloadForConflict, retryEvents, replace, startPolling, stopPolling };
}
