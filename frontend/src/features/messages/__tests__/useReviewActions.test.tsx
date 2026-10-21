// ABOUTME: Tests the review actions hook with a stubbed detail handle, so a version can move while a group is open.
// ABOUTME: Covers closing on a moved version, the send lockout reset, note key rotation and a 429 on a non send intent.
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { VersionedMessage } from '../api/messageApi';
import type { MessageDetailHandle, Outcome } from '../hooks/useMessageDetail';
import { useReviewActions } from '../hooks/useReviewActions';
import type { MessageFailure } from '../utils/messageErrors';
import { details } from './messageTestServer';

const VERSION_MOVED = 'Este mensaje cambió. Revísalo de nuevo antes de continuar.';

function handle(version: number, run: MessageDetailHandle['run']): MessageDetailHandle {
  return {
    state: { kind: 'loaded', detail: { ...details.pendingApproval, version }, version },
    events: { kind: 'loaded', events: [] },
    poll: 'idle',
    retry: vi.fn(),
    loadEvents: vi.fn(),
    // A failed read keeps an unsure attempt unsure.
    readAgain: vi.fn(async () => ({ kind: 'failed', failure: { kind: 'unexpected' } }) as Outcome<never>),
    apply: vi.fn(),
    markNotFound: vi.fn(),
    run,
    startPolling: vi.fn(),
    stopPolling: vi.fn()
  };
}

const failing = (failure: MessageFailure) =>
  vi.fn(async () => ({ kind: 'failed', failure }) as Outcome<never>) as unknown as MessageDetailHandle['run'];

function setup(run: MessageDetailHandle['run']) {
  return renderHook(({ version }) => useReviewActions({
    messageId: details.pendingApproval.messageId,
    detail: handle(version, run),
    announce: vi.fn(),
    onCorrect: vi.fn()
  }), { initialProps: { version: 1 } });
}

// covers: AC-7, AC-10
describe('useReviewActions', () => {
  it('closes an open group and ends the send lockout when the version moves', async () => {
    const { result, rerender } = setup(failing({ kind: 'rateLimited' }));
    act(() => result.current.open('send'));
    await act(() => result.current.confirm());
    expect(result.current.group?.step.kind).toBe('rateLimited');
    expect(result.current.sendLocked).toBe(true);

    rerender({ version: 2 });
    expect(result.current.group).toBeNull();
    expect(result.current.alert).toBe(VERSION_MOVED);
    expect(result.current.sendLocked).toBe(false);
  });

  it('closes instead of offering a retry when the version moved while the request was in flight', async () => {
    let finish: (outcome: Outcome<VersionedMessage>) => void = () => {};
    const run = vi.fn(() => new Promise((resolve) => { finish = resolve; })) as unknown as MessageDetailHandle['run'];
    const { result, rerender } = setup(run);
    act(() => result.current.open('approve'));
    let confirming: Promise<void> = Promise.resolve();
    act(() => { confirming = result.current.confirm(); });
    rerender({ version: 2 });
    expect(result.current.group?.step.kind).toBe('submitting');

    await act(async () => {
      finish({ kind: 'failed', failure: { kind: 'refused', code: 'NO_CONTACT_CONSENT' } });
      await confirming;
    });
    expect(result.current.group).toBeNull();
    expect(result.current.alert).toBe(VERSION_MOVED);
  });

  it('keeps the key for an unchanged retry after a refusal and mints a new one for an edited note', async () => {
    const { result } = setup(failing({ kind: 'refused', code: 'NO_CONTACT_CONSENT' }));
    act(() => result.current.open('reject'));
    act(() => result.current.setNote('Cliente pidió esperar'));
    const opened = result.current.group!.key;
    await act(() => result.current.confirm());
    expect(result.current.group?.step).toEqual({
      kind: 'refused',
      message: 'El cliente no ha dado consentimiento para este canal.'
    });

    await act(() => result.current.confirm());
    expect(result.current.group?.key).toBe(opened);

    act(() => result.current.setNote('Cliente pidió esperar una semana'));
    expect(result.current.group?.key).not.toBe(opened);
    expect(result.current.group?.step.kind).toBe('open');
  });

  it('holds an unsure attempt through Cerrar and lets it go once the version moves', async () => {
    const run = failing({ kind: 'uncertain' });
    const { result, rerender } = setup(run);
    act(() => result.current.open('approve'));
    const key = result.current.group!.key;
    await act(() => result.current.confirm());
    expect(result.current.group?.step.kind).toBe('uncertain');
    expect(result.current.unsureIntent).toBe('approve');

    act(() => result.current.close());
    act(() => result.current.open('reject'));
    expect(result.current.group).toBeNull();
    act(() => result.current.open('approve'));
    expect(result.current.group).toMatchObject({ intent: 'approve', key, step: { kind: 'uncertain' } });

    act(() => result.current.close());
    rerender({ version: 2 });
    expect(result.current.unsureIntent).toBeNull();
    act(() => result.current.open('reject'));
    expect(result.current.group).toMatchObject({ intent: 'reject', openedOnVersion: 2, step: { kind: 'open' } });
  });

  it('treats a 429 on approve as a refusal and leaves Enviar unlocked', async () => {
    const { result } = setup(failing({ kind: 'rateLimited' }));
    act(() => result.current.open('approve'));
    await act(() => result.current.confirm());
    expect(result.current.group?.step).toEqual({
      kind: 'refused',
      message: 'Hay demasiadas solicitudes. Espera un momento e intenta de nuevo.'
    });
    expect(result.current.sendLocked).toBe(false);
  });
});
