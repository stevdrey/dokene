// ABOUTME: Composes Phase 3 messaging into the follow-up detail (issue 140): review of a handed-off AI draft and the
// ABOUTME: customer's delivery history, from which a message reopens in review. Nothing here sends by itself.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@/shared/components/Button';
import { customerApi } from '@/features/customers/api/customerApi';
import type { MessageDraft, QueueItemResponse } from '@/features/followups/types';
import type { QueueRefreshReason } from '@/features/followups/hooks/useFollowUpAssistant';
import { MessageReview } from '@/features/messages/components/MessageReview';
import { DeliveryHistory, type DeliveryHistoryRefresh } from '@/features/messages/components/DeliveryHistory';
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
  /** The handed-off draft was submitted as a message, so it must not be handed off again. */
  onDraftSubmitted: (draft: MessageDraft) => void;
  /** Review holds a request in flight or an unsure outcome: it must not be closed or replaced until that settles. */
  busy: boolean;
  /** A stable setter: review reports busy through it (`MessageReview` `onBusyChange`). */
  onBusyChange: (busy: boolean) => void;
}

type PrimaryContact =
  | { state: 'loading' }
  | { state: 'ready'; contactId: string; recipientPhone: string }
  | { state: 'missing' }
  | { state: 'failed' };

/** A lookup result tagged with the hand-off and attempt it answers, so an older answer is never reused. */
type LoadedContact = { contact: PrimaryContact; handOff: number; attempt: number };

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
 * again for every hand-off, not taken from the queue item or an earlier hand-off, so a primary phone changed since
 * then is never used. Until the lookup for the current hand-off answers, the result is loading.
 */
function usePrimaryContact(customerId: string, handOff: number | null, attempt: number): PrimaryContact {
  const [loaded, setLoaded] = useState<LoadedContact | null>(null);
  useEffect(() => {
    if (handOff === null) return;
    let active = true;
    const settle = (contact: PrimaryContact) => {
      if (active) setLoaded({ contact, handOff, attempt });
    };
    customerApi
      .getCustomer(customerId)
      .then(({ customer }) => {
        const primary = customer.phones.find((phone) => phone.primary);
        settle(primary ? { state: 'ready', contactId: primary.id, recipientPhone: primary.e164 } : { state: 'missing' });
      })
      .catch(() => settle({ state: 'failed' }));
    return () => {
      active = false;
    };
  }, [customerId, handOff, attempt]);
  if (loaded === null || loaded.handOff !== handOff || loaded.attempt !== attempt) {
    return { state: 'loading' };
  }
  return loaded.contact;
}

export const FollowUpMessaging: React.FC<FollowUpMessagingProps> = ({
  item,
  tenantId,
  role,
  panel,
  onPanelChange,
  onRequestRefresh,
  onDraftSubmitted,
  busy,
  onBusyChange
}) => {
  const [contactAttempt, setContactAttempt] = useState(0);
  // Asks the history to read again after a submit or a change made in review, keeping its pages and open row.
  const historyRef = useRef<DeliveryHistoryRefresh>(null);
  const refreshHistory = useCallback(() => historyRef.current?.refresh(), []);
  const contact = usePrimaryContact(item.customerId, panel.kind === 'compose' ? panel.nonce : null, contactAttempt);

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
          // A new hand-off (nonce) starts a fresh compose. The version is the draft's own, never the queue item's: a
          // policy that moved on leaves this compose stale until a new draft is written and handed off.
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
            policyVersion: panel.draft.policyVersion,
            initialBody: panel.draft.body
          }}
          onSubmitted={() => {
            onDraftSubmitted(panel.draft.source);
            refreshHistory();
          }}
          onPolicyStale={() => void onRequestRefresh('stale')}
          onMessageChanged={refreshHistory}
          onBusyChange={onBusyChange}
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
        onMessageChanged={refreshHistory}
        onBusyChange={onBusyChange}
      />
    );
  }

  return (
    <section aria-label="Mensajes de WhatsApp" style={sectionStyle}>
      {review !== null && (
        <div style={sectionStyle}>
          {review}
          <div>
            <Button variant="ghost" size="sm" onClick={() => onPanelChange({ kind: 'none' })} disabled={busy}>
              Cerrar revisión
            </Button>
          </div>
        </div>
      )}
      <DeliveryHistory
        ref={historyRef}
        tenantId={tenantId}
        customerId={item.customerId}
        role={role}
        // While review is busy, opening another message would replace it, so the history offers no way to.
        onOpenMessage={busy ? undefined : (messageId) => onPanelChange({ kind: 'review', messageId })}
      />
    </section>
  );
};
