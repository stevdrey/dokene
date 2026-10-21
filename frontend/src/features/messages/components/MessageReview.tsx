// ABOUTME: Root of the message review component: a thin switch between compose and review modes for one tenant.
// ABOUTME: Remounts on a tenant, customer, contact or message change, renders nothing for an inactive tenant, and owns the live region.
import { useCallback, useState } from 'react';
import { httpClient } from '@/shared/api/httpClient';
import type { VersionedMessage } from '../api/messageApi';
import type { MessageResponse } from '../types';
import { COPY } from '../utils/messageLabels';
import { MessageCompose, type ComposeInput } from './MessageCompose';
import { MessageDetail } from './MessageDetail';
import './MessageReview.css';

export type MessageReviewMode =
  | ({ kind: 'compose' } & ComposeInput)
  | { kind: 'review'; messageId: string; customerDisplayName?: string };

export interface MessageReviewProps {
  tenantId: string;
  role: string | null;
  mode: MessageReviewMode;
  onSubmitted?: (messageId: string) => void;
  onPolicyStale?: () => void;
}

type Current =
  | { kind: 'fromProps' }
  | { kind: 'review'; messageId: string; customerDisplayName?: string; initial?: VersionedMessage }
  | { kind: 'correction'; input: ComposeInput };

function propKey(tenantId: string, mode: MessageReviewMode): string {
  return mode.kind === 'compose'
    ? `${tenantId}|compose|${mode.customerId}|${mode.contactId}`
    : `${tenantId}|review|${mode.messageId}`;
}

export function MessageReview(props: MessageReviewProps) {
  if (httpClient.getTenantId() !== props.tenantId) return null;
  // Keying by the identifying props remounts everything, so no state of a previous value renders even once.
  return <MessageReviewSwitch key={propKey(props.tenantId, props.mode)} {...props} />;
}

function MessageReviewSwitch({ tenantId, role, mode, onSubmitted, onPolicyStale }: MessageReviewProps) {
  const [current, setCurrent] = useState<Current>({ kind: 'fromProps' });
  const [announcement, setAnnouncement] = useState('');

  const announce = useCallback((text: string) => setAnnouncement(text), []);

  const handleSubmitted = useCallback(
    (read: VersionedMessage, customerDisplayName: string) => {
      setCurrent({ kind: 'review', messageId: read.detail.messageId, customerDisplayName, initial: read });
      setAnnouncement(COPY.submitted);
      onSubmitted?.(read.detail.messageId);
    },
    [onSubmitted]
  );

  const handleCorrect = useCallback((cancelled: MessageResponse, customerDisplayName: string | undefined) => {
    setCurrent({
      kind: 'correction',
      input: {
        customerId: cancelled.customerId,
        customerDisplayName: customerDisplayName ?? '',
        contactId: cancelled.contactId,
        recipientPhone: cancelled.recipientPhone,
        action: cancelled.action,
        templateIntent: cancelled.templateIntent,
        origin: cancelled.origin,
        policyVersion: cancelled.sourcePolicyVersion,
        initialBody: cancelled.body
      }
    });
  }, []);

  const composeView = (input: ComposeInput, fromCorrection: boolean) => (
    <MessageCompose
      key={`${tenantId}|compose|${input.customerId}|${input.contactId}|${fromCorrection}`}
      input={input}
      role={role}
      fromCorrection={fromCorrection}
      onSubmitted={(read) => handleSubmitted(read, input.customerDisplayName)}
      onPolicyStale={fromCorrection ? undefined : onPolicyStale}
    />
  );

  const reviewView = (messageId: string, customerDisplayName?: string, initial?: VersionedMessage) => (
    <MessageDetail
      key={`${tenantId}|review|${messageId}`}
      messageId={messageId}
      role={role}
      customerDisplayName={customerDisplayName || undefined}
      initial={initial}
      announce={announce}
      onCorrect={(cancelled) => handleCorrect(cancelled, customerDisplayName)}
    />
  );

  let view;
  if (current.kind === 'correction') {
    view = composeView(current.input, true);
  } else if (current.kind === 'review') {
    view = reviewView(current.messageId, current.customerDisplayName, current.initial);
  } else if (mode.kind === 'compose') {
    view = composeView(mode, false);
  } else {
    view = reviewView(mode.messageId, mode.customerDisplayName);
  }

  return (
    <div className="message-review-root">
      {view}
      <p role="status" className="message-review__live">
        {announcement}
      </p>
    </div>
  );
}
