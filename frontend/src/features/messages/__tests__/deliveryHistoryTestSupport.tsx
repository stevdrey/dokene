// ABOUTME: Shared setup for the delivery history suites: fixture pages built from the detail fixtures and the no leak spies.
// ABOUTME: Every suite that uses it proves only GET requests, no unexpected request, no console call and no storage write.
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, vi, type MockInstance } from 'vitest';
import { httpClient } from '@/shared/api/httpClient';
import { DeliveryHistory } from '../components/DeliveryHistory';
import type { MessageListItem, MessagePageResponse, MessageResponse } from '../types';
import {
  FakeServer,
  details,
  detailPath,
  detailReply,
  eventsFixtures,
  eventsPath,
  eventsReply,
  historyPath,
  ok,
  variant,
  type Reply
} from './messageTestServer';

export const CUSTOMER_A = details.pendingApproval.customerId;
export const CUSTOMER_B = '7a5c0000-0000-4000-8000-0000000c0002';
export const CURSOR_2 = 'eyJjcmVhdGVkQXQiOiIyMDI2LTEwLTEyVDE1OjAwOjAwWiJ9';

/** Copies only the MessageListItem members, independently of the production helper, so the page shape is the contract's. */
export function listItemOf(detail: MessageResponse): MessageListItem {
  const { messageId, customerId, contactRemoved, status, outcomeUnknown, action, templateIntent, locale, origin, createdAt, updatedAt, version } =
    detail;
  return { messageId, customerId, contactRemoved, status, outcomeUnknown, action, templateIntent, locale, origin, createdAt, updatedAt, version };
}

export function pageOf(messages: readonly MessageResponse[], nextCursor: string | null = null): MessagePageResponse {
  return { items: messages.map(listItemOf), nextCursor };
}

/** A SENDING message the provider has not answered yet; the fixtures only carry the frozen one. */
export const nonFrozenSending: MessageResponse = variant(details.sendingOutcomeUnknown, {
  messageId: '7a5c0000-0000-4000-8000-000000000015',
  outcomeUnknown: false,
  attempts: [{ attemptNumber: 1, startedAt: '2026-10-13T15:10:00Z', outcome: 'STARTED', failureCategory: null }],
  version: 4
});

export const everyOutcome: readonly MessageResponse[] = [nonFrozenSending, ...Object.values(details)];

export function forCustomerB(detail: MessageResponse, suffix: string): MessageResponse {
  return variant(detail, { messageId: `7a5c0000-0000-4000-8000-0000000b${suffix}`, customerId: CUSTOMER_B });
}

const CONSOLE_METHODS = ['log', 'info', 'warn', 'error', 'debug'] as const;

export interface HistorySuite {
  server: FakeServer;
}

/** Registers the suite hooks; the returned object always points at the current test's server. */
export function useHistorySuite(): HistorySuite {
  const suite = { server: new FakeServer() };
  let consoleSpies: MockInstance[] = [];
  let storageSpy: MockInstance | null = null;

  beforeEach(() => {
    vi.restoreAllMocks();
    consoleSpies = CONSOLE_METHODS.map((method) => vi.spyOn(console, method));
    storageSpy = vi.spyOn(Storage.prototype, 'setItem');
    httpClient.setCsrfToken('csrf-a');
    suite.server = new FakeServer();
    suite.server.install();
  });

  afterEach(() => {
    expect(suite.server.unexpected).toEqual([]);
    expect(suite.server.calls.filter((call) => !call.key.startsWith('GET ')).map((call) => call.key)).toEqual([]);
    for (const spy of consoleSpies) expect(spy).not.toHaveBeenCalled();
    expect(storageSpy).not.toHaveBeenCalled();
  });

  return suite;
}

export interface RenderOptions {
  customerId?: string;
  role?: string | null;
  tenantId?: string;
  /** The tenant the client holds, when it should differ from the prop; defaults to the prop. */
  activeTenantId?: string | null;
  onOpenMessage?: (messageId: string) => void;
}

export function renderHistory(options: RenderOptions = {}) {
  const tenantId = options.tenantId ?? 'tenant-a';
  const customerId = options.customerId ?? CUSTOMER_A;
  const role = options.role === undefined ? 'OPERATOR' : options.role;
  httpClient.setTenantId(options.activeTenantId === undefined ? tenantId : options.activeTenantId);
  const view = render(
    <DeliveryHistory tenantId={tenantId} customerId={customerId} role={role} onOpenMessage={options.onOpenMessage} />
  );
  return {
    ...view,
    rerenderWith(next: { tenantId?: string; customerId?: string; role?: string | null }) {
      const nextTenant = next.tenantId ?? tenantId;
      httpClient.setTenantId(nextTenant);
      view.rerender(
        <DeliveryHistory
          tenantId={nextTenant}
          customerId={next.customerId ?? customerId}
          role={next.role === undefined ? role : next.role}
          onOpenMessage={options.onOpenMessage}
        />
      );
    }
  };
}

export function servePage(server: FakeServer, messages: readonly MessageResponse[], customerId = CUSTOMER_A, nextCursor: string | null = null) {
  server.on(historyPath(customerId), ok(pageOf(messages, nextCursor)));
}

export function serveRow(server: FakeServer, detail: MessageResponse, events = eventsFixtures.rejected.events, ...detailReplies: Reply[]) {
  server
    .on(detailPath(detail.messageId), ...(detailReplies.length > 0 ? detailReplies : [detailReply(detail)]))
    .on(eventsPath(detail.messageId), eventsReply(detail, events));
}

export function toggleOf(messageId: string): HTMLButtonElement {
  const button = document.querySelector(`[aria-controls="delivery-row-${messageId}"]`);
  if (!(button instanceof HTMLButtonElement)) throw new Error(`no row for ${messageId}`);
  return button;
}

export function rowOf(messageId: string): HTMLElement {
  const row = toggleOf(messageId).closest('li');
  if (!row) throw new Error(`no row element for ${messageId}`);
  return row;
}

export function hasRow(messageId: string): boolean {
  return document.querySelector(`[aria-controls="delivery-row-${messageId}"]`) !== null;
}

export function panelOf(messageId: string): HTMLElement {
  const panel = document.getElementById(`delivery-row-${messageId}`);
  if (!panel) throw new Error(`no panel for ${messageId}`);
  return panel;
}

export function rowIds(): string[] {
  return Array.from(document.querySelectorAll('[aria-controls^="delivery-row-"]')).map((button) =>
    (button.getAttribute('aria-controls') ?? '').replace('delivery-row-', '')
  );
}

export async function listed() {
  await screen.findByRole('list', { name: 'Mensajes del cliente' });
}

export async function rowLoaded(detail: MessageResponse) {
  await waitFor(() => {
    expect(panelOf(detail.messageId)).toHaveTextContent('Texto del mensaje');
    expect(panelOf(detail.messageId).querySelector('[aria-busy="true"]')).toBeNull();
  });
}

export function liveRegion(): HTMLElement {
  return screen.getByRole('status');
}
