import { useCallback, useEffect, useRef, useState } from 'react';
import { followUpApi } from '@/features/followups/api/followUpApi';
import { ApiError, AbortedTenantRequestError, StaleSessionError } from '@/shared/api/httpClient';
import { DRAFT_BODY_MAX_CODE_POINTS } from '@/features/followups/types';
import type { DraftResponse, RecommendationResponse } from '@/features/followups/types';
import { countCodePoints, truncateToCodePoints } from '@/features/followups/utils/textLength';
import { customerApi } from '@/features/customers/api/customerApi';
import { messageApi } from '@/features/messages/api/messageApi';
import { newIdempotencyKey } from '@/features/messages/hooks/useMessageAction';
import {
  errorCodeSentence,
  FORBIDDEN_SENTENCE,
  NETWORK_SENTENCE,
  NOT_FOUND_SENTENCE,
  rateLimitedSentence
} from '@/features/messages/utils/messageLabels';

export type AssistantErrorKind = 'forbidden' | 'rate-limited' | 'failed';

export type AssistantStep<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'result'; data: T; version: number }
  /** The follow-up changed under the assistant: nothing produced before this point is actionable. */
  | { kind: 'stale'; refresh: StaleRefresh }
  | { kind: 'error'; error: AssistantErrorKind };

/** Why the assistant asks the workbench to reload the queue. */
export type QueueRefreshReason = 'stale' | 'ineligible';

const DETERMINISTIC_INELIGIBILITY: readonly string[] = [
  'DO_NOT_CONTACT',
  'NO_CONTACT_CONSENT',
  'CUSTOMER_ARCHIVED',
  'FOLLOW_UP_INELIGIBLE'
];

/**
 * An INELIGIBLE result caused by the customer's deterministic state (archived, do-not-contact,
 * no consent, no longer due) means the due queue no longer matches reality, even though the
 * policy version did not change. A disallowed action does not: the customer is still eligible.
 */
function changesTheQueue(data: RecommendationResponse | DraftResponse): boolean {
  if (data.status !== 'INELIGIBLE') return false;
  if (data.evaluation) return data.evaluation.eligible === false;
  return data.rejectionReason !== null && DETERMINISTIC_INELIGIBILITY.includes(data.rejectionReason);
}

/** Whether the queue already carries the new policy version the stale result is waiting for. */
export type StaleRefresh = 'pending' | 'done' | 'failed';

/** Outcome of the queue reload triggered by a deterministic ineligibility result. */
export type IneligibleRefresh = 'idle' | 'pending' | 'failed';

export type CopyStatus = 'idle' | 'copied' | 'failed';
export type PendingDiscard = 'regenerate-draft' | 'requery' | null;

/**
 * Hand off of the edited draft to the approval queue (spec 0004 AC-6). One click is one intent:
 * the idempotency key is reused while `draftText` is unchanged and rotated when it changes after a
 * refusal, so a retry replays and an edited text is a new submission.
 */
export type SubmissionStep =
  | { kind: 'idle' }
  | { kind: 'resolvingContact' }
  | { kind: 'submitting' }
  | { kind: 'submitted'; messageId: string; customerDisplayName: string }
  | { kind: 'refused'; code?: string; message: string; customerDisplayName: string | null };

export const CONTACT_FETCH_FAILED_SENTENCE = 'No se pudo obtener el contacto del cliente.';
export const NO_PRIMARY_PHONE_SENTENCE = 'El cliente no tiene un teléfono principal.';
export const SUBMISSION_LOCALE = 'es-419';


interface Options {
  customerId: string;
  policyVersion: number;
  /** Reloads the queue; resolves true only when it now reflects the current follow-up state. */
  onRequestRefresh: (reason?: QueueRefreshReason) => Promise<boolean>;
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
  const [ineligibleRefresh, setIneligibleRefresh] = useState<IneligibleRefresh>('idle');
  const [submission, setSubmission] = useState<SubmissionStep>({ kind: 'idle' });
  const submissionKey = useRef<{ key: string; text: string } | null>(null);
  const submissionController = useRef<AbortController | null>(null);

  const recController = useRef<AbortController | null>(null);
  const draftController = useRef<AbortController | null>(null);
  const refreshRef = useRef(onRequestRefresh);
  useEffect(() => {
    refreshRef.current = onRequestRefresh;
  });
  const mountedRef = useRef(true);
  const refreshToken = useRef(0);
  const ineligibleToken = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      recController.current?.abort();
      draftController.current?.abort();
      submissionController.current?.abort();
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
      ok = await refreshRef.current('stale');
    } catch {
      ok = false;
    }
    if (!mountedRef.current || token !== refreshToken.current) return;
    setRecommendation((prev) =>
      prev.kind === 'stale' ? { kind: 'stale', refresh: ok ? 'done' : 'failed' } : prev
    );
  }, []);

  // The result stays visible either way; a failed reload is reported and can be retried so the
  // operator is never left with an outdated list and no way to know.
  const refreshForIneligibility = useCallback(async () => {
    const token = ++ineligibleToken.current;
    setIneligibleRefresh('pending');
    let ok = false;
    try {
      ok = await refreshRef.current('ineligible');
    } catch {
      ok = false;
    }
    if (!mountedRef.current || token !== ineligibleToken.current) return;
    setIneligibleRefresh(ok ? 'idle' : 'failed');
  }, []);

  // Any staleness signal (409, STALE_STATE or a response for another policy version), whichever
  // request raised it, invalidates both steps: no advice from the old state stays actionable.
  // The edited draft text is deliberately kept so the operator can decide what to do with it.
  const markStale = useCallback(() => {
    setPendingDiscard(null);
    setCopyStatus('idle');
    setRecommendation({ kind: 'stale', refresh: 'pending' });
    setDraft({ kind: 'idle' });
    // A submitted hand off stays visible through staleness; anything else is no longer actionable.
    setSubmission((prev) => (prev.kind === 'submitted' ? prev : { kind: 'idle' }));
    void runRefresh();
  }, [runRefresh]);

  const requestRecommendation = useCallback(async () => {
    recController.current?.abort();
    draftController.current?.abort();
    refreshToken.current += 1;
    ineligibleToken.current += 1;
    setIneligibleRefresh('idle');
    const controller = new AbortController();
    recController.current = controller;

    setPendingDiscard(null);
    setDraft({ kind: 'idle' });
    setDraftText('');
    setDraftDirty(false);
    setCopyStatus('idle');
    submissionController.current?.abort();
    submissionKey.current = null;
    setSubmission({ kind: 'idle' });
    setRecommendation({ kind: 'loading' });

    try {
      const { data, version } = await followUpApi.requestRecommendation(customerId, policyVersion, controller.signal);
      if (controller.signal.aborted) return;
      if (data.status === 'STALE_STATE' || version !== policyVersion) {
        markStale();
        return;
      }
      setRecommendation({ kind: 'result', data, version });
      if (changesTheQueue(data)) void refreshForIneligibility();
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
  }, [customerId, policyVersion, markStale, refreshForIneligibility]);

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
        if (changesTheQueue(data)) void refreshForIneligibility();
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
    [customerId, policyVersion, markStale, refreshForIneligibility]
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

  // Resolves the primary contact at click time, then submits the edited draft as an AI_DRAFT message.
  // Only the backend decides whether the message is accepted; this never approves or sends anything.
  const submitToApproval = useCallback(async () => {
    if (recommendation.kind !== 'result' || !recommendation.data.recommendation) return;
    if (draft.kind !== 'result' || !draft.data.draft) return;
    if (submission.kind === 'resolvingContact' || submission.kind === 'submitting' || submission.kind === 'submitted') return;
    const body = draftText;
    if (body.trim() === '' || countCodePoints(body) > DRAFT_BODY_MAX_CODE_POINTS) return;

    const held = submissionKey.current;
    const idempotencyKey = held && held.text === body ? held.key : newIdempotencyKey();
    submissionKey.current = { key: idempotencyKey, text: body };

    submissionController.current?.abort();
    const controller = new AbortController();
    submissionController.current = controller;

    setSubmission({ kind: 'resolvingContact' });
    let customerDisplayName: string | null = null;
    let contactId: string;
    try {
      const { customer } = await customerApi.getCustomer(customerId);
      if (controller.signal.aborted) return;
      customerDisplayName = customer.displayName;
      const primary = Array.isArray(customer.phones) ? customer.phones.find((phone) => phone.primary) : undefined;
      if (!primary) {
        setSubmission({ kind: 'refused', message: NO_PRIMARY_PHONE_SENTENCE, customerDisplayName });
        return;
      }
      contactId = primary.id;
    } catch (err) {
      if (controller.signal.aborted || isAbortLike(err)) {
        setSubmission({ kind: 'idle' });
        return;
      }
      setSubmission({ kind: 'refused', message: CONTACT_FETCH_FAILED_SENTENCE, customerDisplayName });
      return;
    }

    const { action, templateIntent } = recommendation.data.recommendation;
    setSubmission({ kind: 'submitting' });
    try {
      const { detail } = await messageApi.submit(
        customerId,
        {
          request: { contactId, action, templateIntent, body, locale: SUBMISSION_LOCALE, origin: 'AI_DRAFT' },
          policyVersion,
          idempotencyKey
        },
        controller.signal
      );
      if (controller.signal.aborted) return;
      setSubmission({ kind: 'submitted', messageId: detail.messageId, customerDisplayName: customerDisplayName ?? '' });
      void refreshRef.current();
    } catch (err) {
      if (controller.signal.aborted || isAbortLike(err)) {
        setSubmission({ kind: 'idle' });
        return;
      }
      if (err instanceof ApiError) {
        if (err.status === 409 && err.code === 'STALE_VERSION') {
          markStale();
          return;
        }
        if (err.code === 'FOLLOW_UP_INELIGIBLE') {
          setSubmission({ kind: 'refused', code: err.code, message: errorCodeSentence(err.code), customerDisplayName });
          void refreshForIneligibility();
          return;
        }
        if (err.status === 429) {
          const seconds = err.retryAfterSeconds ?? 60;
          setSubmission({ kind: 'refused', code: err.code ?? 'RATE_LIMITED', message: rateLimitedSentence(seconds), customerDisplayName });
          return;
        }
        if (err.code) {
          setSubmission({ kind: 'refused', code: err.code, message: errorCodeSentence(err.code), customerDisplayName });
          return;
        }
        const message = err.status === 403 ? FORBIDDEN_SENTENCE : err.status === 404 ? NOT_FOUND_SENTENCE : err.message;
        setSubmission({ kind: 'refused', message, customerDisplayName });
        return;
      }
      setSubmission({ kind: 'refused', message: NETWORK_SENTENCE, customerDisplayName });
    }
  }, [recommendation, draft, submission.kind, draftText, customerId, policyVersion, markStale, refreshForIneligibility]);

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
    retryRefresh: runRefresh,
    ineligibleRefresh,
    retryIneligibleRefresh: refreshForIneligibility,
    submission,
    submitToApproval
  };
}
