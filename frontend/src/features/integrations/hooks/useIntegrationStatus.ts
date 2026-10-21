// ABOUTME: Holds the integration panel's two server reads for one workspace and scopes every request to it.
// ABOUTME: Aborts and resets on a tenant switch, drops late responses, and turns a 401 into sessionEnded.
import { useCallback, useEffect, useRef, useState } from 'react';
import { AbortedTenantRequestError, ApiError, StaleSessionError } from '@/shared/api/httpClient';
import { integrationApi, type IntegrationRead } from '../api/integrationApi';
import type { IntegrationResponse, TemplateResponse } from '../types';
import { isNotFound, readErrorMessage } from '../utils/integrationErrors';

export type RecordState =
  | { kind: 'loading' }
  | { kind: 'absent' }
  | { kind: 'loaded'; record: IntegrationResponse; etag: string }
  | { kind: 'error'; message: string }
  | { kind: 'sessionEnded' };

export type TemplatesState =
  | { kind: 'loading' }
  | { kind: 'loaded'; items: readonly TemplateResponse[] }
  | { kind: 'error'; message: string };

/** `dropped` means the response belongs to an older generation or the session ended; callers ignore it. */
export type Outcome<T> = { kind: 'ok'; value: T } | { kind: 'failed'; error: unknown } | { kind: 'dropped' };

export interface IntegrationStatus {
  record: RecordState;
  templates: TemplatesState;
  retryRecord: () => void;
  reloadTemplates: () => void;
  /** Re reads the record without leaving the current state on failure, for stale recovery. */
  rereadRecord: () => Promise<Outcome<IntegrationRead | null>>;
  applyRecord: (read: IntegrationRead) => void;
  run: <T>(task: (signal: AbortSignal) => Promise<T>) => Promise<Outcome<T>>;
}

const LOADING = { kind: 'loading' } as const;
const DROPPED = { kind: 'dropped' } as const;

function isAbort(error: unknown): boolean {
  return (error instanceof Error && error.name === 'AbortError') || error instanceof AbortedTenantRequestError;
}

function endsSession(error: unknown): boolean {
  return (error instanceof ApiError && error.status === 401) || error instanceof StaleSessionError;
}

export function useIntegrationStatus(tenantId: string): IntegrationStatus {
  const [record, setRecord] = useState<RecordState>(LOADING);
  const [templates, setTemplates] = useState<TemplatesState>(LOADING);
  const generationRef = useRef<AbortController | null>(null);
  // Each read bumps its sequence, so a reply to an earlier read or retry is never applied.
  const recordSeq = useRef(0);
  const templatesSeq = useRef(0);

  const endSession = useCallback(() => {
    generationRef.current?.abort();
    recordSeq.current += 1;
    templatesSeq.current += 1;
    setRecord({ kind: 'sessionEnded' });
    setTemplates(LOADING);
  }, []);

  const run = useCallback(
    async <T,>(task: (signal: AbortSignal) => Promise<T>): Promise<Outcome<T>> => {
      const generation = generationRef.current;
      if (!generation || generation.signal.aborted) return DROPPED;
      const isCurrent = () => generationRef.current === generation && !generation.signal.aborted;
      try {
        const value = await task(generation.signal);
        return isCurrent() ? { kind: 'ok', value } : DROPPED;
      } catch (error) {
        if (!isCurrent() || isAbort(error)) return DROPPED;
        if (endsSession(error)) {
          endSession();
          return DROPPED;
        }
        return { kind: 'failed', error };
      }
    },
    [endSession]
  );

  const retryRecord = useCallback(() => {
    const seq = ++recordSeq.current;
    setRecord(LOADING);
    void run((signal) => integrationApi.getIntegration(signal)).then((outcome) => {
      if (seq !== recordSeq.current || outcome.kind === 'dropped') return;
      if (outcome.kind === 'ok') {
        setRecord({ kind: 'loaded', ...outcome.value });
      } else if (isNotFound(outcome.error)) {
        setRecord({ kind: 'absent' });
      } else {
        setRecord({ kind: 'error', message: readErrorMessage(outcome.error) });
      }
    });
  }, [run]);

  const reloadTemplates = useCallback(() => {
    const seq = ++templatesSeq.current;
    setTemplates(LOADING);
    void run((signal) => integrationApi.getTemplates(signal)).then((outcome) => {
      if (seq !== templatesSeq.current || outcome.kind === 'dropped') return;
      setTemplates(
        outcome.kind === 'ok'
          ? { kind: 'loaded', items: outcome.value.items }
          : { kind: 'error', message: readErrorMessage(outcome.error) }
      );
    });
  }, [run]);

  const rereadRecord = useCallback(async (): Promise<Outcome<IntegrationRead | null>> => {
    const seq = ++recordSeq.current;
    const outcome = await run((signal) => integrationApi.getIntegration(signal));
    if (seq !== recordSeq.current || outcome.kind === 'dropped') return DROPPED;
    if (outcome.kind === 'ok') {
      setRecord({ kind: 'loaded', ...outcome.value });
      return outcome;
    }
    if (isNotFound(outcome.error)) {
      setRecord({ kind: 'absent' });
      return { kind: 'ok', value: null };
    }
    return outcome;
  }, [run]);

  const applyRecord = useCallback((read: IntegrationRead) => {
    recordSeq.current += 1;
    setRecord({ kind: 'loaded', ...read });
  }, []);

  useEffect(() => {
    const generation = new AbortController();
    generationRef.current = generation;
    retryRecord();
    reloadTemplates();
    return () => generation.abort();
  }, [tenantId, retryRecord, reloadTemplates]);

  return { record, templates, retryRecord, reloadTemplates, rereadRecord, applyRecord, run };
}
