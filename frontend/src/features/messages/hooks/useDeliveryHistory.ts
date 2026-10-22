// ABOUTME: Holds one customer's message history: pages, per row refreshes, the open row id and the live announcement.
// ABOUTME: Every reply is applied under the version rule after a generation check, and a 401 ends everything at once.
import { useCallback, useEffect, useRef, useState } from 'react';
import { messageApi, type VersionedMessage } from '../api/messageApi';
import type { MessageListItem, MessagePageResponse } from '../types';
import {
  contactChangedSentence,
  deliveryOutcome,
  HISTORY_COPY,
  loadedMoreSentence,
  outcomeChangedSentence
} from '../utils/deliveryOutcome';
import { classifyFailure, type MessageFailure } from '../utils/messageErrors';
import { listItemFromDetail, mergeItem } from '../utils/mergeItem';

/** `forbidden` keeps its own sentence; every other failure of a read is the plain error of that read. */
export type ReadFailure = 'failed' | 'forbidden';

export interface HistoryRow {
  item: MessageListItem;
  refreshing: boolean;
  refreshFailure: ReadFailure | null;
  notFound: boolean;
}

export type HistoryState =
  | { kind: 'loading' }
  | {
      kind: 'ready';
      rows: HistoryRow[];
      nextCursor: string | null;
      pagesLoaded: number;
      loadingMore: boolean;
      moreFailure: ReadFailure | null;
      moreFailures: number;
      refreshing: boolean;
      refreshFailure: ReadFailure | null;
    }
  | { kind: 'error' }
  | { kind: 'forbidden' }
  | { kind: 'sessionEnded' };

export interface Announcement {
  seq: number;
  text: string;
}

type Outcome<T> = { kind: 'ok'; value: T } | { kind: 'failed'; failure: MessageFailure } | { kind: 'dropped' };

const DROPPED = { kind: 'dropped' } as const;

export interface DeliveryHistoryHandle {
  state: HistoryState;
  openMessageId: string | null;
  announcement: Announcement | null;
  toggleRow: (messageId: string) => void;
  refreshRow: (messageId: string) => void;
  refreshList: () => void;
  loadMore: () => void;
  retryFirstPage: () => void;
  /** A detail the open row read on its own; the row item follows it under the version rule, silently. */
  applyDetail: (messageId: string, read: VersionedMessage) => void;
  markNotFound: (messageId: string) => void;
  endSession: () => void;
}

interface Options {
  /** Called after a row refresh landed while that row was open, so the open row can take the fresh detail and events. */
  onRowRefreshed?: (messageId: string, read: VersionedMessage) => void;
}

function freshRow(item: MessageListItem): HistoryRow {
  return { item, refreshing: false, refreshFailure: null, notFound: false };
}

function readyFrom(page: MessagePageResponse): HistoryState {
  return {
    kind: 'ready',
    rows: page.items.map(freshRow),
    nextCursor: page.nextCursor,
    pagesLoaded: 1,
    loadingMore: false,
    moreFailure: null,
    moreFailures: 0,
    refreshing: false,
    refreshFailure: null
  };
}

function readFailure(failure: MessageFailure): ReadFailure {
  return failure.kind === 'forbidden' ? 'forbidden' : 'failed';
}

/**
 * New messages are always newer than every held one (keyset cursor), so they go on top; not found rows leave.
 * A full page that shares no row with the held list means more than a page of messages arrived since the last
 * read: the held rows would sit below a gap the old cursor can never fill, so the page starts the list again.
 */
function mergeFirstPage(held: HistoryState & { kind: 'ready' }, page: MessagePageResponse): HistoryState {
  const heldIds = new Set(held.rows.map((row) => row.item.messageId));
  const incoming = new Map(page.items.map((item) => [item.messageId, item]));
  const overlaps = held.rows.some((row) => incoming.has(row.item.messageId));
  if (!overlaps && page.nextCursor !== null && held.rows.length > 0) return readyFrom(page);
  const fresh = page.items.filter((item) => !heldIds.has(item.messageId)).map(freshRow);
  const kept = held.rows
    .filter((row) => !row.notFound)
    .map((row) => {
      const reply = incoming.get(row.item.messageId);
      return reply ? { ...row, item: mergeItem(row.item, reply) } : row;
    });
  // A replaced cursor is a fresh start for Cargar más, so its failure leaves with the cursor it came from.
  const keepsCursor = held.pagesLoaded > 1;
  return {
    ...held,
    rows: [...fresh, ...kept],
    nextCursor: keepsCursor ? held.nextCursor : page.nextCursor,
    moreFailure: keepsCursor ? held.moreFailure : null,
    moreFailures: keepsCursor ? held.moreFailures : 0,
    refreshing: false,
    refreshFailure: null
  };
}

function appendPage(
  held: HistoryState & { kind: 'ready' },
  page: MessagePageResponse
): { state: HistoryState; added: number } {
  const index = new Map(held.rows.map((row, position) => [row.item.messageId, position]));
  const rows = [...held.rows];
  const added: HistoryRow[] = [];
  for (const item of page.items) {
    const position = index.get(item.messageId);
    if (position === undefined) added.push(freshRow(item));
    else rows[position] = { ...rows[position], item: mergeItem(rows[position].item, item) };
  }
  return {
    state: {
      ...held,
      rows: [...rows, ...added],
      nextCursor: page.nextCursor,
      pagesLoaded: held.pagesLoaded + 1,
      loadingMore: false,
      moreFailure: null,
      moreFailures: 0
    },
    added: added.length
  };
}

export function useDeliveryHistory(customerId: string, { onRowRefreshed }: Options = {}): DeliveryHistoryHandle {
  const [state, setState] = useState<HistoryState>({ kind: 'loading' });
  const [openMessageId, setOpenMessageId] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState<Announcement | null>(null);
  const stateRef = useRef(state);
  const openRef = useRef(openMessageId);
  const mountRef = useRef<AbortController | null>(null);
  const listGeneration = useRef(0);
  const moreController = useRef<AbortController | null>(null);
  const rowControllers = useRef(new Map<string, AbortController>());
  const announcementSeq = useRef(0);

  // State changes go through here so async handlers always see the latest held state, not a stale closure.
  const commit = useCallback((next: HistoryState) => {
    stateRef.current = next;
    setState(next);
  }, []);

  const commitRow = useCallback(
    (messageId: string, change: (row: HistoryRow) => HistoryRow) => {
      const held = stateRef.current;
      if (held.kind !== 'ready') return;
      commit({ ...held, rows: held.rows.map((row) => (row.item.messageId === messageId ? change(row) : row)) });
    },
    [commit]
  );

  const setOpen = useCallback((messageId: string | null) => {
    openRef.current = messageId;
    setOpenMessageId(messageId);
  }, []);

  const announce = useCallback((text: string) => {
    announcementSeq.current += 1;
    setAnnouncement({ seq: announcementSeq.current, text });
  }, []);

  const endSession = useCallback(() => {
    mountRef.current?.abort();
    commit({ kind: 'sessionEnded' });
  }, [commit]);

  /** Runs one read under `controller`, which the mount controller also aborts while the read is in flight. */
  const read = useCallback(
    async <T,>(task: (signal: AbortSignal) => Promise<T>, controller: AbortController): Promise<Outcome<T>> => {
      const mount = mountRef.current;
      if (!mount || mount.signal.aborted || controller.signal.aborted) return DROPPED;
      const forward = () => controller.abort();
      mount.signal.addEventListener('abort', forward, { once: true });
      try {
        const value = await task(controller.signal);
        return controller.signal.aborted ? DROPPED : { kind: 'ok', value };
      } catch (error) {
        if (controller.signal.aborted) return DROPPED;
        const failure = classifyFailure(error);
        if (failure.kind === 'dropped') return DROPPED;
        if (failure.kind === 'sessionEnded') {
          endSession();
          return DROPPED;
        }
        return { kind: 'failed', failure };
      } finally {
        mount.signal.removeEventListener('abort', forward);
      }
    },
    [endSession]
  );

  const loadFirstPage = useCallback(() => {
    const generation = ++listGeneration.current;
    commit({ kind: 'loading' });
    const controller = new AbortController();
    void read((signal) => messageApi.listCustomerHistory(customerId, null, signal), controller).then((outcome) => {
      if (generation !== listGeneration.current || outcome.kind === 'dropped') return;
      if (outcome.kind === 'failed') {
        commit({ kind: outcome.failure.kind === 'forbidden' ? 'forbidden' : 'error' });
        return;
      }
      commit(readyFrom(outcome.value));
    });
  }, [commit, customerId, read]);

  const refreshList = useCallback(() => {
    const held = stateRef.current;
    if (held.kind !== 'ready' || held.refreshing) return;
    const generation = ++listGeneration.current;
    // A page that lands after the refresh could sit beside rows the refresh replaced, so it is dropped here.
    moreController.current?.abort();
    moreController.current = null;
    commit({ ...held, refreshing: true, refreshFailure: null, loadingMore: false });
    const controller = new AbortController();
    void read((signal) => messageApi.listCustomerHistory(customerId, null, signal), controller).then((outcome) => {
      const current = stateRef.current;
      if (generation !== listGeneration.current || outcome.kind === 'dropped' || current.kind !== 'ready') return;
      if (outcome.kind === 'failed') {
        commit({ ...current, refreshing: false, refreshFailure: readFailure(outcome.failure) });
        return;
      }
      const next = mergeFirstPage(current, outcome.value);
      commit(next);
      // A list started again may no longer hold the open row.
      const open = openRef.current;
      if (open !== null && next.kind === 'ready' && !next.rows.some((row) => row.item.messageId === open)) setOpen(null);
    });
  }, [commit, customerId, read, setOpen]);

  const loadMore = useCallback(() => {
    const held = stateRef.current;
    if (held.kind !== 'ready' || held.loadingMore || held.refreshing || held.nextCursor === null) return;
    const cursor = held.nextCursor;
    const controller = new AbortController();
    moreController.current = controller;
    commit({ ...held, loadingMore: true, moreFailure: null });
    void read((signal) => messageApi.listCustomerHistory(customerId, cursor, signal), controller).then((outcome) => {
      const current = stateRef.current;
      if (moreController.current !== controller || outcome.kind === 'dropped' || current.kind !== 'ready') return;
      moreController.current = null;
      if (outcome.kind === 'failed') {
        commit({
          ...current,
          loadingMore: false,
          moreFailure: readFailure(outcome.failure),
          moreFailures: current.moreFailures + 1
        });
        return;
      }
      const appended = appendPage(current, outcome.value);
      commit(appended.state);
      if (appended.added > 0) announce(loadedMoreSentence(appended.added));
    });
  }, [announce, commit, customerId, read]);

  const markNotFound = useCallback(
    (messageId: string) => {
      rowControllers.current.get(messageId)?.abort();
      rowControllers.current.delete(messageId);
      commitRow(messageId, (row) => ({ ...row, refreshing: false, refreshFailure: null, notFound: true }));
      if (openRef.current === messageId) setOpen(null);
    },
    [commitRow, setOpen]
  );

  const applyDetail = useCallback(
    (messageId: string, detail: VersionedMessage) => {
      commitRow(messageId, (row) => ({ ...row, item: mergeItem(row.item, listItemFromDetail(detail.detail)) }));
    },
    [commitRow]
  );

  const refreshRow = useCallback(
    (messageId: string) => {
      const held = stateRef.current;
      if (held.kind !== 'ready') return;
      const row = held.rows.find((candidate) => candidate.item.messageId === messageId);
      if (!row || row.refreshing || row.notFound) return;
      const controller = new AbortController();
      rowControllers.current.set(messageId, controller);
      commitRow(messageId, (current) => ({ ...current, refreshing: true, refreshFailure: null }));
      void read((signal) => messageApi.getDetail(messageId, signal), controller).then((outcome) => {
        if (rowControllers.current.get(messageId) !== controller || outcome.kind === 'dropped') return;
        rowControllers.current.delete(messageId);
        if (outcome.kind === 'failed') {
          if (outcome.failure.kind === 'notFound') {
            markNotFound(messageId);
            return;
          }
          commitRow(messageId, (current) => ({
            ...current,
            refreshing: false,
            refreshFailure: readFailure(outcome.failure)
          }));
          return;
        }
        const current = stateRef.current;
        if (current.kind !== 'ready') return;
        const before = current.rows.find((candidate) => candidate.item.messageId === messageId);
        if (!before) return;
        const merged = mergeItem(before.item, listItemFromDetail(outcome.value.detail));
        commitRow(messageId, (row) => ({ ...row, refreshing: false, item: merged }));
        // The status decides, not the label: two statuses may share one label (both "Aún no enviado"). A flipped
        // contactRemoved is a change too, since the row shows or drops its tag with it.
        const statusChanged = merged.status !== before.item.status || merged.outcomeUnknown !== before.item.outcomeUnknown;
        const contactChanged = merged.contactRemoved !== before.item.contactRemoved;
        const previous = deliveryOutcome(before.item.status, before.item.outcomeUnknown);
        const next = deliveryOutcome(merged.status, merged.outcomeUnknown);
        const sentences = [
          statusChanged ? outcomeChangedSentence(next, previous.label === next.label) : null,
          contactChanged ? contactChangedSentence(merged.contactRemoved) : null
        ].filter((sentence): sentence is string => sentence !== null);
        announce(sentences.length > 0 ? sentences.join(' ') : HISTORY_COPY.unchanged);
        if (openRef.current === messageId) onRowRefreshed?.(messageId, outcome.value);
      });
    },
    [announce, commitRow, markNotFound, onRowRefreshed, read]
  );

  const toggleRow = useCallback(
    (messageId: string) => {
      const held = stateRef.current;
      if (held.kind !== 'ready') return;
      const row = held.rows.find((candidate) => candidate.item.messageId === messageId);
      if (!row || row.notFound) return;
      setOpen(openRef.current === messageId ? null : messageId);
    },
    [setOpen]
  );

  useEffect(() => {
    const mount = new AbortController();
    mountRef.current = mount;
    loadFirstPage();
    return () => mount.abort();
  }, [loadFirstPage]);

  return {
    state,
    openMessageId,
    announcement,
    toggleRow,
    refreshRow,
    refreshList,
    loadMore,
    retryFirstPage: loadFirstPage,
    applyDetail,
    markNotFound,
    endSession
  };
}
