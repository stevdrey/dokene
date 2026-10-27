// ABOUTME: Composes Phase 3 messaging into the follow-up detail (issue 140): review of a handed-off AI draft and the
// ABOUTME: customer's delivery history, from which a message reopens in review. Nothing here sends by itself.
import React, { useEffect, useState } from 'react';
import { Button } from '@/shared/components/Button';
import { customerApi } from '@/features/customers/api/customerApi';
import type { QueueItemResponse } from '@/features/followups/types';
import type { QueueRefreshReason } from '@/features/followups/hooks/useFollowUpAssistant';
import { MessageReview } from '@/features/messages/components/MessageReview';
import { DeliveryHistory } from '@/features/messages/components/DeliveryHistory';
import type { ReviewableDraft } from './AiAssistantPanel';

/** What the review area shows: nothing, a draft being composed for approval, or an existing message. */
export type MessagePanel =
  | { kind: 'none' }
  | { kind: 'compose'; draft: ReviewableDraft; nonce: number }
  | { kind: 'review'; messageId: string };

interface FollowUpMessagingProps {
  item: QueueItemResponse;
  tenantId: string;
  role: string | null;
  panel: MessagePanel;
  onPanelChange: (panel: MessagePanel) => void;
  onRequestRefresh: (reason?: QueueRefreshReason) => Promise<boolean>;
}

type PrimaryContact =
  | { state: 'loading' }
  | { state: 'ready'; contactId: string; recipientPhone: string }
  | { state: 'missing' }
  | { state: 'failed' };

const CONTACT_MISSING = 'Este cliente no tiene un teléfono principal; agrégalo en su perfil para enviar el mensaje.';
const CONTACT_FAILED = 'No pudimos cargar el teléfono del cliente. Inténtalo de nuevo.';

const sectionStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 'var(--space-12)' };
const noteStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  color: 'var(--color-text-muted)'
};

/**
 * The message is addressed to the customer's primary phone, whose id is the contact id the submit binds. It is read
 * when a draft is handed off, not from the queue item, so a phone changed since the queue loaded is not used.
 */
function usePrimaryContact(customerId: string, enabled: boolean, attempt: number): PrimaryContact {
  const [contact, setContact] = useState<PrimaryContact>({ state: 'loading' });
  useEffect(() => {
    if (!enabled) return;
    let active = true;
    setContact({ state: 'loading' });
    customerApi
      .getCustomer(customerId)
      .then(({ customer }) => {
        if (!active) return;
        const primary = customer.phones.find((phone) => phone.primary);
        setContact(primary ? { state: 'ready', contactId: primary.id, recipientPhone: primary.e164 } : { state: 'missing' });
      })
      .catch(() => {
        if (active) setContact({ state: 'failed' });
      });
    return () => {
      active = false;
    };
  }, [customerId, enabled, attempt]);
  return contact;
}

export const FollowUpMessaging: React.FC<FollowUpMessagingProps> = ({
  item,
  tenantId,
  role,
  panel,
  onPanelChange,
  onRequestRefresh
}) => {
  const [contactAttempt, setContactAttempt] = useState(0);
  // Remounts the history after a submit so the new message is listed without a manual refresh.
  const [historyGeneration, setHistoryGeneration] = useState(0);
  const contact = usePrimaryContact(item.customerId, panel.kind === 'compose', contactAttempt);

  let review: React.ReactNode = null;
  if (panel.kind === 'compose') {
    if (contact.state === 'loading') {
      review = <p style={noteStyle}>Cargando el teléfono del cliente…</p>;
    } else if (contact.state === 'missing') {
      review = <p role="alert" style={noteStyle}>{CONTACT_MISSING}</p>;
    } else if (contact.state === 'failed') {
      review = (
        <div role="alert" style={{ ...sectionStyle, gap: 'var(--space-8)' }}>
          <p style={noteStyle}>{CONTACT_FAILED}</p>
          <div>
            <Button variant="secondary" size="sm" onClick={() => setContactAttempt((value) => value + 1)}>
              Reintentar
            </Button>
          </div>
        </div>
      );
    } else {
      review = (
        <MessageReview
          // A new hand-off (nonce) starts a fresh compose; a new policy version alone is adopted in place.
          key={`compose|${panel.nonce}`}
          tenantId={tenantId}
          role={role}
          mode={{
            kind: 'compose',
            customerId: item.customerId,
            customerDisplayName: item.displayName,
            contactId: contact.contactId,
            recipientPhone: contact.recipientPhone,
            action: panel.draft.action,
            templateIntent: panel.draft.templateIntent,
            origin: 'AI_DRAFT',
            policyVersion: item.policyVersion,
            initialBody: panel.draft.body
          }}
          onSubmitted={() => setHistoryGeneration((value) => value + 1)}
          onPolicyStale={() => void onRequestRefresh('stale')}
        />
      );
    }
  } else if (panel.kind === 'review') {
    review = (
      <MessageReview
        key={`review|${panel.messageId}`}
        tenantId={tenantId}
        role={role}
        mode={{ kind: 'review', messageId: panel.messageId, customerDisplayName: item.displayName }}
      />
    );
  }

  return (
    <section aria-label="Mensajes de WhatsApp" style={sectionStyle}>
      {review !== null && (
        <div style={sectionStyle}>
          {review}
          <div>
            <Button variant="ghost" size="sm" onClick={() => onPanelChange({ kind: 'none' })}>
              Cerrar revisión
            </Button>
          </div>
        </div>
      )}
      <DeliveryHistory
        key={historyGeneration}
        tenantId={tenantId}
        customerId={item.customerId}
        role={role}
        onOpenMessage={(messageId) => onPanelChange({ kind: 'review', messageId })}
      />
    </section>
  );
};
