import { useCallback, useEffect, useRef, useState } from 'react';
import { followUpApi } from '@/features/followups/api/followUpApi';
import { ApiError, AbortedTenantRequestError, StaleSessionError } from '@/shared/api/httpClient';
import { DRAFT_BODY_MAX_CODE_POINTS } from '@/features/followups/types';
import type { DraftResponse, RecommendationResponse } from '@/features/followups/types';
import { truncateToCodePoints } from '@/features/followups/utils/textLength';

export type AssistantErrorKind = 'forbidden' | 'rate-limited' | 'failed';

export type AssistantStep<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'result'; data: T; version: number }
  /** The follow-up changed under the assistant: nothing produced before this point is actionable. */
  | { kind: 'stale'; refresh: StaleRefresh }
  | { kind: 'error'; error: AssistantErrorKind };

/** Whether the queue already carries the new policy version the stale result is waiting for. */
export type StaleRefresh = 'pending' | 'done' | 'failed';

export type CopyStatus = 'idle' | 'copied' | 'failed';
export type PendingDiscard = 'regenerate-draft' | 'requery' | null;


interface Options {
  customerId: string;
  policyVersion: number;
  /** Reloads the queue; resolves true only when it now reflects the current follow-up state. */
  onRequestRefresh: () => Promise<boolean>;
}

function isAbortLike(err: unknown): boolean {
  return (
    err instanceof AbortedTenantRequestError ||
    err instanceof StaleSessionError ||
    (typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError')
  );
}

function isConflict(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409;
}

function toErrorKind(err: unknown): AssistantErrorKind {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'forbidden';
    if (err.status === 429) return 'rate-limited';
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
  const mountedRef = useRef(true);
  const refreshToken = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      recController.current?.abort();
      draftController.current?.abort();
    };
  }, []);

  // Reloads the queue and records whether it worked. "Actualizar recomendación" is only
  // offered once the list carries the new version; otherwise it would resend the old
  // If-Match and conflict again.
  const runRefresh = useCallback(async () => {
    const token = ++refreshToken.current;
    setRecommendation((prev) => (prev.kind === 'stale' ? { kind: 'stale', refresh: 'pending' } : prev));
    let ok = false;
    try {
      ok = await refreshRef.current();
    } catch {
      ok = false;
    }
    if (!mountedRef.current || token !== refreshToken.current) return;
    setRecommendation((prev) =>
      prev.kind === 'stale' ? { kind: 'stale', refresh: ok ? 'done' : 'failed' } : prev
    );
  }, []);

  // Any staleness signal (409, STALE_STATE or a response for another policy version), whichever
  // request raised it, invalidates both steps: no advice from the old state stays actionable.
  // The edited draft text is deliberately kept so the operator can decide what to do with it.
  const markStale = useCallback(() => {
    setPendingDiscard(null);
    setCopyStatus('idle');
    setRecommendation({ kind: 'stale', refresh: 'pending' });
    setDraft({ kind: 'idle' });
    void runRefresh();
  }, [runRefresh]);

  const requestRecommendation = useCallback(async () => {
    recController.current?.abort();
    draftController.current?.abort();
    refreshToken.current += 1;
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
        markStale();
        return;
      }
      setRecommendation({ kind: 'result', data, version });
    } catch (err) {
      if (controller.signal.aborted) return;
      if (isAbortLike(err)) {
        setRecommendation({ kind: 'idle' });
        return;
      }
      if (isConflict(err)) {
        markStale();
        return;
      }
      setRecommendation({ kind: 'error', error: toErrorKind(err) });
    }
  }, [customerId, policyVersion, markStale]);

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
          markStale();
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
        if (isConflict(err)) {
          markStale();
          return;
        }
        setDraft({ kind: 'error', error: toErrorKind(err) });
      }
    },
    [customerId, policyVersion, markStale]
  );

  const editDraft = useCallback((text: string) => {
    setDraftText(truncateToCodePoints(text, DRAFT_BODY_MAX_CODE_POINTS));
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
    cancelDiscard,
    retryRefresh: runRefresh
  };
}
