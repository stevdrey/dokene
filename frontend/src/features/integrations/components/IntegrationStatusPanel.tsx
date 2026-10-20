// ABOUTME: Shows a workspace's WhatsApp integration with a server proved readiness badge and template availability.
// ABOUTME: Owners and admins edit the non secret fields and run the ownership proof; other roles get a read only view.
import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { integrationApi, type SavePrecondition } from '../api/integrationApi';
import { useIntegrationStatus, type RecordState } from '../hooks/useIntegrationStatus';
import type { IntegrationRequest, IntegrationResponse } from '../types';
import { COPY } from '../utils/integrationCopy';
import { readErrorMessage, writeFailure, type WriteOperation } from '../utils/integrationErrors';
import { readiness, type Missing, type Readiness } from '../utils/readiness';
import { canManageIntegration } from '../utils/roles';
import { IntegrationForm } from './IntegrationForm';
import { IntegrationValues } from './IntegrationValues';
import { TemplateAvailability } from './TemplateAvailability';
import './IntegrationStatus.css';

interface IntegrationStatusPanelProps {
  workspace: { tenantId: string; role: string | null };
}

type ActionState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'verifying' }
  | { kind: 'stale'; notice: string; current: IntegrationResponse | null; rereadError: string | null }
  | { kind: 'failed'; message: string }
  | { kind: 'succeeded'; announcement: string };

const IDLE: ActionState = { kind: 'idle' };

const MISSING_COPY: Record<Missing, string> = {
  enabled: COPY.missingEnabled,
  outbound: COPY.missingOutbound,
  ownership: COPY.missingOwnership,
  templates: COPY.missingTemplates
};

const LIVE_MODIFIER: Partial<Record<ActionState['kind'], string>> = {
  failed: ' integration-status__live--error',
  stale: ' integration-status__live--warning'
};

const STALE_NOTICE: Record<WriteOperation, string> = { save: COPY.saveStale, verify: COPY.verifyStale };

export function IntegrationStatusPanel({ workspace }: IntegrationStatusPanelProps) {
  // Keying by tenant remounts the view on a switch, so no state from the previous tenant renders even once.
  return <IntegrationStatusView key={workspace.tenantId} tenantId={workspace.tenantId} role={workspace.role} />;
}

function IntegrationStatusView({ tenantId, role }: { tenantId: string; role: string | null }) {
  const headingId = useId();
  const status = useIntegrationStatus(tenantId);
  const { record, templates } = status;
  const [editing, setEditing] = useState(false);
  const [action, setAction] = useState<ActionState>(IDLE);
  // A 403 hides the controls until the next successful read, which always produces a new record state object.
  const [forbiddenAt, setForbiddenAt] = useState<RecordState | null>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const focusEditOnClose = useRef(false);

  const roleManages = canManageIntegration(role);
  const manages = roleManages && forbiddenAt !== record;
  const busy = action.kind === 'saving' || action.kind === 'verifying';
  const formOpen = manages && (editing || record.kind === 'absent');

  useEffect(() => {
    if (!editing && focusEditOnClose.current) {
      focusEditOnClose.current = false;
      editButtonRef.current?.focus();
    }
  }, [editing]);

  if (record.kind === 'sessionEnded') {
    return (
      <section className="integration-status" aria-labelledby={headingId}>
        <h2 id={headingId} className="integration-status__heading">
          {COPY.panelHeading}
        </h2>
        <p className="integration-status__supporting">{COPY.sessionEnded}</p>
      </section>
    );
  }

  const closeForm = () => {
    focusEditOnClose.current = true;
    setEditing(false);
  };

  const resetAfterDrop = () => {
    setAction(IDLE);
    setEditing(false);
  };

  const recoverStale = async (operation: WriteOperation) => {
    const notice = STALE_NOTICE[operation];
    const reread = await status.rereadRecord();
    if (reread.kind === 'dropped') return resetAfterDrop();
    setAction(
      reread.kind === 'ok'
        ? { kind: 'stale', notice, current: reread.value?.record ?? null, rereadError: null }
        : { kind: 'stale', notice, current: null, rereadError: readErrorMessage(reread.error) }
    );
  };

  const handleWriteFailure = async (error: unknown, operation: WriteOperation) => {
    const failure = writeFailure(error, operation);
    if (failure.kind === 'stale') return recoverStale(operation);
    if (failure.kind === 'forbidden') {
      setForbiddenAt(record);
      setEditing(false);
    }
    setAction({ kind: 'failed', message: failure.message });
  };

  const save = async (request: IntegrationRequest) => {
    if (busy) return;
    const precondition: SavePrecondition =
      record.kind === 'loaded' ? { kind: 'version', etag: record.etag } : { kind: 'create' };
    // Keeps the form open if a stale create turns the record from absent into loaded.
    setEditing(true);
    setAction({ kind: 'saving' });
    const outcome = await status.run((signal) => integrationApi.saveIntegration(request, precondition, signal));
    if (outcome.kind === 'dropped') return resetAfterDrop();
    if (outcome.kind === 'failed') return handleWriteFailure(outcome.error, 'save');
    status.applyRecord(outcome.value);
    status.reloadTemplates();
    closeForm();
    setAction({ kind: 'succeeded', announcement: COPY.saved });
  };

  const verify = async () => {
    if (busy || record.kind !== 'loaded') return;
    setAction({ kind: 'verifying' });
    const outcome = await status.run((signal) => integrationApi.verifyOwnership(record.etag, signal));
    if (outcome.kind === 'dropped') return resetAfterDrop();
    if (outcome.kind === 'failed') return handleWriteFailure(outcome.error, 'verify');
    status.applyRecord(outcome.value);
    status.reloadTemplates();
    setAction({ kind: 'succeeded', announcement: COPY.ownershipVerified });
  };

  const openEditor = () => {
    setAction(IDLE);
    setEditing(true);
  };

  const cancelEditor = () => {
    setAction(IDLE);
    closeForm();
  };

  const staleOperation: WriteOperation = editing ? 'save' : 'verify';
  const statusText =
    action.kind === 'succeeded'
      ? action.announcement
      : action.kind === 'failed'
        ? action.message
        : action.kind === 'stale'
          ? action.notice
          : '';

  return (
    <section className="integration-status" aria-labelledby={headingId}>
      <header className="integration-status__header">
        <div className="integration-status__title">
          <h2 id={headingId} className="integration-status__heading">
            {COPY.panelHeading}
          </h2>
          <p className="integration-status__supporting">{COPY.panelIntro}</p>
        </div>
        <ReadinessSummary readiness={readiness(record, templates)} />
      </header>

      <div
        role="status"
        className={`integration-status__live${LIVE_MODIFIER[action.kind] ?? ''}${statusText ? '' : ' integration-status__live--empty'}`}
      >
        {statusText}
      </div>

      {record.kind === 'loading' && (
        <div aria-busy="true" className="integration-status__placeholder">
          <p>{COPY.loading}</p>
        </div>
      )}

      {record.kind === 'error' && (
        <div className="integration-status__read-error">
          <p>{record.message}</p>
          <Button type="button" size="sm" onClick={status.retryRecord}>
            {COPY.retry}
          </Button>
        </div>
      )}

      {record.kind === 'absent' && (
        <div className="integration-status__absent">
          <h3 className="integration-status__section-heading">{COPY.absentHeading}</h3>
          <p className="integration-status__supporting">{COPY.absentIntro}</p>
        </div>
      )}

      {record.kind === 'loaded' && !formOpen && (
        <RecordCard record={record.record} manages={manages} editButtonRef={editButtonRef} onEdit={openEditor} />
      )}

      {formOpen && (
        <IntegrationForm
          initial={record.kind === 'loaded' ? record.record : null}
          submitting={action.kind === 'saving'}
          autoFocus={record.kind === 'loaded'}
          stale={
            action.kind === 'stale'
              ? { current: action.current, rereadError: action.rereadError, onRetry: () => void recoverStale('save') }
              : null
          }
          onSubmit={(request) => void save(request)}
          onCancel={record.kind === 'loaded' ? cancelEditor : undefined}
        />
      )}

      {action.kind === 'stale' && !formOpen && action.rereadError && (
        <div className="integration-status__read-error">
          <p>{action.rereadError}</p>
          <Button type="button" size="sm" onClick={() => void recoverStale(staleOperation)}>
            {COPY.retry}
          </Button>
        </div>
      )}

      {record.kind === 'loaded' && manages && (
        <div className="integration-status__proof">
          <p className="integration-status__supporting">{COPY.verifyHint}</p>
          <Button type="button" onClick={() => void verify()} disabled={busy || editing}>
            {action.kind === 'verifying' ? COPY.verifying : COPY.verify}
          </Button>
        </div>
      )}

      {!roleManages && record.kind !== 'loading' && record.kind !== 'error' && (
        <p className="integration-status__read-only">{COPY.readOnly}</p>
      )}

      <TemplateAvailability state={templates} onRetry={status.reloadTemplates} />
    </section>
  );
}

function RecordCard({
  record,
  manages,
  editButtonRef,
  onEdit
}: {
  record: IntegrationResponse;
  manages: boolean;
  editButtonRef: RefObject<HTMLButtonElement | null>;
  onEdit: () => void;
}) {
  const headingId = useId();
  return (
    <section className="integration-status__section integration-status__card" aria-labelledby={headingId}>
      <div className="integration-status__card-header">
        <h3 id={headingId} className="integration-status__section-heading">
          {COPY.cardHeading}
        </h3>
        {manages && (
          <Button ref={editButtonRef} type="button" size="sm" onClick={onEdit}>
            {COPY.edit}
          </Button>
        )}
      </div>
      <IntegrationValues record={record} />
    </section>
  );
}

function ReadinessSummary({ readiness: state }: { readiness: Readiness }) {
  const label =
    state.kind === 'ready'
      ? COPY.readinessReady
      : state.kind === 'notReady'
        ? COPY.readinessNotReady
        : state.kind === 'checking'
          ? COPY.readinessChecking
          : COPY.readinessUndetermined;
  const variant = state.kind === 'ready' ? 'success' : state.kind === 'notReady' ? 'warning' : 'neutral';
  return (
    <div className="integration-status__readiness">
      <Badge variant={variant}>{label}</Badge>
      {state.kind === 'notReady' && (
        <ul className="integration-status__missing" aria-label={COPY.missingListLabel}>
          {state.missing.map((missing) => (
            <li key={missing}>{MISSING_COPY[missing]}</li>
          ))}
        </ul>
      )}
      <p className="integration-status__footnote">{COPY.readinessFootnote}</p>
    </div>
  );
}
