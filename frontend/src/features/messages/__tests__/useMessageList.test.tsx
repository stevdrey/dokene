// ABOUTME: Hook tests for useMessageList: loading, paging, late response drop, row patching and error wording.
// ABOUTME: Covers spec 0004 AC-1 (list and Cargar más), AC-3 (in place patch) and AC-8 (403 wording).

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { AbortedTenantRequestError, ApiError, StaleSessionError, httpClient } from '@/shared/api/httpClient';
import { describeError, isAbortLike, useMessageList } from '../hooks/useMessageList';
import type { MessageFilterKey } from '../utils/filters';
import { deferred, installFetchRouter, pendingItem } from './fixtures';

// jsdom's DOMException lives in another realm, so a plain Error named AbortError stands in for fetch's abort.
const abortError = () => Object.assign(new Error('aborted'), { name: 'AbortError' });

const secondItem = { ...pendingItem, messageId: 'msg-2', customerDisplayName: 'Rodrigo Pérez' };
const thirdItem = { ...pendingItem, messageId: 'msg-3', customerDisplayName: 'Ana Soto' };

describe('useMessageList', () => {
  let router: ReturnType<typeof installFetchRouter>;

  beforeEach(() => {
    httpClient.setTenantId('tenant-1');
    router = installFetchRouter();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    httpClient.setTenantId(null);
  });

  it('loads the filter statuses and exposes the page (AC-1)', async () => {
    router.set('GET /api/messages', { body: { items: [pendingItem], nextCursor: 'next-1' } });

    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));

    expect(result.current.step).toBe('loading');
    await waitFor(() => expect(result.current.step).toBe('ready'));
    expect(result.current.items).toEqual([pendingItem]);
    expect(result.current.nextCursor).toBe('next-1');
    expect(router.calls[0].url.searchParams.getAll('status')).toEqual(['PENDING_APPROVAL']);
  });

  it('appends the next page on loadMore and keeps the earlier rows (AC-1)', async () => {
    router.set('GET /api/messages', (_init, url) =>
      url.searchParams.get('cursor') === 'next-1'
        ? { body: { items: [secondItem], nextCursor: null } }
        : { body: { items: [pendingItem], nextCursor: 'next-1' } }
    );
    const { result } = renderHook(() => useMessageList({ filterKey: 'all', customerId: null }));
    await waitFor(() => expect(result.current.step).toBe('ready'));

    act(() => result.current.loadMore());

    expect(result.current.loadingMore).toBe(true);
    await waitFor(() => expect(result.current.loadingMore).toBe(false));
    expect(result.current.items.map((item) => item.messageId)).toEqual(['msg-1', 'msg-2']);
    expect(result.current.nextCursor).toBeNull();
    expect(router.calls[1].url.searchParams.get('cursor')).toBe('next-1');
  });

  it('ignores loadMore when there is no next page', async () => {
    router.set('GET /api/messages', { body: { items: [pendingItem], nextCursor: null } });
    const { result } = renderHook(() => useMessageList({ filterKey: 'all', customerId: null }));
    await waitFor(() => expect(result.current.step).toBe('ready'));

    act(() => result.current.loadMore());

    expect(result.current.loadingMore).toBe(false);
    expect(router.calls).toHaveLength(1);
  });

  it('drops a late response from the previous filter when the chip changes (AC-1)', async () => {
    const slow = deferred();
    router.set('GET /api/messages', (_init, url) =>
      url.searchParams.get('status') === 'PENDING_APPROVAL'
        ? { body: { items: [pendingItem], nextCursor: null }, delay: slow.promise }
        : { body: { items: [thirdItem], nextCursor: null } }
    );
    const { result, rerender } = renderHook(
      ({ filterKey }: { filterKey: MessageFilterKey }) => useMessageList({ filterKey, customerId: null }),
      { initialProps: { filterKey: 'pending' as MessageFilterKey } }
    );

    rerender({ filterKey: 'failed' });
    await waitFor(() => expect(result.current.step).toBe('ready'));
    slow.resolve();
    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.items.map((item) => item.messageId)).toEqual(['msg-3']);
  });

  it('scopes the list to the customer filter and reloads when it clears (AC-6)', async () => {
    router.set('GET /api/messages', { body: { items: [], nextCursor: null } });
    const { result, rerender } = renderHook(
      ({ customerId }: { customerId: string | null }) => useMessageList({ filterKey: 'all', customerId }),
      { initialProps: { customerId: 'cust-1' as string | null } }
    );
    await waitFor(() => expect(result.current.step).toBe('ready'));

    rerender({ customerId: null });
    await waitFor(() => expect(router.calls).toHaveLength(2));

    expect(router.calls[0].url.searchParams.get('customerId')).toBe('cust-1');
    expect(router.calls[1].url.searchParams.has('customerId')).toBe(false);
  });

  it('patches one row in place without a refetch (AC-3)', async () => {
    router.set('GET /api/messages', { body: { items: [pendingItem, secondItem], nextCursor: null } });
    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));
    await waitFor(() => expect(result.current.step).toBe('ready'));

    act(() => result.current.patchItem('msg-2', { status: 'APPROVED', version: 2 }));

    expect(result.current.items[0]).toEqual(pendingItem);
    expect(result.current.items[1]).toEqual({ ...secondItem, status: 'APPROVED', version: 2 });
    expect(router.calls).toHaveLength(1);
  });

  it('reload fetches the list again with the same filter', async () => {
    router.set('GET /api/messages', { body: { items: [pendingItem], nextCursor: null } });
    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));
    await waitFor(() => expect(result.current.step).toBe('ready'));

    act(() => result.current.reload());

    await waitFor(() => expect(router.calls).toHaveLength(2));
    await waitFor(() => expect(result.current.step).toBe('ready'));
  });

  it('shows the forbidden sentence on 403 (AC-8)', async () => {
    router.set('GET /api/messages', { status: 403 });
    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));

    await waitFor(() => expect(result.current.step).toBe('error'));

    expect(result.current.error).toBe('No tienes permiso para esta acción.');
  });

  it('shows the network sentence when fetch itself fails', async () => {
    router.spy.mockImplementation(async () => {
      throw new TypeError('Failed to fetch');
    });
    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));

    await waitFor(() => expect(result.current.step).toBe('error'));

    expect(result.current.error).toBe('No se pudo conectar. Intenta de nuevo.');
  });

  it('keeps the loaded rows when loadMore fails and surfaces the error', async () => {
    router.set('GET /api/messages', (_init, url) =>
      url.searchParams.get('cursor') ? { status: 500, body: { message: 'Error interno' } } : { body: { items: [pendingItem], nextCursor: 'n' } }
    );
    const { result } = renderHook(() => useMessageList({ filterKey: 'all', customerId: null }));
    await waitFor(() => expect(result.current.step).toBe('ready'));

    act(() => result.current.loadMore());

    await waitFor(() => expect(result.current.loadingMore).toBe(false));
    expect(result.current.items).toEqual([pendingItem]);
    expect(result.current.error).toBeTruthy();
  });

  it('stays quiet on an aborted request', async () => {
    router.spy.mockImplementation(async () => {
      throw abortError();
    });
    const { result } = renderHook(() => useMessageList({ filterKey: 'pending', customerId: null }));

    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.step).toBe('loading');
    expect(result.current.error).toBeNull();
  });
});

describe('isAbortLike', () => {
  it.each([
    ['an AbortError', abortError()],
    ['a tenant abort', new AbortedTenantRequestError()],
    ['a stale session', new StaleSessionError()]
  ])('is true for %s', (_label, err) => {
    expect(isAbortLike(err)).toBe(true);
  });

  it('is false for an API error', () => {
    expect(isAbortLike(new ApiError(500, 'boom'))).toBe(false);
  });
});

describe('describeError', () => {
  it('maps 403 to the forbidden sentence and other API errors to their message', () => {
    expect(describeError(new ApiError(403, 'x'))).toBe('No tienes permiso para esta acción.');
    expect(describeError(new ApiError(500, 'Error interno'))).toBe('Error interno');
  });

  it('maps a fetch TypeError and unknown values to the network sentence', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toBe('No se pudo conectar. Intenta de nuevo.');
    expect(describeError(undefined)).toBe('No se pudo conectar. Intenta de nuevo.');
  });
});
