// ABOUTME: Shows the stored WhatsApp integration values and its three status flags as text badges.
// ABOUTME: Used by the record card and by the current values list after a stale save.
import { Badge } from '@/shared/components/Badge';
import type { IntegrationResponse } from '../types';
import { COPY } from '../utils/integrationCopy';

export function IntegrationValues({ record }: { record: IntegrationResponse }) {
  return (
    <>
      <ul className="integration-status__flags" aria-label="Estado de la integración">
        <li>
          <Badge variant={record.enabled ? 'success' : 'archived'}>{record.enabled ? COPY.enabled : COPY.disabled}</Badge>
        </li>
        <li>
          <Badge variant={record.outboundEnabled ? 'success' : 'warning'}>
            {record.outboundEnabled ? COPY.outboundOn : COPY.outboundOff}
          </Badge>
        </li>
        <li>
          <Badge variant={record.webhookVerified ? 'success' : 'warning'}>
            {record.webhookVerified ? COPY.ownershipProven : COPY.ownershipUnproven}
          </Badge>
        </li>
      </ul>
      <dl className="integration-status__values">
        <div>
          <dt>{COPY.labelPhoneNumberId}</dt>
          <dd>{record.phoneNumberId}</dd>
        </div>
        <div>
          <dt>{COPY.labelBusinessAccountId}</dt>
          <dd>{record.businessAccountId}</dd>
        </div>
        <div>
          <dt>{COPY.labelDisplayPhoneNumber}</dt>
          <dd>{record.displayPhoneNumber ?? COPY.noDisplayNumber}</dd>
        </div>
        <div>
          <dt>{COPY.labelSecretRef}</dt>
          <dd>
            <code>{record.secretRef}</code>
            <span className="integration-status__note">{COPY.secretRefNote}</span>
          </dd>
        </div>
      </dl>
    </>
  );
}
