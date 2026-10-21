// ABOUTME: Inline form for the non secret WhatsApp integration fields, used to create or replace the record.
// ABOUTME: Validates with the server patterns on submit and asks before disabling a stored enabled integration.
import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode, type RefObject } from 'react';
import { parsePhoneNumberFromString } from 'libphonenumber-js/min';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import type { IntegrationRequest, IntegrationResponse } from '../types';
import { COPY } from '../utils/integrationCopy';
import { IntegrationValues } from './IntegrationValues';

export interface StaleView {
  /** The record the re read returned; null when the re read failed or found no record. */
  current: IntegrationResponse | null;
  rereadError: string | null;
  onRetry: () => void;
}

interface IntegrationFormProps {
  /** The stored record to edit, or null to create one. */
  initial: IntegrationResponse | null;
  submitting: boolean;
  autoFocus: boolean;
  stale: StaleView | null;
  onSubmit: (request: IntegrationRequest) => void;
  /** Omitted in create mode, where there is no card to return to. */
  onCancel?: () => void;
}

interface FormValues {
  phoneNumberId: string;
  businessAccountId: string;
  displayPhoneNumber: string;
  secretRef: string;
  enabled: boolean;
}

type TextField = Exclude<keyof FormValues, 'enabled'>;

// The same patterns the server applies, so client validation never refuses a value the server stored.
const RULES: ReadonlyArray<{ field: TextField; pattern: RegExp; message: string; optional?: boolean }> = [
  { field: 'phoneNumberId', pattern: /^[0-9]{5,32}$/, message: COPY.invalidIdentifier },
  { field: 'businessAccountId', pattern: /^[0-9]{5,32}$/, message: COPY.invalidIdentifier },
  { field: 'displayPhoneNumber', pattern: /^\+[1-9][0-9]{7,14}$/, message: COPY.invalidDisplayNumber, optional: true },
  { field: 'secretRef', pattern: /^[A-Za-z0-9_-]{1,128}$/, message: COPY.invalidSecretRef }
];

function initialValues(record: IntegrationResponse | null): FormValues {
  return {
    phoneNumberId: record?.phoneNumberId ?? '',
    businessAccountId: record?.businessAccountId ?? '',
    displayPhoneNumber: record?.displayPhoneNumber ?? '',
    secretRef: record?.secretRef ?? '',
    enabled: record?.enabled ?? false
  };
}

function toRequest(values: FormValues): IntegrationRequest {
  const displayPhoneNumber = values.displayPhoneNumber.trim();
  return {
    phoneNumberId: values.phoneNumberId.trim(),
    businessAccountId: values.businessAccountId.trim(),
    ...(displayPhoneNumber ? { displayPhoneNumber } : {}),
    enabled: values.enabled,
    secretRef: values.secretRef.trim()
  };
}

function firstInvalid(request: IntegrationRequest): { field: TextField; message: string } | null {
  for (const rule of RULES) {
    const value = request[rule.field] ?? '';
    if (rule.optional && value === '') continue;
    if (!rule.pattern.test(value)) return { field: rule.field, message: rule.message };
  }
  return null;
}

function phonePreview(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  return parsePhoneNumberFromString(trimmed)?.formatInternational() ?? null;
}

export function IntegrationForm({ initial, submitting, autoFocus, stale, onSubmit, onCancel }: IntegrationFormProps) {
  const idPrefix = useId();
  const [values, setValues] = useState<FormValues>(() => initialValues(initial));
  const [invalid, setInvalid] = useState<{ field: TextField; message: string } | null>(null);
  const [pendingDisable, setPendingDisable] = useState<IntegrationRequest | null>(null);
  const submitRef = useRef<HTMLButtonElement>(null);
  const fieldRefs: Record<TextField, RefObject<HTMLInputElement | null>> = {
    phoneNumberId: useRef<HTMLInputElement>(null),
    businessAccountId: useRef<HTMLInputElement>(null),
    displayPhoneNumber: useRef<HTMLInputElement>(null),
    secretRef: useRef<HTMLInputElement>(null)
  };
  const storedEnabled = initial?.enabled ?? false;
  const preview = phonePreview(values.displayPhoneNumber);

  useEffect(() => {
    if (autoFocus) fieldRefs.phoneNumberId.current?.focus();
    // Focus moves once, when the form opens.
  }, []);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const request = toRequest(values);
    const failure = firstInvalid(request);
    setInvalid(failure);
    if (failure) {
      fieldRefs[failure.field].current?.focus();
      return;
    }
    if (storedEnabled && !request.enabled) {
      // The modal returns focus to whatever held it when it opened; make that the submit button.
      submitRef.current?.focus();
      setPendingDisable(request);
      return;
    }
    onSubmit(request);
  };

  const confirmDisable = () => {
    if (pendingDisable) onSubmit(pendingDisable);
    setPendingDisable(null);
  };

  const textField = (field: TextField, label: string, hint?: string, extra?: ReactNode) => {
    const id = `${idPrefix}-${field}`;
    const hintId = hint ? `${id}-hint` : undefined;
    const errorId = invalid?.field === field ? `${id}-error` : undefined;
    const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
    return (
      <div className="integration-form__field">
        <label htmlFor={id}>{label}</label>
        <input
          ref={fieldRefs[field]}
          id={id}
          name={field}
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={values[field]}
          aria-invalid={errorId ? true : undefined}
          aria-describedby={describedBy}
          onChange={(event) => setValues((current) => ({ ...current, [field]: event.target.value }))}
        />
        {hint && (
          <p id={hintId} className="integration-form__hint">
            {hint}
          </p>
        )}
        {extra}
        {errorId && invalid && (
          <p id={errorId} className="integration-form__error">
            {invalid.message}
          </p>
        )}
      </div>
    );
  };

  return (
    <div className="integration-form-layout">
      <form className="integration-form" noValidate onSubmit={handleSubmit}>
        <p className="integration-form__proof-hint">{COPY.formProofHint}</p>
        {textField('phoneNumberId', COPY.labelPhoneNumberId)}
        {textField('businessAccountId', COPY.labelBusinessAccountId)}
        {textField(
          'displayPhoneNumber',
          COPY.labelDisplayPhoneNumber,
          COPY.displayNumberHint,
          preview && (
            <p className="integration-form__hint">
              {COPY.displayNumberPreview} {preview}
            </p>
          )
        )}
        {textField('secretRef', COPY.labelSecretRef, COPY.secretRefHint)}
        <div className="integration-form__check">
          <input
            id={`${idPrefix}-enabled`}
            name="enabled"
            type="checkbox"
            checked={values.enabled}
            onChange={(event) => setValues((current) => ({ ...current, enabled: event.target.checked }))}
          />
          <label htmlFor={`${idPrefix}-enabled`}>{COPY.labelEnabled}</label>
        </div>
        <div className="integration-form__actions">
          <Button ref={submitRef} type="submit" variant="primary" disabled={submitting}>
            {submitting ? COPY.saving : COPY.save}
          </Button>
          {onCancel && (
            <Button type="button" variant="ghost" disabled={submitting} onClick={onCancel}>
              {COPY.cancel}
            </Button>
          )}
        </div>
      </form>
      {stale && <StaleValues stale={stale} />}
      <Modal isOpen={pendingDisable !== null} onClose={() => setPendingDisable(null)} title={COPY.disableTitle}>
        <p className="integration-status__supporting">{COPY.disableBody}</p>
        <div className="integration-form__actions integration-form__actions--end">
          <Button type="button" variant="ghost" onClick={() => setPendingDisable(null)}>
            {COPY.cancel}
          </Button>
          <Button type="button" variant="danger" onClick={confirmDisable}>
            {COPY.confirm}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

function StaleValues({ stale }: { stale: StaleView }) {
  const headingId = useId();
  return (
    <section className="integration-form__current" aria-labelledby={headingId}>
      <h3 id={headingId} className="integration-status__label">
        {COPY.currentValuesHeading}
      </h3>
      {stale.current && <IntegrationValues record={stale.current} />}
      {stale.rereadError && (
        <div className="integration-status__read-error">
          <p>{stale.rereadError}</p>
          <Button type="button" size="sm" onClick={stale.onRetry}>
            {COPY.retry}
          </Button>
        </div>
      )}
    </section>
  );
}
