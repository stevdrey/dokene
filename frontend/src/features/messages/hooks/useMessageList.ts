// ABOUTME: Loads the Mensajes list for one filter chip and optional customer, with cursor paging.
// ABOUTME: Patches rows in place after actions and drops late responses with a generation counter.

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, AbortedTenantRequestError, StaleSessionError } from '@/shared/api/httpClient';
import { messageApi } from '../api/messageApi';
import type { MessageListItem, MessageStatus } from '../types';
import { filterByKey, type MessageFilterKey } from '../utils/filters';
import { FORBIDDEN_SENTENCE, NETWORK_SENTENCE } from '../utils/messageLabels';

export type MessageListStep = 'idle' | 'loading' | 'ready' | 'error';

export interface MessageListState {
  readonly step: MessageListStep;
  readonly items: readonly MessageListItem[];
  readonly nextCursor: string | null;
  readonly loadingMore: boolean;
  readonly error: string | null;
}

const INITIAL: MessageListState = { step: 'idle', items: [], nextCursor: null, loadingMore: false, error: null };

export function isAbortLike(err: unknown): boolean {
  return (
    err instanceof AbortedTenantRequestError ||
    err instanceof StaleSessionError ||
    (err instanceof Error && err.name === 'AbortError')
  );
}

export function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return FORBIDDEN_SENTENCE;
    return err.message;
  }
  if (err instanceof TypeError) return NETWORK_SENTENCE;
  if (err instanceof Error && err.message) return err.message;
  return NETWORK_SENTENCE;
}

export interface UseMessageListOptions {
  readonly filterKey: MessageFilterKey;
  readonly customerId: string | null;
}

export function useMessageList({ filterKey, customerId }: UseMessageListOptions) {
  const [state, setState] = useState<MessageListState>(INITIAL);
  const generationRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    generationRef.current += 1;
    const generation = generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    setState({ ...INITIAL, step: 'loading' });

    messageApi
      .list({ statuses: filterByKey(filterKey).statuses, customerId: customerId ?? undefined }, controller.signal)
      .then((page) => {
        if (generation !== generationRef.current) return;
        setState({ step: 'ready', items: page.items, nextCursor: page.nextCursor, loadingMore: false, error: null });
      })
      .catch((err: unknown) => {
        if (generation !== generationRef.current || isAbortLike(err)) return;
        setState({ ...INITIAL, step: 'error', error: describeError(err) });
      });

    return () => {
      controller.abort();
    };
  }, [filterKey, customerId, reloadToken]);

  const reload = useCallback(() => setReloadToken((token) => token + 1), []);

  const loadMore = useCallback(() => {
    if (state.step !== 'ready' || state.loadingMore || state.nextCursor === null) return;
    const generation = generationRef.current;
    const cursor = state.nextCursor;
    setState((current) => ({ ...current, loadingMore: true }));
    messageApi
      .list(
        { statuses: filterByKey(filterKey).statuses, customerId: customerId ?? undefined, cursor },
        controllerRef.current?.signal
      )
      .then((page) => {
        if (generation !== generationRef.current) return;
        setState((current) => ({
          ...current,
          items: [...current.items, ...page.items],
          nextCursor: page.nextCursor,
          loadingMore: false
        }));
      })
      .catch((err: unknown) => {
        if (generation !== generationRef.current || isAbortLike(err)) return;
        setState((current) => ({ ...current, loadingMore: false, error: describeError(err) }));
      });
  }, [state.step, state.loadingMore, state.nextCursor, filterKey, customerId]);

  const patchItem = useCallback((messageId: string, patch: { status: MessageStatus; version: number }) => {
    setState((current) => ({
      ...current,
      items: current.items.map((item) => (item.messageId === messageId ? { ...item, ...patch } : item))
    }));
  }, []);

  return { ...state, reload, loadMore, patchItem };
}
