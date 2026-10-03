import { useCallback, useEffect, useRef, useState } from 'react';
import { followUpApi } from '@/features/followups/api/followUpApi';
import { ApiError, AbortedTenantRequestError, StaleSessionError } from '@/shared/api/httpClient';
import type { DraftResponse, RecommendationResponse } from '@/features/followups/types';

export type AssistantErrorKind = 'forbidden' | 'rate-limited' | 'conflict' | 'failed';

export type AssistantStep<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'result'; data: T; version: number }
  | { kind: 'error'; error: AssistantErrorKind };

export type CopyStatus = 'idle' | 'copied' | 'failed';
export type PendingDiscard = 'regenerate-draft' | 'requery' | null;

export const DRAFT_MAX_LENGTH = 1000;

interface Options {
  customerId: string;
  policyVersion: number;
  onRequestRefresh: () => void;
}

function isAbortLike(err: unknown): boolean {
  return (
    err instanceof AbortedTenantRequestError ||
    err instanceof StaleSessionError ||
    (typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError')
  );
}

function toErrorKind(err: unknown): AssistantErrorKind {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'forbidden';
    if (err.status === 429) return 'rate-limited';
    if (err.status === 409) return 'conflict';
  }
  return 'failed';
}

/**
 * Local, in-memory state for the Next Best Action panel. Nothing here is persisted and
 * nothing here triggers a business side effect: every result is advisory and tied to the
 * policy version it was produced for.
 */
export function useFollowUpAssistant({ customerId, policyVersion, onRequestRefresh }: Options) {
  const [recommendation, setRecommendation] = useState<AssistantStep<RecommendationResponse>>({ kind: 'idle' });
  const [draft, setDraft] = useState<AssistantStep<DraftResponse>>({ kind: 'idle' });
  const [draftText, setDraftText] = useState('');
  const [draftDirty, setDraftDirty] = useState(false);
  const [pendingDiscard, setPendingDiscard] = useState<PendingDiscard>(null);
  const [copyStatus, setCopyStatus] = useState<CopyStatus>('idle');

  const recController = useRef<AbortController | null>(null);
  const draftController = useRef<AbortController | null>(null);
  const refreshRef = useRef(onRequestRefresh);
  refreshRef.current = onRequestRefresh;

  useEffect(
    () => () => {
      recController.current?.abort();
      draftController.current?.abort();
    },
    []
  );

  const requestRecommendation = useCallback(async () => {
    recController.current?.abort();
    draftController.current?.abort();
    const controller = new AbortController();
    recController.current = controller;

    setPendingDiscard(null);
    setDraft({ kind: 'idle' });
    setDraftText('');
    setDraftDirty(false);
    setCopyStatus('idle');
    setRecommendation({ kind: 'loading' });

    try {
      const { data, version } = await followUpApi.requestRecommendation(customerId, policyVersion, controller.signal);
      if (controller.signal.aborted) return;
      if (data.status === 'STALE_STATE' || version !== policyVersion) {
        setRecommendation(
          data.status === 'STALE_STATE'
            ? { kind: 'result', data, version }
            : { kind: 'error', error: 'conflict' }
        );
        refreshRef.current();
        return;
      }
      setRecommendation({ kind: 'result', data, version });
    } catch (err) {
      if (controller.signal.aborted) return;
      if (isAbortLike(err)) {
        setRecommendation({ kind: 'idle' });
        return;
      }
      const error = toErrorKind(err);
      setRecommendation({ kind: 'error', error });
      if (error === 'conflict') refreshRef.current();
    }
  }, [customerId, policyVersion]);

  const requestDraft = useCallback(
    async (rec: RecommendationResponse) => {
      const recommended = rec.recommendation;
      if (!recommended) return;
      draftController.current?.abort();
      const controller = new AbortController();
      draftController.current = controller;

      setPendingDiscard(null);
      setCopyStatus('idle');
      setDraft({ kind: 'loading' });

      try {
        const { data, version } = await followUpApi.requestDraft(
          customerId,
          { action: recommended.action, templateIntent: recommended.templateIntent },
          policyVersion,
          controller.signal
        );
        if (controller.signal.aborted) return;
        if (data.status === 'STALE_STATE' || version !== policyVersion) {
          setDraft(
            data.status === 'STALE_STATE'
              ? { kind: 'result', data, version }
              : { kind: 'error', error: 'conflict' }
          );
          refreshRef.current();
          return;
        }
        setDraft({ kind: 'result', data, version });
        setDraftText(data.draft?.body ?? '');
        setDraftDirty(false);
      } catch (err) {
        if (controller.signal.aborted) return;
        if (isAbortLike(err)) {
          setDraft({ kind: 'idle' });
          return;
        }
        const error = toErrorKind(err);
        setDraft({ kind: 'error', error });
        if (error === 'conflict') refreshRef.current();
      }
    },
    [customerId, policyVersion]
  );

  const editDraft = useCallback((text: string) => {
    setDraftText(text.slice(0, DRAFT_MAX_LENGTH));
    setDraftDirty(true);
    setCopyStatus('idle');
  }, []);

  const copyDraft = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(draftText);
      setCopyStatus('copied');
    } catch {
      setCopyStatus('failed');
    }
  }, [draftText]);

  const askDiscard = useCallback((action: Exclude<PendingDiscard, null>) => setPendingDiscard(action), []);
  const cancelDiscard = useCallback(() => setPendingDiscard(null), []);

  return {
    recommendation,
    draft,
    draftText,
    draftDirty,
    pendingDiscard,
    copyStatus,
    requestRecommendation,
    requestDraft,
    editDraft,
    copyDraft,
    askDiscard,
    cancelDiscard
  };
}
