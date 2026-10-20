// ABOUTME: Read only list of the workspace message templates and whether each one can be sent now.
// ABOUTME: Has its own loading, error and retry so a failed template read never hides the integration record.
import { useId } from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { intentLabel } from '@/features/followups/utils/aiLabels';
import type { TemplatesState } from '../hooks/useIntegrationStatus';
import type { TemplateResponse } from '../types';
import { COPY } from '../utils/integrationCopy';

interface TemplateAvailabilityProps {
  state: TemplatesState;
  onRetry: () => void;
}

type BadgeVariant = 'success' | 'warning' | 'neutral' | 'archived';

function templateBadge(item: TemplateResponse): { label: string; variant: BadgeVariant; showsStatus: boolean } {
  if (item.sendable) return { label: COPY.templateReady, variant: 'success', showsStatus: false };
  if (!item.mapped) return { label: COPY.templateUnmapped, variant: 'neutral', showsStatus: false };
  if (!item.enabled) return { label: COPY.templateDisabled, variant: 'archived', showsStatus: false };
  if (item.approvalStatus === 'PENDING' || item.approvalStatus === 'UNVERIFIED') {
    return { label: COPY.templatePending, variant: 'warning', showsStatus: true };
  }
  return { label: COPY.templateUnavailable, variant: 'warning', showsStatus: true };
}

export function TemplateAvailability({ state, onRetry }: TemplateAvailabilityProps) {
  const headingId = useId();

  return (
    <section className="integration-status__section" aria-labelledby={headingId}>
      <h3 id={headingId} className="integration-status__section-heading">
        {COPY.templatesHeading}
      </h3>
      {state.kind === 'loading' && (
        <div aria-busy="true" className="integration-status__placeholder">
          <p>{COPY.templatesLoading}</p>
        </div>
      )}
      {state.kind === 'error' && (
        <div className="integration-status__read-error">
          <p>{state.message}</p>
          <Button type="button" size="sm" onClick={onRetry}>
            {COPY.retry}
          </Button>
        </div>
      )}
      {state.kind === 'loaded' && (
        <>
          <p className="integration-status__supporting">
            {COPY.templatesCount(state.items.filter((item) => item.sendable).length, state.items.length)}
          </p>
          <ul className="integration-templates">
            {state.items.map((item) => {
              const badge = templateBadge(item);
              return (
                <li key={item.templateIntent} className="integration-templates__row">
                  <span className="integration-templates__intent">{intentLabel(item.templateIntent)}</span>
                  <span title={badge.showsStatus ? item.approvalStatus : undefined}>
                    <Badge variant={badge.variant}>
                      {badge.label}
                      {badge.showsStatus && ' '}
                      {badge.showsStatus && (
                        <span className="sr-only">{`${COPY.templateStatusPrefix} ${item.approvalStatus}`}</span>
                      )}
                    </Badge>
                  </span>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </section>
  );
}
